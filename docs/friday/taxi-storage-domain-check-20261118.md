# Storage-domain CHECK: test-only контракт до реализации

База localbc3df9b / remoted713b38fc08b7f1ced051e56a9768b2d6e327d3d. Цель следующего узкого пакета: показать, может ли проверяемое сохранённое значение быть совместимым с нынешним ExactGeneration без молчаливой normalization. Это НЕ app migration/model/writer rollout, не production cutover и не готовое durable продолжение поиска.

## Выбор исследуемого domain

Нынешний adapter не менять. Его signed bounded domain шире исследуемого storage subset: NULL либо неотрицательное целое менее 10**128. PG хранит его как unconstrained NUMERIC с scale=0; SQLite candidate — canonical ASCII digits TEXT. Отказ raw `1.000` в PG намерен: decoder умеет принять его, но storage subset требует canonical integer scale. Не представлять это как полную эквивалентность всех adapter/raw входов. Отрицательные старые/corrupt данные не исправляются, migration preflight остаётся OFF, а не новым account409.

## PostgreSQL actual-schema controls

Оба actual bootstrap source, свежие guarded clones и phase1/2 cleanup без FORCE/terminate/retry. Phase1 upgrade собственных двух колонок, затем test-local ADD двух именованных CHECK в одной caller transaction. ACCESS EXCLUSIVE до authoritative manifest/raw preflight; all resume NULL и existing eligible nonnegative integer scale0 values. Не принимать raw непроверенные данные на веру. Unsupported shape/dependencies reject до DDL.

Кандидат для каждого value:

```sql
value IS NULL OR (
  value::text NOT IN ('NaN', 'Infinity', '-Infinity')
  AND value = trunc(value)
  AND value >= 0
  AND value < 1e128::numeric
  AND scale(value) = 0
)
```

Это исследуемый CHECK на используемом PG, не уже утверждённый DDL. SQL three-valued logic/NULL контролируются явно. NUMERIC(128,0) не использовать вместо validation: casts могут округлить fraction. Никакой rewrite существующих values/decoder.

Positive обеих колонок: NULL, 0, 2**53+1, MAX-1/MAX/MAX+1, 10**40+1, 10**128-1, actual typed adapter bind/result/RETURNING/equality CAS и independent raw Decimal readback. Negative raw controls: fraction, negative, NaN/±Infinity, 10**128, integral `1.000`, bounded huge scale. Проверить exact SQLSTATE23514 и конкретное имя column CHECK; не считать driver/bind rejection constraint proof. После savepoint/outer rollback independent unchanged full data/catalog.

Два CHECK добавляются атомарно: injected failure после первого/второго ADD восстанавливает исходный NUMERIC catalog/data. Данные preflight при rejection не меняются. Manifest allowlist только двух exact CHECK definitions/имён, остальное включая source-specific ledger indexes неизменно. Совместный UPDATE двух колонок с одним допустимым и одним overflow value должен отказать без частичной записи. На границе max→max+1 отказ отдельно не означает бесконечный прогресс или новый account-policy.

## SQLite controls: только отдельная synthetic fixture, НЕ actual rebuild

Свежий собственный tempfile, minimal test-local table с двумя TEXT колонками и named CHECK. Не изменять actual InstantOrder и не выдавать это за SQLite app migration/rebuild. Использовать actual adapter через test-local Core; independent physical connection readback/rollback и физический typeof/quote(value). Guards собственных paths, no production DSN, cleanup files/sidecars после закрытия connections.

Кандидат predicate для каждого value: NULL либо typeof='text', length 1..128, byte length равна text length (защита embedded NUL/nonASCII), NOT GLOB '*[^0-9]*', и либо ровно '0', либо первый символ '1'..'9'. Canonical digits only, без sign/leading zero/whitespace/scientific/decimal spelling. SQL affinity может преобразовать вход до CHECK: гарантируется только сохранённое значение, НЕ первоначальная точность raw caller.

