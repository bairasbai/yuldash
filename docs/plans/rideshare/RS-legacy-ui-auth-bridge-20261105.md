# План: собственный участник через обычный вход, мост OTP03
Статус DRAFT / RE-REVIEW_REQUIRED после P1 dotenv. Предыдущая приёмка ограничена source-stage; весь план/runtime НЕ принят, реализация/запуск не разрешены этим уточнением.
Автор: /root/gate175_deadline_analysis; будущие исходники, данные, процессы и запуск принадлежат /root.
Сейчас разрешён только S/docs/plans/rideshare/RS-legacy-ui-auth-bridge-20261105.md.
S = C:/Users/Bayra/.codex/worktrees/rs-plans-20261024/Yuldash.
E = S/test-results/legacy-reconcile-20261102.
T = C:/Users/Bayra/AppData/Local/yuldash/legacy-reconcile-20261102.

## Цель и границы
Первый результат — один собственный синтетический участник, созданный штатным UI (экраном приложения), с доказанной принадлежностью.
HomeBack, отмена водителем и номер автомобиля — последующие отдельные шаги. Полная попутка и платежи открыты.
Старые участники5/7 — NO_ARM: не читать их телефоны, коды, записи и не принимать прежнюю сцену за владение.
Существующий OTPbridge03 в E/T не найден; этот план не объявляет его реализованным.
Принятый198 доказывает только движение40 без PNG, не вход/создание участника/полную видимость текста.
Проверенная квитанция E/native-runtime-stop199-verified.json: SHA256 64b53a3d93c24523003e34210656fadc212ff54c83dae69a7b9dcea626316982.
Root штатно закрыл13 служб171;10 портов были свободны на момент199. Перед будущим стартом нужна новая проверка; shared ADB3288 не принадлежит уборке.

## Проверенные исходные места
A = S/backend/app/routers/auth.py:35–36 импортирует send_sms по значению; request_code:363, _request_code:373.
A:398–401 создаёт штатный OTP; :420–428 — flush → send_sms → commit; :430–431 добавляет dev_code в dev-ответ.
A:529–554 ищет/создаёт User; _complete_login:325–349 вызывает штатную выдачу токенов и возвращает пользователя.
B = S/backend/app/services.py:1247 — send_sms; mock/dev:1292 пишет OTP в журнал. Этот fallback вызывать нельзя.
C = E/native-preflight03-source/qa_legacy_ui_api02.py:43–55 — QA-route и local/dev/mock-проверки; :59–117 — /__qa/preflight.
C:93–96 проверяет старую связь сцены12/9/7/5, но не доказывает её происхождение; :136–139 — sourceSHA/рождение процесса; :161–163 — runtime-record.
SHA256 C = f7c29ad93977a1391ae4cf3a33dd8dfb43a36f67c8825a2892c05c2583ca6470; sender/callback03 отсутствуют.
D = E/native-preflight197-source/native-ui-preflight197.mjs:276 строит digits argv; :619 скрывает argv только в event; :621 вызывает настоящий cp.spawn(file,args).
D:1043 держит argsKey в RAM, :1048 очищает его; :1049–1051 передаёт скрипт ввода в argv; finally:1053–1054 освобождает ссылки.
D:1056–1061 создаёт UI/XML-файл через uiautomator dump в OWN_XML (:20), читает и удаляет: этого недостаточно для приватной фазы.
D:686–693 требует точный wrapperSHA/runtime-record;03 нельзя подложить под прежний PIN.
S/backend/app/db.py:85–95 создаёт схему и вызывает SQLite-миграцию.
S/backend/app/main.py:76–98 — lifespan; даже seed_demo=false не выключает тарифы/справочники :86–97.

