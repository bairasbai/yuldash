# PLAN06 — штатный Windows wakeup с закрытой сетевой политикой
DRAFT: нужна независимая приёмка. Этот документ НЕ разрешает source, socket, loop, DB или HOST-run.
Owner /root; автор /root/gate175_deadline_analysis; lease ведёт Root.
Единственная запись сейчас: S/docs/plans/rideshare/RS-auth-windows-wakeup-20261105.md. Другие файлы не менять.
S=C:/Users/Bayra/.codex/worktrees/rs-plans-20261024/Yuldash; E=S/test-results/legacy-reconcile-20261102.
B=C:/Users/Bayra/AppData/Local/Programs/Python/Python312; V=C:/Users/Bayra/Yuldash/backend/.venv/Lib/site-packages.
T=C:/Users/Bayra/AppData/Local/yuldash/legacy-reconcile-20261102; L=C:/Users/Bayra/AppData/Local/yuldash/rideshare-claude-tails-20261024.

## Основание и граница доказательства
E/auth-memory05-diag-results.json SHA256 2e432adebdda55a0e06aeb9f60f5a7282665643e5cd30c625dfb403bb58d9609:
actual SOCKET_BIND / BEFORE_ANYIO_RUN / enteredAsyncBody=false / IO_FORBIDDEN; Session0, worker0, schema68 empty, physical1.
Отказ сохранён. Caller stack не записан: конкретный fallback-caller — вывод из pinned исходника и metadata Root, не захваченный стек.
Cleanup: E/auth-memory05-diag-process-cleanup.json SHA256 d9a716c460b60b8b3a1f9d2c58d4082dc28fae9eadb2555d3d174fda766a03d0.
Auth155/PLAN04 — опоры; Source04/05 immutable. Auth/OTP/телефоны/участники5/7 закрыты.
B/Lib/socket.py:598–650 — fallback: listener→bind(loopback,0)→listen→client nonblocking connect→accept→listener.close→peer verification.
:294–295 — raw accepted fd появляется ДО Python-wrapper; :652–665 выбор fallback зависит от native socketpair.
B/Lib/asyncio/proactor_events.py:632–643/785–790 — constructor вызывает socketpair; pair nonblocking, main-thread wakeup fd.
:775–783/804/820–832 — отмена read-future, закрытие pair, IOCP recv, отправка ровно NUL внутренним wakeup.
B/Lib/asyncio/base_events.py:842–851 и V/anyio/_backends/_asyncio.py:1015–1041 — worker возвращает результат через call_soon_threadsafe.
V/anyio/_backends/_asyncio.py:2420–2432 принимает loop_factory; B/Lib/asyncio/runners.py:135–145 вызывает его.
runners.py:65–81/100–128 — shutdown/SIGINT; windows_events.py:319–333 — завершение IOCP self-read.

## Выбранный минимальный producer
Первый срез — wakeup + настоящий FastAPI worker БЕЗ DB/Settings/app imports. Это не доказательство transactions/Auth.
Private loop_factory передать штатному AnyIO backend_options; он один раз создаёт обычный Windows ProactorEventLoop.
Никаких subclass, замены stdlib methods/socketpair, изменения policy/Systemsettings, версии или dependencies.
Открыть child-local audit permit только внутри этого producer; consume попытку ДО constructor, на исходном thread, один loop на child.
Bind pending loop по strong self из pinned _make_self_pipe frame; после constructor require result is pending и точные pair slots.
Проверять strong code-object/function/module identities всей цепочки factory→Runner→Proactor init→_make_self_pipe→fallback.
Одного имени, co_filename, stack string или localhost недостаточно. Все ABI/callsite/pins до permit; mismatch→terminal DENY.
На успехе закрыть permit ДО coroutine body. Ни socket refs, ни loop/capability не выдаются fixture/evidence; loop получает только Runner.
Scope — pinned QA child; не sandbox для враждебного Python.

## Поимённое исключение QA-policy и бюджет
Исключение WindowsWakeup06: только штатная self-pair для доставки результата реального worker при ordinary bind/connect DENY.
Не открывать socket.*/localhost. Прежние guards сохранены; foreign socket creation DENY.
Audit-prefix проверять точно: socket.__new__ (self,family,type,proto), bind/connect (self,address), без логирования args.
Local Lib/test/test_audit.py:118–128 подтверждает события new/bind; точную native tuple/adoption ABI ещё проверить HOST fixtures.
Ожидаемый бюджет из fallback: 2 новые socket (listener/client) + 1 accepted fd/wrapper; максимум3 одновременно, 1 bind, 1 connect.
Каждая роль — strong object, точные AF_INET/SOCK_STREAM/proto0, pinned source frame/line, свой thread, permit/deadline.
Listener bind — ТОЛЬКО ('127.0.0.1',0); connect — ТОЛЬКО свой client к уже назначенному адресу именно своего listener.
Endpoint port не задаёт caller: получить от listener после bind, хранить RAM; reject wrong role/endpoint/order/replay/budget.
Listener.settimeout(remaining) перед разрешённым bind ограничивает accept; не менять global default timeout.
Исходный monotonic deadline2s — TTL grants/приёмки конструктора: после него новых grants/успеха нет. Native-операция/cleanup ограничены child20s/outer150s; 2s не срок полного закрытия.
Registration без callback/await/пользовательского кода/socket getter.
Принять pair только после ОБЕИХ штатных проверок getpeername/getsockname (socket.py:638–641) и проверки deadline.
После _make_self_pipe require listener closed, accepted is loop._ssock, client is loop._csock, обе nonblocking.
Listener закрыт до body; конечные2 socket живут весь loop. Worker wakeup не требует нового bind/connect/создания socket.
Только штатный NUL wakeup; no HTTP/getter/payload. Endpoint/bytes/args не печатать.

