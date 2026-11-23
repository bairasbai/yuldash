#!/usr/bin/env python3
"""Explain failed jobs for a verified, current, same-repository pull request."""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path
from urllib import error, parse, request

import gemini36_pr_review as shared

CI_PATH = ".github/workflows/ci.yml"
FAILED = {"failure", "timed_out"}
MAX_JOBS = 6
MAX_DOWNLOAD = 8 * 1024 * 1024
STAMP = re.compile(r"^\d{4}-\d\d-\d\dT[\d:.]+Z\s*")
ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
# Only static values from the trusted revision may cross the API boundary.
JOBS = {
    "backend-tests": ("BACKEND_SQLITE", "Run tests"),
    "backend-tests-postgres": (
        "BACKEND_POSTGRES", "Run tests on Postgres (FK + реальная гонка брони под FOR UPDATE)"
    ),
    "android-unit-tests": (
        "ANDROID_UNIT", "Тесты экранов + порог покрытия (Robolectric + Compose + JaCoCo)"
    ),
}
BACKEND = {"BACKEND_SQLITE", "BACKEND_POSTGRES"}
CATEGORIES = {
    "REPOSITORY_SECRET_SCAN": "repository secret-scan test failed",
    "ROBOLECTRIC_MAVEN_RATE_LIMIT": "Robolectric SDK retrieval reportedly returned Maven Central HTTP 429",
    "PYTEST_FAILURE": "pytest reported a failure without an allowlisted cause",
    "KOTLIN_COMPILATION_FAILURE": "Kotlin compile task reportedly failed",
}
INSTRUCTION = (
    "Ты разбираешь сбой GitHub Actions. JSON содержит только фиксированные коды CI. "
    "job и conclusion — код задания и статус GitHub, diagnostic — локальная эвристика, "
    "а не подтверждённая причина. REPOSITORY_SECRET_SCAN — упал тест хранимых секретов, "
    "это не доказывает утечку. ROBOLECTRIC_MAVEN_RATE_LIMIT — сигнал 429 при загрузке SDK; "
    "PYTEST_FAILURE и KOTLIN_COMPILATION_FAILURE — признаки этапа, а не первопричины. "
    "UNKNOWN означает недостаток данных. Верни JSON с cause, location, fix по-русски. "
    "Не выдумывай файлы, строки, вывод тестов и точную причину. Предложи проверяемый шаг, "
    "не отключение проверок. Не утверждай, что тестировал, принял или исправил изменения."
)


def positive_id(value):
    return type(value) is int and value > 0


def same_pr(pr, run, repo, default_branch):
    head = pr.get("head") or {}
    base = pr.get("base") or {}
    return (pr.get("state") == "open" and pr.get("draft") is False
            and head.get("sha") == run["head_sha"]
            and head.get("ref") == run["head_branch"]
            and (head.get("repo") or {}).get("full_name") == repo
            and (base.get("repo") or {}).get("full_name") == repo
            and base.get("ref") == default_branch)


def current_pr(run, repo, headers, default_branch):
    """Return a unique open PR from GitHub's run/commit association, never guess by branch."""
    base = f"{shared.API}/repos/{repo}"
    current = shared.request_json(f"{base}/actions/runs/{run['id']}", headers)
    if (any(current.get(k) != run.get(k) for k in
            ("id", "event", "head_sha", "head_branch", "run_attempt", "status", "conclusion", "path"))
            or (current.get("head_repository") or {}).get("full_name") != repo):
        return None
    candidates = current.get("pull_requests") or []
    if not candidates:
        candidates = shared.request_json(
            f"{base}/commits/{run['head_sha']}/pulls?per_page=100", headers)
        if len(candidates) >= 100:
            return None
    numbers = {entry["number"] for entry in candidates if positive_id(entry.get("number"))}
    if len(numbers) != 1:
        return None
    number, = numbers
    pr = shared.request_json(f"{base}/pulls/{number}", headers)
    return number if same_pr(pr, run, repo, default_branch) else None