## Bootstrap до первого Settings(): P1, требуется повторная приёмка
E/auth-bridge03-plan-independent-review.json SHA256 ddb7f3ed395d3f9bc16dc0776d5fcc38a05767f15ca8cd1f32eace218514051d принял только ограниченный source-stage, НЕ весь план/runtime.
S/backend/app/config.py:1012 задаёт env_file=".env", :1237 выполняет Settings() при import; SHA256 файла 7cb7d05624699bb3b84717bb68ff45b835d7aa8ef5b855972edbb09848ce0476.
V = C:/Users/Bayra/Yuldash/backend/.venv/Lib/site-packages; P = V/pydantic_settings. До импорта проверять actual origins и полные pins, не только version/name.
Pins SHA256: P/__init__.py ff66988e63ae908d0ab176e4740b279cd093730c4397ca928b95ff763189f6f7; P/main.py 8d0818d7fce849bec49de9b8491e673f56e9a196f274ee3843f4a6993ac3ba7c; P/sources/base.py caa7618753dcd70d538a50acbd008d9950ba2108bd82a1149284c5efde88fed4.
Pins SHA256: P/sources/providers/env.py 48583a01a7958126b068f790837430c4e8d7bb9c6d7687adeda6aedfc4bf133d; dotenv.py 617f55be13574381cbbc84006277005c762c4b102601ab33c8df3765c09ed3d1; secrets.py 9390858d2e889901f898ffdb4da549dc8abc445fee97497d14450f25463c60b9.
По P/main.py:316 явный _env_file=None отменяет class env_file; dotenv.py:95–98 сразу возвращает пустой результат, не проверяя/открывая путь.
_secrets_dir=None НЕ универсальный запрет: P/main.py:373 и secrets.py:50 берут class config; пустая последовательность не задаёт путей (secrets.py:61–72).
Одного settings_customise_sources недостаточно: DotEnv source конструируется main.py:395 ДО hook:417; env.py:72–75 читает os.environ уже в constructor.
До создания owned child передать НОВЫЙ whitelist env, не наследовать всё с поздней очисткой; только проверенные OS-пути, явный PATH/TEMP/TMP, без PYTHON*/провайдеров/DB/auth параметров родителя.
Предложенный запуск абсолютным approved Python с -I -S и явными pinned module paths; отключение .pth/sitecustomize и безопасный startup ещё обязан доказать HOST bootstrap fixture. CWD-only не защита.
До app.config/db/services/auth/main: assert этих модулей нет в sys.modules; проверить config AST: ровно Settings(BaseSettings), один нулевой Settings():1237, нет override __init__/_settings_init_sources/_settings_build_values; любые отличия → DENY.
Выбран child-only RAM shim BaseSettings.__init__, установленный ДО import app.config: разрешён ровно один вызов точного sys.modules["app.config"].Settings, без caller args/kwargs; второй/чужой вызов → DENY.
Shim вызывает сохранённый original_init(self, _env_file=None, _secrets_dir=(), _build_sources=((owned_RAM_source,), owned_RAM_values)); source возвращает только явные OWNED RAM-настройки и новый RAM-secret.
Это source-обоснованный контракт, не готовый код: main.py:212–245 при non-None _build_sources НЕ вызывает _settings_init_sources; :457–482 вызывает единственный RAM-source, затем обычную Pydantic-валидацию :247.
Пустой sources использовать НЕЛЬЗЯ: main.py:483–486 возвращает {}; нужен один callable. Сигнатура _build_sources и все эти ветви pinned; исчезновение/изменение → fail closed, без fallback к dotenv/customise.
Settings/values/RAM-secret не repr/model_dump/JSON/log/argv; ошибки bootstrap/ValidationError подавляются фиксированной причиной без traceback/input_value/chaining. Никакого патча библиотек/прода на диске.
Порядок единственный: clean child → source pins/shim → config/Settings(memory/dev/mock) → pre-db engine factory → db → services/замена sender → auth/проверка alias → product app → QA schema-only lifespan.
ДО import db установить child-only sqlmodel.create_engine factory: допускается только точный первый callsite db.py:31–32 с уже эффективным memory URL; добавить controlled StaticPool, запретить иные URL/plugins/неожиданные kwargs/второй engine.
V/sqlmodel/__init__.py:4 re-export create_engine; SHA256 a466c41e3f1be03aa723720fda834a67cd13758f725a3e7fc407e59d06ba881c. V/sqlalchemy/engine/create.py SHA256 1ef921e16b07fd6e0e00cab596aeb7f8899da21a2bc50a492c20f464437316e8.
create.py:157–164: сам create_engine не устанавливает DBAPI connection; это не доказательство отсутствия любого побочного эффекта импортов. Fixture обязан подтвердить zero connect до controlled schema/readiness.
Никакого placeholder/file engine с последующей подменой: sole OWNED memory engine с самого db import; source-stage pin db/services/auth/main, assert всех использованных engine aliases, DB Session/transaction serialization для всех допущенных routes.
До source/runtime допуска нужны HOST fixtures без секрета: file-open/stat traps для dotenv/secrets без реальных config files; poisoned inherited env/second Settings/signature drift/invalid RAM-value не читаются/не печатаются; проверка startup/import order/engine aliases/zero premature connects.
Полная import/no-file гарантия пока НЕ проверена runtime. Только после fresh review этого контракта → bounded source → эти fixtures → новая независимая приёмка; никакого implementation/import/test сейчас.