Positive/negative literals и typed adapter inputs фиксируются отдельно: NULL vs zero, large canonical128, 129digits, +0/-0/00, nonASCII digits, embedded NUL, spaces, exponent/fraction, BLOB. Настоящая SQLite IntegrityError/constraint attribution и unchanged rollback, не mocked validator. Обязательные negative boundaries: lexical TEXT ordering не numeric; raw SQL arithmetic/CAST может сначала потерять точность, а потом записать другое валидное canonical число. Такой PASS лишь воспроизводит ограничение, не proof защиты старых writers. Не использовать SQL arithmetic в положительном prototype.

## Следующий all-writer/model шаг и сохраняемые блокеры

После результатов отдельно инвентаризировать точные remaining SQL range/coalesce/arithmetic/validators, actual model/schema compatibility и admission/version/cutover boundary. CHECK не мешает старому writer записать другое допустимое число; drain активных процессов не устраняет persisted replay. 128-digit budget всё ещё конечен, tokenless clients и negative-history ABA не решены. Пользователь выбрал durable автоматическое продолжение, а не тихий отказ удаления; этот пакет не добавляет account restriction или producer/worker activation.

Критерий: независимый pre-code review, frozen static, авторские и независимые реальные PG/SQLite controls + применимые unchanged regressions, exact exit/cleanup, отдельная ветка. Main/production/paused операции не трогать.

## Независимый pre-code GO и точности

CHECK валидирует STORED value, не исходный spelling: PG может нормализовать exponent/-0, SQLite affinity преобразует вход. Raw `1.000` отдельно наблюдается как фактический NUMERIC scale3 до ожидания CHECK rejection. PG AND не обещает порядок/short-circuit: все выражения должны безопасно вычисляться для NaN/±Infinity; каждый negative case обязан дать named 23514, иной SQL error — FAIL кандидата, не PASS.

Под ACCESS EXCLUSIVE existing-value preflight проверяет полный finite/integral/nonnegative/bound/scale0 predicate для обеих колонок через IS NOT TRUE, плюс all resume NULL; UNKNOWN не считается eligibility. Два CHECK plain ADD (validated), не NOT VALID/NOT ENFORCED; actual pg_constraint convalidated и definitions читаются обратно. При invalid одной колонке другая валидна для точного attribution; если invalid обе, порядок constraint не обещается. Savepoint rollback оставляет outer transaction usable, независимое чтение доказывает атомарность данных/каталога.

Нынешний signed adapter принимает -1: typed -1 должен получить отказ storage CHECK, не bind validation. Bool/float/overflow typed inputs отвергаются до SQL отдельно. SQLite проверяет actual SQLITE_CONSTRAINT_CHECK и имя в сообщении, а не PostgreSQL code; typeof/byte length/hex используются для NUL/BLOB. SQL text '1e2' и numeric literal 1e2 различаются. Precision counterexample должен реально сохранить другое, но canonical допустимое число, сравниваемое с независимым Python int; scientific-string rejection не заменяет это доказательство. Ни actual SQLite rebuild, ни adapter/global model/account policy не меняются.

## Read-only inventory следующего model/writer шага (eb16b97)

Отдельная source-инвентаризация обнаружила оставшиеся BIGINT-specific границы: `models.py:1175,1184` обе nullable BIGINT; `instant_service.py:1863–1865` SQL coalesce/range guard, :1873–1917 и :1940–1969 Python MAX/next и общий capture guard; :3501–3502 matcher Python MAX. SQL increment уже заменён Python next в исследованных writers, но SQLite TEXT нельзя подключить, оставив числовые SQL predicates. NULL-init :3483–3495 и equality CAS/cleanup/dispatch требуют согласованных adapter bind/result и сохранения конкурентной семантики. Route capture :1996–2024 не является increment; его snapshot/NULL-контракт сохраняется отдельно.

