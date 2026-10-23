"""Ссылка близкому переживает обрыв связи, не выдавая старую машину за свежую.

Исполняем именно встроенный JS страницы в Node VM: сеть, часы, DOM и Leaflet
подменены. Сервер, база и настоящий браузер этим проверкам не нужны.
"""
import ast
import json
from pathlib import Path
import shutil
import subprocess

import pytest


SHARE_SOURCE = Path(__file__).resolve().parents[1] / "app" / "routers" / "share.py"
NODE = shutil.which("node")


def _page_script():
    tree = ast.parse(SHARE_SOURCE.read_text(encoding="utf-8"))
    for statement in tree.body:
        if isinstance(statement, ast.Assign) and any(
            isinstance(target, ast.Name) and target.id == "_PAGE_HTML"
            for target in statement.targets
        ):
            page = ast.literal_eval(statement.value)
            return page.split('<script nonce="__CSP_NONCE__">', 1)[1].split("</script>", 1)[0]
    raise AssertionError("Не найден исполняемый код страницы ссылки")


HARNESS = r"""
const assert = require('node:assert/strict');
const vm = require('node:vm');
const script = __PAGE_SCRIPT__;
const live = {
  kind: 'ride', status: 'onboard', passenger_first_name: 'Тест',
  phase_text: {ru: 'В пути', ba: 'Юлда'},
  from: {lat: 54, lng: 55, text: 'А'}, to: {lat: 55, lng: 56, text: 'Б'},
  car: {lat: 54.5, lng: 55.5},
};

function page(responses) {
  let now = 1000000, timerId = 0, requests = 0, active = 0, maxActive = 0;
  const timers = new Map(), listeners = {}, nodes = {}, layers = new Set(), requestTimes = [];
  const document = {
    hidden: false, visibilityState: 'visible',
    getElementById(id) {
      return nodes[id] ||= {textContent: '', style: {}, classList: {add() {}, remove() {}}};
    },
    addEventListener(name, listener) { listeners[name] = listener; },
  };
  class AbortController {
    constructor() {
      this.signal = {aborted: false, addEventListener(name, listener) {
        if (name === 'abort') this.abortListener = listener;
      }};
    }
    abort() {
      if (this.signal.aborted) return;
      this.signal.aborted = true;
      this.signal.abortListener?.();
    }
  }
  const map = {fitBounds() {}, removeLayer(layer) { layers.delete(layer); }};
  const leaflet = {
    map: () => map, divIcon: (options) => options,
    marker: (position, options) => ({
      car: options.zIndexOffset === 1000,
      addTo() { layers.add(this); return this; }, setLatLng() {},
    }),
    tileLayer: () => ({addTo() {}}), polyline: () => ({addTo() {}}),
  };
  const navigator = {onLine: true};
  const sandbox = {
    document, navigator, L: leaflet, AbortController,
    location: {pathname: '/t/test-share-token'},
    window: {addEventListener(name, listener) { listeners[name] = listener; }},
    Date: class extends Date { static now() { return now; } },
    setTimeout(callback, delay) {
      const id = ++timerId; timers.set(id, {at: now + delay, callback}); return id;
    },
    clearTimeout(id) { timers.delete(id); },
    fetch(url, options) {
      assert.equal(url, '/t/test-share-token/state.json');
      assert.equal(options.cache, 'no-store');
      requests++; active++; maxActive = Math.max(maxActive, active);
      requestTimes.push(now - 1000000);
      const response = responses.shift() || {status: 200, body: live};
      return new Promise((resolve, reject) => {
        const fail = () => reject(new Error('Network unavailable'));
        if (response.hang) options.signal?.addEventListener('abort', fail);
        else if (response.error) fail();
        else resolve({
          status: response.status, ok: response.status >= 200 && response.status < 300,
          json: () => response.badJson ? Promise.reject(new Error('Bad JSON')) : Promise.resolve(response.body),
        });
      }).finally(() => active--);
    },
  };
  vm.runInNewContext(script, sandbox);
  async function flush() { for (let i = 0; i < 30; i++) await Promise.resolve(); }
  async function advance(ms) {
    const target = now + ms;
    for (let steps = 0; steps < 200; steps++) {
      const next = [...timers.entries()].filter(([, timer]) => timer.at <= target)
        .sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) { now = target; await flush(); return; }
      timers.delete(next[0]); now = next[1].at; next[1].callback(); await flush();
    }
    throw new Error('Timer loop without a delay');
  }
  function event(name) { listeners[name]?.(); }
  function visibility(hidden) {
    document.hidden = hidden; document.visibilityState = hidden ? 'hidden' : 'visible';
    event('visibilitychange');
  }
  return {
    flush, advance, event, visibility, nodes, navigator, requestTimes,
    suspend(ms) { now += ms; },
    get requests() { return requests; }, get maxActive() { return maxActive; },
    get cars() { return [...layers].filter(layer => layer.car).length; },
  };
}

const tests = {
  async repeated_network_failures_recover() {
    const p = page([...Array.from({length: 6}, () => ({error: true})), {status: 200, body: live}]);
    await p.flush(); await p.advance(180000);
    assert.ok(p.requests >= 7, 'После пятой ошибки обновления не должны останавливаться');
    assert.deepEqual(p.requestTimes.slice(0, 7), [0, 5000, 15000, 35000, 65000, 95000, 125000]);
    assert.equal(p.nodes.err.style.display, 'none'); assert.equal(p.cars, 1);
  },
  async server_errors_recover() {
    const p = page([...Array.from({length: 6}, () => ({status: 503})), {status: 200, body: live}]);
    await p.flush(); await p.advance(180000);
    assert.ok(p.requests >= 7, 'Временные 5xx не отзывают ссылку');
    assert.equal(p.nodes.err.style.display, 'none'); assert.equal(p.cars, 1);
  },
  async permanent_errors_stop_and_hide_coordinates() {
    for (const status of [401, 403, 404, 410]) {
      const p = page([{status: 200, body: live}, {status}]);
      await p.flush(); assert.equal(p.cars, 1); await p.advance(5000);
      assert.equal(p.nodes.err.style.display, 'flex');
      assert.equal(p.nodes.map.style.display, 'none'); assert.equal(p.cars, 0);
      p.event('online'); p.visibility(true); p.visibility(false); await p.advance(180000);
      assert.equal(p.requests, 2, 'Отозванную ссылку нельзя возобновить событием браузера');
    }
  },
  async stale_car_is_hidden_during_failure() {
    const p = page([{status: 200, body: live}, {error: true}]);
    await p.flush(); assert.equal(p.cars, 1); await p.advance(5000);
    assert.equal(p.cars, 0, 'Последняя точка не должна выглядеть свежей без связи');
    assert.match(p.nodes.foot.textContent, /устарели/);
    assert.match(p.nodes.foot.textContent, /иҫкерҙе/);
    await p.advance(5000); assert.equal(p.cars, 1);
    assert.match(p.nodes.foot.textContent, /Обновляется/);
  },
  async hanging_request_expires_without_overlap() {
    const p = page([{hang: true}, {status: 200, body: live}]);
    await p.flush();
    for (let i = 0; i < 5; i++) { p.event('online'); p.visibility(true); p.visibility(false); }
    assert.equal(p.requests, 1); await p.advance(40000);
    assert.ok(p.requests >= 2, 'Зависший запрос должен завершиться по сроку');
    assert.equal(p.maxActive, 1, 'Запросы не накладываются'); assert.equal(p.cars, 1);
  },
  async hung_refresh_does_not_leave_a_fresh_car() {
    const p = page([{status: 200, body: live}, {hang: true}]);
    await p.flush(); await p.advance(15000);
    assert.equal(p.cars, 0, 'Давняя точка скрывается, даже пока новый запрос завис');
    assert.match(p.nodes.foot.textContent, /устарели/);
    await p.advance(20000); assert.equal(p.cars, 1); assert.equal(p.maxActive, 1);
  },
  async browser_events_resume_polling() {
    const p = page([{error: true}, {status: 200, body: live}]);
    await p.flush(); p.event('online'); await p.advance(0);
    assert.equal(p.requests, 2, 'Возврат сети немедленно возобновляет обновление');
    p.visibility(true); const count = p.requests; await p.advance(60000);
    assert.equal(p.requests, count, 'Скрытая вкладка не опрашивает сервер');
    p.visibility(false); await p.advance(0);
    assert.equal(p.requests, count + 1, 'Открытая вновь вкладка запрашивает свежие данные');
    assert.equal(p.maxActive, 1);
  },
  async offline_waits_for_network_without_requests() {
    const p = page([{status: 200, body: live}]);
    await p.flush(); p.navigator.onLine = false; p.event('offline');
    assert.equal(p.cars, 0); await p.advance(120000); assert.equal(p.requests, 1);
    p.navigator.onLine = true; p.event('online'); await p.flush();
    assert.equal(p.requests, 2); assert.equal(p.cars, 1);
  },
  async suspended_tab_hides_stale_car_before_refresh_finishes() {
    const p = page([{status: 200, body: live}, {hang: true}]);
    await p.flush(); p.visibility(true); p.suspend(60000); p.visibility(false);
    assert.equal(p.cars, 0, 'Возврат вкладки сначала скрывает устаревшую точку');
    await p.flush(); assert.equal(p.requests, 2); assert.equal(p.maxActive, 1);
    await p.advance(25000); assert.equal(p.cars, 1);
  },
  async invalid_response_is_temporary() {
    const p = page([{status: 200, badJson: true}, {status: 200, body: live}]);
    await p.flush(); await p.advance(5000);
    assert.equal(p.requests, 2); assert.equal(p.cars, 1);
    assert.equal(p.nodes.err.style.display, 'none');
  },
  async finished_trip_stays_finished_after_recovery() {
    const finished = {status: 'finished', phase_text: {ru: 'Поездка завершена', ba: 'Сәфәр тамамланды'}};
    const p = page([{status: 200, body: live}, {error: true}, {status: 200, body: finished}]);
    await p.flush(); await p.advance(10000);
    assert.equal(p.nodes.done.style.display, 'flex'); assert.equal(p.nodes.map.style.display, 'none');
    assert.equal(p.cars, 0); const count = p.requests;
    p.event('online'); p.visibility(true); p.visibility(false); await p.advance(180000);
    assert.equal(p.requests, count); assert.equal(p.nodes.foot.style.display, 'none');
  },
};
tests[__SCENARIO__]().then(() => console.log('PASS')).catch(error => {
  console.error(error.message); process.exitCode = 1;
});
"""