## Новая собственная SQLite только в памяти: регистрация до создания
DB-resource: /root, новый run/nonce + PID/birth + engine/physical-connection generation; файлового пути БД/sidecars НЕТ.
Создать новый отдельный in-memory SQLite engine, не открывая/копируя прежнюю БД/actors-файлы/S/backend/yuldash.db/секретные конфиги/ключи.
До db import memory URL уже эффективен; pre-import factory создаёт только OWNED StaticPool engine, без file/URI/ATTACH/placeholder; до auth проверить все engine aliases.
Новый engine должен существовать только в одном процессе, без workers/reload/reconnect; потеря connection/invalidation/dispose до конца → REVOKED, не молчаливое создание новой БД.
Локальный installed source L = C:/Users/Bayra/Yuldash/backend/.venv/Lib/site-packages/sqlalchemy.
L/pool/impl.py:458–472: StaticPool держит ровно одно connection; :461–463 предупреждает об ограниченном reconnect/invalidation.
L/dialects/sqlite/pysqlite.py:230–234: default memory pool различает БД по потокам; :267–282 описывает shared connection/StaticPool/check_same_thread=False.
L/orm/session.py:1461: Session не безопасна при конкурентном использовании потоками. Наличие StaticPool само по себе НЕ сериализует транзакции.
Будущий QA-only engine: memory + StaticPool/check_same_thread=False; одна Session на запрос, никакой совместной concurrent Session.
Нужен QA-gate полной DB-транзакции/сессии для ВСЕХ request/background DB access; прежний lifespan/фоновые DB writers не запускаются. Потоковую передачу Session проверить.
Настоящий HOST fixture без секрета должен доказать shared single connection между request threads, сериализацию/rollback/commit, отсутствие file/temp spill и отказ при connection loss; сейчас НЕ проверено.
Перед схемой отдельно проверить memory temp-store/journal и database_list, без чтения OTP/User records; не обещать отсутствие OS pagefile/dump по одному названию SQLite memory.
Schema-only initializer регистрирует модели/create_all в новой пустой memory DB; старый lifespan с seed_tariffs не запускается.
QA-only состав lifespan требует source-review: обычные auth-маршруты/транзакции сохраняются, startup-побочные записи/внешняя сеть закрыты.
До readiness доказать пустые пользовательские таблицы, включая User/Ride/Booking; старую БД не подключать.
User/Ride/Booking создаются только UI. Прямые INSERT, готовые актёры и qa_synthetic_login_actor01.py запрещены.
Reference seed для первого входа по умолчанию НЕТ; необходимый публичный справочник — отдельные перечень/источник/причина/приёмка; тарифы вне плана.
До readiness: exact local/dev/mock memory DB/Redis, sender identity, отключённые реальные отправители/задачи; конфиги явно в RAM, ключи/env-файлы не читать.
Происхождение синтетического номера и отсутствие реального адресата требуют проверяемой политики; не брать номер из старых записей. Недоказанность — DENY.
Для будущих НЕсекретных HOST fixture artifacts зарезервирован только T/auth-bridge03-owned, owner=/root; exact имена/цель/cleanup регистрировать до создания. Сейчас каталога/файлов не создавать.

## QA-wrapper03 и граница штатной авторизации
Предложенные будущие файлы (сейчас НЕ создавать): E/otp-bridge03-source/qa_legacy_ui_api03.py и отдельные backend/HOST fixtures.
Будущий Native-пакет приватного UI регистрируется отдельным exact source-планом. APP178 и существующие195/197 сейчас не менять.
До import auth заменить services.send_sms закрытым локальным sender; после import явно связать auth.send_sms и assert идентичность ТОМУ ЖЕ объекту.
До приёма запросов assert обеих aliases, exact mock/dev/DB, wrapperSHA/PID/birth/APIgeneration и канала; при несовпадении сервер не ready.
Исходный services.send_sms не вызывать ни при успехе, ни при ошибке. Никакого real/mock/log fallback при сбое pipe/cancel.
Sender принимает только контекст одного разрешённого обычного UI-запроса; чужой вызов отклоняется с откатом штатной транзакции.
Обычные gen_otp, OTP storage/consumption, создание User, issue_tokens и normal APP/AuthStore сохраняют продуктовую семантику.
Запрет persistence относится к мосту, операторским инструментам и доказательствам: не читать getters/dev_code/OTP-таблицы/token/prefs/AuthStore.
Мост не добавляет rawOTP/телефон/хешOTP/token в лог, файл, receipt, traceback, HOST argv, attestation или MAC; OTP не хешировать даже для сравнения/квитанции.
Не обещать отсутствие секретного хранения во всём продукте: A:399–401 сохраняет штатный code (в новом стенде только в memory DB), A:333 выдаёт токены; normal APP/AuthStore не подменяется.
Если запрещено и штатное продуктовое хранение OTP/токенов, это конфликт с неизменённой авторизацией и БЛОКЕР, а не разрешение переписать auth.
В QA-only HTTP-адаптере удалить поле dev_code по имени ДО ответа клиенту, никогда не используя его значение как источник кода.
Сохранить обычные статус/ошибки/sent; не логировать тело и не выдавать код через другой endpoint. Точный адаптер требует source-review.