def failed_jobs(base, run, headers):
    jobs = []
    page = 1
    while True:
        path = f"{base}/actions/runs/{run['id']}/attempts/{run['run_attempt']}/jobs"
        batch = shared.request_json(f"{path}?per_page=100&page={page}", headers)["jobs"]
        jobs.extend(j for j in batch if j.get("conclusion") in FAILED and positive_id(j.get("id")))
        if len(batch) < 100:
            return jobs
        page += 1


class LogRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        target = parse.urlsplit(newurl)
        if (target.scheme != "https" or target.port not in (None, 443) or target.username is not None
                or not (target.hostname or "").endswith(
                    (".blob.core.windows.net", ".actions.githubusercontent.com"))):
            raise ValueError("Unexpected GitHub job log redirect")
        # Signed storage URLs authenticate themselves. Never copy the bearer token.
        return request.Request(newurl)


def job_log(base, job_id, headers, job_code):
    req = request.Request(f"{base}/actions/jobs/{job_id}/logs", headers=headers)
    with request.build_opener(LogRedirect()).open(req, timeout=20) as response:
        data = response.read(MAX_DOWNLOAD + 1)
    if len(data) > MAX_DOWNLOAD:
        return "UNKNOWN"
    return classify_log(data.decode("utf-8", errors="replace"), job_code)


def classify_log(log, job_code):
    """Return only fixed enums; logs are untrusted, so signals are advisory."""
    if job_code not in BACKEND | {"ANDROID_UNIT"}:
        return "UNKNOWN"
    fallback = "UNKNOWN"
    groups = []
    in_pem = False
    for raw in log.splitlines():
        if len(raw) > 4096:
            continue
        line = STAMP.sub("", ANSI.sub("", raw)).strip()
        if "-----BEGIN " in line:
            in_pem = True
        if in_pem:
            if "-----END " in line:
                in_pem = False
            continue
        if line.startswith("##[group]"):
            name = line.removeprefix("##[group]").casefold()
            groups.append(name.startswith(("run ", "runner image", "operating system", "environment"))
                          or bool(groups and groups[-1]))
            continue
        if line.startswith("##[endgroup]"):
            if groups:
                groups.pop()
            continue
        if groups and groups[-1]:
            continue
        if job_code in BACKEND:
            if re.match(r"^FAILED (?:backend/)?tests/test_no_secrets_under_version_control\.py::", line):
                return "REPOSITORY_SECRET_SCAN"
            if re.match(r"^FAILED (?:backend/)?tests/", line):
                fallback = "PYTEST_FAILURE"
        elif job_code == "ANDROID_UNIT":
            if (re.search(r"https://(?:repo\.maven\.apache\.org|repo1\.maven\.org)/maven2/"
                          r"org/robolectric/android-all-instrumented/", line, re.I)
                    and re.search(r"\b(?:HTTP(?: response code)?|status(?: code)?)[: ]+429\b", line, re.I)):
                return "ROBOLECTRIC_MAVEN_RATE_LIMIT"
            if re.fullmatch(r"> Task :app:compile[A-Za-z0-9]+Kotlin FAILED", line):
                fallback = "KOTLIN_COMPILATION_FAILURE"
    return fallback


def collect_evidence(base, jobs, headers):
    evidence = []
    for job in jobs:
        name = job.get("name")
        if not isinstance(name, str) or name not in JOBS:
            continue
        job_code, exact_step = JOBS[name]
        matched_step = any(isinstance(step, dict) and step.get("name") == exact_step
                           and step.get("conclusion") in FAILED for step in job.get("steps", []))
        category = "UNKNOWN"
        if matched_step:
            try:
                category = job_log(base, job["id"], headers, job_code)
            except (OSError, ValueError, TimeoutError):
                pass
        evidence.append((job["id"], {"job": job_code, "conclusion": job["conclusion"],
                                     "diagnostic": category if isinstance(category, str) and category in CATEGORIES else "UNKNOWN"}))
        if len(evidence) == MAX_JOBS:
            break
    return evidence