SCENARIOS = (
    "repeated_network_failures_recover",
    "server_errors_recover",
    "permanent_errors_stop_and_hide_coordinates",
    "stale_car_is_hidden_during_failure",
    "hanging_request_expires_without_overlap",
    "hung_refresh_does_not_leave_a_fresh_car",
    "browser_events_resume_polling",
    "offline_waits_for_network_without_requests",
    "suspended_tab_hides_stale_car_before_refresh_finishes",
    "invalid_response_is_temporary",
    "finished_trip_stays_finished_after_recovery",
)


@pytest.mark.skipif(NODE is None, reason="Для исполнения JS публичной страницы нужен Node.js")
@pytest.mark.parametrize("scenario", SCENARIOS)
def test_share_network_recovery(scenario):
    code = HARNESS.replace("__PAGE_SCRIPT__", json.dumps(_page_script())).replace(
        "__SCENARIO__", json.dumps(scenario)
    )
    result = subprocess.run([NODE, "-"], input=code, capture_output=True, text=True, timeout=10,
                            encoding="utf-8")
    assert result.returncode == 0, f"{scenario}: {result.stderr.strip()}"


if __name__ == "__main__":
    # Локальный запуск без pytest/conftest: не поднимает БД даже для окружения тестов.
    for scenario in SCENARIOS:
        test_share_network_recovery(scenario)
    print(f"{len(SCENARIOS)} JS-сценариев прошли")