## Один обычный запрос и канал только через память
Перед UI-нажатием Root проверяет03 и через отдельный control-pipe взводит один случайный nonce с APIgeneration.
Nonce связывает серверное окно с единственным request-id; не заявлять, что неизменённый APP передаёт его HTTP-заголовком.
Привязка: run/nonce/generation/request-id + wrapperSHA/PID/birth + controller PID/birth + endpoint/method + peer/UID/boot/foreground.
Localhost-peer сам не доказывает источник APP: нужна проверяемая связь UI-нажатия, закрытой сети своего UID и единственного запроса; неясная/двойная/чужая кандидатура — DENY.
Ровно1 обычный UI request-code. CLI POST, второй POST/resend, dev_code-getter и обход UI запрещены.
Sender-callback кладёт код только в bounded RAM-slot этого request-id; callback ДО commit ещё НЕ успешная доставка.
Состояния: ARMED → CALLBACK_PENDING → COMMIT_CONFIRMED → RESPONSE_SENT → CLIENT_OTP_SCREEN → CONSUMED; неизвестность → REVOKED.
Seal только после доказанного commit той же транзакции и успешного обычного HTTP-ответа; after_commit без ответа недостаточно.
ASGI send не доказывает clientreceived: потребление только после свежего валидированного OTP-экрана клиента этой цепочки.
До CLIENT_OTP_SCREEN plaintext потребителю не выдавать; затем ровно одно чтение по bound nonce/generation. Verify-code — только ordinary UI.
Expiry ограничен исходным Native session120s и штатным OTP TTL консервативно от начала запроса; proof5s/launch10s не продлевать, часы не менять.
Rollback/response loss/disconnect/duplicate/replay/смена peer-PID-boot-generation/cancel/expiry/unknown CLOSE: revoke, освободить ссылки, no retry/no authority.
Физического затирания managed-memory/OS buffers не обещать; ограничить lifetime/размер, запретить сериализацию/dumps/debug logging, закрыть handles.
Предпочтение — наследуемые анонимные pipe с явным allowlist handles: отдельные bounded data/control channels; не stdout журналов и не localhost OTP-getter/socket.
Фрейм несёт необходимые RAM-поля привязки и один код; public runtime/receipt — только identities/статусы/счётчики, без телефона/кода/token/их хешей.
Windows Python↔Native inheritance/framing/EOF/backpressure/owner binding ещё НЕ проверены: нужен настоящий HOST fixture с несекретным маркером.
HOST fixture не отправляет SMS/HTTP и не запускает SDK; ошибки только фиксированные причины, без frame/body.

## Приватный UI и ввод: блокеры до actual
С начала номера до конца входа запретить ordinary visible/filedump/screenshot/PNG, UI-трассы и сохранение сырых строк; tapText Confirm тоже вызывает dump и пока запрещён.
S/android/app/src/main/java/com/yuldash/app/LoginScreen.kt:708,712 и :916,922 — value=code/NumberPassword; явный PasswordVisualTransformation поиском не найден. Реальная XML-видимость значений НЕ проверена.
Нужен bounded RAM-only collector полного XML: exact UTF-8/EOF/лимиты/полное дерево, свой package/структура/attrs/parents, actor/PID/boot/foreground/фаза.
Stage0 metadata-only PUBLIC P0: через тот же NativeSDK/Root, новую source/model/runtime-цепочку на изолированном стенде проверить stream feasibility без OTP. Никакого CUA/instrumentation bypass/новой captureauthority.
uiautomator /proc/self/fd/1 — лишь кандидат: поддержку, отсутствие промежуточного файла и полное дерево доказать сначала на публичном несекретном экране.
Если producer пишет XML на диск или отдаёт неполный/неизвестный UI — sensitive actual запрещён; последующее удаление не исправляет утечку.
Телефон/OTP сравниваются и вводятся только в RAM текущей фазы; наружу collector возвращает фиксированное состояние/безопасные счётчики, без значений/хешей.
Нынешний digits НЕ принят для auth: redaction event D:619 не убирает raw input из настоящего host argv D:621,1049–1051.
Цель нового private guarded host-stdin handoff: без секрета в HOST argv/agent-evidence/логах/ошибках, с прежними nonce/5s/one-use/knownCLOSE/проверками.
Guest input-process argv может содержать значение в RAM; host stdin-only не доказывает его отсутствие. Факт OS-аудита/дисковой записи старого argv НЕ установлен; такого утверждения не делать.
Гарантию host-argv/evidence и неизвестную guest/OS границу отдельно принять по исходникам/фикстурам; не вводить общий новый запрет guest argv без основания и не обходить guards.
Штатный logout/конечная уборка нового входа без чтения prefs/AuthStore требуют проверки; не объявлять app-side секреты удалёнными без доказательства.