def analyze(event, repo, headers, key, *, enabled=False):
    if enabled is not True:
        return "Пропуск: внешний анализ CI не включён."
    run = event.get("workflow_run") or {}
    default_branch = (event.get("repository") or {}).get("default_branch")
    if (not re.fullmatch(r"[\w.-]+/[\w.-]+", repo, re.ASCII)
            or not isinstance(default_branch, str) or not default_branch
            or (event.get("repository") or {}).get("full_name") != repo
            or event.get("action") != "completed" or run.get("status") != "completed"
            or run.get("event") != "pull_request" or run.get("path") != CI_PATH
            or run.get("conclusion") not in FAILED
            or (run.get("head_repository") or {}).get("full_name") != repo
            or not re.fullmatch(r"[a-f0-9]{40}", run.get("head_sha") or "")
            or not run.get("head_branch") or not positive_id(run.get("id"))
            or not positive_id(run.get("run_attempt"))):
        return "Пропуск: запуск не относится к упавшей проверке PR этого репозитория."
    pr = current_pr(run, repo, headers, default_branch)
    if not pr:
        return "Пропуск: GitHub не подтвердил единственный актуальный открытый PR без черновика и попытку."
    run_id, attempt = run["id"], run["run_attempt"]
    marker = f"<!-- yuldash-gemini-ci-run-{run_id} -->"
    revision = f"<!-- ci-failure-v2 {run['head_sha']} attempt-{attempt} -->"
    current = shared.find_comment(repo, pr, headers, marker)
    if current and revision in current["body"].splitlines():
        return f"Разбор этой попытки уже опубликован в PR #{pr}."
    base = f"{shared.API}/repos/{repo}"
    jobs = failed_jobs(base, run, headers)
    if not jobs:
        return "В этой попытке отсутствуют задания со статусом failure или timed_out."
    evidence = collect_evidence(base, jobs, headers)
    diagnostic = [item for _, item in evidence]
    result = None
    keys = ("cause", "location", "fix")
    if any(item["diagnostic"] in CATEGORIES for item in diagnostic):
        if current_pr(run, repo, headers, default_branch) != pr:
            return "Пропуск: версия PR или попытка CI изменилась до анализа."
        result = shared.generate_json(INSTRUCTION, json.dumps(diagnostic), key)
        if not all(isinstance(result.get(k), str) and result[k].strip() for k in keys):
            raise ValueError("Incomplete model response")
    link = f"https://github.com/{repo}/actions/runs/{run_id}/attempts/{attempt}"
    lines = [marker, "## Разбор упавшего CI", "",
             f"[Запуск {run_id}, попытка {attempt}]({link}) · `{run['head_sha'][:12]}`",
             f"Распознано заданий: {len(evidence)} из {len(jobs)}. "
             "Строки логов не отправляются. Это подсказка, не приёмка."]
    for job_id, item in evidence:
        job_url = f"https://github.com/{repo}/actions/runs/{run_id}/job/{job_id}"
        lines.append(f"- [Задание {job_id}]({job_url}) `{item['job']}` · `{item['diagnostic']}`")
    if result is None:
        lines.extend(["", "Недостаточно данных о причине. Проверьте задания в GitHub Actions."])
    else:
        for title, k in zip(("Возможная причина", "Где проверить", "Следующий шаг"), keys):
            lines.extend(["", "### " + title, shared.safe(result[k], 1400)])
    if current_pr(run, repo, headers, default_branch) != pr:
        return "Пропуск: версия PR или попытка CI изменилась во время анализа."
    lines.extend(["", revision])
    shared.upsert_comment(repo, pr, headers, marker, "\n".join(lines))
    return f"Разбор попытки {attempt} опубликован в PR #{pr}."


def main():
    if os.environ.get("YULDASH_GEMINI_CI_DIAGNOSTICS") != "true":
        print("Skipped: external CI analysis is disabled")
        return
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
    headers = {"Authorization": "Bearer " + os.environ["GITHUB_TOKEN"],
               "X-GitHub-Api-Version": "2022-11-28"}
    message = analyze(event, os.environ["GITHUB_REPOSITORY"], headers, os.environ["GEMINI_API_KEY"], enabled=True)
    print(message)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
            print(message, file=output)


if __name__ == "__main__":
    try:
        main()
    except error.HTTPError as exc:
        sys.exit(f"CI analysis API request failed: HTTP {exc.code}")
    except (OSError, TimeoutError, ValueError, KeyError, TypeError):
        sys.exit("CI analysis failed: unavailable context, network or invalid response")
