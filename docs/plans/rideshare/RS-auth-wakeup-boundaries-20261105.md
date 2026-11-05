# Source09: отказы и отмены собственной Windows-пары

Владелец плана/будущего выполнения: Root; автор отдельных QA-файлов: gate175. Продуктовый код не меняется. Сейчас только план; фактический запуск09 не разрешён.

Основание: один worker-positive Source08 фактически PASS, E/auth-wakeup08-host-results.json SHA995e2b8659b80c5136feee83cfdfca73212af144d90ae8410c2d5fb8ca72aa29; уборка SHAe5464ff240ed74f9227ba7a6e518f7dfb1156eecb58a86de4a77eaeb5d44f1f1. Наблюдались audit2/3strong roles, nativeFD/peers/worker/закрытие пары; oldrawFD=false/directintegerclose0. Три процесса завершены; ports51230/51231 active0; TIME_WAIT51230 отдельно, владение не приписано. Независимая приёмка actual08 требуется до реализации09. Источники08 и доказательства неизменны.

1. Сохранить принятый Source08 returned-slot ownership/close contract, физические3 роли, bind1/connect1, default DENY; не увеличивать original grant/admissionTTL2s, child20/parent120/outer150/stop3/kill3, bounded streams/queue. Никаких stdlib/policy/version изменений.
2. Базовый positive после изменения helper обязательно входит в набор. Обновить 21 старый control для новой returned-slot ветки честно; прежние accepted-audit/adoption названия и object-budget не считать выполненными в этой ветке.
3. Всего 24 выбранных случая: 1positive +23controls. Точный список ниже. Все реальные ожидания используют original monotonic deadline; не вводить fakeclock и не продлевать TTL.
4. Четыре post-slot точки: внедрённый отказ, внедрённая отмена и реальное истечение после завершения cleanup-only slot registration до допуска; ещё реальное истечение после peer/output и установки LOOP_READY перед последним deadline/return. Во всех этих случаях сохранены3strongroles, nativeFD и metadata; body не должен начаться, штатное закрытие pair обязательно. Это не проверка отказа native raw-fd adoption.
5. Для role-budget-binding использовать только явно обозначенную модель проверяемого счётчика при реальных3ролях. Не создавать четвёртый сокет и не заявлять native prevention четвёртого объекта. wrong-family/endpoint/order/peer expectations тоже модели; не настоящие чужие endpoints.
6. Для replay и ordinary bind/connect сохранить реальные вызовы. Host требует returnedSlotCaptured/slotAdoptionObserved/nativeFDValidated/peers/physical3/закрытие pair вместо ложного exact3audit/raw-adoption. Worker enter/body/exit/return остаются обязательными там, где они должны произойти. Реальный cancel-body сохраняет проверку отмены и выхода из context.
7. Raw-FD флаги допускаются только по старому фактическому provenance; если действительно наблюдался raw fd, прежняя проверка закрытия остаётся. После вернувшегося конструктора integerclose запрещён. Неизвестное владение/закрытие — UNKNOWN/non-PASS.
8. Helper/host/parent имеют один одинаковый case/reason catalog и закрытые типы facts; parent выбирает ровно все24, failfast при первом отказе. Различать ожидаемый DENY и неожиданное ERROR; не скрывать пропуски. Непройденное/модель не выдавать за native PASS.
9. Точный catalog (ID -> expected reason):
worker-positive -> OK
foreign-factory -> WAKE_CALLER
foreign-thread -> WAKE_THREAD
foreign-loop -> WAKE_SCOPE
foreign-socket -> WAKE_SCOPE
wrong-family-boundary -> WAKE_FAMILY
wrong-endpoint-binding -> WAKE_ENDPOINT
wrong-order -> WAKE_ORDER
role-budget-binding -> WAKE_BUDGET
replay -> WAKE_ONCE
source-pin -> SOURCE_PIN
abi-drift -> WAKE_ABI
expiry-build -> WAKE_EXPIRED
peer-expectation-binding -> WAKE_PEER
cancel-build -> WAKE_CANCEL
constructor-fail -> WAKE_CONSTRUCTOR
returned-slot-fail-before-admission -> WAKE_SLOT_FAILURE
cancel-body -> WAKE_CANCEL
ordinary-bind -> WAKE_SCOPE
ordinary-connect -> WAKE_SCOPE
expiry-after-returned-slot -> WAKE_EXPIRED
expiry-before-first-socket -> WAKE_EXPIRED
expiry-before-final-return -> WAKE_EXPIRED
cancel-after-returned-slot -> WAKE_CANCEL
10. Классификация24: positive1; native default-denial6 (четыре foreign +ordinary bind/connect); replay1; injected build failure/cancel2; injected post-slot failure/cancel2; real expiry4; real body cancellation1; binding models5; source/ABI models2. Сумма1+6+1+2+2+4+1+5+2=24; ни одна группа не подменяет другую.
11. Подготовка только в новых путях: E/auth-wakeup09-source/{qa_wakeup09.py,wakeup09_host_case.py,test_wakeup09.py}, E/auth-wakeup09-source-plan.json, E/auth-wakeup09-source-change.json — sole writer gate175; E/auth-wakeup09-root-host.ps1 — только Root. Exact leases до записи,6pins/cap262144 и короткий08→09diff/inverse. Reviewer не исполняет код; после реализации — свежий независимый gpt-6.1-sol, не автор плана/кода.
12. Только после независимых plan/source приёмок и Root fresh6+16pins/PE/base/venvcfg/nonreparse preflight может быть разрешён один cohort24 через pinned Rootdriver Expected24. DefaultDENY/worker/cleanup/knownCLOSE/live PID FILETIME command cwd +listener/client/accepted-peer port roles сохраняются. Никаких дополнительных probes/моделей и повторов неизменённого отказа. Одно ожидаемое DENY с неизвестной уборкой — ERROR/non-PASS.
13. Будущие runtime пути (сейчас не создавать): T/auth-wakeup09-owned; L/auth-wakeup09-host.stdout.log/.stderr.log; E/auth-wakeup09-host-results.json и E/auth-wakeup09-host-process-cleanup.json. Root отвечает за точную остановку своих процессов и измерение active TCP; TIME_WAIT отдельно. После CLOSE/независимой приёмки Root удаляет свою пустую cwd и ненужные rawдубли; minimal source/results/cleanup/reviews сохраняются до окончания evidence-retention.
14. Успех24 не разрешает DB/Auth/OTP/HTTP/server/integration40/fullmode. Интеграция с memory04 требует следующего отдельного плана: старый audit hook безусловно DENY bind/connect и два hook не заменяют один другим. Никаких ключей, защищённых файлов, старых actors, SDK/Gradle/эмулятора/GitHub/main/push/deploy.

E=S/test-results/legacy-reconcile-20261102; S=C:/Users/Bayra/.codex/worktrees/rs-plans-20261024/Yuldash.
T=C:/Users/Bayra/AppData/Local/yuldash/legacy-reconcile-20261102; L=C:/Users/Bayra/AppData/Local/yuldash/rideshare-claude-tails-20261024.