## Принадлежность участника и аттестация
Receipt только после commit нового User, успешного verify-response и доказанного UI-перехода клиента; до этого владение НЕ доказано.
Проекция: новая DB/run identity, new user-id, created-in-this-run=true, request/response counters, commit/client-phase statuses; без телефона/OTP/token/их хешей.
User-id обрабатывается в RAM whitelist-проекцией response/commit; не читать/сохранять токены тела или AuthStore и не заменять авторизацию.
Прежнюю сцену12/9/7/5 исключить из03 attestation; readiness доказывает новую пустую OWNED DB/схему/sender/channel.
WrapperSHA/runtime-record/Native policy — закрытая связанная пара02/03 с exact mode/db/sender/schema/source pins; AUTH требует03, fallback02 запрещён.
Новый SHA неизвестен до source-freeze; старый f7c29 PIN нельзя подменять неподтверждённым значением.

## Стадии и критерии приёмки
1. PLAN → fresh security-review: штатное хранение/peer-binding/guest argv/XML/schema-only lifespan; открытые блокеры не считать PASS.
2. Только отдельный ACK → bounded source, exact ownership новых путей/inverse; без APP UI-переписывания/новых зависимостей/SDK-версий/ключей.
3. Backend/mock модели: настоящий request-handler/generator/commit, ровно1request, sender identities/noFallback/dev_code removal; rollback/response loss/duplicate/replay/expiry/cancel, запрет OTP-getters/старой БД.
4. HOST fixtures без секрета: single memory connection/thread/Session/rollback/connection-loss, реальные pipes/PID/birth/foreign peer-gen-nonce/truncation/overflow/EOF/hang/unknownCLOSE; без code в disk/log/HOST argv, гарантированное закрытие.
5. Native privacy модели + отдельный несекретный producer-test: полный RAM XML/file-write trap/secret-free HOST argv-events-errors, ordinary UI/guards, no premature consume/no input после unknownCLOSE; сохранить прежние612 тестов.
6. Source/runtime attestation03/пустой DB/текущего APK/source/новой сети → fresh review конкретного Root driver → ONE первый UI-участник.
7. Независимая приёмка creation/ownership/cleanup. Неудача не разрешает повтор/secondPOST/старых5/7.

## Ресурсы и уборка
Будущие процессы только /root: PID/birth/full command/cwd/ports, скрытые окна, finite deadline; перед стартом leases и свежая проверка портов.
Предложенные порты только8000/56379 и выделенный Root emulator5554/5555 после проверки; shared ADB3288 и чужой6379 не трогать.
Secret pipe без TCP-порта; loopback API не становится OTP-каналом. Никаких фоновых watchers/широких HTTP capabilities.
После ошибки/отмены/успеха: revoke → закрыть pipes/свои children → штатно остановить свои API/Redis/APP/VM → проверить точные PID/порты.
Memory DB: закрыть все Session/connection и dispose единственного engine после revoke; проверить завершение процесса. DB-file/sidecars создавать/удалять не требуется.
После приёмки сохранить обезличенные обязательные квитанции/source pins; убрать только зарегистрированные own НЕсекретные HOST-temp artifacts/пустой каталог после закрытия handles и проверки состава/границ.
App-side уборка только проверенным ordinary logout/выделенной средой; не читать/стирать общие prefs и чужой AVD.
Дополнительные временные файлы регистрировать до создания: exact paths/owner/purpose/cleanup; source и обязательные доказательства сохранять.
Прод/реальныеSMS/деньги-тарифы/чужие данные/main-push/ключи-env-local.properties/API-login вместо UI запрещены; требующее §8 действие — отдельное «да» Александра.
Сейчас только этот PLAN: реализации/БД/pipes/моделей/Node-SDK-API-запросов/временных процессов — 0.