`matching_resume_revision` в production пока только model, а не готовый producer/consumer. Неподключённые `taxi_tried_history_init/reader/write`, `taxi_offer_publication_lab` сохраняют BIGINT validators; pure resume policy не задаёт общий 128-digit limit. Bootstrap `db.py:64–95` не делает type migration. Явный `order_payload` не должен случайно смениться общим model serializer с большими JS numbers.

Следующий исполнимый кандидат требует единого model/bind/guard/validator контракта и actual-schema ORM controls, а не только успешного CHECK. Старый binary может записать другое допустимое значение, persisted jobs/snapshots/restart scripts и backup restore переживают drain. Tokenless replay и negative-history ABA не закрыты. Конечный 128-digit максимум всё ещё требует решения продолжения без скрытого нового account refusal. Эта инвентаризация read-only, не разрешение wiring/cutover или завершение durable функции.

## Frozen static review и первый локальный результат

Runner a1b6d38, tests eb16b97; независимый reviewer не нашёл P0/P1/P2 и разрешил авторские изолированные прогоны. Ruff F / bash syntax / diff check PASS. Комментарий о составном predicate уточнён 2d028f2: наблюдение результата не доказывает forced evaluation всех термов или их порядок.

Авторский synthetic SQLite: 68 PASS, 0.50 с, actual exit 0 (`/tmp/taxi-storage-check-author-sqlite1.log`, `.exit`); собственные tempfile каталоги удалены. Этот результат ещё не PG и не независимая runtime приёмка. Реальный PG запускается отдельно согласованным runner.

Авторский combined `storage-check-contract` на 2d028f2: 190 PASS за 127.57 с, actual exit 0 (`/tmp/taxi-storage-check-author-pg1.log`, `.exit`). Из них 122 actual PG cases по двум независимым bootstrap sources и 68 synthetic SQLite cases, не 190 PG. Named failures для специальных NUMERIC значений, оба validated CHECK, raw/typed attribution и rollback controls прошли. Все guarded clone teardown/registry checks завершились, runner удалил свой временный кластер; SQLite own directories отсутствуют. Независимые повтор и shared/default regressions ещё отдельные обязательные шаги; FULL не требуется для неизменённого runtime в этом test-only пакете.

## Независимая финальная приёмка

Source 2d028f2 (дальнейшие коммиты только этого отчёта). Независимый combined storage: 190 PASS, 117.02 с, actual exit 0, `/tmp/taxi-storage-check-independent.log/.exit`; 122 PG + 68 SQLite. Shared phase1 regression: 42 PASS, 41.93 с; default unchanged regression: 255 PASS, 12.37 с; оба actual exit 0, логи `/tmp/taxi-storage-check-independent*.log/.exit`. После каждого собственные кластеры удалены; SQLite directories отсутствуют. В двух storage source roots lkfykcx5/uyz6ipqa все 244 before-create/drop guard JSONL завершились zero, nonzero не наблюдался.

Final narrow PASS, открытых P0/P1/P2 нет. Ruff F / diff check PASS. Secrets scan нашёл 3 значения, независимо классифицированных как synthetic child JWT и два literal SQL, не реальные credentials; scanner не ослаблен и результат не объявляется «0 findings». FULL не запускался: пакет test-only, runtime/models/migrations/adapter неизменны. Ресурс передан Amio после фактического завершения и cleanup.

Вывод: выбранный CHECK subset исполним и отвергает проверенные недопустимые сохранённые значения. SQLite CAST controls одновременно доказали ограничение: другое допустимое число после потери точности проходит CHECK. Это не защита исходного input, не full adapter equivalence, не all-writer protocol, не миграция/cutover и не готовое durable восстановление. Следующий шаг — независимый план согласованных model/bind/guard/validator изменений с actual ORM/schema controls, без автоматической активации или отказа удаления аккаунта.