## Отказы, teardown и собственность
Любой mismatch/timeout/cancel/исключение — terminal DENY; не повторять producer и не создавать другой loop/новую pair.
Native accepted fd до wrapper — ОБЯЗАТЕЛЬНЫЙ отрицательный контроль, не доказанная по source уборка.
Учитывать listener/client/raw accepted fd/wrapper; чужие/неподтверждённые fd не закрывать.
Установить фактическую adoption/close семантику pinned _socket; при неясном владении terminal и known child exit, не ложный clean.
При падении constructor bounded finally закрывает только свои ресурсы; cleanup-error→UNKNOWN.
Штатные Runner.close/Proactor.close/SIGINT/wakeup-fd оставить: signal.set_wakeup_fd(cs.fileno()) и сброс -1 на main thread.
Не утверждать восстановление прежнего wakeup-fd: штатный close сбрасывает -1; cold isolated child не делит signal state с другими задачами.
Teardown: завершить/cancel задачи, дождаться worker, штатный shutdown, отменить IOCP read, закрыть pair и loop, проверить strong identities.
Root регистрирует child PID/FILETIME/argv/cwd ДО ожидания; port initially unknown, затем числовые роли p/q сразу после получения.
Listener p кратковременно LISTEN; pair p/q живёт до close. Root сверяет собственные активные TCP endpoints и закрытые handles.
TIME_WAIT фиксировать отдельно как состояние ОС; не выдавать за активного владельца и не обещать «порт свободен».
Никаких probe-connect для проверки уборки, process-name kill или действий над чужими PID/портами.

## Проверки и отдельные допуски
Plan→fresh security review→bounded Source06→fresh exact source/model admission→Root один bounded wake-only HOST cohort.
До source/run согласовать фиксированные IDs/N. Существенные negative: foreign caller/thread/loop/socket, wrong endpoint/family,
replay/budget/order, changed pins/ABI, expired permit, peer mismatch, cancel/constructor failure, accepted-fd adoption failure.
Модели≠native cleanup/peer/worker proof.
HOST cohort обязан проверить штатную pair и реальный FastAPI contextmanager_in_threadpool/worker-return, затем известный teardown;
native negative на accepted-fd, timeout/peer/cancel проверяют отсутствие новой authority и owned leaks. Нет автоматического retry.
Projection: fixed codes/roles/counts/bools, PID/FILETIME, числовые ports; no repr/args/payload/raw stderr/hash secret.
Parent сохраняет matched self-frame и безопасный failed projection до functional FAIL; known-CLOSE/8192-byte caps сохраняются.
После принятого wake-only результата отдельный Source/integration gate для transactions и оставшегося40-case cohort.
Wakeup/schema68 не принимают Session/rollback/cancel/Auth. Source05 не повторять.

## Будущие пути и source pins
После отдельного допуска Root регистрирует ровно6: E/auth-wakeup06-source/{qa_wakeup06.py,wakeup06_host_case.py,test_wakeup06.py},
E/auth-wakeup06-source-plan.json, E/auth-wakeup06-source-change.json, E/auth-wakeup06-root-host.ps1; all≤262144B.
Future cwd T/auth-wakeup06-owned; logs L/auth-wakeup06-host.stdout.log/.stderr.log; E/auth-wakeup06-host-results.json/-process-cleanup.json.
Сейчас этих ресурсов не создавать. Parent120s/child20s/stop3+3s/outer150s, clean5env, direct base PE, exact selfPID/FILETIME vs Popen как05.
Base python.exe SHA805fd29612c221ebea570d372ec5ed46093c25a1033f1765de9b2f8103868886, 3.12.9/105832B; venv sites прежние.
Полные Source05 pins и AnyIO/FastAPI/Starlette pins наследовать как references, а не читать configs/env/ключи.
Текущие прочитанные primary SHA256 (перед Source06 перечитать и fail closed при отличии):
Lib/socket.py 423c823130b3e26ebf5bd5645cf1d263d5e3e23c1ffcd02ddd54acb60eb7d6b5
Lib/asyncio/proactor_events.py b00ece2377455b6dd2069e8e7d1efd6f79b635b37feb4fc5b47471477e2d3f86
Lib/asyncio/runners.py 14dd7723373ceed13e9f45fdb578e0e1aea46d62d63023dd5cbc118ca9f57a19
DLLs/_socket.pyd 961dc7c65a28c174688e2f6b7803a615b9c034849a17cfc6527a27ccb2eeadb0
python312.dll b6227a506a9963e7c8182785a54e14a193af51f7b277a61dda04492b499f49ad

## Почему не pipe первым
B/Lib/asyncio/windows_utils.py:32–75 создаёт Windows named pipe namespace через CreateNamedPipe/CreateFile.
windows_events.py:482–491/538–546 умеет pipe ReadFile/WriteFile. Но штатный self-pipe ожидает setblocking/send и main-thread signal wakeup fd.
Pipe возможен как кандидат; совместимость signal handle не доказана, нужны иные wrappers/teardown.
Native ABI/partial-fd cleanup блокируют приёмку до fixtures.
Socket/loop/DB/новый код не запускались. Новых процессов/runtime-файлов0.
