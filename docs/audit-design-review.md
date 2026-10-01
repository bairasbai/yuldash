# Независимый аудит дизайна Android — доказательства B09

Документ создан 30.09.2026 независимым агентом `design_audit`. Текущие статусы и очередь остаются только в [audit-blocks.md](audit-blocks.md). Этот документ содержит перечень интерфейса, наблюдения и доказательства; он не является второй очередью задач.

## Граница проверки

Проверка `DESIGN-SCAN-20260930` относится к действующему Android-коду в `android/app/src/main`. Правила: [AGENTS.md](../AGENTS.md), §3/4/4.5/12, и [design.md](design.md). Прочитаны правила, навигация и целевые участки, перечисленные ниже. Число строк, отпечаток файла и обнаруженная функция не означают, что весь файл семантически разобран.

Метод: чтение исходников, структурный поиск `Screen`, `HomeTab`, `@Composable`, переменных состояния и ветвей, расчёт контраста из фактических ARGB-токенов. SHA256 нормализует CRLF/CR в LF без иных изменений — тот же алгоритм, что в [tools/audit_inventory.py](../tools/audit_inventory.py): `hashlib.sha256(data.replace(b"\r\n", b"\n").replace(b"\r", b"\n"))`.

Окружение: Windows, локальная рабочая копия. Эмулятор, adb, Gradle, сервер и БД этим агентом не запускались: владельцем общих ресурсов остаётся основной агент. Android API, экран, TalkBack, язык/тема конкретного устройства не проверены. Я не могу это подтвердить. Снимков экрана в этой проверке нет; доказательство каждого замечания — место в исходниках и расчёт либо явное отсутствие нужной семантики.

## Передача работы

- Замороженная передача R45, 01.10.2026, 15:45:58 МСК, /root/design_resume: по переданной root явной просьбе пользователя текущий этап остановлен. DESIGN061–068 имеют source-доказательства на SHA R43/R45, actual RED/fix/GREEN открыты;069 кандидат pending/restoration без actual воспроизведения;059 current Completed zero extension открыт. R42 Callback functional2/2 bounded, визуальная confirmation/BA056 не приняты. Новых исследований/прогонов/правок source не начинать от этого агента; дальнейшая интеграция/отчёт/Git выполняется root в рамках новой просьбы пользователя. Design-doc writer закончил, файл заморожен после этой записи; своих фоновых процессов/замков/владения Gradle/эмулятором/БД нет.

- Актуальная передача R45, 01.10.2026, 15:44:23 МСК, /root/design_resume: Android ChatScreen1692–1898 + PWA ChatInbox288 целиком/связи server861–922/AppNavHome154–161 прочитаны. DESIGN067 — RU-only generated preview/fallback при BA;068 — done inbox/lost-item успех не приводит к доступному чату;059 расширен current Completed zero amount fallback;069 — saveable busy restoration кандидат, controlled RED открыт. Backend lost-item109/6 и RatingReopen105/4 прочитаны без выполнения. Далее оставшийся RideshareCompleted938 и BookingCompletionDestination225, затем новые chat consumers; Callback checkpoint/PNG приоритет. Единственный design-doc writer, shared/source/build/device readonly, своих процессов/замков нет.

- Актуальная передача R44, 01.10.2026, 15:32:42 МСК, /root/design_resume: дочитаны ActiveTripDeletion253 и 6 дополнительных test files, всего7/74 определения; own8 hashes совпали inventory. 19/22 Deep308 теста проверяют legacy компоненты без найденных production calls; ChatContent185/9 относится к другому Content, не ActiveTrip ChatComposer. Deletion6 meaningful disk/server side-effects, opt-in ChatRetry2 требует loopback adapter и без env skipped; новых прогонов нет. Source R43 findings061–066 и Callback R42/визуальный056 остаются. Далее настоящий Chats/ChatScreen wrapper в RidesRequestsChatScreens.kt, неизменённые test/source не перечитывать; новые Callback checkpoint/PNG принять приоритетно. Только design-doc writer, shared/source/resources readonly; своих процессов и замков нет.

- Актуальная передача R43, 01.10.2026, 15:26:03 МСК, /root/design_resume: весь BookingActiveTripScreen.kt3817 прочитан по объединению R18/R41/R43 при неизменном b161ac19…; зависимости частично, 5 тестовых файлов полностью/12 определений, без новых прогонов. DESIGN061–064 — чужое WS-эхо заменяет моё ожидающее сообщение, серверный отказ не откатывает passenger status, правка теряет черновик, список доступа скрывает GET-error/429 после commit. DESIGN065 — офлайн-паспорт теряет согласованную сумму/метод,066 — boarding-code error остаётся вечной загрузкой. Только source-доказательства; actual RED/fix/runtime открыты. Callback R42 functional2/2 принят, actual визуальная карточка/BA header056 ещё открыты. Далее ActiveTripDeletion253 и компонентные тесты/оставшийся PWA1212; приоритет свежему Callback checkpoint/PNG. Только design-doc writer, общие ресурсы/source read-only, собственных фоновых процессов и замков нет.

- Актуальная передача R42, 01.10.2026, 15:10:24 МСК, /root/design_resume: QA-B08-001 Android actual V3 functionaldevice2/2 принят только для synthetic SimpleMode→Callback/current HTTP/pending/doubletap/503 same-draftretry→200. Самостоятельно прочитаны primary13.406с/21.094wall/PID28896, пятьmarkers AndroidPID8351,2APK+5PNG rawSHA; main source неизменён. Accepted PNG не показывают confirmation из-за IME, visual acceptance открыт; DESIGN056 BA header остаётся. first2Toast failures/v2marker failures сохранены. ActiveTrip wrapper1428–2803 прочитан, компоненты/серверные связи/тесты ещё открыты, root получил source кандидаты061–064. Только design-doc writer, общие ресурсы/source read-only, своих процессов/замков нет.

- Актуальная передача R41, 01.10.2026, 15:00:01 МСК, /root/design_resume: полностью BookingScreen326–769/компоненты772–1340/Minor3500–3540, web Booking218 и связанные DTO/server/create/caller/tests разобраны. DESIGN057 — booked seats/total price названы free seats/per seat;058 — свежий false verified подавлен old true;059 — явный0 ₽ скрыт/price0 заменён прежним ненулевым;060 — fresh details status не управляет actions, remount-only journey не доказывает текущий экран. Source-only, actual RED/fix/GREEN открыты, root уведомлён. Приоритет остаётся final Callback040 device freeze: v2 тоже2fail из marker offscreen после принятия, новый продуктовый дефект не присвоен. Следующий основной исходник ActiveTrip1428–2673 после изменённых callback доказательств. Единственный writer design-doc; source/shared docs read-only, своих процессов/замков нет.

- Актуальная передача R40, 01.10.2026, 14:51:59 МСК, /root/design_resume: QA-B08-001 / DESIGN040 backend принят ограниченно по первичным RED18/3, final24/24 и мутации18/3. JVM final41/41 самостоятельно сверены по пяти XML, но device first2/2failure после HTTP с Toast/Looper не принят. RU/BA pending PNG просмотрены; DESIGN056 — BA font2 заголовок обрезан сверху при открытой IME. Root получил сбой и адаптивный остаток. Окончательные Android freeze/повтор/PNG ещё ожидаются; Booking326–1140/Minor3500–3539 прочитаны, связи сервера/тестов и дальнейшие компоненты не закончены. Единственный writer design-doc; source/shared docs read-only, своих процессов/замков нет.

- Актуальная передача R39, 01.10.2026, 14:40 МСК, /root/design_resume: DESIGN055 source chain admin support reply→type/id→Main369 теряет ID→YuldashApp714 открывает донат. Actual FCM/экранные RED и исправления открыты. SupportBoost R38 закончено; Booking326–769 дочитано, далее private компоненты772–1140/Minor3500 и caller/server/tests. Единственный writer design-doc; source/shared docs/resources read-only, своих процессов/замков нет.

- Актуальная передача R38,01.10.2026,14:32 МСК,/root/design_resume: полностью SupportBoost1–951, webSupport193/Boost301, ключевые server/API/testcontracts. DESIGN050 anysuccess→manual0₽/fallbackrequisites;051 lateGETA→succeedsB;052 BAplannameRU;053 bonus/carryfailurehidden;054 manualterminalnotobserved. Source-only, actual RED/fix/GREEN/device/providers открыты. QA022R36 иQA003R32 прежниеboundedприёмки. Следующее BookingScreen326–769/source+связи; важныеfrozenfix отrootприоритет. Только design-doc writer; source/shareddocs/resourcesread-only, своихпроцессов/замковнет.

- Актуальная передача R37, 01.10.2026, 14:16 МСК, /root/design_resume: полностью VerifyDriver760–1188, связи profile/OCR/private-docs, web Verify321. DESIGN-044 — результат verify/verified потерян;045 — initial loading выглядит установленным none;046 — refresh перезаписывает ввод;047 — submit старых фото при замене;048 — web скрывает профиль failure;049 — decode синхронно на Main (измеренный ущерб открыт). Source-only, runtime RED/fix/GREEN открыты. QA022 R36 принята ограниченно; wholeScreen/B09/96/TalkBack/physical/external остаются открыты. Далее SupportBoost donation/boost по существующей карте. Единственный writer design-doc, shared source/docs/resources read-only; своих процессов/замков нет.

- Актуальная передача R36, 01.10.2026, 14:05 МСК, /root/design_resume: QA-B01-022 независимо принят только для изменённого export UI/privacy механизма: diff+6 current device cases+13 JVM XML=70, primary14 final, three caught mutations, реальный BA PNG1080×1350 самостоятельно просмотрен. 91 manifest records+14 raw snapshots=105 файловых проверок, mismatches0. Полноэкранных текущих MyStats RU/BA screenshot-pair нет, full visual/TalkBack/font-theme-viewports/реальные сервисы открыты. DESIGN040–043 R35 source-only. Далее VerifyDriver762–1188/private-docs по исходной карте96. Единственный writer design-doc; source/shared docs/resources read-only, своих процессов/замков нет.

- Актуальная передача R35, 01.10.2026, 14:00 МСК, /root/design_resume: полностью прочитан Help2335–2490, SOS wrapper283–474/content479–708 как связь FAQ, source web Help164/Callback126. DESIGN-040 — callback сообщает успех при недоставленной и не сохранённой просьбе; DESIGN-041 — скрыта ошибка Telegram ACTION_VIEW; DESIGN-042 — нет роли/состояния раскрытия FAQ; DESIGN-043 — SOS FAQ обещает SMS без отдельной отправки и условий. Source-only, actual RED/fix/GREEN и TalkBack открыты. Root получил критерии. QA003 R32 принята ограниченно; QA022 frozen UI ещё ожидается. Далее scoped QA022 по v2 checkpoint или VerifyDriver762–1188 и документы по существующей карте. Единственный writer design-doc, другие source/docs/resources read-only; своих процессов/замков нет.

- Актуальная передача R34, 01.10.2026, 13:46 МСК, /root/design_resume: полностью разобраны Blocklist2041–2128, Report2132–2170/ReportCategoryDialog2212–2290/ReportList2298–2333 и все четыре onSend потребителя; source-паритет web Blocklist129/Report285. DESIGN-036 — hidden partners GET failure; DESIGN-037 — picker исключает только-такси/доставка/торг; DESIGN-038 — описание жалобы теряется до ответа; DESIGN-039 — web alsoBlock failure скрыт. Только source, actual RED/fix не выполнены. Block concurrency→дубли/Compose key и report-dedup race — серверные probes, не исполненный дефект; privacy права заранее блокировать незнакомого сохранять. QA003 R32 ограниченная приёмка сохранена. Далее Help2336–2490 и соответствующие ссылки/переходы. QA022 frozen evidence ещё ожидается; source/shared docs read-only, единственный writer design-doc, своих процессов/замков нет.

- Актуальная передача R33, 01.10.2026, 13:35 МСК, /root/design_resume: завершены AdminRequest1914–1963/AdminResponses1967–2037 со связями API/server/automatch/tests и source-сравнением webapp. DESIGN-032 — историческая цена вместо текущей; DESIGN-033 — поздний список/карточки другой заявки; DESIGN-034 — предлагаются запрещённые действия; DESIGN-035 — после Android create теряется ID для следующего шага. Это source, actual UI/API RED ещё открыт. Phone-normalization/dedup/гео/уведомления admin-create переданы root как отдельные серверные probes. QA003 ограниченная приёмка R32 сохранена. Следующее непрочитанное — Blocklist2041–2130 и связи blocks/reportable/privacy, затем Report; QA022 ожидает frozen UI. Единственный writer design-doc, общие source/resources read-only; своих процессов/замков нет.

- Актуальная передача R32, 01.10.2026, 13:26 МСК, /root/design_resume: QA-B07-003 независимо принят только для согласованной суммы нового счёта, защиты изменения после pending/paid и шести проверенных чередований PostgreSQL. Сверены 14 текущих файлов и 14 raw snapshots, 42 артефакта, первичный PG85/85 и четыре пойманные мутации; собственный JSON bf05bef6c52adc62391ec6aabf0b03bc4651f3aca227750203e46e320feaf8b0. Исторические несогласованные строки, настоящий провайдер, Android-оплата, весь B07/B09 и 96 экранов открыты. AdminRequest1914–1963/AdminResponses1967–2037 уже прочитаны, следующим разобрать их API/server/tests и поздние ответы; QA022 ожидает новые замороженные доказательства. Единственный writer design-doc; source/shared docs read-only, своих общих процессов и замков нет.

- Актуальная передача R31, 01.10.2026, 13:11 МСК, /root/design_resume: закончен AdminPaymentRequests1721–1910/API/server/test разбор. DESIGN-028 — ошибки debts/summary скрыты, DESIGN-029 — причина forgive теряется послеотказа, DESIGN-030 — UIaggregateSUM списывается singledebt, DESIGN-031 — confirmcanceled manual отвечает succeeded безактивации. Только source, новыеactualRED ещё открыты; root получил критерии. QA003R30 source+старыеsequential74 проверены, новые79 и controlledPGrace ждут независимой финальнойприёмки; root сообщил79GREEN, это сообщение ещё не доказательствоreview. СледующееUIчтение — AdminRequest1914–1963/AdminResponses1967–2037; Sharedsource/resources read-only, процессы/замки неиспользуются.

- Актуальная передача R29, 01.10.2026, 12:57 МСК, /root/design_resume: завершён AdminReports1528–1663 со связями категорий/API/rights/quality/tests. DESIGN-027 — server cap200 и отсутствие Android filter/page скрывают старые открытые жалобы; actual201-case UIRED ещё открыт. Badge — три дополнительных нарушения DESIGN-026, approveCTA — consumer DESIGN-001. R27 new-payment amount принят ограниченно, R28 QA003RED6/4 и lock-order риск переданы; root сообщил новый fix, следующим идёт его независимый review. Затем AdminPaymentRequests1721–1910; CabinetR17 не повторяется. QA022 ждёт frozen evidence; общих процессов/замков нет, единственный writer документа.

- Актуальная передача R27, 01.10.2026, 12:49 МСК, `/root/design_resume`: минимальный QA-B07-002/DESIGN-022 принят только для источника суммы новой операции на frozen wallet42adc9/testc0c22e. Independently проверены PG XML68/68 =14 новых+54 соседних, семь первичных provider/Payment/ledger/receipt наблюдений, isolated runner и source hashes; R25 SQLite/mutation history сохранена. Whole money/real provider/Android CTA/concurrency не принимаются. Соседи paid/pending agreement теперь имеют отдельный actual RED QA-B07-0036/4, его предложение/тесты разбираются независимо; прежний R25 pending PG относится к истории. Следующее Android чтение — AdminReports1528–1670, DocImage/AdminDrivers R26 не повторяется. QA022 runtime acceptance ожидает новые evidence владельца. Общих процессов/замков нет, единственный writer данного документа.

- Актуальная передача R26, 01.10.2026, 12:35 МСК, `/root/design_resume`: разобраны DocImage/AdminDrivers/AutoCheckRow1350–1521, SecureImageRequest68/SecureWindow61 полностью, moderation/private-doc endpoints и тесты в указанных границах. DESIGN-024 — Crop фиксированного preview скрывает края документа без полного просмотра; DESIGN-025 — image loading/error не объясняются и retry отсутствует; DESIGN-026 — рассчитанный контраст AutoCheckRow ниже4.5 в четырёх сочетаниях foreground/background. Это source/расчёт, actual UI RED и исправления ещё открыты. Root получил замечания; токены/защитные helpers не изменены. R25 денежная приёмка ждёт PG, source+SQLite/mutations проверены. Следующее непрочитанное — AdminReports1528–1670; QA022/PG proof принимаются отдельно по поручению. B09/96 экраны и внешние условия открыты, общими процессами/замками не владею.

- Актуальная передача R25, 01.10.2026, 12:23 МСК, `/root/design_resume`: прочитано важное денежное исправление QA-B07-002/DESIGN-022. Полный wallet diff минимален; source и SQLite RED14/5 → GREEN14/14 +54 соседних XML, обе mutation-копии и реальные сохранённые Payment/ledger/receipts на подменённой границе провайдера проверены независимо. Итоговая приёмка участка ожидает root PostgreSQL proof; whole money/UI/real provider не принимаются. Сохранён собственный independent-design JSON. Root получил два соседних открытых воспроизведения: изменение agreement после paid и при старом pending; setter не фиксирует расчётную сумму. Source не менял. Продолжаю DocImage/AdminDrivers, ожидая PG/QA022 evidence; документы/сборка/устройство/БД у прежних владельцев.

- Актуальная передача R24, 01.10.2026, 12:15 МСК, `/root/design_resume`: разобраны FiltersScreen/FilterPrefs1273–1282,1304–1347, client map/filter/page связи и полный server rides_near575–666; сохранён DESIGN-023 — фильтр проверяет только первые пять поездок и скрывает доступ к следующей странице. При шести разрешённых поездках с подходящей только шестой quiet-поездкой source показывает ложное «нет поездок» и предлагает сброс; actual UI RED ещё не исполнен. Прочитаны четыре Filters-теста, два paged API-теста, шесть готовых More/Empty-компонентных тестов и весь MapScreenInstrumentedTest160; ни один не доказывает этот путь. Root получил критерий, source остаются read-only. R23 scoped DESIGN-018/MyData8 принят; QA022 и DESIGN-022 у владельцев. Следующее непрочитанное — DocImage1350–1386/AdminDrivers1387–1527 с API/правами/ошибками, после сверки hashes и прежних maps. Весь B09/96 экранов открыт; единственный writer данного документа, собственных общих процессов/замков нет.

- Актуальная передача R23, 01.10.2026, 12:08 МСК, `/root/design_resume`: независимо принят DESIGN-018 в исходном воспроизведении DataRow/cardStored=false: минимальный diff, исправленный RED 2/8 → GREEN 8/8, два соседних export-теста и новые реальные RU/BA PNG font2/light. Повторный device-профиль содержит восемь успешных MyData-проверок; общий combined11 завершился с тремя MyStats failures и не принимается как GREEN. Сверены checkpoint `34bace007c57e50149ba6f2937cac49ce8e05f0be9610161dd584dbaa9bad42b`, восемь current sources/snapshots, 24 artifact-записи, две JUnit-записи и debug APK; расхождений нет. R19 MyData-хэш/PNG теперь исторические, новую версию принимает только R23. Весь экран, 320dp/dark/physical/TalkBack/96 экранов и B09 остаются открытыми. Следующая приёмка — QA022/MyStats либо денежный DESIGN-022 по доказательствам владельцев; доступное чтение — FiltersScreen1306–1350 и связанные prefs/nav. Только этот документ writable; собственных build/device/DB процессов и замков нет.

- Актуальная передача R22, 01.10.2026, 11:56 МСК, `/root/design_resume`: разобраны PaymentInfo/PricingInfo1062–1271 и весь PayOnlineCard257, связанные capability/оплата/receipt диапазоны. DESIGN-020 — отказ открытия браузера скрывается за Waiting; DESIGN-021 — healthsbp_manual включает CTA, которую wallet в production отклоняет503; DESIGN-022 — CTA/receipt берут pay_amount, а online pay_booking169 начисляет booking.price. Source различия подтверждены, деньги/production/runtimeRED не запускались. Root получил точные критерии и correctedlines169/425. Денежную пробу022 приоритизировать перед визуальными предложениями, без молчаливого изменения бизнес-правила. DESIGN-018/019 и015–017 открыты, QA021 scoped acceptance R19 сохранена. Следующая независимая приёмка — изменения018 либо022 по актуальным diff/test/XML/PNG; следующее чтение — FiltersScreen1306–1350 и related prefs/nav после сверки прежних read maps. Этот агент остаётся единственным writer данного документа, source/shared docs read-only; Gradle/device/PG/production/GitHub/фоновых процессов/замков не использует.

- Актуальная передача R21, 01.10.2026, 11:40 МСК, `/root/design_resume`: разобраны RouteWatchesScreen552–706/API1938–1972/serverroute_watch125/связи matching, четыре server-test файла и timeutil105. Нового DESIGN-ID/исполненного PASS нет. UI create/delete failures объясняются Toast, форма сохраняется при отказе; двойной tap/поздний success/редактирование пока runtime probes. Новый server probe: duplicate branch74 хранит raw watch_date, create89 применяет client_dt_to_utc; одинаковый repeat возле полуночи может изменить календарный день при offset5. Различие source подтверждено, API/PG reproduction не выполнено. Array GET/raw-list нормально оборачивается ApiClient3498, ложное contract замечание не создано. Root получил дату/concurrency/критерии. QA021 R19 принят узко, DESIGN-018/019 и B09/96screens остаются открытыми. Следующее чтение — PricingInfoScreen1140–1280/связанные тарифы и тексты после проверки прежних read maps; ближайшая приёмка root изменений — DESIGN-018 по actual PNG/layout. Единственный writer этого файла design_resume; source/shared docs/Gradle/device/PG read-only, собственных фоновых процессов/замков нет.

- Актуальная передача R20, 01.10.2026, 11:33 МСК, `/root/design_resume`: завершён разбор NotificationsScreen279–551, API/feed/parser/DTO и server notifications135, двух server-test файлов175/149 и шести Android test-файлов. DESIGN-019 — Android markAll338–341 сразу объявляет unread0/read=true и игнорирует POST Result, кнопка401 исчезает; отказ не откатывается/не объясняется. markRead330–336 тот же механизм. Static failure-path подтверждён, настоящий UI RED не выполнен; root получил критерии. PWA readAll184–199 имеет rollback, историческое architecture872 относится к PWA, не принимает Android. Filter SegmentedTabs — дополнительный неисправленный потребитель DESIGN-001/002. QA021 scoped acceptance R19 актуальна по указанным хэшам, DESIGN-018 открыт. Следующий непрочитанный независимый сосед — RouteWatchesScreen552–706 и его create/delete/сервер/тесты; не запускать неизменённый общий набор. Все source/shared docs read-only, единственный писатель данного документа design_resume; собственных device/build/DB процессов/замков нет.

- Актуальная передача R19, 01.10.2026, 11:27 МСК, `/root/design_resume`: независимо принята только проверенная связка QA-B01-021 MyDataScreen → нажатие выгрузки → локальный HTTP → реальный FileProvider/содержимое/записанный share-intent → выход/аккаунт B и поздние HTTP/IO; отдельно приняты три настоящие grant/revoke пробы shell UID2000. Финальный device8/8 за30.455с и JVM62/62 сверены по первичным log/XML; source10/artifact100/APK2 и десять snapshot-копий совпали с checkpoint `cc0ba2bee6b95d98205807685f25817f3a5972d98d5ecf7314e6b6cdbe804a1b`. ОС chooser в journey перехвачен, настоящий backend/полная навигация/physical/release/TalkBack не доказаны. DESIGN-018 открыт: просмотренный BA PNG font2/light показывает разрыв слов в узкой колонке DataRow; прежняя вёрстка не менялась при privacy-fix. История first8/3failure и прежнего device2GREEN сохранена. Ближайшее чтение — NotificationsScreen279–551 и связанные API/server/tests; часть исходного UI уже разобрана, новая запись ещё не завершена. Этот агент пишет только данный документ, сборкой/устройством не владеет; root получил scoped acceptance и замечание.

- Актуальная передача R18, 01.10.2026, 11:11 МСК, `/root/design_resume`: разобраны Settings/Safety/ThemePicker/FontScalePicker/связи сохранения и уведомлений. DESIGN-017 — выбор темы в Settings809 изменяет только RAM, тогда как новый процесс MainActivity268–269 читает прежний `yuldash_theme/dark_override`; единственный обнаруженный writer key — MapScreen937–938. Static persistence разрыв подтверждён, true process-restart/UI RED не выполнен. Два radio-dialog — дополнительные непринятые потребители DESIGN-002; Help unread badge — DESIGN-001. Root получил исходники/точные критерии и правильное имяprefs. По-прежнему нет source/device/build/DB действий агента; QA021 финальные freeze/evidence ожидаются, старые device2GREEN новую реализацию не принимают.

- Актуальная передача R17, 01.10.2026, 11:05 МСК, `/root/design_resume`: целиком разобран AdminSupportScreen325 и навигационный AppNavAdmin73, Theme87; связанные API/связи прочитаны отдельно. DESIGN-016 — подготовленный ответ администратора стирается до HTTP-результата, failure не отображается и текст не возвращается — однозначно подтверждён кодом295/115–118, UI RED ещё открыт. Дополнительные open/close failure и late Back являются конкретными непринятыми probes. NearbyFilterChip остаётся отдельным неисправленным потребителем прежних DESIGN-001/002. Root получил критерий потери текста и владеет исправлением; этот агент пишет только данный документ, не использует сборку/устройство/БД. QA021 новую generation/UUID версию по старому device2GREEN не принимает; актуальные freeze/evidence ещё ожидаются.

- Актуальная передача R16, 01.10.2026, 11:00 МСК, `/root/design_resume`: продолжено чтение после R15, прежняя карта96 экранов сохранена. Целиком разобраны SupportChatScreen459, серверный support369 и два server-test файла114/104; прочитаны точные связанные диапазоны общей ленты/навигации/API. DESIGN-015 — молчаливый отказ закрытия обращения — подтверждён обработчиком439, но UI RED ещё не исполнен. Найден дополнительный неисправленный потребитель DESIGN-001: ChatFeedBubble2041/2046 (текст14/16sp белый на тёмном CanonGreen2, рассчитанный контраст3.193129:1). Предыдущие принятые компоненты R10 не повторялись. Root получил критерии; следующий независимый непрочитанный соседний экран — AdminSupportScreen, вторая сторона этого диалога. Приёмка текущего QA-B01-021 ожидает актуальные source/tests/device proof; initial device2GREEN относится к исторической первой версии cleanup, не к новой generation/UUID реализации. Единственный писатель этого документа теперь `/root/design_resume`; общие source/docs/Gradle/adb/БД остаются у root, фоновых процессов и устройства этот агент не использует.

- Текущая передача R10, 01.10.2026, `/root/design_continue`: финальная версия DESIGN-010/011 независимо принята в пределах описанных ниже компонентов. Сверены 9 исходников/тестов, 91 отпечаток артефактов, финальные XML (32/32) и device log (8/8), отдельно просмотрены 8 PNG. Полный цикл idle→click→loading→idle, Role.Button и отсутствие дублированного idle-описания доказаны тестами. Финальные кадры после прокрутки родительской Button показывают всю CTA; нижняя граница 544dp при viewport 568dp. Прежний пробел R9 по этому кадру закрыт новым доказательством, история сохранена.
- Последнее чтение R14, 01.10.2026, 10:24 МСК: целиком разобран CreateRideScreen1158, включая PrivacyScreen, и два связанных test-файла. DESIGN-014 подтверждён кодом: отказ очистки недавних адресов закрывает подтверждение без объяснения результата. Дата без выбора, сохранение остановок и состояния справочника клиник переданы как отдельные кандидаты на воспроизведение. AST выявил 18 определений тестов при 12 разных именах в test_places; это не число исполненных/собранных pytest тестов. DESIGN-012/013 из R12/R13 остаются без UI RED/фиксов/повторной приёмки; root получил точные критерии. Backend canInvite/пауза и consent-concurrency переданы как probes без объявления исполненного обхода прав.
- Последнее доступное чтение R15, 10:30 МСК: целиком разобраны MyStatsScreen569, backend stats226 и3 небольших серверных test-файла. Смена аккаунта/отмена share, длинный текст Canvas, состояние наград и malformed response пока имеют критерии, а не новое исполненное доказательство. Root получил связь share с QA-B01-021. Новых DESIGN-ID/PASS этой части нет; source/Gradle/adb/PG не использовались.
- Следующий независимый участок — чтение/связи `MyDataScreen` и существующих проверок; оно сохранено в R11 (09:53 МСК). GET/loading/error/данные, export/file/share и delete dialog разобраны, запуск их runtime-проверок не выполнен. Export/очистка файла при смене аккаунта и отказ chooser переданы root как кандидаты для контролируемого воспроизведения, без объявления новой утечки. Следующее исполнение определяется только реестром [audit-blocks.md](audit-blocks.md); root сейчас отвечает за B01 rollback, исходники, сборку и эмулятор. DESIGN-006…009, TalkBack, физический телефон, полный путь обязательного обновления/магазин и остальные экраны остаются непроверенными в указанных границах. У этого агента нет окна adb, запущенных процессов и новых замков.
- Историческая передача R9, 01.10.2026, 09:35 МСК, `/root/design_continue`: независимо сверены UI diff/сохранённые XML/JSON/log/PNG R4. DESIGN-010 подтверждён8/8RED и минимальный фикс имени принят для ограниченного loading-компонента; source UiKit741ae5… . DESIGN-011 подтверждён large-font кадрами и высотой paragraph; ForceUpdate544b33… исправил полную высоту текста/прокрутку,32/32JVM иdevice6/6 подтверждены. Финальный PNG **после** merged Button.performScrollTo ещё требуется: сохранённый step2 снимает предыдущий Text-scroll, нижний край кнопки в нём срезан. Не объявляется доказанное окончательное отображение всей кнопки.
- Следующий конкретный шаг root для этого фрагмента: сохранить final PNG/полные bounds после финального Button-scroll; дополнить реальный idle→click→loading→idle и сохранённое semantic-tree R4-A, если критерий относится к полному переходу. После этих узких доказательств независимый агент обновит приёмку. Root выполняет важный B01 rollback-участок; source/build/device принадлежат ему. DESIGN-006…009 ещё без UI RED/фикса, остальные R4-C/D и полный B09 открыты по реестру.
- Историческая передача 01.10.2026, 08:56 МСК, `/root/design_continue`: прочитаны весь этот документ, перечень 96 Screen/вложений и R1–R3; перечень не пересоздавался. DESIGN-005 принят только в границах компонента в R3, весь B09 остаётся частичным.
- Следующий узкий участок: `DESIGN-PROBE-R4-20261001` ниже — доступное название настоящего AppButton при loading, геометрия ForceUpdateScreen и AppStaleStrip при ширине 320dp/fontScale2; Intro/system bars остаётся отдельным runtime-риском. Отсутствие явной семантики/scroll прочитано; новый RED/GREEN ещё не получен, визуальный дефект этого участка не объявлен подтверждённым.
- Единственный писатель этого документа — `/root/design_continue`; root исправляет исходники и владеет Gradle/эмулятором. У этого агента нет окна adb, сборок и фоновых процессов. Root переданы конкретные критерии новых проверок; source и тесты этим агентом не редактируются.
- Новое действие владельца: после текущего B01 storage-участка выполнить настоящие Compose probes из R4 и передать XML, semantic-tree/metrics, версии исходников и кадры для независимой приёмки. Устройство, TalkBack и весь полный HomeShell этим статическим чтением не проверены. Я не могу это подтвердить.
- Продолжение чтения 01.10.2026: R5 разобрал SavedPlacesScreen/Geocoder и подтвердил кодом DESIGN-006 (отказ поиска адреса скрыт); R6 разобрал AppReviewScreen и подтвердил расчётом DESIGN-007 (контраст выбранной звезды в светлой теме 1.853327:1 < 3). UI RED/исправления этих двух замечаний ещё не выполнены; ближайшее исполнение root по-прежнему R4-A/B после B01. Эти записи не меняют единую очередь реестра.
- R7 прочитал DriverProfileScreen277строк: DESIGN-008 — рейтинг конкретного отзыва представлен только скрытыми для семантики иконками; фактический TalkBack/semantic-tree ещё не исполнен. Root получил точный критерий и минимальный кандидат; исходники и ресурсы остались у root.
- R8 полностью разобрал CouponsScreen728строк и общий shortDate. DESIGN-009 — отказ свежего GET в detail скрыт за прежним preview; UI RED открыт. CouponTab/городские фильтры добавлены как непроверенные потребители DESIGN-002. Некорректный shortDate отдельно требует pure-helper RED; полный coupon activation/redeem путь и PostgreSQL этим агентом не запускались.

Историческая передача после R2 (сохранена; актуальный результат DESIGN-005 находится в R3):

- Последнее действие: завершена независимая приёмка RoleCard по RED 1/GREEN 6 (`DESIGN-REVIEW-R2-20260930`); прочитаны device XML 8/8 и два новых PNG при крупном шрифте. Подтверждён отдельный дефект DESIGN-005 — обрезание нижних подписей. Историческая приёмка R1 сохранена ниже.
- Текущий критерий B09: полный перечень экранов и существенных внутренних состояний, затем визуальный обход RU/BA × светлая/тёмная × малый/большой экран × системный крупный шрифт.
- Файл этого агента: только `docs/audit-design-review.md`. Исходники, реестр и общий журнал он не меняет.
- Повторное чтение подтвердило исправления конечных цветов DESIGN-001/003 и описания DESIGN-004. Пропущенный в R1 `OnboardingRoleCard` исправлен и принят в R2 чтением и локальным тестом переключения. Device-проверка принимает только семантику/переключение выбранных компонентов; DESIGN-005 не принят. Более широкий runtime остаток записан в R1/R2.
- Отдельного окна владения эмулятором нет; фоновых процессов нет; замков сборки/устройства этот агент не создавал.
- Следующий конкретный шаг владельца исходников: получить RED по реальному TextLayoutResult нижних подписей RU/BA при fontScale 2 и ширине 320dp, исправить DESIGN-005, сохранить GREEN и новые PNG. Этот агент повторно принимает diff/доказательства без adb. Независимый статический остаток: `UiKit.AppButton` loading, затем остальные формы/диалоги по таблице компонентов. Реальный TalkBack, выбранные фильтры/свои сообщения и полный экран на устройстве нужны отдельно.

## Подтверждённые замечания

### DESIGN-001 — белый текст на адаптивной зелёной заливке

Связанные блоки: B09; чат B05/B02; доход B07; клиники B02; зона курьера B04; онбординг B01.

Условия: включить тёмную тему; открыть свои текстовые/голосовые сообщения, выбрать период заработка/клинику/зону курьера/язык онбординга. Нарушение измеримое: правило §4.5 требует контраст текста не ниже 4.5:1.

Доказательство исходного кода:

| Экран / состояние | Место | Фон / текст |
|---|---|---|
| Активная попутка / свои сообщения | `BookingActiveTripScreen.kt::MessageBubble`, 3702–3756; `ForeignMediaBubble`, 3644–3660; место вызова 2441 | `CanonGreen2` / `Color.White` |
| Мой заработок водителя / выбранный период | `DriverEarningsScreen.kt::EarnPeriodChip`, 146–164 | `CanonGreen2` / `Color.White`, 14sp-текст (`MoneyType.Body`) |
| Поездки к клинике / выбранная клиника | `ClinicRidesScreen.kt::ClinicChip`, 238–271 | `CanonGreen2` / `Color.White`; город с alpha .85 |
| Курьер / выбранная зона | `CourierScreen.kt::CourierZoneChip`, 846–872 | анимируется к `CanonGreen2` / `Color.White` |
| Онбординг / выбранный язык | `YuldashApp.kt::OnboardingLangChip`, 1814–1825 | `CanonGreen2` / `Color.White`, 14sp |
| Вход / основная кнопка Telegram | `LoginScreen.kt::LoginFormContent`, 694–719 | `CanonGreen2` / `Color.White`, 16sp (`LoginBody`) |
| Вход / активный язык | `LoginScreen.kt::LoginLangChip`, 1188–1210 | `CanonGreen2` / `Color.White`, 14sp (`LoginCaption`) |
| SOS / «Позвонить 112» | `SosVerifyScreens.kt::SosScreen`, 529–539 | `CanonRed` / `Color.White`, 19sp Bold; строгий порог проекта 4.5:1 |

Токены: [CanonTokens.kt](../android/app/src/main/java/com/yuldash/app/CanonTokens.kt), `CanonGreen2` = `#27A463` в тёмной теме, `CanonBg` = `#0F1613`; `CanonOnFilled` = `CanonBg`. Формула: `L=.2126*R+.7152*G+.0722*B`, каналы sRGB сначала линеаризуются; контраст = `(max(Lfg,Lbg)+.05)/(min(Lfg,Lbg)+.05)`. Точная команда расчёта и числовой вывод записаны ниже.

Влияние: выбранные надписи и собственные сообщения читаются хуже принятой нормы. Это не вкусовое пожелание. Минимальное исправление: над адаптивным `CanonGreen2` брать `CanonOnFilled`; текст над фиксированными градиентами/фотографией оставить в соответствующем токене `CanonOnAccent`. Не менять палитру целиком.

Существующий `ContrastGuardTest` проверяет читаемость пар токенов, а не все фактические применения `Color.White` в экранах. Его успешный результат сам по себе не исключает этот дефект. Нужна отдельная проверка фактических выбранных компонентов и своих сообщений. Проверка после исправления пока не выполнена этим агентом; статус блока не повышен.

### DESIGN-002 — выбранность фильтров не отражена в семантике

Связанные блоки: B09, B07, B02, B01.

Условия: выбрать период заработка, клинику или язык на онбординге. Нарушение измеримое по коду: активное состояние меняет цвета, но не задаёт `selected`, `selectable` или роль выбора в описании элемента для средств доступности.

Доказательство: `DriverEarningsScreen.kt::EarnPeriodChip` 146–164; `ClinicRidesScreen.kt::ClinicChip` 238–271; `YuldashApp.kt::OnboardingLangChip` 1814–1825; `LoginScreen.kt::LoginLangChip` 1188–1210 (есть описание действия смены, но нет selected). Дополнительно: `YuldashApp.kt::OnboardingRoleCard` 2059–2086 (радио-иконка без описания состояния) и `YuldashBottomItem` 2530–2590 (вкладка selected влияет только на цвета/масштаб). Общий `MainActivity.kt::bounceClick` 874–881 добавляет анимацию и `clickable`, а не семантику выбранности. Для сравнения, `CourierScreen.kt::CourierZoneChip` 855–863 уже задаёт `role = Role.RadioButton` и `selected = active`.

Влияние: дерево доступности этих компонентов не содержит признака выбранного варианта; цвет является единственным явным сигналом выбранности. Фактическую фразу TalkBack я не могу подтвердить: устройство и TalkBack не запускались. Минимальное исправление: применить существующий паттерн `selectable`/`semantics` с `selected` и подходящей ролью к каждой группе; не дублировать подпись кнопки через `contentDescription`.

Критерий повторной проверки: Compose-тест проверяет выбранность и переключение семантического дерева, затем устройство/TalkBack подтверждает понятное озвучивание RU/BA. Исправление и повторная проверка пока не приняты этим агентом. `PaymentMethodsScreen.kt::PayMethodRow` в этот дефект не включён: галочка уже имеет двуязычный `contentDescription("Выбрано", "Һайланған")`; нужно отдельно проверить результат объединения семантических узлов, прежде чем объявлять его дефектом.

### DESIGN-003 — контраст активной вкладки нижнего меню

Связанный блок: B09. Условия: Home, нижнее меню, выбрать любую вкладку. В коде `YuldashApp.kt::YuldashBottomItem` 2536–2589 выбранная подпись 12sp получает `CanonGold`, а выбранная иконка `CanonText`. Фон самой подписи — поверхность панели `YuldashBottomBar` 2481–2485 (`CanonSurface.copy(alpha = .98f)`); золотая pill находится только позади иконки.

В светлой теме подпись `CanonGold/#F5B301` на непрозрачной `CanonSurface/#FFFFFF` даёт **1.853327:1**. С учётом фактической alpha .98 поверхности над `CanonBg/#FAFAF6` в `HomeShell` 2438–2456 расчёт даёт **1.851662:1**, ниже 4.5. В тёмной теме светлая иконка `CanonText/#EAF2EC` на золотой pill `CanonGold/#E8C36B` даёт **1.478818:1**, ниже 3 для значащей графики.

Это расчёт конечных цветов исходника после завершения анимации, не измерение пикселей снимка. Формула та же, что DESIGN-001; полупрозрачный фон вычислен поканально `surface*.98 + CanonBg*.02`. Незначительное округление растра не может закрыть такой разрыв до порога.

Влияние: в светлой теме плохо читается название выбранной вкладки; в тёмной — её значок. Минимальное исправление: читабельный `CanonGreen`/`CanonText` для выбранной подписи, фиксированные тёмные `CanonGoldInk` для иконки на золоте. Не менять фон всего меню и фирменное золото. Добавить проверку реально используемой пары цветов, поскольку существующий поиск `color = CanonGold, fontSize` не видит промежуточную переменную `labelColor`. Семантику selected проверить по DESIGN-002. Исправление/проверка устройства ещё не приняты этим агентом.

### DESIGN-004 — описание голосовой кнопки не меняется при воспроизведении

Связанные блоки: B05, B02, B09. Условия: включить голосовое сообщение, пока `playing=true` перейти фокусом средства доступности на кнопку остановки. Доказательство: `BookingActiveTripScreen.kt::MessageBubble` 3711–3734 и `RidesRequestsChatScreens.kt` 3496–3518. В ветке `if (playing)` onClick останавливает и освобождает проигрыватель, иконка становится `Close`, но `contentDescription` всегда остаётся `appText("Воспроизвести", "Уйнатыу")`.

Измеримое несоответствие: название действия в коде обещает воспроизведение, обработчик в этом состоянии выполняет остановку. Влияние: пользователю озвучивания неясно, как остановить голосовое. Фактическая фраза TalkBack и взаимодействие на устройстве не проверены. Минимальное исправление: описание зависит от playing («Остановить» / башкирский черновик); новый перевод основной агент заносит в tasks. Критерий проверки: semantic-tree до старта и после старта, остановка/возвращение описания, затем TalkBack на устройстве. Исправление ещё не принято этим агентом.

## Не подтверждённые на устройстве риски

Это гипотезы для проверки, не доказанные визуальные дефекты:

- `UiKit.kt::AppButtonContent` 190–199: при `loading=true` исчезает текст действия и остаётся только индикатор; отсутствует явное сохранение названия действия/состояния. Проверить семантическое дерево и озвучивание. Наличие индикатора само по себе не доказывает потерю всей информации TalkBack. `UiKitButtonTest.kt::appButton_loading_hidesText` 65–72 проверяет отсутствие текста, `appButton_loading_isNotEnabled` 75–90 — блокировку; название действия в semantic-tree эти тесты не проверяют. Исчезновение видимого текста и потеря доступного названия — разные критерии.
- `ForceUpdateScreen.kt::ForceUpdateScreen`: центральная `Column` без прокрутки содержит фиксированный герой 104dp, отступы и масштабируемый текст. На малом экране при крупном системном шрифте надо проверить достижимость единственной кнопки; без рендера переполнение не объявляется воспроизведённым.
- `EarnPeriodChip`: в исходном коде внутренняя строка содержит 16dp-иконку, 14sp-текст (`MoneyType.Body`) и vertical padding 8dp без минимальной высоты. Измерить фактические области нажатия и отсутствие пересечений расширенных областей; нельзя приравнивать размер нарисованной поверхности к фактической области касания Compose.
- `IntroScreen.kt` 148–161: `onDispose` восстанавливает `isAppearanceLightStatusBars/isAppearanceLightNavigationBars = true`, а не значение актуальной темы; `Theme.kt` 72–87 ожидает `!darkTheme`. В тёмной теме проверить цвет иконок после пропуска и обычного завершения интро. Наличие другого SideEffect не доказывает, что именно он последним сработает при переходе; результат на устройстве пока не подтверждён.

## Что прочитано и что подтверждает чтение

| Участок | Доказательство чтения | Граница |
|---|---|---|
| `MainActivity.kt` 456–565, 874–899 | `Screen`/`HomeTab`, реализация общего нажатия/появления | Общая Activity и все остальные функции целиком здесь не разбирались |
| `AppNavAdmin.kt`, `AppNavOrders.kt`, `AppNavHome.kt` | маршруты и callback навигации | Достижимость настоящими действиями не проверена |
| `YuldashApp.kt` 1090–1612, 1651–1829, 1971–2023, 2059–2088, 2241–2398, 2423–2591 | ветвление Screen/HomeTab, онбординг, язык/роль, lazy-панели, insets, footer, меню | Основной большой state-слой и все компоненты полного файла не прочитаны целиком |
| `CanonTokens.kt` 1–263, `ui/theme/Theme.kt` целиком | адаптивные цвета, формы, шкала текста; ручная тема системных панелей | Полная согласованность Material-компонентов и Canon на экране требует рендера |
| `UiKit.kt` целиком | `RepeatWhileVisible`, `AppButton`, `AppButtonContent`, AppStateContainer, loading/error/empty/stale, banner | Семантика, геометрия и все вызывающие экраны не исполнены; большой шрифт/ограниченный viewport требуют рендера |
| `DriverEarningsScreen.kt` 1–175 | запрос, загрузка/ошибка/пусто/stale, lazy-ключи дней, выбранный период | Точные визуальные размеры и поздние ответы не доказаны чтением |
| `ClinicRidesScreen.kt` 96–271 | два уровня загрузки, выбранная клиника и её карточка | Визуальные состояния/прокрутка и синхронизация ответов требуют исполнения |
| `CourierScreen.kt` 838–896 | зона/фильтры, selected-семантика и анимация цвета | Основная доставка/кабинет целиком не разобраны этим чтением |
| `BookingActiveTripScreen.kt` 3586–3800 | общий пузырь сообщения, голос/текст/чужой медиа-адрес, меню, failed/queued | Чат и жизненный цикл поездки целиком этим чтением не приняты |
| `SecondaryScreens.kt` 781–875 | настройки темы/шрифта/языка, выход и уведомления | Полный файл вторичных экранов не прочитан |
| `ForceUpdateScreen.kt` целиком | insets, появление, пустая/непустая ссылка, запуск внешнего Activity | Малый экран/крупный шрифт и отказ запуска не воспроизведены |
| `IntroScreen.kt` 137–330 | таймлайн, пропуск, reduced motion, системные панели, слово/слоган/герой | Отображение, крупный шрифт и восстановление панелей требуют устройства |
| `LoginScreen.kt` 694–719, 1188–1210, шкала LoginBody/LoginCaption | кнопка Telegram и loading, выбор языка | Остальные шаги входа этим чтением не приняты |
| `SosVerifyScreens.kt` 506–548 | основная экстренная кнопка | Запуск dialer/отказ и весь SOS-путь не исполнялись |
| `PaymentMethodsScreen.kt` 109–436 | выбор/ожидание/ошибка/повтор/«скоро», строки и описания галочки | Реальный запрос двух участников и дерево доступности требуют выполнения |
| `DriverTaxiRidesScreen.kt` 63–185 | загрузка/ошибка/empty/stale, ключи списка, раскрытие итогов | Деньги/сервер/геометрия не подтверждены чтением |
| `ParcelChatScreen.kt` 67–150, `InstantChatScreen.kt` 164–228 | история/ошибка/повтор, read-only и imePadding | Остальной чат, клавиатура и постоянное соединение не исполнялись |
| `RidesRequestsChatScreens.kt` 3496–3518 | переключение проигрывания и описание кнопки | Остальной компонент сообщения не разобран целиком |
| `ContrastGuardTest.kt` целиком; `CanonSourceGuardTest.kt` 1–192; `tools/contrast.py` 1–100 | область существующих проверок контраста/размеров и расчёт | Gradle-тесты не запускались этим агентом |
| `UiKitButtonTest.kt` целиком | показ текста, клик, disabled/loading, стили, заголовок | Эти тесты не доказывают название действия для озвучивания в loading; исполнения не было |

## Полный перечень маршрутов и обнаруженные внутренние состояния

Таблица ниже строится из текущего `enum Screen` и фактических ветвей `YuldashApp`/`AppNav*`. В столбце состояния указаны обнаруженные переменные в теле входной композиции, а не утверждение о наличии проверенного loading/error/empty UI. Пустое поле означает «локальная переменная не найдена», а не «состояний нет»: они могут приходить параметром, ViewModel или вложенным контроллером. Полнота семантического разбора вложений ещё не подтверждена.

Снимок перечня: 30.09.2026, ветка `audit/full-technical-20260930`, HEAD `6fa0595d88bea4fcd59d4a1e2139c2d128cf6821`. Рабочая копия содержит локальные изменения других задач/основного агента; этот документ их не присваивает.

Всего `96` значений Screen — это `len()` списка элементов enum; `97` Kotlin-файлов с `@Composable` — это сумма файлов, содержащих эту аннотацию. Это числа инвентаризации, не число проверенных сценариев.

| Screen | Входная композиция / источник | Источник маршрута | Обнаруженные переменные / входные признаки состояния |
|---|---|---|---|
| `Splash` | inline: брендовый стартовый экран, `YuldashApp.kt:1153–1159` | `YuldashApp.kt:1153` | направление старта решает родитель |
| `Intro` | `IntroScreen` — [IntroScreen.kt](../android/app/src/main/java/com/yuldash/app/IntroScreen.kt), 137–330 | `YuldashApp.kt:1160` | `showMeaning`, `showBrand`, `showUnderline`, `showSlogan`, `sloganBa`, `exiting`, `done`, `showSkip` |
| `Onboarding` | `OnboardingScreen` — [YuldashApp.kt](../android/app/src/main/java/com/yuldash/app/YuldashApp.kt), 1628–1644 | `YuldashApp.kt:1161` | `onSimpleMode` |
| `Login` | `LoginScreen` — [LoginScreen.kt](../android/app/src/main/java/com/yuldash/app/LoginScreen.kt), 304–363 | `YuldashApp.kt:1175` | `appear` |
| `Home` | `HomeRoute` — [AppNavHome.kt](../android/app/src/main/java/com/yuldash/app/AppNavHome.kt), 27–217 | `YuldashApp.kt:1192` | `screen`, `language`, `selectedRide`, `activeTrip`, `activeBookingId`, `startHomeTab`, `responsesRequestId`, `isAdmin`, `partnerAds`, `requestsLoading`, `requestsError`, `onBookingStatus` |
| `CreateRide` | `CreateRideScreen` — [CreateRideScreen.kt](../android/app/src/main/java/com/yuldash/app/CreateRideScreen.kt), 282–441 | `YuldashApp.kt:1215` | `from`, `to`, `dateTime`, `seats`, `price`, `comment`, `petsAllowed`, `childSeat`, `womenOnly`, `smoking`, `baggage`, `airConditioner`, `onlyTrusted`, `quiet`, `noMinors`, `waypoints`, `recurrence`, `category`, `partnerId`, `partners`, `receiverName`, `parcelSize`, `pickup`, `pickupLat`, `pickupLng`, `pickupPointId`, `showPicker`, `priceHintDto`, `publishing`, `publishError`, `geoRoutes` |
| `Support` | `SupportScreen` — [SupportBoostScreen.kt](../android/app/src/main/java/com/yuldash/app/SupportBoostScreen.kt), 263–336 | `YuldashApp.kt:1232` | `selectedAmount`, `customMode`, `customInput`, `completed`, `showSbp`, `donation`, `sending`, `sendError` |
| `Boost` | `BoostScreen` — [SupportBoostScreen.kt](../android/app/src/main/java/com/yuldash/app/SupportBoostScreen.kt), 465–605 | `YuldashApp.kt:1233` | `plans`, `rides`, `loading`, `loadError`, `selectedRideId`, `selectedTier`, `submitting`, `result`, `error`, `credits`, `carriedBoostSec`, `pendingPaymentId`, `checkingPayment`, `paid`, `tries` |
| `Booking` | `BookingScreen` — [BookingActiveTripScreen.kt](../android/app/src/main/java/com/yuldash/app/BookingActiveTripScreen.kt), 326–769 | `YuldashApp.kt:1234` | `details`, `payMethod`, `payAmountText`, `minorPassenger`, `guardianName`, `guardianPhone`, `detailsLoading`, `detailsError`, `detailsReload`, `offlineSaveFailed`, `offlineSaveBusy`, `offlineSaveRetry`, `detailsSession`, `historyRemovalFailed`, `historyRemovalBusy`, `historyRemovalRetry`, `showCancelConfirmation`, `bookingStatus` |
| `ActiveTrip` | `ActiveTripScreen` — [BookingActiveTripScreen.kt](../android/app/src/main/java/com/yuldash/app/BookingActiveTripScreen.kt), 1428–2657 | `YuldashApp.kt:1317` | `messages`, `role`, `driverPhase`, `arrivalVerified`, `aloneWithDriver`, `bookingStatus`, `tripPass`, `removalFailed`, `removalBusy`, `removalRetry`, `offline`, `departIso`, `armAfterMs`, `draft`, `editingId`, `status`, `showDriverFinishConfirmation`, `showShare`, `wsConnected`, `failedIds`, `queuedIds`, `tempSeq`, `boardingCode`, `codeSaveFailed`, `codeSaveBusy`, `codeSaveRetry`, `payMethod`, `payAmount`, `routeFromPoint`, `routeToPoint`, `historyLoading`, `historyError`, `historyTick`, `wasEverConnected`, `myStars`, `reviewText`, `rating`, `reviewSent`, `pickedTags`, `supportDismissed`, `showCancel`, `cancelReason`, `liveLink`, `activeShares`, `showContacts` |
| `Sos` | `SosScreen` — [SosVerifyScreens.kt](../android/app/src/main/java/com/yuldash/app/SosVerifyScreens.kt), 283–474 | `YuldashApp.kt:1328` | `description`, `category`, `sent`, `sending`, `failed`, `rateLimited`, `sosLat`, `sosLng`, `locating`, `locFailed`, `locListener`, `locTimeout` |
| `CreateRequest` | `CreatePassengerRequestScreen` — [AccessibilityScreens.kt](../android/app/src/main/java/com/yuldash/app/AccessibilityScreens.kt), 720–844 | `YuldashApp.kt:1224` | `from`, `to`, `time`, `seats`, `category`, `price`, `comment`, `womenOnly`, `childSeat`, `pets`, `wheelchair`, `baggage`, `nonSmoking`, `airConditioner`, `onlyTrusted`, `submitting`, `pickupPointId`, `pickupLabel` |
| `VerifyDriver` | `VerifyDriverScreen` — [SosVerifyScreens.kt](../android/app/src/main/java/com/yuldash/app/SosVerifyScreens.kt), 762–898 | `YuldashApp.kt:1335` | `make`, `model`, `carColor`, `plate`, `seats`, `licenseUrl`, `carPhotoUrl`, `uploadingLicense`, `uploadingCar`, `docsStatus`, `verified`, `submitting`, `autocheckResult`, `autocheckData`, `submitError`, `uploadError`, `statusFailed`, `statusLoading`, `statusRetry`, `onSelectTab` |
| `Notifications` | `NotificationsScreen` — [SecondaryScreens.kt](../android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt), 279–466 | `YuldashApp.kt:1339` | `selected`, `feed`, `loading`, `error`, `reload`, `onSelectTab` |
| `Safety` | `SafetyScreen` — [SecondaryScreens.kt](../android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt), 707–778 | `YuldashApp.kt:1399` | `verifiedOnly`, `onSelectTab` |
| `Settings` | `SettingsScreen` — [SecondaryScreens.kt](../android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt), 781–926 | `YuldashApp.kt:1408` | `notifications`, `sounds`, `showThemeDialog`, `showFontDialog`, `showLogoutDialog`, `onSelectTab`, `isAdmin` |
| `Privacy` | `PrivacyScreen` — [CreateRideScreen.kt](../android/app/src/main/java/com/yuldash/app/CreateRideScreen.kt), 824–956 | `YuldashApp.kt:1379` | `recentCount`, `askClear`, `clearing`, `reload` |
| `MyData` | `MyDataScreen` — [MyDataScreen.kt](../android/app/src/main/java/com/yuldash/app/MyDataScreen.kt), 77–384 | `YuldashApp.kt:1380` | `loading`, `error`, `data`, `askDeleteDocs`, `deleting`, `docsError`, `exporting`, `exportError` |
| `Rules` | `RulesScreen` — [SecondaryScreens.kt](../android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt), 1021–1058 | `YuldashApp.kt:1381` | локальных признаков не найдено |
| `PaymentInfo` | `PaymentInfoScreen` — [SecondaryScreens.kt](../android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt), 1062–1132 | `YuldashApp.kt:1382` | локальных признаков не найдено |
| `PaymentMethods` | `PaymentMethodsScreen` — [PaymentMethodsScreen.kt](../android/app/src/main/java/com/yuldash/app/PaymentMethodsScreen.kt), 109–293 | `YuldashApp.kt:1383` | `wanted`, `летит`, `неДошло` |
| `PricingInfo` | `PricingInfoScreen` — [SecondaryScreens.kt](../android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt), 1140–1234 | `YuldashApp.kt:1395` | локальных признаков не найдено |
| `Blocklist` | `BlocklistScreen` — [SecondaryScreens.kt](../android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt), 2041–2075 | `YuldashApp.kt:1396` | `blocks`, `partners`, `loading`, `error` |
| `Report` | `ReportScreen` — [SecondaryScreens.kt](../android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt), 2132–2170 | `YuldashApp.kt:1397` | `partners`, `loading`, `error`, `target` |
| `Filters` | `FiltersScreen` — [SecondaryScreens.kt](../android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt), 1306–1333 | `YuldashApp.kt:1398` | `sel` |
| `AdminCabinet` | `AdminCabinetScreen` — [SecondaryScreens.kt](../android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt), 1671–1717 | `AppNavAdmin.kt:25` | `onModeration` |
| `AdminRequest` | `AdminRequestScreen` — [SecondaryScreens.kt](../android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt), 1914–1963 | `AppNavAdmin.kt:53` | `phone`, `name`, `from`, `to`, `seats`, `comment`, `sending` |
| `AdminResponses` | `AdminResponsesScreen` — [SecondaryScreens.kt](../android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt), 1967–2037 | `AppNavAdmin.kt:54` | `reqId`, `resps`, `loading` |
| `AdminDrivers` | `AdminDriversScreen` — [SecondaryScreens.kt](../android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt), 1387–1426 | `AppNavAdmin.kt:48` | `list`, `loading`, `error` |
| `AdminReports` | `AdminReportsScreen` — [SecondaryScreens.kt](../android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt), 1528–1570 | `AppNavAdmin.kt:49` | `list`, `loading`, `error` |
| `AdminTextFlags` | `AdminTextFlagsScreen` — [AdminTextFlagsScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminTextFlagsScreen.kt), 54–82 | `AppNavAdmin.kt:50` | `items`, `loading`, `error`, `kind`, `reloadKey` |
| `AdminSupport` | `AdminSupportScreen` — [AdminSupportScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminSupportScreen.kt), 67–132 | `AppNavAdmin.kt:51` | `items`, `loading`, `error`, `onlyOpen`, `reloadKey`, `thread`, `busy` |
| `AdminPaymentRequests` | `AdminPaymentRequestsScreen` — [SecondaryScreens.kt](../android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt), 1721–1910 | `AppNavAdmin.kt:52` | `list`, `debts`, `summary`, `loading`, `error`, `forgiveTarget`, `forgiveReason`, `showPartnerPay` |
| `RequestsFeed` | `RequestsFeedScreen` — [RidesRequestsChatScreens.kt](../android/app/src/main/java/com/yuldash/app/RidesRequestsChatScreens.kt), 2161–2284 | `YuldashApp.kt:1485` | `feed`, `loading`, `error`, `target`, `price`, `departureNote`, `comment`, `selectedFilter`, `responding`, `withdrawTarget`, `withdrawing` |
| `RequestResponses` | `ResponsesScreen` — [RidesRequestsChatScreens.kt](../android/app/src/main/java/com/yuldash/app/RidesRequestsChatScreens.kt), 2610–2702 | `YuldashApp.kt:1491` | `resps`, `loading`, `error`, `accepting`, `reloadTick`, `counterFor`, `myRequest` |
| `Help` | `HelpScreen` — [SecondaryScreens.kt](../android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt), 2336–2468 | `YuldashApp.kt:1427` | `supportUnread`, `helpQuery`, `onSelectTab` |
| `PassengerCabinet` | `PassengerCabinetScreen` — [ProfileScreen.kt](../android/app/src/main/java/com/yuldash/app/ProfileScreen.kt), 1142–1221 | `YuldashApp.kt:1436` | `bookings`, `bookingsLoading`, `bookingsError`, `bookingsReload`, `serverReqCount`, `myRating`, `restrictions` |
| `DriverCabinet` | `DriverCabinetScreen` — [ProfileScreen.kt](../android/app/src/main/java/com/yuldash/app/ProfileScreen.kt), 1374–1847 | `YuldashApp.kt:1456` | `driverRides`, `ridesError`, `ridesLoading`, `driverBookings`, `driverRating`, `online`, `onlineLoaded`, `debt`, `debtPaying`, `taxiApp`, `pretripNeeded`, `taxiAppLoaded`, `zone`, `showZoneSheet`, `workday`, `restrictions`, `archive`, `archiveLoading`, `archiveError`, `isWomanDriver`, `womanVerified`, `tipsSbp`, `confirmWomanOff`, `bookingsReload`, `ridesReload`, `refreshing` |
| `AdsCabinet` | `AdsCabinetScreen` — [ProfileScreen.kt](../android/app/src/main/java/com/yuldash/app/ProfileScreen.kt), 3868–3949 | `YuldashApp.kt:1496` | `loading`, `error`, `ads`, `stats`, `packages`, `reloadKey`, `submittingId`, `payingAd` |
| `SimpleMode` | `SimpleModeScreen` — [AccessibilityScreens.kt](../android/app/src/main/java/com/yuldash/app/AccessibilityScreens.kt), 264–321 | `YuldashApp.kt:1506` | `showFontDialog` |
| `VoiceRequest` | `VoiceRequestScreen` — [AccessibilityScreens.kt](../android/app/src/main/java/com/yuldash/app/AccessibilityScreens.kt), 536–717 | `YuldashApp.kt:1517` | `recording`, `startMs`, `recordedPath`, `recordedDur`, `uploading`, `submittingText`, `recognizedText` |
| `FamilyOrder` | `FamilyOrderScreen` — [AccessibilityScreens.kt](../android/app/src/main/java/com/yuldash/app/AccessibilityScreens.kt), 1433–1499 | `YuldashApp.kt:1526` | `passenger`, `phone`, `fromCity`, `toCity`, `notifyContact`, `submitting`, `submitError` |
| `TrustedContacts` | `TrustedContactsScreen` — [AccessibilityScreens.kt](../android/app/src/main/java/com/yuldash/app/AccessibilityScreens.kt), 1574–1684 | `YuldashApp.kt:1535` | `showAdd`, `nm`, `rel`, `ph`, `serverContacts`, `loading`, `loadError`, `reloadKey`, `toDelete` |
| `RepeatTrip` | `RepeatTripScreen` — [AccessibilityScreens.kt](../android/app/src/main/java/com/yuldash/app/AccessibilityScreens.kt), 1789–1878 | `YuldashApp.kt:1552` | `frequent`, `loading`, `loadError`, `reload`, `submittingRoute` |
| `CallbackHelp` | `CallbackHelpScreen` — [AccessibilityScreens.kt](../android/app/src/main/java/com/yuldash/app/AccessibilityScreens.kt), 2023–2043 | `YuldashApp.kt:1562` | `reason` |
| `AppReview` | `AppReviewScreen` — [AppReviewScreen.kt](../android/app/src/main/java/com/yuldash/app/AppReviewScreen.kt), 48–107 | `YuldashApp.kt:1588` | `stars`, `text`, `city`, `sending`, `sent`, `error` |
| `AdminReviews` | `AdminReviewsScreen` — [AdminReviewsScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminReviewsScreen.kt), 49–99 | `AppNavAdmin.kt:61` | `loading`, `error`, `publishingId` |
| `AdminAds` | `AdminAdsScreen` — [AdminAdsScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminAdsScreen.kt), 67–171 | `AppNavAdmin.kt:62` | `loading`, `error`, `founderUsed`, `founderLimit`, `stats`, `busyId`, `showForm`, `editing` |
| `AdEditor` | `AdEditorScreen` — [ProfileScreen.kt](../android/app/src/main/java/com/yuldash/app/ProfileScreen.kt), 4209–4320 | `YuldashApp.kt:1501` | `title`, `text`, `button`, `target`, `cities`, `pkg`, `packages`, `packagesError`, `busy` |
| `InstantOrder` | `InstantOrderScreen` — [InstantOrderScreen.kt](../android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt), 1464–1835 | `AppNavOrders.kt:40` | `order`, `scheduledConfirmId`, `scheduledConfirmAt`, `scheduledConfirmFrom`, `scheduledConfirmTo`, `onboardSeenAt`, `checking`, `availability`, `restoreError`, `pollOffline`, `restoreTick`, `cancelReasonForId` |
| `InstantDriverTrip` | `InstantDriverTripScreen` — [InstantOrderScreen.kt](../android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt), 6560–7044 | `AppNavOrders.kt:46` | `order`, `loading`, `busy`, `confirmNoShow`, `confirmCancel`, `actionError`, `loadError`, `reloadTick`, `lastLocSentMs`, `prevSentPoint` |
| `InstantChat` | `InstantChatScreen` — [InstantChatScreen.kt](../android/app/src/main/java/com/yuldash/app/InstantChatScreen.kt), 53–225 | `AppNavOrders.kt:51` | `messages`, `input`, `sending`, `historyLoading`, `historyError`, `historyTick`, `wsConnected`, `tempSeq`, `role`, `orderStatus`, `wasEverConnected` |
| `TaxiOnboarding` | `TaxiOnboardingScreen` — [TaxiOnboardingScreen.kt](../android/app/src/main/java/com/yuldash/app/TaxiOnboardingScreen.kt), 152–214 | `AppNavOrders.kt:55` | `loading`, `loadError`, `application`, `editing`, `reloadKey` |
| `AdminTaxi` | `AdminTaxiScreen` — [AdminTaxiScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminTaxiScreen.kt), 69–433 | `AppNavAdmin.kt:55` | `filter`, `apps`, `loading`, `error`, `cities`, `citiesError`, `newCity`, `cityBusy`, `rejectingId`, `rejectComment`, `dayShift`, `pretrip`, `pretripLoading`, `pretripError` |
| `AdminWaitlist` | `AdminWaitlistScreen` — [AdminWaitlistScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminWaitlistScreen.kt), 52–234 | `AppNavAdmin.kt:56` | `data`, `loading`, `error`, `roleFilter`, `invitedFilter`, `selected`, `inviting` |
| `AdminTaxiPulse` | `AdminTaxiPulseScreen` — [AdminTaxiPulseScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminTaxiPulseScreen.kt), 55–192 | `AppNavAdmin.kt:57` | `pulse`, `loading`, `error`, `reloadTick`, `complaints` |
| `IncomeCalculator` | `IncomeCalculatorScreen` — [IncomeCalculatorScreen.kt](../android/app/src/main/java/com/yuldash/app/IncomeCalculatorScreen.kt), 52–187 | `YuldashApp.kt:1426` | `ridesPerDay`, `partners`, `subPrice`, `boostsPerDay`, `taxiOn`, `avgCheck`, `commissionPct`, `routes`, `kmPerRide`, `fuelPer100`, `fuelPrice` |
| `DriverProfile` | `DriverProfileScreen` — [DriverProfileScreen.kt](../android/app/src/main/java/com/yuldash/app/DriverProfileScreen.kt), 47–82 | `YuldashApp.kt:1589` | `loading`, `error`, `data`, `reloadKey` |
| `RouteWatches` | `RouteWatchesScreen` — [SecondaryScreens.kt](../android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt), 551–678 | `YuldashApp.kt:1374` | `watches`, `loading`, `error`, `from`, `to`, `bothWays`, `submitting` |
| `Trust` | `TrustScreen` — [TrustScreens.kt](../android/app/src/main/java/com/yuldash/app/TrustScreens.kt), 96–186 | `YuldashApp.kt:1590` | `reload`, `loading`, `error`, `data` |
| `Invites` | `InvitesScreen` — [TrustScreens.kt](../android/app/src/main/java/com/yuldash/app/TrustScreens.kt), 324–534 | `YuldashApp.kt:1597` | `reload`, `loading`, `error`, `trust`, `invites`, `codeInput`, `redeeming`, `redeemMsg`, `justJoined`, `creating`, `createError` |
| `Consents` | `ConsentsScreen` — [TrustScreens.kt](../android/app/src/main/java/com/yuldash/app/TrustScreens.kt), 574–671 | `YuldashApp.kt:1598` | `reload`, `loading`, `error`, `consents`, `loaded`, `savingKind`, `saveError` |
| `MyStats` | `MyStatsScreen` — [MyStatsScreen.kt](../android/app/src/main/java/com/yuldash/app/MyStatsScreen.kt), 94–214 | `YuldashApp.kt:1578` | `loading`, `error`, `stats` |
| `ClinicRides` | `ClinicRidesScreen` — [ClinicRidesScreen.kt](../android/app/src/main/java/com/yuldash/app/ClinicRidesScreen.kt), 96–235 | `YuldashApp.kt:1599` | `partners`, `partnersLoading`, `partnersError`, `partnersReload`, `selected`, `rides`, `ridesLoading`, `ridesError`, `ridesReload` |
| `Coupons` | `CouponsScreen` — [CouponsScreen.kt](../android/app/src/main/java/com/yuldash/app/CouponsScreen.kt), 134–154 | `YuldashApp.kt:1608` | `tab`, `detail`, `activated` |
| `PartnerCabinet` | `PartnerCabinetScreen` — [PartnerCabinetScreen.kt](../android/app/src/main/java/com/yuldash/app/PartnerCabinetScreen.kt), 80–120 | `YuldashApp.kt:1609` | `me`, `loading`, `error` |
| `AdminPartners` | `AdminPartnersScreen` — [AdminPartnersScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminPartnersScreen.kt), 54–198 | `AppNavAdmin.kt:63` | `list`, `loading`, `error`, `busyId`, `rejectTarget`, `reason` |
| `AdminModeration` | `AdminModerationScreen` — [AdminModerationScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminModerationScreen.kt), 55–178 | `AppNavAdmin.kt:64` | `queue`, `loading`, `error`, `busyId`, `blockTarget` |
| `PromoCode` | `PromoCodeScreen` — [PromoCodeScreen.kt](../android/app/src/main/java/com/yuldash/app/PromoCodeScreen.kt), 64–116 | `YuldashApp.kt:1610` | `loading`, `error`, `mine`, `justApplied` |
| `AdminPromo` | `AdminPromoScreen` — [AdminPromoScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminPromoScreen.kt), 58–66 | `AppNavAdmin.kt:68` | `creating` |
| `Parcels` | `ParcelsScreen` — [ParcelsScreen.kt](../android/app/src/main/java/com/yuldash/app/ParcelsScreen.kt), 1202–1243 | `AppNavOrders.kt:92` | `tab` |
| `AdminParcels` | `AdminParcelsScreen` — [AdminParcelsScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminParcelsScreen.kt), 61–168 | `AppNavAdmin.kt:69` | `list`, `statement`, `loading`, `refreshing`, `error`, `action` |
| `CourierOnboarding` | `CourierOnboardingScreen` — [CourierOnboardingScreen.kt](../android/app/src/main/java/com/yuldash/app/CourierOnboardingScreen.kt), 97–156 | `AppNavOrders.kt:93` | `loading`, `loadError`, `application`, `editing`, `reloadKey` |
| `Courier` | `CourierScreen` — [CourierScreen.kt](../android/app/src/main/java/com/yuldash/app/CourierScreen.kt), 200–289 | `AppNavOrders.kt:97` | `application`, `applicationChecked`, `applicationRefreshFailed`, `me`, `loading`, `meError`, `reloadKey` |
| `AdminCourier` | `AdminCourierScreen` — [AdminCourierScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminCourierScreen.kt), 70–281 | `AppNavAdmin.kt:70` | `filter`, `apps`, `loading`, `refreshing`, `error`, `rejectingId`, `rejectReason`, `busy` |
| `Wallet` | `WalletScreen` — [WalletScreen.kt](../android/app/src/main/java/com/yuldash/app/WalletScreen.kt), 67–190 | `YuldashApp.kt:1579` | `loading`, `error`, `stale`, `balance`, `ledger`, `payout`, `payoutLoading`, `payoutError` |
| `DriverEarnings` | `DriverEarningsScreen` — [DriverEarningsScreen.kt](../android/app/src/main/java/com/yuldash/app/DriverEarningsScreen.kt), 58–144 | `YuldashApp.kt:1580` | `period`, `loading`, `error`, `data` |
| `SavedPlaces` | `SavedPlacesScreen` — [SavedPlacesScreen.kt](../android/app/src/main/java/com/yuldash/app/SavedPlacesScreen.kt), 393–524 | `YuldashApp.kt:1581` | `saved`, `loading`, `error`, `reload`, `query`, `suggestions`, `pendingHit` |
| `TripReceipt` | `TripReceiptScreen` — [TripReceiptScreen.kt](../android/app/src/main/java/com/yuldash/app/TripReceiptScreen.kt), 73–105 | `YuldashApp.kt:1582` | `receipt`, `loading`, `errorStatus`, `reload` |
| `TaxiReceipt` | `TaxiReceiptScreen` — [TaxiReceiptScreen.kt](../android/app/src/main/java/com/yuldash/app/TaxiReceiptScreen.kt), 101–173 | `AppNavOrders.kt:59` | `receipt`, `loading`, `errorStatus`, `reload`, `loadRemote` |
| `DriverTaxiRides` | `DriverTaxiRidesScreen` — [DriverTaxiRidesScreen.kt](../android/app/src/main/java/com/yuldash/app/DriverTaxiRidesScreen.kt), 63–159 | `AppNavOrders.kt:65` | `data`, `loading`, `error`, `reload` |
| `AdminSos` | `AdminSosScreen` — [AdminSosScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminSosScreen.kt), 76–210 | `AppNavAdmin.kt:58` | `tab`, `list`, `loading`, `error`, `reload`, `openNoteFor`, `note`, `busyId` |
| `TaxiDocuments` | `TaxiDocumentsScreen` — [TaxiDocsScreens.kt](../android/app/src/main/java/com/yuldash/app/TaxiDocsScreens.kt), 142–331 | `AppNavOrders.kt:83` | `app`, `carPhoto`, `loading`, `error`, `reload`, `busy`, `documentsVersion`, `msg`, `errText` |
| `CarPhoto` | `CarPhotoScreen` — [CarPhotoScreen.kt](../android/app/src/main/java/com/yuldash/app/CarPhotoScreen.kt), 91–180 | `AppNavOrders.kt:89` | `state`, `loading`, `error`, `reload`, `busySlot`, `sending`, `shotError`, `pickFor`, `pickKind`, `mode` |
| `PretripCheck` | `PretripCheckScreen` — [TaxiDocsScreens.kt](../android/app/src/main/java/com/yuldash/app/TaxiDocsScreens.kt), 876–1012 | `AppNavOrders.kt:90` | `state`, `loading`, `error`, `reload`, `health`, `car`, `sober`, `note`, `busy`, `errText` |
| `FairnessCenter` | `FairnessCenterScreen` — [FairnessScreens.kt](../android/app/src/main/java/com/yuldash/app/FairnessScreens.kt), 263–395 | `YuldashApp.kt:1583` | `standing`, `list`, `policy`, `loading`, `error`, `reload` |
| `IncidentDetail` | `IncidentDetailScreen` — [FairnessScreens.kt](../android/app/src/main/java/com/yuldash/app/FairnessScreens.kt), 685–1065 | `YuldashApp.kt:1587` | `inc`, `loading`, `error`, `reload`, `statement`, `appealText`, `photos`, `uploading`, `busy`, `errText`, `confirmPeace`, `appealOpen` |
| `AdminIncidents` | `AdminIncidentsScreen` — [AdminIncidentsScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminIncidentsScreen.kt), 114–221 | `AppNavAdmin.kt:59` | `tab`, `list`, `loading`, `error`, `reload`, `resolveTarget` |
| `AdminRatings` | `AdminRatingsScreen` — [AdminRatingsScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminRatingsScreen.kt), 72–160 | `AppNavAdmin.kt:60` | `list`, `loading`, `error`, `reload`, `busyId` |
| `CourierEarnings` | `CourierEarningsScreen` — [CourierEarningsScreen.kt](../android/app/src/main/java/com/yuldash/app/CourierEarningsScreen.kt), 101–224 | `AppNavOrders.kt:91` | `period`, `data`, `loading`, `error`, `reload` |
| `SupportTickets` | `SupportTicketsScreen` — [SupportChatScreen.kt](../android/app/src/main/java/com/yuldash/app/SupportChatScreen.kt), 79–122 | `YuldashApp.kt:1477` | `feed`, `loading`, `error`, `reload`, `composing` |
| `SupportTicket` | `SupportTicketScreen` — [SupportChatScreen.kt](../android/app/src/main/java/com/yuldash/app/SupportChatScreen.kt), 321–459 | `YuldashApp.kt:1481` | `messages`, `subject`, `status`, `input`, `sending`, `loading`, `error`, `reload`, `tempSeq`, `toastMsg` |
| `ScheduledOrders` | `ScheduledOrdersScreen` — [ScheduledOrdersScreen.kt](../android/app/src/main/java/com/yuldash/app/ScheduledOrdersScreen.kt), 67–308 | `YuldashApp.kt:1472` | `data`, `loading`, `error`, `stale`, `reload`, `busyId`, `cancelTarget`, `nowMs` |
| `DriverResponses` | `DriverResponsesScreen` — [DriverResponsesScreen.kt](../android/app/src/main/java/com/yuldash/app/DriverResponsesScreen.kt), 80–150 | `YuldashApp.kt:1486` | `rows`, `loading`, `error`, `busy`, `tick`, `counterFor` |
| `MyTaxiTrips` | `MyTaxiTripsScreen` — [MyTaxiTripsScreen.kt](../android/app/src/main/java/com/yuldash/app/MyTaxiTripsScreen.kt), 58–124 | `AppNavOrders.kt:71` | `orders`, `loading`, `error`, `reload` |
| `ParcelChat` | `ParcelChatScreen` — [ParcelChatScreen.kt](../android/app/src/main/java/com/yuldash/app/ParcelChatScreen.kt), 67–255 | `AppNavOrders.kt:77` | `messages`, `input`, `sending`, `historyLoading`, `historyError`, `historyTick`, `wsConnected`, `tempSeq`, `liveStatus`, `wasEverConnected`, `peerIsCourier`, `parcelStatus` |

### Поверхности вне enum

Пять HomeTab (`MainActivity.kt:560–566`, ветви `YuldashApp.kt::HomeScreen`) раскрываются в Map → `PassengerModeHome`, Rides → `RidesScreen`, Request → `MyRequestsScreen`, Chat → `ChatScreen`, Profile → `ProfileScreen`. Для карты режимы попутки/такси/курьера задаёт `ModeSwitchHome.kt`. `ForceUpdateScreen` блокирует весь корень отдельно от enum. Завершённые и отменённые состояния такси/попутки, формы доставки, вложенные панели, листы выбора, диалоги и меню также входят в аудит: их наличие фиксирует следующий индекс, а не новый набор актуальных задач.

Внутренние существенные варианты: такси `accepted/arriving/onboard/done/cancelled/expired`, входящее водительское предложение `offered`; доставка `accepted/in_transit/delivered/returning/returned`; курьерский допуск без заявки / `pending/rejected/approved`. Источники — ветви статуса `InstantOrderScreen.kt`, `CourierScreen.kt`, `ParcelsScreen.kt`; фактическое отображение каждого варианта ещё нужно связать с устройством и актуальным сценарием блока.

### Индекс композиций, включая вложенные панели/диалоги

Структурный индекс. Аннотация `@Composable` и функция найдены автоматически; запись не объявляет полное чтение/приёмку функции. При продолжении выбирать конкретные диапазоны, читать тело и связи, затем сохранять отдельный результат в B09/связанном блоке.

| Файл | Композиции (начальная строка) |
|---|---|
| [AccessibilityScreens.kt](../android/app/src/main/java/com/yuldash/app/AccessibilityScreens.kt) | `SimpleModeScreen:264`; `SeniorBigAction:324`; `SimpleSmallAction:346`; `AddressSuggestField:397`; `LocalRequestCard:483`; `VoiceRequestPlayRow:506`; `VoiceRequestScreen:536`; `CreatePassengerRequestScreen:720`; `CreatePassengerRequestContent:861`; `RequestMetricCell:1231`; `RequestInputMetricCell:1288`; `RequestToggleChip:1342`; `requestFilterChipColors:1360`; `RequestPublishBar:1370`; `FamilyOrderScreen:1433`; `FamilyOrderFormContent:1508`; `TrustedContactsScreen:1574`; `TrustedContactsContent:1692`; `TrustedContactCard:1761`; `RepeatTripScreen:1789`; `RepeatTripContent:1886`; `FrequentTripCard:1991`; `CallbackHelpScreen:2023`; `CallbackHelpContent:2052`; `VoiceParsedCard:2106` |
| [AdminAdsScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminAdsScreen.kt) | `AdminAdsScreen:67`; `AdAdminCard:174`; `CreateAdForm:283`; `planLabel:379` |
| [AdminCourierScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminCourierScreen.kt) | `AdminCourierScreen:70`; `CourierFilterChip:286`; `CourierStatusBadge:310`; `CourierDocImage:323` |
| [AdminIncidentsScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminIncidentsScreen.kt) | `adminTabLabel:89`; `adminTabHint:106`; `AdminIncidentsScreen:114`; `incidentTone:228`; `appealStatusSuffix:242`; `AdminIncidentCard:249`; `AdminSideBlock:362`; `incidentResolutionLabel:445`; `ResolveIncidentDialog:456`; `ChoiceRow:626` |
| [AdminModerationScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminModerationScreen.kt) | `AdminModerationScreen:55`; `QueueSection:182`; `ReviewReason:199`; `flagLabel:225`; `ModerationCouponCard:233`; `BlockCouponDialog:275` |
| [AdminParcelsScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminParcelsScreen.kt) | `AdminParcelsScreen:61`; `AdminParcelActionDialog:172`; `AdminCloseOption:252`; `ParcelStatementCard:277`; `StatementRow:315`; `AdminParcelCard:327` |
| [AdminPartnersScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminPartnersScreen.kt) | `AdminPartnersScreen:54`; `AdminPartnerCard:201`; `AdminPartnerStatusChip:260` |
| [AdminPromoScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminPromoScreen.kt) | `AdminPromoScreen:58`; `AdminPromoList:71`; `AdminPromoCard:168`; `PromoMetric:234`; `PromoCreateForm:244`; `PromoField:363`; `PromoKindChip:387` |
| [AdminRatingsScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminRatingsScreen.kt) | `AdminRatingsScreen:72`; `RatingsRulesCard:165`; `PendingRatingCard:192`; `RatingStars:265` |
| [AdminReviewsScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminReviewsScreen.kt) | `AdminReviewsScreen:49`; `AdminReviewsContent:106` |
| [AdminSosScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminSosScreen.kt) | `AdminSosScreen:76`; `SosAlarmBanner:214`; `SosHistoryHint:246`; `AdminSosCard:264`; `sosCategoryBadge:446` |
| [AdminSupportScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminSupportScreen.kt) | `AdminSupportScreen:67`; `AdminSupportList:136`; `AdminSupportRow:198`; `AdminSupportThread:238` |
| [AdminTaxiPulseScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminTaxiPulseScreen.kt) | `AdminTaxiPulseScreen:55`; `PulseFunnelCard:202`; `FunnelBars:248`; `PriceComplaintCard:299`; `PulseTile:330`; `PulseDotStat:346` |
| [AdminTaxiScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminTaxiScreen.kt) | `AdminTaxiScreen:69`; `pretripDayLabel:445`; `TaxiFilterChip:459`; `TaxiStatusBadge:475`; `TaxiDocImage:488` |
| [AdminTextFlagsScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminTextFlagsScreen.kt) | `AdminTextFlagsScreen:54`; `AdminTextFlagsContent:86`; `flagLabel:155`; `flagColor:163`; `TextFlagCard:166` |
| [AdminWaitlistScreen.kt](../android/app/src/main/java/com/yuldash/app/AdminWaitlistScreen.kt) | `AdminWaitlistScreen:52`; `WaitlistStatCard:238`; `WaitlistFilterChip:249` |
| [AppNavAdmin.kt](../android/app/src/main/java/com/yuldash/app/AppNavAdmin.kt) | `AdminNav:23` |
| [AppNavHome.kt](../android/app/src/main/java/com/yuldash/app/AppNavHome.kt) | `HomeRoute:27` |
| [AppNavOrders.kt](../android/app/src/main/java/com/yuldash/app/AppNavOrders.kt) | `OrdersNav:21` |
| [AppReviewScreen.kt](../android/app/src/main/java/com/yuldash/app/AppReviewScreen.kt) | `AppReviewScreen:48`; `AppReviewFormContent:118`; `ReviewStarsRow:208`; `ReviewThanksCard:237` |
| [AppText.kt](../android/app/src/main/java/com/yuldash/app/AppText.kt) | `appText:17` |
| [BargainUi.kt](../android/app/src/main/java/com/yuldash/app/BargainUi.kt) | `bargainTurnHint:127`; `BargainSummary:151`; `BargainChainRow:174`; `BargainStep:220`; `BargainTurnLine:250`; `CounterPriceDialog:295` |
| [BookingActiveTripScreen.kt](../android/app/src/main/java/com/yuldash/app/BookingActiveTripScreen.kt) | `BookingScreen:326`; `BookingDecisionBar:772`; `BookingDriverHeroCard:826`; `BookingRouteDecisionCard:912`; `BookingDecisionMetric:984`; `BookingMeetingCard:1004`; `payMethodLabel:1056`; `PayAgreementBlock:1075`; `BookingRouteMapPreview:1142`; `BookingMapLabel:1276`; `RouteMapUnavailableCard:1304`; `SettingsGroup:1342`; `SettingsNavRow:1349`; `SettingSwitchRow:1383`; `CompactProfileBanner:1404`; `ActiveTripScreen:1428`; `FrostyNightBanner:2726`; `RoadsideHelpButton:2753`; `activeTripActionLabel:2794`; `ActiveTripOptionBHero:2804`; `ActiveTripDriverCard:2863`; `ActiveTripBoardingCodeCard:2956`; `ActiveTripProgressCard:3007`; `ActiveTripRouteCard:3107`; `ActiveTripDecisionBar:3171`; `TripRouteHeaderCard:3222`; `DriverApproachingBanner:3249`; `BoardingCodeCard:3297`; `OfflineTripBanner:3369`; `TripPassCard:3392`; `TripPassRow:3443`; `TripStatusButtons:3460`; `MinorPassengerBlock:3500`; `AlonePassengerHint:3554`; `ShareTripRow:3583`; `ActiveSharesList:3607`; `ForeignMediaBubble:3644`; `MessageBubble:3667` |
| [CanonTokens.kt](../android/app/src/main/java/com/yuldash/app/CanonTokens.kt) | `appIsDark:48`; `canonChipInk:169`; `canonBreath:427`; `canonDrift:453` |
| [CarPhotoScreen.kt](../android/app/src/main/java/com/yuldash/app/CarPhotoScreen.kt) | `CarPhotoScreen:91`; `CarPhotoLoading:185`; `CarPhotoError:192`; `CarPhotoNothingToDo:209`; `CarPhotoBody:237`; `CarPhotoDemandHeader:344`; `CarPhotoCleanRules:393`; `CarPhotoHeader:420`; `CarPhotoWaitingForUs:472`; `CarPhotoRejectNote:496`; `CarPhotoRules:509`; `CarPhotoSlotRow:542` |
| [ClinicRidesScreen.kt](../android/app/src/main/java/com/yuldash/app/ClinicRidesScreen.kt) | `ClinicRidesEntryCard:61`; `ClinicRidesScreen:96`; `ClinicChip:239`; `ClinicRideCard:273`; `MetaChip:301` |
| [CouponsScreen.kt](../android/app/src/main/java/com/yuldash/app/CouponsScreen.kt) | `couponCategoryLabel:86`; `CouponsScreen:134`; `CouponsListScreen:157`; `CouponTab:180`; `NearbyCouponsTab:196`; `CouponFilterChip:279`; `CouponCard:296`; `DiscountBadge:330`; `PremiumBadge:341`; `MyCouponsTab:354`; `MyCouponCard:412`; `CouponStatusChip:445`; `CouponDetailView:462`; `ReportCouponDialog:634`; `ActivatedCodeView:676` |
| [CourierEarningsScreen.kt](../android/app/src/main/java/com/yuldash/app/CourierEarningsScreen.kt) | `CourierEarningsScreen:101`; `MoneyPeriodSwitch:234`; `MoneyPeriodSegment:252`; `CourierTotalsCard:291`; `CourierDayRow:340`; `MoneyLine:404`; `MoneySectionHeader:431`; `MoneyStaleStrip:450` |
| [CourierOnboardingScreen.kt](../android/app/src/main/java/com/yuldash/app/CourierOnboardingScreen.kt) | `CourierOnboardingScreen:97`; `CourierApplyFormContent:160`; `CourierTransportChip:408`; `CourierRuleRow:447`; `CourierPendingContent:462`; `CourierApprovedContent:481`; `CourierRejectedContent:500`; `CourierStatusScaffold:522`; `CourierSummaryRow:590`; `courierTransportLabel:602` |
| [CourierScreen.kt](../android/app/src/main/java/com/yuldash/app/CourierScreen.kt) | `CourierCargoRow:157`; `CourierCargoTag:185`; `CourierScreen:200`; `PoputkaDeliveryTabs:293`; `CourierNotApprovedView:333`; `CourierRefreshStrip:445`; `CourierWorkContent:452`; `CourierLiveLocationLink:821`; `CourierZoneChip:847`; `CourierPickChip:879`; `CourierSubTab:904`; `CourierAvailableTab:928`; `CourierFilterRow:1159`; `CourierAvailableCard:1172`; `CourierCarryingTab:1254`; `ParcelTrackMap:1831`; `CourierCarryingCard:1885`; `CourierContactDetails:2156`; `CourierCabinetTab:2242`; `StatementRow:2494`; `CourierRouteRow:2505`; `CourierDeliveryTag:2531` |
| [CourierTroubleDialog.kt](../android/app/src/main/java/com/yuldash/app/CourierTroubleDialog.kt) | `CourierTroubleButton:92`; `TroubleNotice:126`; `CourierTroubleDialog:140` |
| [CreateRideScreen.kt](../android/app/src/main/java/com/yuldash/app/CreateRideScreen.kt) | `CreateRideScreen:282`; `CreateRideFormContent:451`; `PrivacyScreen:824`; `RideTypeChip:972`; `PopularRouteChips:994`; `PriceHintChip:1027`; `FuelHintBlock:1046`; `PickupSuggestionChips:1077` |
| [DirectionGlyph.kt](../android/app/src/main/java/com/yuldash/app/DirectionGlyph.kt) | `YuldashDirectionGlyph:122` |
| [DriverEarningsScreen.kt](../android/app/src/main/java/com/yuldash/app/DriverEarningsScreen.kt) | `DriverEarningsScreen:58`; `EarnPeriodChip:148`; `EarnTotalsCard:168`; `EarnDayRow:197` |
| [DriverProfileScreen.kt](../android/app/src/main/java/com/yuldash/app/DriverProfileScreen.kt) | `DriverProfileScreen:47`; `DriverProfileContent:85`; `DriverHeaderCard:121`; `VerifiedPill:157`; `DriverStatsRow:174`; `StatCell:197`; `DriverReviewCard:226` |
| [DriverResponsesScreen.kt](../android/app/src/main/java/com/yuldash/app/DriverResponsesScreen.kt) | `DriverResponsesScreen:80`; `DriverResponsesContent:154`; `ResponsesLede:230`; `ResponsesStaleNotice:288`; `ResponseSkeletonCard:326`; `DriverResponseCard:357`; `DealPill:448`; `BargainActions:522` |
| [DriverTaxiRidesScreen.kt](../android/app/src/main/java/com/yuldash/app/DriverTaxiRidesScreen.kt) | `DriverTaxiRidesScreen:63`; `TaxiRidesTotalsCard:168`; `TaxiRideRow:221`; `TaxiLedgerCell:306`; `TaxiRideTag:319`; `feeStatusTag:331` |
| [DriverTipsCards.kt](../android/app/src/main/java/com/yuldash/app/DriverTipsCards.kt) | `DriverTipsCard:61`; `RideTipsCard:143` |
| [FairnessScreens.kt](../android/app/src/main/java/com/yuldash/app/FairnessScreens.kt) | `incidentTypeLabel:169`; `incidentStatusLabel:175`; `statusColors:186`; `StatusBadge:210`; `StatusPill:220`; `FairNotice:237`; `fairFieldColors:252`; `FairnessCenterScreen:263`; `StandingCard:399`; `StandingHero:560`; `StandingCounter:588`; `IncidentRow:617`; `IncidentDetailScreen:685`; `IncidentHeaderCard:1068`; `IncidentSideCard:1113`; `EvidenceThumb:1161`; `EvidenceViewer:1180`; `ViewerArrow:1239`; `IncidentVerdictCard:1255`; `EvidencePicker:1322`; `FileIncidentDialog:1389`; `IncidentTypeOption:1549` |
| [ForceUpdateScreen.kt](../android/app/src/main/java/com/yuldash/app/ForceUpdateScreen.kt) | `ForceUpdateScreen:50` |
| [GeoUi.kt](../android/app/src/main/java/com/yuldash/app/GeoUi.kt) | `settlementTitle:79`; `OsmCreditRow:86`; `zoneLabel:100`; `DriverZoneChip:131`; `SettlementPickField:165`; `DriverZoneSheet:241`; `CitySuggestInput:351`; `ZoneToggleCard:404`; `DistrictPickInput:449`; `ZoneOptionCard:505`; `GeoSuggestError:550` |
| [IncomeCalculatorScreen.kt](../android/app/src/main/java/com/yuldash/app/IncomeCalculatorScreen.kt) | `IncomeCalculatorScreen:52`; `BreakdownRow:190`; `CalcSlider:198` |
| [InstantChatScreen.kt](../android/app/src/main/java/com/yuldash/app/InstantChatScreen.kt) | `InstantChatScreen:53` |
| [InstantOrderScreen.kt](../android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt) | `InstantTripPhaseBar:228`; `TaxiPricingBreakdown:301`; `TaxiPriceAloudButton:471`; `TaxiPriceComplaintButton:512`; `TaxiPromoSavingsCard:633`; `taxiPromoHonestText:688`; `TaxiPromoPayRow:699`; `rememberNowMs:747`; `rememberMyLocationFix:817`; `rememberMyPoint:907`; `InstantRouteMap:930`; `InstantOrderScreen:1464`; `InstantOfflineBanner:1838`; `InstantCheckingSkeleton:1866`; `InstantRetryCard:1888`; `InstantNearbyBadge:1960`; `InstantAddressResults:2017`; `InstantDestinationPicker:2094`; `InstantOrderDetails:3173`; `InstantTimingPicker:3306`; `TimingChoiceChip:3345`; `InstantScheduledCreatedCard:3361`; `InstantClassCard:3482`; `InstantChosenOptionsRow:3541`; `InstantOptionsBlock:3583`; `InstantWaitingRow:3691`; `InstantAlternativesBlock:3750`; `InstantDriverEnRouteCard:3851`; `InstantReasonChip:4253`; `instantCancelGroups:4304`; `InstantCancelReasonDialog:4346`; `InstantSafetyRow:4460`; `InstantShareDialog:4511`; `InstantNoDriversCard:4645`; `InstantQueuePulse:4735`; `TaxiDisputeLink:4763`; `TaxiReceiptLink:4800`; `InstantFinalCard:4822`; `DriverPayMethodCard:4907`; `InstantRateAndReport:4961`; `ContactCancelSoftBanner:5046`; `UnpaidReportButton:5069`; `NoShowButton:5114`; `InstantCenterLoader:5157`; `TaxiComingSoonCard:5172`; `WaitlistRoleChip:5381`; `InstantLoginNeeded:5396`; `InstantDriverOnlineController:5415`; `InstantDriverWaitingScreen:5593`; `InstantWaitingOnlinePill:5713`; `InstantWaitingCompactHeader:5756`; `DriverBlockedStrip:5799`; `InstantWaitingDetailedHeader:5833`; `InstantWaitingDemandCard:5849`; `InstantWaitingMetrics:5895`; `InstantWaitingMetric:5930`; `InstantWaitingZoneRow:5958`; `instantWaitingZoneName:5985`; `instantDeclineReasons:6021`; `InstantDeclineReasonPanel:6045`; `InstantOfferOverlay:6141`; `InstantOfferTimer:6298`; `InstantOfferRouteCard:6318`; `InstantOfferMoneyAndClass:6372`; `InstantOfferSmallFact:6426`; `InstantOfferPassengerTrust:6450`; `InstantOfferHonestDetails:6487`; `InstantPointRow:6544`; `InstantDriverTripScreen:6560`; `InstantReceiptReminder:7049`; `InstantRoundTripCard:7092`; `RoundTripWaitChip:7157`; `DriverDestinationCard:7206`; `ChangeDestinationSheet:7316`; `PickStopSheet:7465`; `DriverMutableTripControls:7509`; `DriverStopButton:7567`; `OrderDestinationRow:7609`; `OrderInsertStopRow:7654`; `InstantPayMethodRow:7697`; `OrderStopRow:7715`; `OrderPointRow:7749`; `InstantThanksRow:7794`; `InstantWhereField:7865` |
| [IntroHero.kt](../android/app/src/main/java/com/yuldash/app/IntroHero.kt) | `SkyMotes:42`; `BrandHero:69` |
| [IntroScreen.kt](../android/app/src/main/java/com/yuldash/app/IntroScreen.kt) | `StaggerWord:112`; `IntroScreen:137`; `IntroBrandContent:343` |
| [LoginScreen.kt](../android/app/src/main/java/com/yuldash/app/LoginScreen.kt) | `LoginScreen:304`; `LoginFormCard:369`; `LoginFormContent:548`; `LoginErrorBanner:776`; `LoginLoadingHint:825`; `LoginSmsSection:844`; `LoginDivider:961`; `BrandHero:989`; `LoginHeroFeatures:1116`; `LoginHeroFeature:1137`; `LoginLangToggle:1164`; `LoginLangChip:1188`; `SafetyFooter:1209`; `LoginConsent:1240` |
| [MainActivity.kt](../android/app/src/main/java/com/yuldash/app/MainActivity.kt) | `text:631`; `pluralRu:638`; `seatsText:646`; `starsText:652`; `timeText:656`; `carText:659`; `minutesText:662`; `labelText:665`; `relationText:668`; `titleText:671`; `timeHintText:674`; `bounceClick:874`; `appearIn:885`; `SbpTransferSheet:901`; `SberPayBlock:974` |
| [MapCluster.kt](../android/app/src/main/java/com/yuldash/app/MapCluster.kt) | `carClusterBitmap:30` |
| [MapScreen.kt](../android/app/src/main/java/com/yuldash/app/MapScreen.kt) | `MapScreen:271`; `MapHero:703`; `timeGreeting:883`; `HomeHeader:894`; `QuickSearchCard:990`; `SeniorAccessCard:1137`; `YandexMapCard:1292`; `RequestPreviewCard:1930`; `PickupPickerOverlay:2050`; `MapPreview:2127`; `MapLabel:2211`; `MapZoomControls:2229`; `MapAdRouteBanner:2253` |
| [MobilityUi.kt](../android/app/src/main/java/com/yuldash/app/MobilityUi.kt) | `MobilityScreenIntro:77`; `TaxiMapFrame:129`; `MobilityRouteTimeline:184`; `MobilityRouteText:220`; `TaxiServiceClassTile:236`; `TaxiFareSummary:377`; `MobilityMetricChip:432`; `MobilityProgressRail:450`; `TaxiTripProgress:545`; `CourierLineHero:560`; `MobilitySegmentTab:629`; `CourierOfferCard:671`; `CourierServiceTypeTile:716`; `CourierFareSummary:772`; `MobilityValueTile:810`; `MobilitySmallTag:820`; `CourierDeliveryProgress:863` |
| [ModeSwitchHome.kt](../android/app/src/main/java/com/yuldash/app/ModeSwitchHome.kt) | `PassengerModeHome:107`; `ModeSwitchBar:246`; `ModeHintSheet:404`; `HintRow:456` |
| [MyDataScreen.kt](../android/app/src/main/java/com/yuldash/app/MyDataScreen.kt) | `MyDataScreen:77`; `DataRow:388`; `LocationUsageCard:413`; `LocationDataRow:508`; `RowDivider:526`; `DriverDocsCard:535`; `FadeInCard:588`; `countText:600`; `selfDeleteText:605` |
| [MyStatsScreen.kt](../android/app/src/main/java/com/yuldash/app/MyStatsScreen.kt) | `MyStatsScreen:94`; `StatsShareCard:218`; `MiniStat:287`; `StatTile:302`; `RankProgressCard:327`; `AchievementsSection:513`; `AchievementRow:541` |
| [MyTaxiTripsScreen.kt](../android/app/src/main/java/com/yuldash/app/MyTaxiTripsScreen.kt) | `MyTaxiTripsScreen:58`; `TaxiTripHistoryCard:128`; `TripRouteLine:206`; `tripDayLabel:218` |
| [ParcelChatScreen.kt](../android/app/src/main/java/com/yuldash/app/ParcelChatScreen.kt) | `ParcelChatScreen:67` |
| [ParcelReceiptDialog.kt](../android/app/src/main/java/com/yuldash/app/ParcelReceiptDialog.kt) | `ParcelReceiptDialog:39`; `ReceiptRow:144` |
| [ParcelsScreen.kt](../android/app/src/main/java/com/yuldash/app/ParcelsScreen.kt) | `DeliverySectionTitle:171`; `DeliveryHint:180`; `DeliveryErrorCard:189`; `parcelSizeLabel:210`; `parcelSizeHint:227`; `cargoTypeLabel:254`; `parcelWeightLabel:278`; `deliveryDateHuman:317`; `deliveryDayName:329`; `parcelStatusStyle:339`; `ParcelStatusChip:358`; `ParcelReturnNotice:378`; `ParcelDeadlineNote:428`; `ParcelRouteRow:492`; `ParcelPhotoStrip:533`; `ParcelAddressBlock:594`; `ParcelAddressRow:642`; `ParcelRaiseBudgetBlock:683`; `ParcelSettlementBlock:765`; `SettlementAmountRow:802`; `ParcelDisputeButton:812`; `ParcelDisputeDialog:822`; `ParcelDisputeTypeOption:1053`; `DialogErrorLine:1094`; `ParcelRateButton:1107`; `ParcelRatedRow:1118`; `ParcelRateDialog:1133`; `ParcelsScreen:1202`; `ParcelTab:1246`; `SendParcelTab:1273`; `ParcelRouteSummary:1867`; `ParcelStepProgress:1904`; `DeliveryTypeCard:1930`; `UrgencyChip:1973`; `DeliveryWaitNote:2014`; `ParcelDeadlinePicker:2050`; `EstimateCard:2100`; `EstimateRow:2151`; `ParcelField:2159`; `ParcelCargoTypePicker:2203`; `ParcelCargoChip:2236`; `ParcelFragileSwitch:2267`; `ParcelSizeCard:2313`; `RulesCheckbox:2343`; `ParcelCreatedView:2391`; `MyParcelsTab:2468`; `MyParcelCard:2722`; `ParcelTrackLinkBlock:2954` |
| [PartnerCabinetScreen.kt](../android/app/src/main/java/com/yuldash/app/PartnerCabinetScreen.kt) | `PartnerCabinetScreen:80`; `PartnerPendingView:125`; `PartnerRejectedView:149`; `PartnerForm:171`; `CategoryChip:244`; `ActivePartnerCabinet:261`; `PartnerDashboard:304`; `SubscriptionCard:407`; `StatementCard:437`; `PartnerCouponRow:462`; `CouponAdminStatusChip:529`; `StatusChip:552`; `CouponForm:564`; `SubscribeView:645`; `PlanCard:723`; `RedeemDialog:758` |
| [PaymentMethodsScreen.kt](../android/app/src/main/java/com/yuldash/app/PaymentMethodsScreen.kt) | `PaymentMethodsScreen:109`; `PaySectionTitle:296`; `PayDivider:302`; `PayNote:314`; `PayMethodRow:320`; `PayFailedRow:389`; `PaySoonRow:433` |
| [PayOnlineCard.kt](../android/app/src/main/java/com/yuldash/app/PayOnlineCard.kt) | `PayOnlineCard:75`; `PayOnlineHeader:246` |
| [Permissions.kt](../android/app/src/main/java/com/yuldash/app/Permissions.kt) | `rememberPermissionGate:54` |
| [PriceSpeech.kt](../android/app/src/main/java/com/yuldash/app/PriceSpeech.kt) | `rememberPriceSpeaker:70` |
| [ProfileScreen.kt](../android/app/src/main/java/com/yuldash/app/ProfileScreen.kt) | `roleLabel:296`; `ProfileScreen:304`; `ProfileSectionLabel:953`; `AvatarUploadOverlay:967`; `ReferralStat:982`; `ProfileStatusPill:999`; `DangerActionCard:1053`; `ProfileActionCard:1079`; `ProfileActionCard:1111`; `PassengerCabinetScreen:1142`; `PassengerCabinetContent:1229`; `DriverCabinetScreen:1374`; `DriverBlockersCard:1873`; `DriverBlockerRow:1934`; `DriverOnlineHint:1944`; `RestrictionsCard:2087`; `DriverDebtBanner:2166`; `TaxiDashboardCard:2317`; `TaxiShiftProgressCard:2448`; `TaxiWeekRestCard:2521`; `TaxiRestCard:2561`; `TaxiOnboardingCta:2626`; `PrioritySection:2674`; `PriorityRow:2734`; `DriverDemandSection:2757`; `PassengerRow:2866`; `DriverCabinetContent:2893`; `ArchiveRideCard:3523`; `CabinetMetric:3563`; `weekdayShort:3577`; `weekdaysSummary:3585`; `DriverScheduleSection:3605`; `ScheduleRow:3712`; `AddScheduleDialog:3743`; `PublicDriverSchedulesCard:3837`; `AdsCabinetScreen:3868`; `AdsCabinetContent:3956`; `AdStatusBadge:4021`; `MyAdCard:4038`; `AdStatsTiles:4131`; `AdsShowcase:4151`; `AdEditorScreen:4209`; `AdField:4323`; `InfoCard:4344`; `EmptyStateCard:4377`; `InlinePartnerAdCard:4412`; `PartnerAdCard:4492`; `AdChip:4600`; `titleText:4615`; `descriptionText:4618`; `addressText:4621`; `categoryText:4624`; `packageText:4627`; `budgetText:4630`; `targetActionText:4633`; `primaryButtonText:4636`; `secondaryButtonText:4639`; `eridText:4642`; `label:4666`; `color:4677`; `label:4688`; `placementsLabel:4694`; `DetailMeta:4711`; `RouteMiniIcon:4720`; `TripInfoRow:4736` |
| [PromoCodeScreen.kt](../android/app/src/main/java/com/yuldash/app/PromoCodeScreen.kt) | `PromoCodeScreen:64`; `PromoInputCard:121`; `PromoSuccessCard:189`; `PromoTaxiDiscountNote:230`; `PromoAppliedCard:270`; `PromoPerkChip:315`; `PromoDiscountStatus:350`; `PromoHonestNote:403` |
| [RideshareCompletedScreen.kt](../android/app/src/main/java/com/yuldash/app/RideshareCompletedScreen.kt) | `RideshareCompletedScreen:90`; `RideshareCompletedContent:280`; `RideshareCompletedHero:428`; `RideshareCompletedPerson:482`; `rideshareRatingTags:543`; `RideshareRatingCard:571`; `RideshareCompletedActionGrid:711`; `CompletedActionCard:759`; `RideshareLostItemRow:798`; `RideshareCompletedPayment:834`; `CompletedInlineMessage:867`; `RideshareCompletedDecisionBar:884` |
| [RidesRequestsChatScreens.kt](../android/app/src/main/java/com/yuldash/app/RidesRequestsChatScreens.kt) | `RidesScreen:276`; `SegmentedTabs:495`; `MyTripCard:523`; `NearbyMoreCard:596`; `NearbyRideCard:621`; `NearbySkeletonCard:724`; `NearbyEmptyCard:742`; `InviteDriverCallout:803`; `RideCard:871`; `VerifiedBadge:1022`; `TrustChip:1072`; `DriverTrustBadges:1086`; `CompactTrustLine:1113`; `BoostBadge:1128`; `PrefChip:1144`; `PrefChip:1156`; `RidePrefChips:1168`; `PrefToggleRow:1187`; `PrefToggleRow:1200`; `NearbyFilterChip:1213`; `Metric:1238`; `MyRequestsScreen:1256`; `CreateRequestButton:1379`; `RequestSummaryCard:1393`; `EditRequestDialog:1473`; `MatchingRidesSection:1521`; `FullRideCard:1566`; `ChatScreen:1692`; `ChatContent:1915`; `ChatFeedBubble:2019`; `ChatFlagPlate:2067`; `YuldashOfficialBadge:2107`; `ChatSafetyDisclaimer:2122`; `RequestsFeedScreen:2161`; `RequestOfferSheet:2289`; `RequestsFeedContent:2374`; `RequestFeedFilterChip:2436`; `PremiumPassengerRequestCard:2459`; `RequestMetricCell:2561`; `RequestPreferenceChip:2572`; `requestCategoryText:2585`; `ResponsesScreen:2610`; `ResponsesContent:2712`; `ResponseRequestSummary:2805`; `PremiumResponseCard:2857`; `ResponseMoneyHero:2926`; `ResponseMoneyCell:2951`; `ResponseDriverTrust:2968`; `ResponseActions:3025`; `ResponseActionLabel:3081`; `OnlineBadge:3094`; `WomanDriverBadge:3105`; `SmallAvatar:3122`; `ListedEmpty:3134`; `ListedError:3147`; `ChatCard:3159`; `ChatComposer:3219`; `QuickReplyChip:3372`; `ChatEmptyState:3387`; `EmojiPicker:3450`; `VoiceMessageCard:3488` |
| [RoadsideHelp.kt](../android/app/src/main/java/com/yuldash/app/RoadsideHelp.kt) | `RoadsideHelpAction:40`; `CourierSosButton:174` |
| [SavedPlacesScreen.kt](../android/app/src/main/java/com/yuldash/app/SavedPlacesScreen.kt) | `placeKindLabel:97`; `QuickPlacesBlock:111`; `QuickPlaceRow:266`; `SaveAsPlaceChips:322`; `SaveAsChip:369`; `SavedPlacesScreen:393`; `SavedPlaceRow:527`; `SavePlaceKindDialog:551`; `DialogKindRow:589`; `SwipeToDeleteRow:616` |
| [ScheduledOrdersScreen.kt](../android/app/src/main/java/com/yuldash/app/ScheduledOrdersScreen.kt) | `ScheduledOrdersScreen:67`; `ScheduledOrderCard:319`; `ActivatedOrderCard:372`; `RouteLine:402`; `activatedScheduledTitle:411`; `CountdownChip:418` |
| [SeasonalBanner.kt](../android/app/src/main/java/com/yuldash/app/SeasonalBanner.kt) | `SeasonalBanner:45` |
| [SecondaryScreens.kt](../android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt) | `NotificationsScreen:279`; `notifTimeAgo:496`; `NotificationRow:511`; `RouteWatchesScreen:551`; `RouteWatchRow:681`; `SafetyScreen:707`; `SettingsScreen:781`; `ThemePickerDialog:930`; `fontScaleLabel:964`; `FontScalePickerDialog:975`; `RulesScreen:1021`; `PaymentInfoScreen:1062`; `PricingInfoScreen:1140`; `PricingBlock:1237`; `PricingWhereRow:1251`; `PaymentStepRow:1263`; `FiltersScreen:1306`; `PersonRow:1336`; `DocImage:1350`; `AdminDriversScreen:1387`; `AdminDriversContent:1433`; `AutoCheckRow:1493`; `AdminReportsScreen:1528`; `reportCategoryLabel:1574`; `AdminReportsContent:1587`; `AdminCabinetScreen:1671`; `AdminPaymentRequestsScreen:1721`; `AdminRequestScreen:1914`; `AdminResponsesScreen:1967`; `BlocklistScreen:2041`; `BlocklistContent:2083`; `ReportScreen:2132`; `ReportCategoryDialog:2212`; `ReportListContent:2298`; `HelpScreen:2336`; `ExpandableHelpRow:2472` |
| [SecureWindow.kt](../android/app/src/main/java/com/yuldash/app/SecureWindow.kt) | `SecureWindow:30` |
| [SosVerifyScreens.kt](../android/app/src/main/java/com/yuldash/app/SosVerifyScreens.kt) | `SosScreen:283`; `SosContent:480`; `SosDirectCallChip:725`; `VerifyDriverScreen:762`; `VerifyDriverContent:905`; `StatusBanner:1032`; `DriverReasonBanner:1048`; `SubmitErrorBanner:1121`; `UploadTile:1138`; `StepDot:1167`; `DocumentRow:1176` |
| [SupportBoostScreen.kt](../android/app/src/main/java/com/yuldash/app/SupportBoostScreen.kt) | `SupportScreen:263`; `SupportContent:344`; `BoostScreen:465`; `BoostContent:637`; `BoostRideRow:788`; `BoostPlanCard:816`; `BoostResultCard:851`; `BoostResultContent:869`; `StateMessage:927` |
| [SupportChatScreen.kt](../android/app/src/main/java/com/yuldash/app/SupportChatScreen.kt) | `SupportTicketsScreen:79`; `SupportTicketsList:125`; `SupportTicketRow:200`; `SupportStatusChip:245`; `NewTicketForm:260`; `SupportTicketScreen:321` |
| [TaxiCompletedScreen.kt](../android/app/src/main/java/com/yuldash/app/TaxiCompletedScreen.kt) | `TaxiPassengerCompletedScreen:75`; `TaxiCompletedTopBar:218`; `TaxiCompletedPerson:245`; `TaxiRatingPicker:299`; `TaxiRatingThanks:377`; `TaxiCompletedMoney:395`; `TaxiCompletedActionRow:416`; `positiveTaxiRatingTags:457`; `negativeTaxiRatingTags:464` |
| [TaxiDocsScreens.kt](../android/app/src/main/java/com/yuldash/app/TaxiDocsScreens.kt) | `TaxiDocumentsScreen:142`; `TaxiDocsSkeleton:335`; `SmallSectionLabel:344`; `FootnoteRow:358`; `InlineNotice:375`; `TaxiPermitRegistryBlock:425`; `TaxiCarPhotoBlock:524`; `PermitWarning:577`; `PermitWarningLine:601`; `PermitStep:611`; `TaxiDocsHeader:628`; `TaxiDocRow:695`; `DocStatusPill:773`; `TaxiDocDateField:788`; `PretripCheckScreen:876`; `PretripSkeleton:1016`; `PretripIntroCard:1026`; `PretripProgress:1060`; `PretripHint:1096`; `PretripCheckItem:1118`; `PretripDoneCard:1161` |
| [TaxiDriverCancelledScreen.kt](../android/app/src/main/java/com/yuldash/app/TaxiDriverCancelledScreen.kt) | `TaxiDriverCancelledScreen:78`; `TaxiDriverCancelledHeader:282`; `TaxiDriverCancelledRoute:326`; `TaxiDriverCancelledFacts:344`; `TaxiDriverCancelledFactRow:390`; `TaxiDriverCancelledFact:408`; `TaxiDriverCancelledMoney:440`; `TaxiDriverCancelledProblemRow:514`; `TaxiDriverCancelledDialogChoice:551` |
| [TaxiDriverCompletedScreen.kt](../android/app/src/main/java/com/yuldash/app/TaxiDriverCompletedScreen.kt) | `TaxiDriverCompletedScreen:73`; `TaxiDriverIncomeHero:257`; `TaxiDriverMoneySummary:299`; `taxiDriverPayMethodShortLabel:333`; `TaxiDriverMoneyCell:340`; `TaxiDriverMoneyDivider:359`; `TaxiDriverPassengerRating:364`; `TaxiDriverPassengerIdentity:507`; `taxiDriverRatingTags:556`; `TaxiDriverCompletedActionRow:571` |
| [TaxiDriverNavigationScreen.kt](../android/app/src/main/java/com/yuldash/app/TaxiDriverNavigationScreen.kt) | `TaxiDriverOnboardNavigator:53`; `DriverNavigatorHeader:189`; `DriverNavigatorPassengerCard:215`; `DriverNavigatorAction:280`; `DriverNavigatorDestination:302`; `DriverNavigatorPayment:327`; `DriverNavigatorCircleButton:342`; `DriverNavigatorSosButton:364` |
| [TaxiLocationMark.kt](../android/app/src/main/java/com/yuldash/app/TaxiLocationMark.kt) | `TaxiCarGlyph:219`; `TaxiPickupGlyph:298`; `TaxiPickupMapMarker:339`; `TaxiLocateGlyph:373` |
| [TaxiOnboardingScreen.kt](../android/app/src/main/java/com/yuldash/app/TaxiOnboardingScreen.kt) | `TaxiOnboardingScreen:152`; `TaxiApplyFormContent:218`; `TaxiClassChip:709`; `TaxiChoiceChip:747`; `TaxiRuleRow:783`; `TaxiPendingContent:798`; `TaxiApprovedContent:817`; `TaxiRejectedContent:837`; `TaxiStatusScaffold:859`; `classMissingText:930`; `TaxiMyClassesCard:954`; `TaxiClassRow:1035`; `TaxiSummaryRow:1103` |
| [TaxiReceiptScreen.kt](../android/app/src/main/java/com/yuldash/app/TaxiReceiptScreen.kt) | `TaxiReceiptScreen:101`; `TaxiReceiptTopBar:177`; `TaxiReceiptCard:206`; `TaxiReceiptDocument:214`; `TaxiReceiptCompactRating:302`; `TaxiReceiptHeader:355`; `TaxiReceiptLine:420`; `TaxiReceiptDriverLine:444`; `TaxiReceiptHairline:477`; `TaxiReceiptLine:485`; `TaxiReceiptBreakdown:512`; `TaxiReceiptNote:597`; `TaxiAfterRideActions:617`; `TaxiReceiptActionRow:873`; `TaxiActionHead:933`; `TaxiReceiptSkeleton:958`; `TaxiReceiptPendingCard:968` |
| [TaxiSearchingScreen.kt](../android/app/src/main/java/com/yuldash/app/TaxiSearchingScreen.kt) | `TaxiSearchingScreen:64`; `rememberSearchNearbyState:247`; `SearchNearbyPill:270`; `searchStatusText:331`; `SearchOrderSummary:347`; `SearchDetailsCard:417`; `SearchActionButton:456`; `SearchRadar:482` |
| [TaxiSheet.kt](../android/app/src/main/java/com/yuldash/app/TaxiSheet.kt) | `TaxiSheetScaffold:67`; `TaxiSheetHandle:199` |
| [TaxiTripScreen.kt](../android/app/src/main/java/com/yuldash/app/TaxiTripScreen.kt) | `TaxiTripScreen:124`; `TripCompactHeader:394`; `TripCompactAction:484`; `TripCompactPaymentSafety:517`; `TripMapStatusPill:566`; `TripStatusHeader:608`; `TripOnboardRouteSummary:665`; `TripDriverCard:696`; `TripDriverAvatar:812`; `TripLabeledAction:836`; `TripPriceRow:873`; `TripImComingButton:927`; `TripRouteBlock:959`; `TripDestinationPending:1048`; `TripFinishedPrompt:1133`; `TripCancelButton:1221`; `TripPaidCancelDialog:1241`; `TripSosButton:1278`; `TripSafetySheet:1302`; `ActiveTripBar:1365`; `tripsPhrase:1436`; `TripLightSwitch:1454`; `TripMinimizeButton:1486`; `TripRecenterButton:1508` |
| [TripLiveLink.kt](../android/app/src/main/java/com/yuldash/app/TripLiveLink.kt) | `LiveLinkCard:42` |
| [TripReceiptScreen.kt](../android/app/src/main/java/com/yuldash/app/TripReceiptScreen.kt) | `TripReceiptScreen:73`; `ReceiptCard:110`; `ReceiptRow:320`; `ReceiptDriverRow:333`; `ReceiptDivider:350`; `ReceiptSkeleton:357`; `ReceiptPendingCard:367` |
| [TrustScreens.kt](../android/app/src/main/java/com/yuldash/app/TrustScreens.kt) | `localized:72`; `trustLadderTitles:76`; `TrustScreen:96`; `TrustLevelCard:190`; `TrustLadder:228`; `BenefitRow:264`; `TrustNextCard:274`; `TrustInviteCallout:300`; `InvitesScreen:324`; `InviteCodeRow:537`; `ConsentsScreen:574`; `ConsentRow:682` |
| [Theme.kt](../android/app/src/main/java/com/yuldash/app/ui/theme/Theme.kt) | `YuldashTheme:63` |
| [UiKit.kt](../android/app/src/main/java/com/yuldash/app/UiKit.kt) | `RepeatWhileVisible:122`; `AppButton:147`; `AppButtonContent:190`; `AppCard:208`; `SectionHeader:227`; `SkeletonBox:240`; `SkeletonCard:260`; `AppLoading:275`; `AppErrorState:288`; `AppNoticeCard:326`; `AppStaleStrip:364`; `AppPullRefresh:437`; `AppEmptyState:452`; `AppStateContainer:468`; `ConnectionBanner:510` |
| [UpdateBanner.kt](../android/app/src/main/java/com/yuldash/app/UpdateBanner.kt) | `UpdateBanner:83` |
| [WalletScreen.kt](../android/app/src/main/java/com/yuldash/app/WalletScreen.kt) | `WalletScreen:67`; `WalletBalanceCard:197`; `WalletLedgerRow:250`; `PayoutSoonCard:289`; `PayoutCard:325`; `PayoutCardDialog:522`; `ledgerKindLabel:593` |
| [WeatherWarningCard.kt](../android/app/src/main/java/com/yuldash/app/WeatherWarningCard.kt) | `WeatherWarningCard:68`; `WeatherWarningRow:137`; `rememberRouteWeather:163` |
| [WinterProtocol.kt](../android/app/src/main/java/com/yuldash/app/WinterProtocol.kt) | `WinterArrivalWatcher:47`; `WinterArrivalDialog:80` |
| [YuldashApp.kt](../android/app/src/main/java/com/yuldash/app/YuldashApp.kt) | `YuldashApp:319`; `OnboardingScreen:1628`; `OnboardingContent:1651`; `OnboardingLangToggle:1800`; `OnboardingLangChip:1814`; `onbAppear:1830`; `OnboardingHeroCard:1837`; `OnboardingFeatureCard:1952`; `OnboardingRoleChooser:1971`; `OnboardingSimpleModeCard:1997`; `OnboardingRoleCard:2059`; `OnboardingTrustStrip:2089`; `OnboardingMiniTrust:2100`; `OnboardingIconBubble:2109`; `OnboardingSafetyNote:2134`; `OnboardingDots:2145`; `ScreenTopBar:2222`; `HomeScreen:2239`; `HomeShell:2423`; `YuldashBottomBar:2476`; `YuldashBottomItem:2529` |

## Отпечатки снимка исходников

Ни один отпечаток ниже не означает, что весь файл разобран. Он позволяет заметить изменение исходника и необходимость повторной проверки. Подтверждённые замечания относятся к указанным диапазонам и этим отпечаткам исходного состояния.

| Путь | SHA256 (LF) | Физические строки |
|---|---|---|
| `android/app/src/main/java/com/yuldash/app/AccessibilityScreens.kt` | `669ce8af81574590660a23600d9add5f82d8cb9a0839ba8049cc7e34e4931684` | 2125 |
| `android/app/src/main/java/com/yuldash/app/AdminAdsScreen.kt` | `07507883605359e9e815ed8280c4c593cc71c8cfd5962b76a7abacc9c68d543a` | 383 |
| `android/app/src/main/java/com/yuldash/app/AdminCourierScreen.kt` | `4c590c4f83ad296203ecb10ed42eeef74d005d6e6e7e195b9287b99cff3efd0a` | 332 |
| `android/app/src/main/java/com/yuldash/app/AdminIncidentsScreen.kt` | `2c9fea8e9269be2a6ba7b1b798369dd2c4e2d1d6d9b6972f709d9d13c383e093` | 651 |
| `android/app/src/main/java/com/yuldash/app/AdminModerationScreen.kt` | `cc2323bb0dfdefd4f695644e97691754a2c115dfad2fb776da4a3c7e546146be` | 306 |
| `android/app/src/main/java/com/yuldash/app/AdminParcelsScreen.kt` | `de713b164a35f96970d118317957c8e1e4ee5d20621e9032d1b60af2ec5987f2` | 395 |
| `android/app/src/main/java/com/yuldash/app/AdminPartnersScreen.kt` | `780d648bdb970db3ea97208ef5c4bd48f352da7fd72ac3984c9311dcf2ed390b` | 273 |
| `android/app/src/main/java/com/yuldash/app/AdminPromoScreen.kt` | `a84359f7d5c3466c97c51d9850e0c3fb1c0fc038b58eae35c4670388c6089ca5` | 401 |
| `android/app/src/main/java/com/yuldash/app/AdminRatingsScreen.kt` | `b6364ac0899cea7c7b07687c5b54b05838023ece7ac64000fa81f48d43745229` | 276 |
| `android/app/src/main/java/com/yuldash/app/AdminReviewsScreen.kt` | `52179ccc0a18ff9c5a655b01ed85da0d1e6b823d1c9715bdefb49072534a5c5a` | 175 |
| `android/app/src/main/java/com/yuldash/app/AdminSosScreen.kt` | `02c0f9c1c2ab67cd4cbfb3ed05ac3d313067e2e8fcf8a3f960e5517fb6805162` | 450 |
| `android/app/src/main/java/com/yuldash/app/AdminSupportScreen.kt` | `ba9b6749e587ca2783cf0ff1ff554d89445ff8a1fdb2836727724535550e1163` | 325 |
| `android/app/src/main/java/com/yuldash/app/AdminTaxiPulseScreen.kt` | `8a3190e280093c2738a45e184ec572b342c61651089bf4ff9062c1eae25436b0` | 354 |
| `android/app/src/main/java/com/yuldash/app/AdminTaxiScreen.kt` | `8162cfadd898dbcef725fe0f5be2f30534610f005553c4b54e0ad8f63dfa4349` | 496 |
| `android/app/src/main/java/com/yuldash/app/AdminTextFlagsScreen.kt` | `6b7b46f04f82118f6e1dd4b35919dfceeb2ceea6ab38b430ddb1b10a29f4e512` | 193 |
| `android/app/src/main/java/com/yuldash/app/AdminWaitlistScreen.kt` | `dfa3c7494ee1ee5f874f574cba17d3992a24b08648c472436779aa197da6bce5` | 261 |
| `android/app/src/main/java/com/yuldash/app/AppNavAdmin.kt` | `362a6591b8ffca1128fac98412bce7f8bfc3e606f6f8c966c0095c98359a3843` | 73 |
| `android/app/src/main/java/com/yuldash/app/AppNavHome.kt` | `5db45864b267437310c2fb448136b55eef1e7eb423fb90c1eed31ccf65ca6cd4` | 217 |
| `android/app/src/main/java/com/yuldash/app/AppNavOrders.kt` | `e22015d9e2dd1e7ad986db7fefb18a1d7c65ee9eed2fb62f715a08e3f722a229` | 105 |
| `android/app/src/main/java/com/yuldash/app/AppReviewScreen.kt` | `9d5028df425d1c462b505d8f3792dfd245fac10e0bcff1b4307becdb46cc1f57` | 270 |
| `android/app/src/main/java/com/yuldash/app/AppText.kt` | `91cbe8ee02bce3a921d0f71a558c24f47e7144d0a8a29ab822e9b6fc42a00306` | 22 |
| `android/app/src/main/java/com/yuldash/app/BargainUi.kt` | `16e0d2dbf4b46d35be47e90c02f8b19e9b6ccf4da58beb792cc32bbbf38c9d12` | 470 |
| `android/app/src/main/java/com/yuldash/app/BookingActiveTripScreen.kt` | `7d166373444d9d61549b44b32fc4056bcd54f19a64ce8bf468868fb80614d294` | 3815 |
| `android/app/src/main/java/com/yuldash/app/CanonTokens.kt` | `91cd5231e9948d9fc4ba6ec013e010562d0ba9ca07f7c5d8d856ff8a11416687` | 497 |
| `android/app/src/main/java/com/yuldash/app/CarPhotoScreen.kt` | `3f71b189eb142d90603fba19b69b1b27f9d7f054b3fb5bef9f6b2753f183a1e2` | 643 |
| `android/app/src/main/java/com/yuldash/app/ClinicRidesScreen.kt` | `cff844bb549f7128d1ae229abfdfc6259f7146d5967493a4272d8a290bb94738` | 307 |
| `android/app/src/main/java/com/yuldash/app/CouponsScreen.kt` | `125afa67661c8ff90bd770f566f803642980fd9bce8aa046c6c3037cfde69646` | 728 |
| `android/app/src/main/java/com/yuldash/app/CourierEarningsScreen.kt` | `b4ccb2119192fb7e99d691a404cde2d98be5102d1c61b7b1cc6bc6817f15ba82` | 463 |
| `android/app/src/main/java/com/yuldash/app/CourierOnboardingScreen.kt` | `129f4273580accddbbbb014a8685828a1ed0296bf97f6c9c63cf7a9d78cb7e82` | 606 |
| `android/app/src/main/java/com/yuldash/app/CourierScreen.kt` | `74b3071e600c870b79fd18060e3df93d5b204ec59c71db2537dc8bf559b10f13` | 2546 |
| `android/app/src/main/java/com/yuldash/app/CourierTroubleDialog.kt` | `cd7e506e0ee5d24fcc42687096ec86619bd7cea0ac3613c2d7bced6dcaa559e4` | 289 |
| `android/app/src/main/java/com/yuldash/app/CreateRideScreen.kt` | `e61f945748d5e84ee2b059cc8a88023ba9e14fdc4a47fefc37c213c61609f98e` | 1158 |
| `android/app/src/main/java/com/yuldash/app/DirectionGlyph.kt` | `cfbb58d0b1874cf9f4105952f88f2ad6a4ab82c17bc9f6c7454269898ae86222` | 265 |
| `android/app/src/main/java/com/yuldash/app/DriverEarningsScreen.kt` | `750b5a2dea9dfa0c8fedd3d0ad77ce1199eb37f01c9033b0d472778236f5f629` | 250 |
| `android/app/src/main/java/com/yuldash/app/DriverProfileScreen.kt` | `35b51ae0fe76d9f4d5c5ab391a542ed90b2c857aaa1bd30202a4093d04309f6f` | 277 |
| `android/app/src/main/java/com/yuldash/app/DriverResponsesScreen.kt` | `515ae6455deed9a5802d30a22dce2a5c98d2910314112febbc2601bd44f0ba14` | 564 |
| `android/app/src/main/java/com/yuldash/app/DriverTaxiRidesScreen.kt` | `f0342ade1e00250d1d61be3a676b24404e22e7c8f33c72efc9fe41e111cc0c03` | 337 |
| `android/app/src/main/java/com/yuldash/app/DriverTipsCards.kt` | `22b1832390c50e5d73dfc32d8c2768c84883f3109b4d71dad27c1dc09b3f84c2` | 183 |
| `android/app/src/main/java/com/yuldash/app/FairnessScreens.kt` | `7216503fb9f490a07be2716655dea24198d155b1e66dff19ed74cf9e7e9d4088` | 1576 |
| `android/app/src/main/java/com/yuldash/app/ForceUpdateScreen.kt` | `2a1683611d9931bc2f5de8fe0f645b990fe6ef3aa2c1cdc74591698c73da581d` | 128 |
| `android/app/src/main/java/com/yuldash/app/GeoUi.kt` | `5d923fc721bd0a10e4735634d3eb38c9639a80af42869cf21baabc17e2f90b0c` | 571 |
| `android/app/src/main/java/com/yuldash/app/IncomeCalculatorScreen.kt` | `55594cd4c212d58424ffb9287de3e279d30ce1b3c87fddf30fe1eab298fae5c1` | 213 |
| `android/app/src/main/java/com/yuldash/app/InstantChatScreen.kt` | `b661657be05c32ea1194505d6cf877f6606ac93f19c1ee3a7857cde543447687` | 225 |
| `android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt` | `7c5d3fc91554f91728c969476360d500ee442b1d9fea5969f0c658f1c4ab6b81` | 7913 |
| `android/app/src/main/java/com/yuldash/app/IntroHero.kt` | `eaa29e92a7041a4566b7dd5e9607c47eda67ffb3b5d4aea876f3df1c7a1b493c` | 83 |
| `android/app/src/main/java/com/yuldash/app/IntroScreen.kt` | `d0b2ef814118e1d5cf6dd87686b2a85adb35c9953e73d3692a3a9e775b0e7758` | 379 |
| `android/app/src/main/java/com/yuldash/app/LoginScreen.kt` | `50c30783dca3c33d79cb44908f79f565ed8e6feba44d30e13714724288c4f780` | 1302 |
| `android/app/src/main/java/com/yuldash/app/MainActivity.kt` | `79c7865bb0a17ae2d1d5ac1ef6268aadd8cebedeb422c99e887d7444d70ebabe` | 1007 |
| `android/app/src/main/java/com/yuldash/app/MapCluster.kt` | `51150c491ea518f75cc6897ab618995d0706f3fff6f34ed40b14715b5b547de0` | 68 |
| `android/app/src/main/java/com/yuldash/app/MapScreen.kt` | `b7d83366da56899b8454e85535d9e8ca9bdec9eb7d7b360cd335b42e89d28bc7` | 2292 |
| `android/app/src/main/java/com/yuldash/app/MobilityUi.kt` | `96351b19abff2bf679b1a6276fb08f2142a503645dd0224411af79eccb74decd` | 874 |
| `android/app/src/main/java/com/yuldash/app/ModeSwitchHome.kt` | `a35c6952a5efb989286c36fcc741de7d493486f00c1ecdb8c82277d03786e442` | 469 |
| `android/app/src/main/java/com/yuldash/app/MyDataScreen.kt` | `86105cbaedc9b07e12bd44adeb9bc72a38466014d5532bb8f00b36079d27de47` | 633 |
| `android/app/src/main/java/com/yuldash/app/MyStatsScreen.kt` | `7d92ca2f615e05e8fdd1e4400c0e6780f521f76761a2414c67a3fc9f24d8ef99` | 569 |
| `android/app/src/main/java/com/yuldash/app/MyTaxiTripsScreen.kt` | `57c2f1491fab9e375563930d9e17c20c9d7da6d13309306d1dcc5d9a5fdb4e35` | 228 |
| `android/app/src/main/java/com/yuldash/app/ParcelChatScreen.kt` | `389547f1b13c367d46434c033b57cba9108a47f72fb58310c829c7fa75dc5146` | 255 |
| `android/app/src/main/java/com/yuldash/app/ParcelReceiptDialog.kt` | `d0b0f05d6c99dfe33e920bc2719986027aaef6184736840d79800fce1e47640a` | 166 |
| `android/app/src/main/java/com/yuldash/app/ParcelsScreen.kt` | `4568a54e3b1efc52b598501e18118251b5a449f857ad30a7788e2f0bb154ea52` | 3044 |
| `android/app/src/main/java/com/yuldash/app/PartnerCabinetScreen.kt` | `9a6bc1f9b6b97264b92107e096071f87ff641f086cfcef9e38bdb8b6219d9f46` | 818 |
| `android/app/src/main/java/com/yuldash/app/PaymentMethodsScreen.kt` | `291ad9f1d3c57dfdeb35d866e6ddf934e0021993574e5c8039fe7440ec42e409` | 458 |
| `android/app/src/main/java/com/yuldash/app/PayOnlineCard.kt` | `91c056f9197ffbe0bc0e363d845027e446b79f211b8f1a7550eabba38b633c09` | 257 |
| `android/app/src/main/java/com/yuldash/app/Permissions.kt` | `255573dc8f89cf65e6aa6d544a94bc5f071e74426417ef1d1239fbfc19dd5785` | 197 |
| `android/app/src/main/java/com/yuldash/app/PriceSpeech.kt` | `1d784b1aaee551d63fc640d3171b544cf707c5083425c6d094dc4ffc33687c15` | 99 |
| `android/app/src/main/java/com/yuldash/app/ProfileScreen.kt` | `f2963adae108d2b0865ae7a7255a2c8f933a6aaaa324a820cb235dea82e95e4d` | 4819 |
| `android/app/src/main/java/com/yuldash/app/PromoCodeScreen.kt` | `0cb129fe456d1e203d844f92b8041a0fcaf87af8b1f4c1c31d538cfd5b142324` | 413 |
| `android/app/src/main/java/com/yuldash/app/RideshareCompletedScreen.kt` | `f9dfb99316abcfd3671fbcc280af627bf99b2684842d477639d8635a7b4002f9` | 938 |
| `android/app/src/main/java/com/yuldash/app/RidesRequestsChatScreens.kt` | `686b73b7f9cf9dc0eea15ddbbdd808fe8311aa7397f976fed3e44574cfc0717c` | 3535 |
| `android/app/src/main/java/com/yuldash/app/RoadsideHelp.kt` | `0465e642ebdf03fbfa2665529a7aab1ece1a331f0cb7152fc1b3f51cfbdac7f8` | 182 |
| `android/app/src/main/java/com/yuldash/app/SavedPlacesScreen.kt` | `f58a9a0066a1fb2699f2f3868efabd0fb55b3b0aacd2b865139ccfb133e7fa20` | 679 |
| `android/app/src/main/java/com/yuldash/app/ScheduledOrdersScreen.kt` | `152bf51a2c5d7c3c69d59d54f355d692a76b88b16f218748ebe1453572161a7e` | 468 |
| `android/app/src/main/java/com/yuldash/app/SeasonalBanner.kt` | `4cba35082b7304ad9833c57606dd62dce787d711d0c8df6e49a583ccbc85c027` | 136 |
| `android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt` | `335684e9e57c5aa625c089f3e708de55bce02c95d5c8d59501ba319ccac63b1f` | 2490 |
| `android/app/src/main/java/com/yuldash/app/SecureWindow.kt` | `2e5a913bcd985780ea71e1dc2a00db6d1a86e53a157fbffc664f1a78a879c49c` | 61 |
| `android/app/src/main/java/com/yuldash/app/SosVerifyScreens.kt` | `a0f6897f0b07adb786dae84107063f6865e0ee23dac8e10bf14f55af7df0e7b0` | 1188 |
| `android/app/src/main/java/com/yuldash/app/SupportBoostScreen.kt` | `5723e610ec8a0811c4d5d039a91c461afffb131269f77bc51c09c23577ee255e` | 951 |
| `android/app/src/main/java/com/yuldash/app/SupportChatScreen.kt` | `d62261334b13742dd65c6a4c8277301c1831546d06a711618d9b626fa47a9090` | 459 |
| `android/app/src/main/java/com/yuldash/app/TaxiCompletedScreen.kt` | `a27419f0f7ff46eb624b96744c6cdbf06ac0dc4dd37513d222af64fd2bbb59fa` | 468 |
| `android/app/src/main/java/com/yuldash/app/TaxiDocsScreens.kt` | `fad58caa6f1d69849f7901a47743cd203bd8557e63edbaad594e0aaf8f909a67` | 1200 |
| `android/app/src/main/java/com/yuldash/app/TaxiDriverCancelledScreen.kt` | `72d0decd4c04a2b9155da5a8746547550c69c1a42aeed374b1ee780c0c5a2837` | 596 |
| `android/app/src/main/java/com/yuldash/app/TaxiDriverCompletedScreen.kt` | `bc37fe83df33f13bfd1a2b75561ff806863f82a7da71fd07221c045629765a85` | 607 |
| `android/app/src/main/java/com/yuldash/app/TaxiDriverNavigationScreen.kt` | `4ba7a8c95c408705aff5ae99c3175e6a25fb8e1be46efc7b2d3b3c486041ee81` | 377 |
| `android/app/src/main/java/com/yuldash/app/TaxiLocationMark.kt` | `fed902f9c5095dc1c26228177a2b3e00db83a0800cf345f35e3d76fb96b777d7` | 381 |
| `android/app/src/main/java/com/yuldash/app/TaxiOnboardingScreen.kt` | `bb578b5c6ba1a8b4c21f3f7f4e8279e5ebf52519b13638d6ca3b490f019d488f` | 1108 |
| `android/app/src/main/java/com/yuldash/app/TaxiReceiptScreen.kt` | `a15d8315c47b1679642d21fc09fea7156d69528da82f273cddab77d070f181ff` | 989 |
| `android/app/src/main/java/com/yuldash/app/TaxiSearchingScreen.kt` | `a12679bbf304d383e75484e66dfd9dd76df34c4bf44b6c5390f3263f91aeae77` | 501 |
| `android/app/src/main/java/com/yuldash/app/TaxiSheet.kt` | `bb1a7b19b242143f40b93a6c6d333967a62fc37d0378dd8304a599b01afa43d2` | 216 |
| `android/app/src/main/java/com/yuldash/app/TaxiTripScreen.kt` | `6c1bd07caa5e496b339c6f547b920b8fe15051c13726aabaf771d4804aaa2c8c` | 1531 |
| `android/app/src/main/java/com/yuldash/app/TripLiveLink.kt` | `dc41d95e270f1858558a863e35bf7f4f1b0e829e549b3feba5e5fe830f4e2e6e` | 97 |
| `android/app/src/main/java/com/yuldash/app/TripReceiptScreen.kt` | `ac57e30ecceb1a4d0a211d562a5dba2b11bfad224d7afd2cf1dabf2a9b0e4a5e` | 385 |
| `android/app/src/main/java/com/yuldash/app/TrustScreens.kt` | `ba14a3f920bd5f052825fd98231b54d439282018ccfa0a4d86aba3e9c4f93d1c` | 769 |
| `android/app/src/main/java/com/yuldash/app/ui/theme/Theme.kt` | `1eabf99357b62d6dcfcd2e68fc03fde51291b051db38b28fe3285060ab2790b3` | 87 |
| `android/app/src/main/java/com/yuldash/app/UiKit.kt` | `359bb425b3e1bd9f1ed7cd35bcd06a1d60036327c233f27ae1985ec0a0c278c4` | 574 |
| `android/app/src/main/java/com/yuldash/app/UpdateBanner.kt` | `dd6d69e23dad74461f0fcb3a9a4e3c392a18e16459335db5ecfafb7f6dcafdc7` | 199 |
| `android/app/src/main/java/com/yuldash/app/WalletScreen.kt` | `9126f689e04cc8d050e8150688e67b4c45ad2d4b986b39dbeb48db54868e3aeb` | 611 |
| `android/app/src/main/java/com/yuldash/app/WeatherWarningCard.kt` | `d186432cac1a94f83b0d7b72076299643f0bf72de6410a597520aad31d9a3df6` | 184 |
| `android/app/src/main/java/com/yuldash/app/WinterProtocol.kt` | `51833b5184c48d0221d42f762504a0103210dec1a13740746171d6b903ef9bec` | 118 |
| `android/app/src/main/java/com/yuldash/app/YuldashApp.kt` | `60c589bfc8b84e54c822e1a4e9c7f92b3ce76e7d1ff37b566f72a82d4961592a` | 2591 |
| `android/app/src/test/java/com/yuldash/app/ContrastGuardTest.kt` | `3bb865d0fb2d2f9e4e2570233e6e5384f68cda5036826023dd3d1bf8e8a4ae79` | 256 |
| `tools/contrast.py` | `0ec619d1ae5c802a57a5272505a186948461dce761327b5d9e509b344080487b` | 122 |
| `tools/audit_inventory.py` | `bec81a29adabe13511e154cde5cbd6d0ef021c3e3197adc4a3fbbda011956d15` | 169 |

## Воспроизводимый расчёт DESIGN-001

Команда (Python из доступного локального runtime; без Gradle/устройства и без изменения исходников):

```powershell
$env:PYTHONDONTWRITEBYTECODE = '1'
@'
import importlib.util
spec=importlib.util.spec_from_file_location("contrast", "tools/contrast.py")
m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
t=m.parse()
for i,mode in enumerate(("light", "dark")):
    print(mode, "white/green", m.ratio(0xFFFFFFFF,t["CanonGreen2"][i]))
    print(mode, "filled/green", m.ratio(t["CanonBg"][i],t["CanonGreen2"][i]))
'@ | & 'C:/Users/Bayra/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' -
```

Фактический результат независимого расчёта:

| Тема | Белый / CanonGreen2 | CanonOnFilled (=CanonBg) / CanonGreen2 |
|---|---|---|
| Светлая | 6.607087:1 | 6.314426:1 |
| Тёмная | 3.193129:1 | 5.746390:1 |

Дополнительный прочитанный тест: `android/app/src/test/java/com/yuldash/app/CanonSourceGuardTest.kt`, SHA256(LF) `c2eaf602a037b6fe64902bd586e1f655a52c52115ded99fd2a802fde77ebdf42`, строки `310`; приёмка чтением только 1–192, исполнения не было.

Дополнительный расчёт DESIGN-001: white/CanonRed, светлая `5.357804:1`, тёмная `3.309651:1`. Порог 4.5 — строгое правило проекта; для крупного жирного текста внешняя методика может использовать другой порог, но приёмка проекта явно требует 4.5.

Прочитан целиком `android/app/src/test/java/com/yuldash/app/UiKitButtonTest.kt`; SHA256(LF) `43797c84588fa6fc0b6f5593e38da485ebc9554bfa1dbcc1095339f6a65a3051`; физические строки 138. Проверка исходника теста отличается от его запуска.

## Контрольная точка независимого прохода, 30.09.2026

Первичный перечень сохранён; статические замечания DESIGN-001–004 воспроизводимы по коду и расчётам. Исходники продукта этим агентом не менялись. Полное чтение всех 978 композиций, отображение экранов из 97 UI-файлов, визуальные варианты, геометрия/пиксельный контраст, TalkBack, отказ внешнего приложения и повторная независимая проверка исправлений ещё не выполнены. Ни B09, ни весь дизайн этим проходом не закрыты. Исторические отпечатки не переписывать после исправления: новую приёмку добавлять отдельным разделом с новым diff/отпечатками/результатом.

## DESIGN-REVIEW-R1-20260930 — повторное независимое чтение исправлений

Дата / ответственный: 30.09.2026, независимый `design_audit`. Ветка `audit/full-technical-20260930`, HEAD `6fa0595d88bea4fcd59d4a1e2139c2d128cf6821`, локальные незакоммиченные изменения. Это приёмка конкретного diff, а не всего B09. B01 навигацию отдельно проверяет `auth_independent_review`; здесь её результат не пересчитывается.

Источник diff: `git diff --` для `LoginScreen.kt`, `YuldashApp.kt`, `DriverEarningsScreen.kt`, `ClinicRidesScreen.kt`, `CourierScreen.kt`, `BookingActiveTripScreen.kt`, `SosVerifyScreens.kt`, `RidesRequestsChatScreens.kt`. Дополнительно целиком прочитан `SelectedControlsAccessibilityTest.kt`, независимо разобраны сохранённые RED XML и GREEN JUnit ZIP. Исторические исходные отпечатки и замечания выше сохранены без изменения.

### Доказательства исполнения, которые независимо сверены

- [RED log](../test-results/audit-design-controls-red-20260930.log) и [RED XML](../test-results/audit-design-controls-red-20260930.xml): XML содержит `tests=5, failures=5, errors=0, skipped=0`. Три падения проверяют действительный цвет TextLayoutResult (3.193129 на зелёном, 1.853327 выбранной навигационной подписи), два — отсутствие selected в реальном семантическом дереве. Это подтверждение исходных дефектов, не искусственная мутационная проверка.
- [GREEN log](../test-results/audit-b01-design-green-20260930.log), [JUnit ZIP](../test-results/audit-b01-design-green-20260930-junit.zip), [counts и отпечатки](../test-results/audit-b01-design-green-20260930-counts.json): ZIP разобран отдельно; сумма `6+7+3+3+5+1 = 25` тестов, `0` failures/errors/skipped в шести классах. Из них непосредственно новый дизайн-набор — `5` случаев `SelectedControlsAccessibilityTest`, остальные относятся к соседним API/входным проверкам и существующему сторожу контраста. Они не являются 25 отдельными проверками всего дизайна.
- GREEN log подтверждает `:app:assembleDebug` и `BUILD SUCCESSFUL in 1m 33s`. Сборка — отдельное доказательство компиляции, не визуального качества.
- При первом чтении R1 все `11` SHA256 в counts совпали с файлами рабочей копии при той же нормализации LF. Во время независимого review root начал расширять SelectedControlsAccessibilityTest для найденного ниже пропуска RoleCard; новый тест уже отличается от GREEN-снимка. Поэтому старый GREEN сохраняется как исторический и не выдаётся за проверку нового теста/будущего RoleCard-fix.
- Среда тестов, подтверждённая аннотациями: Robolectric API 34, native graphics, `w411dp-h1200dp`, RU и отдельная BA-проверка выбранной вкладки. Тема явно задана в цветовых тестах. Тесты не используют настоящие Telegram/SMS/БД/MapKit/GPS. Этот независимый агент сам Gradle/adb не запускал: он сверял чужой сохранённый результат с кодом и XML.

### Приёмка каждого компонента DESIGN-001

| Компонент / текущий источник | Фактическое исправление | Независимое принятие и предел |
|---|---|---|
| `LoginScreen.kt::LoginFormContent` 707–722, TG-кнопка | текст, иконка и индикатор теперь `CanonOnFilled` | Принято чтением diff и фактического GetTextLayoutResult-теста RU/dark; loading-текст/озвучивание/BA/device отдельно |
| `LoginScreen.kt::LoginLangChip` 1192–1213 | конечный активный fg `CanonOnFilled` | Принято по исходнику и расчёту конечной пары; его реальные состояния на устройстве/промежуточные кадры не проверены |
| `YuldashApp.kt::OnboardingLangChip` 1834–1847 | активный текст `CanonOnFilled` | Принято чтением и GetTextLayoutResult-тестом активного состояния dark |
| `DriverEarningsScreen.kt::EarnPeriodChip` 153–172 | активные текст/иконка `CanonOnFilled`; min height 48dp | Принято чтением и расчётом пары; фактическая высота именно EarnPeriodChip/переключение/BA не исполнены |
| `ClinicRidesScreen.kt::ClinicChip` 243–277 | имя/город/иконка активной клиники `CanonOnFilled`; alpha .85 для города убрана | Принято по diff и расчёту; реальный запрос, длинные данные и скриншот не проверены |
| `CourierScreen.kt::CourierZoneChip` 847–874 | конечная активная ink `CanonOnFilled` | Принято по исходнику/конечной паре; ход анимации и реальные зоны остаются runtime-критерием |
| `BookingActiveTripScreen.kt::MessageBubble` 3733–3758 | свои голосовая подпись/иконка и текст `CanonOnFilled` | Принято по конкретным ветвям и расчёту; настоящее воспроизведение/чат и все размеры не исполнены |
| `BookingActiveTripScreen.kt::ForeignMediaBubble` 3644–3662 | обе собственные подписи `CanonOnFilled` | Принято по diff и расчёту; URI/переносы в реальном пузыре ещё не исполнены |
| `SosVerifyScreens.kt::SosContent` 529–539 | текст/иконка 112 `CanonOnFilled` | Принято по diff и расчёту; функция находится в `SosContent` внутри SosScreen. Вызов dialer не выполнялся |

Токены не менялись. Повторный расчёт через `tools/contrast.py`, `parse()` и `ratio()`:

| Конечная пара | Светлая | Тёмная | Требование |
|---|---|---|---|
| CanonOnFilled / CanonGreen2 | 6.314426:1 | 5.746390:1 | текст ≥4.5 |
| CanonOnFilled / CanonRed | 5.120480:1 | 5.544077:1 | текст ≥4.5 |
| CanonGreen / CanonSurface | 12.022319:1 | 10.253072:1 | подпись меню ≥4.5 |
| CanonGoldInk / CanonGold | 6.655164:1 | 7.312128:1 | иконка меню ≥3 |

### Приёмка DESIGN-002

| Компонент | Фактический diff | Доказательство / остаток |
|---|---|---|
| OnboardingLangChip | selected=active, Role.RadioButton | Принято по source и semantic-tree тесту выбранного состояния; высота ≥48dp проверена именно у этой композиции |
| LoginLangChip | selected=active, Role.RadioButton | Принято по source; есть прежний onClickLabel смены языка. Изменение selected через фактический click не исполнено |
| EarnPeriodChip | mergeDescendants, selected=active, Role.RadioButton | Принято чтением; реальный семантический узел/смена периода не проверены новым набором |
| ClinicChip | mergeDescendants, selected=selected, Role.RadioButton | Принято чтением; динамический список/смена клиники требуют исполнения |
| YuldashBottomItem | mergeDescendants, selected=selected, Role.Tab | Принято по source и BA semantic-tree: активная «Сәфәрҙәр», неактивная «Карта». Сам тест не нажимает для смены вкладки |
| **OnboardingRoleCard** | **В первом R1 diff исправление отсутствует** | **Не принято:** текущий диапазон 2079–2108 по-прежнему содержит только bounceClick/цвет/декоративную radio-иконку без selected. Пропуск передан root. Новый RED/GREEN с переключением Passenger↔Driver требуется сохранить отдельно |

Итого DESIGN-002 целиком в снимке R1 не принят из-за RoleCard. Запись не отменяет принятие конкретных уже исправленных компонентов. Автор не принимает «selected у одной кнопки» за доказательство всех групп выбора.

### Приёмка DESIGN-003 и DESIGN-004

- DESIGN-003: `YuldashApp.kt::YuldashBottomItem` 2556–2558 — выбранная подпись теперь `CanonGreen`, иконка `CanonGoldInk`, фон золотой pill сохранён. Принято по diff, расчёту обеих тем и тесту реального TextLayoutResult выбранной подписи в light. Сам тест использует непрозрачную CanonSurface, а реальная панель alpha .98; запас конечного контраста значительный, но окончательный растр/иконка на устройстве этим тестом не измерены. Проверки анимации, BA-длины, крупного шрифта и TalkBack остаются открытыми.
- DESIGN-004: обе кнопки (`BookingActiveTripScreen.kt::MessageBubble` 3733–3736 и `RidesRequestsChatScreens.kt::VoiceMessageCard` 3515–3518) теперь имеют `playing ? appText("Остановить", "Туҡтатыу") : appText("Воспроизвести", "Уйнатыу")`. Принято по исходнику: описание соответствует ветви stop/start. [tasks.md](tasks.md) содержит BA-черновик на подтверждение Александру. Новые пять тестов **не воспроизводят голосовое** и не проверяют изменение описания; MediaPlayer, возврат к Play после остановки/окончания, сетевой отказ и TalkBack остаются отдельными runtime-критериями.

### Остаток R1 и следующий точный шаг

1. Root исправляет RoleCard; новый тест должен начинать с настоящего `OnboardingRoleChooser`, утверждать Role.RadioButton/selected, нажимать Driver и Passenger и проверять обе стороны, сохранить RED и GREEN.
2. После изменения YuldashApp/теста добавить новую приёмку с новыми LF-хэшами и результатом целевого прогона. Не переписывать GREEN-снимок 25/25.
3. Root сохраняет кадры исправленного APK RU/BA, light/dark, большого шрифта/малого viewport и нужных selected/error/loading состояний. Старый `audit-start-emulator-20260930.png` предшествует diff; он не является подтверждением этих исправлений и в R1 не использован для их приёмки.
4. Эмулятором по-прежнему владеет root; прямого окна adb этот агент не получил. Подготовленные новые изображения может независимо проверить через view_image. Физический телефон/TalkBack/живые провайдеры этим локальным review не заменены.

Ни весь дизайн, ни B09 не объявлены проверенными. Этот раздел отделяет source-acceptance, принятые сохранённые тесты, компиляцию и остающуюся проверку устройства.

## DESIGN-REVIEW-R2-20260930 — RoleCard и новое доказательство устройства

Дата / ответственный: 30.09.2026, независимый `design_audit`. Ветка/HEAD те же, локальный diff без коммита. Повтор обоснован пропуском OnboardingRoleCard в R1 и изменением этого компонента/его теста. Только этот документ изменён независимым агентом; сборкой и emulator-5580 владеет root.

### DESIGN-002: повторная приёмка OnboardingRoleCard

Источники: фактический `git diff -- android/app/src/main/java/com/yuldash/app/YuldashApp.kt`, `OnboardingRoleChooser` 1992 и `OnboardingRoleCard` 2080–2112; `SelectedControlsAccessibilityTest.kt::roleChooserExposesSelectionAndSwitchesBothWays` 105–119.

Карточка теперь задаёт `semantics(mergeDescendants = true) { this.selected = selected; role = Role.RadioButton }` на родительском Card после bounceClick. Обе роли создаются через этот компонент, поэтому Role/selected относятся к каждой карточке. Тест не подменяет выбранность статическим true: начинает с реального OnboardingRoleChooser, состояние Passenger находится в `remember/mutableStateOf`; callback записывает нажатую роль. Проверяются Passenger selected и Role.RadioButton, Driver unselected → click → selected, Passenger unselected → click → selected, Driver unselected. Для Driver роль отдельным assertion не проверена, но общий путь создания RoleCard подтверждён чтением кода.

- [RED log](../test-results/audit-design-role-red-20260930.log), [RED XML](../test-results/audit-design-role-red-20260930.xml): независимо разобран XML `tests=1, failures=1, errors=0, skipped=0`. Падение — отсутствие Selected у настоящего родителя «Я пассажир»; дерево содержит OnClick/mergeDescendants, но не содержит selected/Role. Это исходный дефект продукта, а не сбой стенда.
- [GREEN log](../test-results/audit-design-role-green-20260930.log), [JUnit ZIP](../test-results/audit-design-role-green-20260930-junit.zip), [counts/хэши](../test-results/audit-design-role-green-20260930-counts.json): независимо разобран один suite с `6` тестами, `0` failures/errors/skipped. Это прежние пять уникальных дизайн-случаев плюс один новый RoleCard-случай. Повтор предыдущих пяти не увеличивает число уникальных проверок.
- Воспроизводимая команда владельца, записанная в [audit-journal.md](audit-journal.md): из android с настроенным JAVA_HOME выполнить `./gradlew.bat :app:testDebugUnitTest --tests com.yuldash.app.SelectedControlsAccessibilityTest :app:assembleDebug --no-daemon`. GREEN log содержит assembleDebug и `BUILD SUCCESSFUL in 1m 2s`; suite time `12.955` с из XML относится только к тестовому классу.
- При первом чтении R2 все `11` нормализованных SHA256 в counts независимо совпали с исходниками. Версия принятого RoleCard: YuldashApp SHA256(LF) `6aa16a35d66f6039a1dd7635d227337dd571b5027b08f3491a395e0f9c041f01`. Версия принятого класса из шести тестов: SelectedControlsAccessibilityTest SHA256(LF) `a2cd3578833ef08a421b703eac3b095f9e541c62335a63e9de9206e40c9922b3`.
- Во время этой приёмки root добавил два новых overflow-теста. Текущий тест уже имеет другой SHA256(LF) `f307c2a497023c405bb111c07940dd5085e6cfb865a6fe0058677fa77f7c92f9`. Этот новый снимок пока не принимается по GREEN 6; результат GREEN 6 сохранён как историческая проверка версии a2cd… .

Вывод R2: пропуск OnboardingRoleCard устранён и принят по исходнику/тесту переключения. Все конкретно перечисленные в DESIGN-002 статические пропуски теперь исправлены; это не означает, что все группы выбора приложения испытаны. RoleCard проверен локально в RU на Robolectric API 34. Прохождение полного онбординга, сохранение роли после его завершения/перезапуска, BA и настоящая речь TalkBack этим тестом не доказаны.

### DESIGN-DEVICE-20260930: фактический объём instrumented-доказательства

Прочитан целиком `android/app/src/androidTest/java/com/yuldash/app/SelectedControlsInstrumentedTest.kt`, SHA256(LF) `99dfb35362e289b2fdc966f1a789126a918f818f83dffa16c0c1f00fda4ec46f`. Дополнительно прочитаны BilingualComposeTest/YuldashViewModelInstrumentedTest для точного учёта результата.

[Device log](../test-results/audit-controls-device-20260930.log), [XML](../test-results/audit-controls-device-20260930.xml), [архив инструментальных доказательств](../test-results/audit-controls-device-20260930-evidence.zip) независимо сверены. XML и XML внутри ZIP совпадают по составу: **2 + 2 + 4 = 8** случаев, failures/errors/skipped `0`; log подтверждает connectedDebugAndroidTest, Pixel_API35(AVD) / Android 15 и `BUILD SUCCESSFUL in 1m 13s`.

- Два SelectedControlsInstrumentedTest собирают компонентный стенд с OnboardingLangChip и YuldashBottomBar. Light/RU и dark/BA: выбранный язык имеет selected/Role.RadioButton и высоту ≥48dp; выбранная Rides-вкладка имеет selected/Role.Tab; реальный click по «Карта» меняет обе selected-стороны; после кадра реальный click по другому языку меняет selected обоих языковых chips. Это исполненная семантика/действия, а не полный экран YuldashApp.
- Два BilingualComposeTest показывают одну тестовую надпись через appText. Они проверяют RU/BA-хелпер, но не все тексты продукта.
- Четыре YuldashViewModelInstrumentedTest создают ViewModel/SavedStateHandle, проверяют defaults/restore/persist/corrupt fallback. Они не завершают Android-процесс и не доказывают настоящее восстановление после его смерти.
- Устройство в журнале владельца: emulator-5580, API 35, 1080×2340, 440dpi, font_scale 2.0, радиоканалы отключены; API подтверждается XML, размер обоих PNG независимо прочитан как 1080×2340 RGBA. DPI/системный font_scale взяты из журнала владельца, этот агент adb не вызывал. Сервер/БД не используются в композиции этого стенда; MapKit, GPS, push, Telegram и SMS не проверяются.
- Первый [device compile failure](../test-results/audit-controls-device-compile-failure-20260930.log) сохранён: тест импортировал недоступное расширение weight, компиляция упала до исполнения (`BUILD FAILED in 26s`). Из исправленного исходника импорт убран; следующий log зелёный. Это проблема нового тестового исходника, не найденный runtime-сбой приложения; исходный сбой не стёрт.
- Для сохранения PNG после очистки external files выполнен повтор только двух SelectedControls. [Capture log](../test-results/audit-controls-device-capture-20260930.log) содержит `OK (2 tests)`, 6.53 с. Это повтор ради артефакта, а не два новых уникальных сценария.

Два кадра просмотрены независимым агентом через view_image(detail=original): [light/RU](../test-results/audit-controls-light-ru-20260930.png), [dark/BA](../test-results/audit-controls-dark-ba-20260930.png). Кадр сохраняется **после** нажатия Map, до смены языка; поэтому выбранная вкладка на нём «Карта». Кадры подтверждают конечные цвета выбранного языка и нижней панели в этом стенде, но не измеряют весь пиксельный контраст. Они также выявили DESIGN-005 ниже; зелёные assertions selected его не проверяют.

Отпечатки бинарных артефактов — SHA256 без нормализации: light/RU PNG `e5824e3c1b6d8440fb24ab8b194211d7315e26a046da4f59c6fe2d484878fdeb`; dark/BA PNG `f1b4f45489adef72f4c182c666689ddfd5c641d44f34150a8a74a9c846ad0b82`. Их нельзя заменить новыми кадрами под старым именем без сохранения истории.

## DESIGN-005 — подписи нижней панели обрезаны при крупном шрифте

Связанный блок B09; экран Home / YuldashBottomBar, выбранная Map-вкладка; обязательные критерии AGENTS §4.5: уважение системного шрифта, длинный текст RU/BA и адаптив. Дата / ответственный: 30.09.2026, независимый design_audit. Это подтверждённое нарушение отображения, не предложение по вкусу.

Воспроизведение: стенд SelectedControlsInstrumentedTest на API 35, системный font_scale 2.0, RU/light или BA/dark, ширина кадра 1080px при настройке владельца 440dpi; нажать «Карта» и сохранить onRoot.captureToImage(). Артефакты/версии стенда и исходника приведены в R2. Ожидание: все названия вкладок читаются целиком по высоте, а длинное название размещается без потери смысла при доступном шрифте. Факт: на обоих новых PNG нижние части **всех пяти** названий отрезаны одинаковой горизонтальной границей. «Поездки»/«Сәфәрҙәр», «Заявка»/«Ғариза» и «Профиль» также не вмещаются целиком в ширину одной строки.

Место: `YuldashApp.kt::YuldashBottomBar` 2509–2515 — Row задаёт фиксированную `.height(78.dp)` и отступы; `YuldashBottomItem` 2566–2573 — пять равных Columns с отступами/spacing; 2580–2586 — pill/иконка; 2606–2612 — Text fontSize=12sp, maxLines=1, Ellipsis без явного style/lineHeight. `ui/theme/Theme.kt` 83–86 не задаёт отдельную Typography; существующий `CanonMicro` в CanonTokens.kt 494 уже содержит 12sp/17sp. Точную долю влияния наследуемого lineHeight отдельно от высотных ограничений я не могу подтвердить без сохранённого TextLayoutResult; сама обрезка подтверждена кадрами.

Влияние: пользователь с увеличенным шрифтом видит отрезанные названия основных разделов и вынужден распознавать их по верхним частям букв/иконке. Наличие корректного selected в дереве доступности не устраняет этот видимый дефект. Семантическое onNodeWithText находит полную строку, даже когда растр её обрезает.

Минимальный кандидат исправления для владельца YuldashApp: заменить фиксированную height на heightIn(min=78.dp), согласовать стиль подписи с существующим CanonMicro, разрешить перенос до двух строк при крупном шрифте, оставить рост высоты по содержимому и область нажатия ≥48dp. Не отключать системное масштабирование и не уменьшать текст ради зелёного теста. Конкретная геометрия должна быть принята по повторному рендеру; только этой записи для приёмки недостаточно.

Критерий защитного теста: получить фактический GetTextLayoutResult для **всех пяти** RU/BA подписей при ширине 320dp/fontScale 2, сохранить lineHeight/размер/hasVisualOverflow и проверить отсутствие didOverflowWidth/Height; проверить Text-границы внутри панели и тач-цель каждой вкладки ≥48dp. Затем проверить нормальный шрифт и повторить кадры обоих device-вариантов; при изменении текущих исходников/теста добавить новую приёмку с новыми SHA, сохранив R2. Тест должен падать на текущей фиксированной высоте, а не подменять подпись/масштабирование. Root получил находку и владеет исправлением; этот независимый агент исходник не меняет.

Пределы: дефект воспроизведён на реальном instrumented-компоненте YuldashBottomBar, а не полном HomeShell. Влияние на высоту всего экрана/карточки/клавиатуру после фикса нужно проверить отдельно. Малый/большой физический телефон, другие API, TalkBack и все остальные 96 маршрутов этим стендом не проверены. DESIGN-005 и полный B09 остаются открытыми.

## DESIGN-REVIEW-R3-20260930 — повторная приёмка DESIGN-005

Дата / ответственный: 01.10.2026 по времени проекта, независимый `/root/security_standards`. Агент не писал исходники нижнего меню или этих UI-тестов. По разрешению root единолично дополняет этот документ после чтения завершённого R2; исходные R1/R2 и снимки не изменены. Ветка `audit/full-technical-20260930`, HEAD `6fa0595d88bea4fcd59d4a1e2139c2d128cf6821`, локальные изменения без коммита. Сборкой и emulator-5580 владеет root; этот reviewer Gradle/adb не запускал.

Повтор обоснован исправлением подтверждённой обрезки, добавлением проверки фактического TextLayoutResult и изменением выравнивания панели. Критерий этой приёмки: все пять RU/BA названий отображаются без overflow при 200% шрифта, подписи и иконки выровнены, области нажатия не меньше 48dp, канонический размер текста не уменьшен. Объём — компонент YuldashBottomBar в стенде, не весь HomeShell и не весь B09.

### Прочитанные источники и актуальность

- `YuldashApp.kt` 2499–2618 прочитан целиком для обоих компонентов. Row теперь использует `heightIn(min=78.dp)`, `Alignment.Top`; каждая подпись — `CanonMicro`, `fillMaxWidth`, центрирование, до двух строк. Пять действий и `Role.Tab/selected` сохранены. Текст не ограничивает системный fontScale. Активные цвета остаются CanonGreen/CanonGoldInk/CanonGold, неактивные CanonMutedStrong/CanonMuted.
- `CanonTokens.kt` 83–109 и 480–496: CanonMicro = 12sp/17sp, с тем же масштабированием Android. LF SHA256 токенов `91cd5231e9948d9fc4ba6ec013e010562d0ba9ca07f7c5d8d856ff8a11416687`.
- `SelectedControlsAccessibilityTest.kt` 1–179 и `SelectedControlsInstrumentedTest.kt` 1–111 прочитаны целиком. Первый использует настоящие композиции/GetTextLayoutResult и ширину 320dp при LocalDensity.fontScale=2, проверяет обе локали, overflow, высоту и верх всех вкладок. Второй проверяет настоящие размеры текста, границы по вертикали в корне, обе стороны selected после click, ширину и высоту каждого родительского tab ≥48dp, совпадение верха вкладок.
- Все **14** SHA256(LF) в [актуальном counts](../test-results/audit-b01-logout-nav-green-20260930-counts.json) независимо пересчитаны и совпали. Это проверка актуальности, а не утверждение полного чтения всех 14 файлов. Версии: YuldashApp `92883a6d253da875ddec50a229f2d565e937cb7051556613bf5c1d353a34487f`; unit UI-тест `8faa14335c3254e880898d84be637888e99d167fddaf23ddc07a2f193a0b1aac`; instrumented UI-тест `2c69f75a1ad5b5bbd45955576eab02c3af18a83b6ca5be189d7dbef8cf76f015`.

### Разные виды доказательств

1. Исторический [RED XML](../test-results/audit-design-nav-font-red-20260930.xml) независимо разобран: два overflow-случая, оба падают на «Карта» при 320dp/fontScale=2, errors/skips 0. [Первое исправление](../test-results/audit-design-nav-font-first-fix-20260930.xml) сохраняет два падения уже на горизонтальном overflow «Чат» (`42×28`, lineHeight=17sp). Это подтверждает полезность фактического layout-теста; повторное успешное выполнение не стирает промежуточный сбой. Текущие assertions расширены по сравнению с исходным RED.
2. В [новом JUnit ZIP](../test-results/audit-b01-logout-nav-green-20260930-junit.zip) независимо разобран именно SelectedControlsAccessibilityTest: **8/8**, failures/errors/skips 0, suite 10.595с; включает обе overflow-проверки и шесть прежних случаев. Общее 29/29 содержит другие storage-тесты и не считается числом дизайн-сценариев. [Log](../test-results/audit-b01-logout-nav-green-20260930.log) отдельно подтверждает assembleDebug и BUILD SUCCESSFUL за 1м14с.
3. [Финальный device log](../test-results/audit-b01-final-controls-device-20260930.log): **2/2**, 7.791с. Состав соответствует двум текущим SelectedControlsInstrumentedTest. Это сохранённое исполнение root, не новый запуск reviewer. Команда воспроизведения владельцем после установки APK/test APK: `adb -s emulator-5580 shell am instrument -w -e class com.yuldash.app.SelectedControlsInstrumentedTest com.yuldash.app.test/com.yuldash.app.StorageAuditRunner`.
4. Оба PNG независимо просмотрены через `view_image(detail=original)): [light/RU](../test-results/audit-b01-final-controls-light-ru-20260930.png), [dark/BA](../test-results/audit-b01-final-controls-dark-ba-20260930.png). Размер 1080×2340; все названия показаны целиком, верх иконок и подписи на одной линии, нижние части букв не отрезаны. Длинные слова переносятся: RU «Поездки»/«Заявка»/«Профиль», BA «Сәфәрҙәр»/«Профиль».
5. [RU metrics](../test-results/audit-b01-final-controls-light-ru-20260930.json) и [BA metrics](../test-results/audit-b01-final-controls-dark-ba-20260930.json): **10 из 10** записей имеют overflowWidth=false и overflowHeight=false; fontSize=12sp/lineHeight=17sp; верх каждого текста 2081px, низ 2158px либо 2252px, ширина 211 либо 212px. Значения перечислены из JSON, не оценены по картинке. JSON собирается до click при выбранной Rides; PNG — после click при выбранной Map. Эти два состояния нельзя объявлять одним и тем же layout-снимком.

Среда: компонентная Column/Surface с OnboardingLangChip, Spacer и YuldashBottomBar; API/сервер/БД в стенде не вызываются. API35/Android15 подтверждён предыдущим [device XML этой группы](../test-results/audit-design-nav-font-device-20260930.xml). Повторная read-only проверка root 01.10 сохранена в [environment.txt](../test-results/audit-final-device-environment-20261001.txt), прочитанном reviewer: физический размер 1080×2340, density 440 без override, системный font_scale 2.0. emulator-5580 указан владельцем; reviewer собственный adb не выполнял. При Android-формуле density=dpi/160 ширина устройства =1080/(440/160)≈392.73dp. Следовательно, сохранённый device-кадр **не доказывает** 320dp: эта более узкая ширина проверена отдельными Robolectric-тестами. Robolectric API34/native graphics с host qualifier411dp, а нужный компонент явно ограничен Box.width(320dp).

### Приёмка и остаток

**DESIGN-005 принят в границе исходного компонентного дефекта:** высотная обрезка и overflow названий устранены и подтверждены актуальным source, защитными unit-тестами и новыми кадрами/проверками устройства. Выравнивание по верхнему краю принято чтением `Alignment.Top`, assertion всех tab tops и обоими PNG. Touch ≥48×48dp принят по текущим исполненным instrumented assertions; unit при 320dp проверяет высоту, ширину в этом варианте отдельно не утверждает.

Цвета канонические, шрифт не уменьшен. Повторный расчёт конечных **непрозрачных** пар по sRGB-формуле `(max(L1,L2)+0.05)/(min(L1,L2)+0.05)`, где `L=0.2126R+0.7152G+0.0722B` после преобразования в линейный sRGB: selected/Surface light12.022319:1, dark10.253072:1; inactive/Surface light7.935901:1, dark9.603548:1; GoldInk/Gold light6.655164:1, dark7.312128:1. Это вычисление из конкретных Canon-значений, не измерение всего растра или промежуточных кадров. Реальная панель имеет alpha0.98; сложный фон/анимация/контраст всех остальных экранов остаются вне этой приёмки.

Предложение по оформлению, **не измеримое нарушение**: на 200% перенос внутри «Поездки», «Заявка», «Профиль», «Сәфәрҙәр» заметен и слова разных вкладок стоят близко. Возможный последующий подбор переноса/макета допустим только с сохранением полного текста, системного масштаба и области нажатия. Сам по себе такой перенос не означает возврат исходной обрезки.

Открыто: влияние выросшей панели на реальные HomeShell/карточки/клавиатуру; обычный и другие размеры экрана в реальном полном пути; все selected-tab варианты, badge/долг, живые смены состояния; настоящая речь TalkBack, cutout/системные панели других устройств, физический телефон, остальные маршруты/состояния B09. Проверка границ в instrumented source относится к корню по вертикали, не ко всем дочерним границам панели; горизонтальное размещение поддержано fixed-width layout/overflow и PNG. Нельзя считать весь дизайн проверенным по двум снимкам компонента.

Бинарные SHA256 без нормализации: light/RU PNG `024dabb95111920fb040e9e719730c39954657384aba3ac524d26b5ebd8ffcd3`; dark/BA PNG `a2a497dcafa62ca2388e8d8ccdd22da4d854ffa0bce8d2119387ef25023402b7`. Metrics JSON raw SHA256: RU `5b73291fc158920735320bf43167aa52f198e3ced270021f4d4ac449dc68d881`, BA `68c535d4dd8661b266b332d54f2b8b1f81788f561e74d33e2473ea77b9d7d4ac`. Следующий шаг — root переносит ограниченную приёмку DESIGN-005 в единственный реестр и продолжает открытые полные UI-сценарии.

## DESIGN-PROBE-R4-20261001 — общие состояния и обязательное обновление

Дата / ответственный: 01.10.2026, 08:56 МСК, независимый `/root/design_continue`. B09 и связанные B01/B07. Ветка `audit/full-technical-20260930`, HEAD `6fa0595d88bea4fcd59d4a1e2139c2d128cf6821`; рабочая копия с локальными изменениями root и сохранёнными чужими изменениями. Этот агент меняет только данный документ. Операционная система Windows; Gradle, adb, сервер, БД и внешние приложения не запускались. Чтение и подготовка воспроизводимых критериев не выдаются за исполненный тест или проверку устройства. Исторические R1–R3 не изменены.

### Файлы и предел чтения

| Файл | Прочитанный диапазон / назначение | SHA256(LF) текущего файла |
|---|---|---|
| `UiKit.kt` | 1–574 целиком: кнопки, состояния и общие плашки; семантика loading, ветви style/enabled, размеры | `359bb425b3e1bd9f1ed7cd35bcd06a1d60036327c233f27ae1985ec0a0c278c4` |
| `ForceUpdateScreen.kt` | 1–128 целиком: blocking screen, непустая/пустая ссылка, запуск Activity/отказ, insets/анимация/геометрия | `2a1683611d9931bc2f5de8fe0f645b990fe6ef3aa2c1cdc74591698c73da581d` |
| `UiKitButtonTest.kt` | 1–138 целиком: тексты, клик, loading/disabled и стили; границы assertions | `43797c84588fa6fc0b6f5593e38da485ebc9554bfa1dbcc1095339f6a65a3051` |
| `UiKitStatesTest.kt` | 1–133 целиком: error/retry/empty/action; тесты не измеряют layout крупного шрифта | `30e7a450be232f25016f8886c451e4e8768262249209ad67a588ea86bb9e9e66` |
| `UiKitStateContainerTest.kt` | 1–187 целиком: приоритет loading/error/empty/content и сохранение content; тесты не проверяют доступное имя кнопки | `49f879aa93788c69a6091f0084e6af3ee6e0918f41379caa178055ff1b3c5b82` |
| `YuldashApp.kt` | 1004–1024: ForceUpdate до общего дерева; перечень и старые источники R3 прочитаны в документе, весь файл не объявляется повторно прочитанным | `92883a6d253da875ddec50a229f2d565e937cb7051556613bf5c1d353a34487f` |
| `PromoCodeScreen.kt` | 119–188: реальная кнопка Accent «Применить» / «Ҡулланыу», busy до API и обратно, без дополнительного semantic label | `0cb129fe456d1e203d844f92b8041a0fcaf87af8b1f4c1c31d538cfd5b142324` |
| `TrustScreens.kt` | 735–764: реальная кнопка Secondary «Отметить» / «Билдәләү», loading=saving, min height48dp | `ba14a3f920bd5f052825fd98231b54d439282018ccfa0a4d86aba3e9c4f93d1c` |
| `IntroScreen.kt` | 137–200: setup/cleanup системных панелей и reduced motion; остальной Intro новым проходом не разобран | `d0b2ef814118e1d5cf6dd87686b2a85adb35c9953e73d3692a3a9e775b0e7758` |
| `ui/theme/Theme.kt` | 1–87 целиком: Material palette и SideEffect панелей | `1eabf99357b62d6dcfcd2e68fc03fde51291b051db38b28fe3285060ab2790b3` |

Отпечатки независимо получены 01.10.2026 по тому же алгоритму `sha256(data.replace(CRLF, LF).replace(CR, LF))`; физические строки посчитаны `len(data.splitlines())`. UiKit, ForceUpdate, Intro, Theme и три старых UiKit-теста совпали с ранее записанными отпечатками; новый запуск неизменённого полного набора ради количества не выполнялся. У YuldashApp совпал хэш R3. Новые проверки нужны из-за ранее не проверенных критериев, а не из-за объявленного дрейфа этих UI-файлов.

### R4-A — AppButton во время загрузки: ещё требуется воспроизведение

Путь: пользователь нажимает «Применить» в PromoCode либо «Отметить» в Consents → busy/saving=true → общий AppButton → запрос API → успех/ошибка → busy/saving=false. Общий код `UiKit.kt` 147–199: все четыре style вычисляют realEnabled=enabled&&!loading; loading-ветвь содержит только CircularProgressIndicator22dp. Надпись Text появляется только в else. На родителе не задано доступное имя действия или состояние loading. Это подтверждённое устройство исходника; потерю полного доступного названия и фактическую речь TalkBack я не могу подтвердить без semantic-tree/устройства.

Правило: AGENTS §4.5 — доступность и понятные состояния; сохранять возможность понять, какое действие сейчас выполняется. Существующие `appButton_loading_hidesText` и `appButton_loading_isNotEnabled` подтверждают желаемое отсутствие видимого Text и запрет клика, но не доступное имя действия. Остановка тестовых часов перед setContent нужна бесконечному индикатору; сама она не подменяет продуктовый loading.

Точный probe владельца: показать настоящий AppButton с text через `appText`, управляемым loading=false, нажать и реально поменять loading=true, снять merged и unmerged semantic-tree по parent/testTag. В каждом состоянии утверждать доступное имя действия (Text или ContentDescription/label), Role.Button и disabled именно при loading; проверить ProgressBarRangeInfo и отсутствие вызова callback при повторе. Затем loading=false восстанавливает имя и разрешённость. Выполнить для Primary/Accent/Secondary/Danger, RU/BA. Отдельный screenshot показывает только видимую подпись/спиннер; его недостаточно для доступности.

Если RED подтвердит потерю имени, минимальный кандидат — сохранить text в семантике родительской кнопки только при loading и двуязычное описание ожидания, сохранив индикатор и блокировку. Текст «загрузка» не заменяет имя исходного действия. Новый BA-текст root заносит в «Переводы на проверку». Не менять сетевой протокол ради этого компонента. Приёмка после исправления: те же реальные состояния и два дерева, затем TalkBack отдельно. До RED это риск, не новый подтверждённый DESIGN-дефект.

### R4-B — ForceUpdateScreen: адаптив и единственный выход ещё не проверены

Поверхность вне enum; `YuldashApp.kt` 1013–1016 возвращает её вместо обычного приложения. `ForceUpdateScreen.kt` 58–126: Box fillMaxSize/systemBarsPadding, центрированная Column с horizontal padding32dp, герой104dp, масштабируемые заголовок/описание, CTA min height54dp; scroll отсутствует. Не пустая ссылка даёт единственную кнопку; пустая — текст, где взять версию. Открытие ссылки99–105 обёрнуто в runCatching без отображения отказа. Наличие ограничения высоты и отсутствия scroll — чтение; обрезание CTA не воспроизведено. Я не могу это подтвердить.

Точный probe: настоящий ForceUpdateScreen с синтетической `https://example.invalid/update`, RU/BA × light/dark; Box320×568dp и широкий viewport, fontScale1 и2, финальный кадр после ENTRY. Сохранить TextLayoutResult (title/body/CTA), bounds каждого Text и Button относительно доступной области, затем проверить достижимость CTA без отключения fontScale; если он ниже видимой области, настоящий scroll должен приводить к доступной кнопке. CTA не нажимать в реальный store. Отдельно проверить пустую ссылку и недоступное ACTION_VIEW через контролируемый отказ launch; ожидается понятное состояние с дальнейшим действием. Число, язык и размер стенда записать рядом с XML/PNG; текущая проверка на устройстве392.73dp из R3 не заменяет 320dp.

Правило AGENTS §4.5: малый экран/крупный шрифт, системные панели, ошибки/повтор; влияние возможного дефекта — пользователь обязательного обновления не достигает единственного разрешённого действия. Если layout RED подтверждён, кандидат — единый прокручиваемый контент с insets и минимальной высотой/центрированием при достаточном месте. Если launch failure RED подтверждён, минимальная локальная двуязычная обратная связь. Решение root после воспроизведения; текущая запись не считает оба дефекта подтверждёнными.

### R4-C — соседние общие состояния и панель Intro

- `UiKit.kt::AppStaleStrip` 364–402: текст имеет weight1; Retry с иконкой/текстом не имеет weight и задан maxLines1. Проверить в настоящем контейнере320dp/fontScale2 обе локали: ненулевая доступная ширина пояснения, его TextLayoutResult без overflow, читаемый Retry и touch≥48dp; допустим рост строки по содержимому. Риск конкуренции ширин по коду пока не объявляется визуальным нарушением.
- `UiKit.kt::ConnectionBanner` 510–552: warning-текст maxLines1. Узкий/крупный BA требует фактического layout; наличие строки в semantic-tree не доказывает отсутствие растрированной обрезки. Баннер и ошибка конкретного списка — разные состояния, оба остаются в полном сценарии B09.
- `IntroScreen.kt` 148–161 устанавливает светлые системные иконки во время интро, onDispose принудительно делает light-appearance=true. `Theme.kt` 72–80 задаёт !darkTheme в другом SideEffect. Порядок этих эффектов и конечный цвет после выхода не доказаны чтением. Device-case владельца: тёмная тема → первый запуск Intro → skip, отдельно естественное завершение и reduce-motion → следующий экран; сохранить видимые панели и appearance flags после перехода. По светлой теме такой же контроль. Не объявлять неправильную панель без этого исполнения.

### Контрольная точка R4

Завершено: согласованы точные новые probes с root, прочитаны общие source/test-ветви, сохранены текущие LF-хэши и ограничения. Исправлений UI этим агентом нет. Ни новых RED/GREEN, ни новых PNG/метрик, ни речи TalkBack этот узкий проход ещё не получил. Текущие статусы/порядок остаются в `audit-blocks.md`; этот раздел — доказательство чтения и критерии повторной проверки, не конкурирующая очередь. Следующий шаг — root запускает R4-A/B/C после текущего B01-фрагмента либо передаёт разрешённое окно/сохранённые доказательства; независимый агент принимает конкретные результаты и остаётся доступен для проверки исправлений.

### R4-D — полный разбор LoginScreen и состояния PATH-01

Продолжение того же независимого чтения 01.10.2026. Прочитан **LoginScreen.kt 1–1307 целиком**, включая импорты, обработчики и все вложенные композиции. Текущий SHA256(LF) `9c25953bea9aa4e22adc6fdd4b87790504d8d8294d663e87651c059368460f26` совпал со снимком [R3 counts](../test-results/audit-b01-logout-nav-green-20260930-counts.json). Прежний 50c307… в первоначальном перечне — исторический снимок до исправлений R1; его не подменяем текущим хэшем. Чтение полного файла не означает визуальную приёмку всего экрана или исполнение всех перечисленных состояний.

Дополнительно полностью прочитаны `LoginScreenContentTest.kt` (93 строки, SHA256(LF) `09dcea0288480f02cc166597d0666e48a7ed719dbe2adc30c9ded9d962ba4bd3`), `LoginFormContentTest.kt` (205, `bc49bbd598dc7f3c63554713139edb428b9ec5239dd852f01e34fd4cddae17ad`), `LoginDeepContentTest.kt` (250, `2b90dbb1ca8ef52cbc9c0269bc65fd07e0a60ce6cba3ff43acc3e9808ee5369a`) и `data/ApiClientLateLoginSessionTest.kt` (118, `28bbe9044c9425b61154453dbdc805c4ea837a9c81cd73be0743672e108dfdd2`). Эти классы здесь не запускались. Сопоставление с тестами — чтение их реальных assertions, не добавление успешных запусков к счётчику.

| Функция → действие → состояние | Связь с запросом/результатом | Наличие доказательства / незакрытый критерий |
|---|---|---|
| LoginScreen307–367 → открыть Login | Scroll охватывает Hero и форму; карточка поднимается custom layout на120dp; Hero min560dp растёт с текстом | Код прочитан. Layout на малом/широком экране, клавиатура и увеличенный шрифт не исполнены этим проходом. Изолированная форма в высоком тестовом окне их не доказывает |
| LoginFormCard373–453 → «Войти через Telegram» | tgStart → request_id/очистка code → tgMode → ACTION_VIEW; двойной старт ограждён loading. Ошибка старта возвращает error | Основной кнопочный loading относится к R4-A. Требуются реальный UI start/error/retry и отказ внешнего Activity; runCatching414–418 сам не показывает отказ launch |
| Form611–687 → ввести имя/код, «Войти» | tgVerify459–483 → auth → fireUpdateName/onContinue; 400/403/409/410/429 и SessionPersistenceException имеют разные сообщения/needPhone/freshCodeRequired | Все ветви и двуязычные строки прочитаны. Старые content-тесты показывают готовые error/needPhone, а не получают каждое состояние настоящим ответом. Для полного PATH-01 нужны реальные запросы/повтор, RU/BA layout и доступность заполненных полей |
| «Открыть Telegram ещё раз»489–506 | needPhone=true открывает текущий чат; иначе tgStart выдаёт новый request_id и очищает code/freshCodeRequired | Поведение привязано к серверу, не только к изменившейся надписи. Два callback-теста открытия/надписи не подтверждают новый request_id и внешний Telegram. Настоящий провайдер остаётся отдельно |
| «Назад»507/683–687 во время verify | Сбрасывает tgMode/code/error; ранее запущенная scope.launch продолжает ожидать, успех вызывает onContinue465 | Передан root **неподтверждённый** сценарий позднего ответа. Нужен задержанный настоящий HTTP → Back → release, проверка принятого правила отмены и токенов/экранов. Не приравнивать к ApiClientLateLoginSessionTest: он делает logout и новый аккаунт; Back сам logout не вызывает |
| SMS848–962 → телефон/код/смена номера | BuildConfig.SMS_LOGIN_ENABLED управляет доступностью; requestCode/verifyCode остаются реализованы510–540; при loading смена номера926 не ограждена | В текущих тестах флаг false, ветви формы проверяются напрямую. Это явно ограниченная проверка скрытого конфигурацией кода, а не основание исключить его из аудита. Реальные SMS/платные сообщения не отправлялись |
| Hero993–1164 → язык/обещания | Адаптивный CanonGreen2 участвует в полупрозрачных вуалях поверх фото; белые надписи/иконки размещены на составном фоне | Нельзя доказать контраст всех пикселей только ratio белого и одного токена. Требуются финальные и промежуточные кадры каждой темы, BA длины/200% шрифт; декоративное изображение не описано для озвучивания, подписи рядом имеют текст |
| LoginLangToggle1168–1210 → сменить язык | Неактивный чип вызывает onToggleLanguage; selected/Role.RadioButton/label присвоены; min64×48dp | Прежняя ограниченная приёмка R1 сохранена. Content-тесты проверяют callback активного/неактивного языка, но не естественный полный screen переход/сохранение текущего шага и системные панели |
| LoginConsent1245–1307 → условия/политика | Текст 18+/согласие и обе внешние ссылки находятся только на начальном выборе; ACTION_VIEW ошибки проглатываются | Content-тесты утверждают существование текста и отсутствие crash после клика, не фактический показ документа. Переносы/48dp на узком крупном BA и отсутствие handler требуют отдельного измерения; реальные юридические документы этим чтением не проверены |

Отдельные воспроизводимые риски для владельца:

- Поля имени/кода633–654 и SMS885–924 используют только placeholder, без постоянного label. Проверить **после настоящего ввода** merged/unmerged semantics: понятное название поля должно сохраняться вместе со значением; пустой placeholder-тест не доказывает заполненное состояние. Фактическую речь TalkBack я не могу подтвердить.
- ErrorBanner780–824 рисует принятую серверную ошибку и общую подсказку про интернет/ожидание, включая состояния истёкшего кода и отказа сохранения. В R4-D это не объявлено неправильным без проверки смыслового контекста; приёмка должна оценивать, ведёт ли «Повторить» к правильному следующему шагу, а не только к callback.
- Клавиатура: реальные LoginScreen/код/имя в узком viewport, фокус ввода → IME показана → поле, ошибка и CTA доступны прокруткой → Back сначала закрывает IME → возврат к шагу. Отсутствие явного imePadding в LoginScreen само по себе не является доказательством дефекта: системная конфигурация и родительские insets влияют на результат.

Тесты готовой формы в LoginDeepContentTest используют `w411dp-h2600dp` и сами пишут, что scroll вызовы там могут быть no-op. Их assertions текстов/callback полезны для своей границы, но не подменяют обычную высоту устройства, реальный PATH-01, потерю процесса, keyboard/appearance. Обнаружение кода вне первоначально прочитанных диапазонов увеличило объём **чтения**, а не число доказанных UI-сценариев. Root получил новые критерии, исходники этого файла независимый агент не менял.

## DESIGN-SCAN-R5-20261001 — сохранённые адреса и поиск

Дата / ответственный: 01.10.2026, независимый `/root/design_continue`. Связанные B01/PATH-01 (дверь после входа), B03 (адрес назначения) и B09 (состояния UI). Ветка/HEAD прежние; локальные изменения root сохранены. Source не изменён. Gradle, adb, сервер, БД и внешнее геокодирование не выполнялись этим агентом.

Прочитан **SavedPlacesScreen.kt 1–679 целиком**, SHA256(LF) `f58a9a0066a1fb2699f2f3868efabd0fb55b3b0aacd2b865139ccfb133e7fa20` совпал с первоначальным снимком. Все функции и ветви файла разобраны: kind helpers, пустой/непустой QuickPlacesBlock, управление/только выбор, named/recent, подтверждение удаления, QuickPlaceRow, SaveAsPlaceChips/busy/active, SavedPlacesScreen initial/loading/error/stale/content, debounce/suggestions/pendingHit, optimistic delete/save/reload, SavePlaceKindDialog/custom label, DialogKindRow, swipe end/cancel/threshold/key.

Связанные источники: `InstantOrderScreen.kt` 2516–2545 и 2800–2821 — настоящий выбор/удаление/сохранение адреса; 7465–7505 — PickStopSheet. Весь InstantOrder новым чтением не объявляется разобранным. Его текущий SHA256(LF) `7c5d3fc91554f91728c969476360d500ee442b1d9fea5969f0c658f1c4ab6b81` совпал с снимком. `data/GeocoderClient.kt` 1–69 прочитан полностью: `suggestResult`, cache, parsing, `suggest`, reverse; SHA256(LF) `d07e13e45fae23289fd4e0c87bce3574a8ddbf3b98bd7ae42bd88219349d23bb`. `data/GeocoderAndNetworkTest.kt` 1–203 прочитан полностью, SHA256(LF) `089a660c5e77f48f83e041945235efc722c7e5f3a17692cd2fbe97414f008dbd`; этот проход его не запускал.

Связанные решения прочитаны по месту: `decisions.md` раздел 22.08.2026 «Свои адреса видны при заказе, порядок — по привычке», `lessons.md` раздел «Крестики и “Мои адреса” в чужом листе ничего не делали (волна162)». Их исторические слова «проверено вживую» не приняты за новое доказательство текущего UI.

### DESIGN-006 — поиск адреса скрывает серверную ошибку

Экран / состояние: SavedPlacesScreen, поиск нового адреса, сохранённый список успешно получен, свежий query≥2символов, `/geocode` возвращает HTTP500/502 или сетевой отказ. Правило AGENTS §4.5: ошибка/нет сети имеет понятное состояние и действие повторения; не приравнивать её к настоящему отсутствию результатов. Это подтверждённое **однозначным кодом** отсутствие локальной обработки отказа, не субъективное замечание. Новый UI RED, снимок и устройство ещё не выполнены.

Доказательство:

1. `SavedPlacesScreen.kt` 418–422 вызывает `GeocoderClient.suggest(query).take(6)` и записывает только suggestions. Для query≥2 после debounce нет переменных loading/error/result поиска.
2. `GeocoderClient.kt` 55 реализует `suggestResult(query).getOrDefault(emptyList())`; failure превращается в emptyList. 51–54 прямо ограничивают helper местами, где сбой и «не нашли» равноценны, и требуют `suggestResult` для экранов с адресным полем.
3. `SavedPlacesScreen.kt` 440–467 рисует поле и `suggestions.forEach`; при пустом списке поиска в этом месте не появляется ни пояснение, ни retry. Состояния error/loading475–487 относятся к **getSavedPlaces**, а не к geocode.
4. `GeocoderAndNetworkTest` различает 502 failure, success(empty) и намеренный fallback500 старого `suggest`. Это исходник тестов, не сохранённый результат их исполнения в R5. Общая ConnectionBanner реагирует на отсутствие связи через ApiClient; наличие глобальной плашки не восполняет локальный HTTP500 error/retry и не доказывает состояние этого поля.

Влияние: человек вводит допустимый адрес, видит пустую область подсказок и не узнаёт, что запрос не выполнен; повтор без изменения поля недостижим как явное действие. Сохранённые адреса при этом могут отображаться, поэтому ошибка поиска маскируется работающим списком.

Точное воспроизведение для root: настоящий SavedPlacesScreen, синтетический local MockWebServer/API auth, `GET /places/saved`200 с двумя синтетическими записями; ввести уникальный query, которого нет в process cache → дождаться `/geocode?q=…`500 → assert двуязычный **search** error, сохранность прежнего списка, возможность retry без правки query и отсутствие сообщения «не нашли». Retry →200 с hit → выбрать hit → настоящий диалог выбора kind. Отдельно200 `items:[]` — нормальное пустое состояние; 200 с данными; delayed/сменённый query; короткий ввод без HTTP; RU/BA. Не использовать настоящего провайдера и платные запросы.

Минимальный кандидат исправления: использовать существующий `suggestResult`, хранить отдельные состояния поиска, явный повтор того же query, различать error/success empty/loading, сохранить debounce и отмену предыдущего query. Не ломать загруженный список и не вводить новую библиотеку. Новые видимые тексты через appText, BA-черновики root в tasks. После фикса: RED→GREEN на реальном UI пути, соседние геокодер/адресные тесты, кадры ошибки/пусто и широкий/узкий крупный шрифт; независимый reviewer принимает текущий diff/хэши. Исторический файл f58a9… сохраняется в доказательствах. На момент R5 исправления и исполненного UI RED нет; B09 не закрыт.

### Прочие результаты R5 и ограничения

| Участок | Прочитанное поведение | Следующий измеримый критерий / предел |
|---|---|---|
| QuickPlacesBlock111–263 | Дом/работа/три custom/пять recent; named delete спрашивает; recent delete имеет swipe и крестик; вход в SavedPlaces сохраняется в режиме управления | Реальный полный order → SavedPlaces → возврат к исходному действию, ширина/IME/крупный шрифт; статическое наличие callback не доказывает полный путь |
| QuickPlaceRow266–315 | min56dp, title/subtitle maxLines1; delete IconButton рисуется40dp, содержит двуязычное имя адреса | Измерить **реальную** область touch и её пересечения/клики у 200% текста. Нарисованные40dp не равны автоматическому доказательству touch<48dp: Compose может расширять касание |
| SaveAsPlaceChips322–388 | busyKind запрещает повтор API, chips показывают active/loading, активный текст «Сохранено» | Row без weight/scroll требует320dp/fontScale2 layout; одинаковые «Сохранено» требуют semantic-tree с типом адреса. Пока риск, не воспроизведённый дефект |
| SavedPlaceRow527–548 / Dialog551–601 | management delete без внутреннего confirmation; кнопка48dp; добавление custom label ограничено40символами | Диалог long address/40BA/IME и большой шрифт, сохранность meaning при maxLines1; вопрос подтверждения удаления — UX-предложение, отдельное правило не придумано |
| SavedPlacesScreen493–497 | Каждый delete запоминает весь prev, удаляет локально; failure возвращает prev | **Риск гонки, ещё без runtime:** начальный [A,B] → deleteA pending → deleteB success → A500 возвращает [A,B] и вновь рисует успешно удалённыйB. Root переданы точный порядок и критерий: восстановить толькоfailedA либо согласовать с сервером, не возвращать успешно удалённыйB |
| SwipeToDeleteRow616–679 | Порог96dp, stable key recent.id, drag cancel возвращает0, onDelete после перехода gone=true | Отказ удаления/перечитывание может оставить gone-state при той жеid; проверить delayed failed DELETE и возврат адреса в поле, gesture/button и TalkBack отдельно. Не утверждается, что это уже воспроизведено |

Отдельный расчёт **прошедшей конечной пары**, чтобы не выдумывать дефект: активный SaveAsChip использует CanonSurface/CanonGreen2; локальный `tools/contrast.py::parse/ratio` дал light **6.607087:1**, dark **5.002975:1** по той же sRGB формуле `(Lmax+.05)/(Lmin+.05)`. Обе ≥4.5. Указанные фактические непрозрачные цвета не заменяют layout, selected-state или всего pixel raster. Здесь не требуется замена CanonSurface лишь из-за совпадения зелёной заливки с ранее найденным DESIGN-001.

Неиспользуемый в текущих найденных вызовах вариант: `управление=false` в QuickPlacesBlock описан комментариями как режим чужого листа. Поиск Kotlin source/tests выявил единственный product-call2518 в InstantOrderScreen, он оставляет true и передаёт настоящие callbacks. Текущий PickStopSheet7465 вообще не вызывает QuickPlacesBlock. Пустая ветвь147–175 не учитывает false и всё равно создаёт приглашение, однако действующий путь с false не найден; поэтому это несоответствие внутреннего контракта/комментария, **не подтверждённая мёртвая кнопка действующего сценария**. Код по этому признаку не удаляется.

Передача R5: DESIGN-006 и точный UI RED переданы root; агент пишет только этот файл. Следующий исполненный root участок остаётся R4-A/B по согласованию, затем SavedPlaces reproduction по критериям блока. Для дальнейшего чтения/визуального обхода полный перечень96 Screen выше сохранён; новое чтение увеличило разобранные файлы, а не долю испытанных интерфейсов. Root владеет сборкой/устройством и исходниками, независимый агент остаётся доступен для приёмки.

## DESIGN-SCAN-R6-20261001 — отзыв о приложении

Дата / ответственный: 01.10.2026, независимый `/root/design_continue`. Связанный B09, форма оценки в профиле; ветка `audit/full-technical-20260930`, HEAD `6fa0595d88bea4fcd59d4a1e2139c2d128cf6821`, локальные изменения root/прежних участников сохранены. Этот агент не изменял исходники, не запускал Gradle, устройство, HTTP, БД или провайдера. Для этого узкого прохода доказательства — полное чтение перечисленных файлов и локальный расчёт фактических токенов.

Прочитан **AppReviewScreen.kt 1–270 целиком**, SHA256(LF) `9d5028df425d1c462b505d8f3792dfd245fac10e0bcff1b4307becdb46cc1f57`. Прочитаны полностью `AppReviewContentTest.kt` 1–110 (`768a7d7587bf6a05531044277d620d0d82aa29fd4003ddba629269f0411a936f`) и `AppReviewFormContentTest.kt` 1–252 (`a3b7122445c2fa8931ab54763dd8e5ef2c2c4f507de308ec0e21719410fd102a`). Отпечатки независимо пересчитаны по прежней LF-нормализации; строки — `len(data.splitlines())`. Их неизменность подтверждает версию чтения, а не новое исполнение старых тестов.

| Функция → действие → состояние | Сервер / результат | Доказательство / незакрытый критерий |
|---|---|---|
| AppReviewScreen48–113 → открыть / заполнить | stars по умолчанию5; text до600, city до60; canSubmit требует trimmed text≥10 и !sending | Все ветви прочитаны. Ввод Unicode/BA/пробелов/пределов, IME и восстановление после настоящего процесса требуют исполнения, не считаются пройденными |
| ReviewFormContent118–200 → оценить и написать | Постоянные labels у текста/города, счётчик trimmed length, scroll + imePadding; выбор вызывает onStarsChanged | Content-тесты утверждают тексты, callbacks, ограничения/готовые ошибки в высоком `w411dp-h2600dp`. Они не доказывают обычную высоту устройства, полный экран и реальный серверный переход |
| «Отправить»94–110 /177–189 | submitAppReview(stars,text.trim,city.trim) → success: sent=true/ReviewThanksCard; failure: двуязычный netError; loading запрещает кнопку | Реальный запрос, повтор после потери ответа, сохранность черновика и серверный результат этого прохода не исполнены. loading содержит только спиннер без отдельного имени на кнопке — сопутствующий кандидат R4-A, требуется настоящее semantic-tree; не переносить результат AppButton автоматически на custom Button |
| ReviewStarsRow208–230 → выбрать1…5 | Заполняются звёзды i≤selected, IconButton вызывает onSelect(i), RU/BA starsText(i) | Контраст фактического tint ниже подтверждён. Фактические touch≥48dp, один выбранный rating в семантике и TalkBack ещё не измерены; визуально заполненные i≤rating не означают несколько одновременно выбранных ratings |
| ReviewThanksCard237–270 → завершить | После подтверждённого success карточка с текстом и CTA onDone; внешний sent-контейнер69–80 не прокручивается | Требуются320×568dp/fontScale2/BA, bounds и достижимость CTA, возврат в профиль. Не объявляется обрезание только из-за отсутствия scroll |

Связка достижимости уточнена чтением точных участков: `ProfileScreen.kt` 886 создаёт настоящую action-card «Оставить отзыв» → `HomeScreen/HomeRoute` передают callback → `AppNavHome.kt` 87 присваивает Screen.AppReview → `YuldashApp.kt` 1608 рисует AppReviewScreen. Запрос `ApiClient.kt` 1479–1485 — auth POST `/reviews`, поля stars/text/city. Прочитан `backend/app/routers/reviews.py` 1–85, а не весь backend: create_review проверяет current_user, частоту, trimmed длину/открытый текст/город, сохраняет published=false и commit. Это подтверждает предусмотренную **связь исходников** с модерацией, не исполненный двухсторонний путь или отсутствие дублей при потере ответа. Для сквозного сценария нужны синтетический пользователь → сохранённая запись → очередь админа → публикация/снятие → результат публичного сайта и ограничение доступа; текущий проход дизайн-агента не выдаёт такой результат.

### DESIGN-007 — выбранные звёзды отзыва используют цвет ниже порога контраста

Экран / состояние: AppReviewScreen → ReviewStarsRow, светлая тема, выбран рейтинг1…5. Правило проекта: `tools/contrast.py` docstring и `ContrastGuardTest.kt` 9/176–199 требуют ≥3:1 для значащих иконок; `CanonTokens.kt` 119–123 прямо объясняет, что звёзды — информация, а CanonGold на светлой карточке ниже этой границы. Измеримое нарушение подтверждено реальным source и вычислением, не субъективным выбором оттенка. Снимок на устройстве и новый UI RED ещё не получены.

Доказательство: `AppReviewScreen.kt` 209–210 задаёт `CanonSurface` фоном Card; 221–224 рисует заполненную звезду для i≤selected с непрозрачным `CanonGold`, без иной фоновой подложки. Существующий guard проверяет **CanonStar**, а этот компонент использует другой токен; прохождение token-guard само по себе не защищает выбор цвета компонента.

| Конечная непрозрачная пара | Светлая тема | Тёмная тема | Порог |
|---|---:|---:|---:|
| Текущий CanonGold / CanonSurface | #F5B301 / #FFFFFF → **1.853327:1** | #E8C36B / #192420 → **9.470607:1** | 3:1 |
| Существующий CanonStar / CanonSurface | #BE7D00 / #FFFFFF → **3.429761:1** | #E7A921 / #192420 → **7.679429:1** | 3:1 |

Воспроизводимый локальный расчёт (без сборки), из корня репозитория; полный `tools/contrast.py` повторно прочитан перед использованием его API:

```powershell
@'
import importlib.util
from pathlib import Path
script = Path('tools/contrast.py').resolve()
spec = importlib.util.spec_from_file_location('contrast', script)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
t = m.parse()
for fg in ('CanonGold', 'CanonStar'):
    for idx, theme in enumerate(('light', 'dark')):
        print(fg, theme, f'{t[fg][idx]:08X}', f'{t["CanonSurface"][idx]:08X}',
              f'{m.ratio(t[fg][idx], t["CanonSurface"][idx]):.12f}')
'@ | & 'C:/Users/Bayra/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' -
```

Ожидалось: значащая выбранная звезда ≥3:1 на Card в обеих темах. Фактически: светлая1.853327061278, тёмная9.470606993720. Формула уже реализована в `tools/contrast.py::over/lum/ratio`: sRGB-каналы переводятся в относительную яркость L, затем `(Lmax+.05)/(Lmin+.05)`, с учётом alpha. Это расчёт фактических непрозрачных токенов; он не объявляется pixel-замером каждого края сглаженной иконки.

Влияние: слабовидящему пользователю труднее различить выбранную оценку на светлой карточке. Минимальное исправление владельца — заменить tint **заполненных** звёзд на существующий CanonStar, сохранив пустые CanonMuted, подписи, callbacks и формы. Новая палитра или переписывание рейтинга для этого не нужны.

Критерий после исправления: новый хэш/точный diff; meaningful test **реального ReviewStarsRow**, обе темы и RU/BA, не только проверка отдельного CanonStar или поиск строки в исходнике. Сохранить кадр/пиксели выбранной иконки и фона Card; проверить контраст её фактически рисуемого непрозрачного внутреннего участка ≥3:1, сохранность результата при выборе1→5→3. В исходном файле такой тест должен падать именно по выбранной звезде светлой темы, после фикса — проходить. Layout/touch/TalkBack остаются отдельными критериями. На момент этой записи root ещё не применил фикс, новый RED/GREEN не получен.

Сопутствующая гипотеза доступности: `ReviewStarsRow` не задаёт selected/stateDescription для текущего значения, а starsText описывает только число. Существующие content-тесты этого не проверяют. Нужен настоящий merged/unmerged semantic-tree после выбора и реальная озвучка; отсутствие нужного статуса по коду не выдаётся здесь за измеренную речь TalkBack. Если выбран radio-паттерн, единственным selected должен быть `i==rating`, а не каждая заполненная звезда `i<=rating`.

Передача R6: точная пара/расчёт/минимальный кандидат DESIGN-007 переданы root. Source/test правит root по очереди после текущих R4-A/B; этот агент сохраняет только независимые доказательства и остаётся доступен для приёмки. Полный B09 не закрыт: R4 runtime, DESIGN-006 UI-путь, DEVICE/TalkBack/полные экраны и другие исходные пункты по реестру ещё требуют доказательств.

## DESIGN-SCAN-R7-20261001 — публичный профиль водителя

Дата / ответственный: 01.10.2026, `/root/design_continue`; B02 и B09, прежняя ветка/HEAD и локальные изменения. Прочитан **DriverProfileScreen.kt 1–277 целиком**, SHA256(LF) `35b51ae0fe76d9f4d5c5ab391a542ed90b2c857aaa1bd30202a4093d04309f6f`, совпал с исходным снимком. Весь файл разобран: load/reload/error/data, LazyColumn/review keys/empty, Header/avatar/blank name/car/verified, stats/null rating/count/tenure, review author/stars/text/date и tenureValue с порогами365/30. Этот агент не запускал UI/Gradle/adb/API/БД; актуальных layout-кадров этого экрана нет.

Достижимость и данные, прочитанные по диапазонам: `RidesRequestsChatScreens.kt` 677–692/932–947 — driver-row с onClickLabel при driverId>0 → LocalOpenDriverProfile; `YuldashApp.kt` 1004–1010 устанавливает id/Screen и провайдер, 1609 создаёт DriverProfileScreen; `ApiClient.kt` 2198–2220 — unauth GET `/drivers/{id}/public`/parser, 6467–6486 — DriverPublicDto/PublicReviewDto. `architecture.md` 1915–1916 описывает ту же связь. Не объявляются прочитанными целиком эти большие файлы.

Серверная связка прочитана в `backend/app/routers/drivers.py` 454–566: PublicReviewOut/DriverPublicOut,404 отсутствующего/неводителя, агрегат завершённых выехавших поездок, rating/count, отбор text_published&&!excluded/непустого текста, сокращённое имя автора, limit1…20, days/car/response. Чтение схемы показывает отсутствие телефона/госномера/координат среди полей ответа; отдельные privacy/security assertions и реальные запросы чужого аккаунта этим агентом не исполнены и не считаются пройденными. Комментарии об исторических исправлениях не заменяют текущих серверных тестов.

| Путь / состояние | Что подтверждено чтением | Измеримый остаток |
|---|---|---|
| Tap реальной карточки → профиль47–82 | loading/error с onRetry/reload/data; HTTP повтор привязан к reloadKey и driverId | Настоящий delayed/500→retry→200/404 путь, возврат к прежнему списку, поздний ответ после другогоdriverId, RU/BA; экран контентом напрямую не подменять |
| Content85–118 → нет/есть отзывы | LazyColumn, empty bilingual, stable derived key, appearIn | Пусто/непусто с настоящим ответом, конец списка/Back/малый/широкий/200% шрифт. API/DB не подменять уже готовыми полями для доказательства всего пути |
| Header121–153 → имя/авто/verified | Имена и авто имеют maxLines1/Ellipsis, Row weight для имени; VerifiedPill157–170 использует CanonGreen2/CanonMint | Long synthetic Unicode/BA, фотоошибка/без фото, verified=true/false, layout/semantics. Ellipsis сам по себе не дефект; требуется сохранность важного смысла |
| Stats174–223 → рейтинг/стаж/число | Null rating «—», 1decimal Locale.US, три equal weight cards, CanonStar | 320dp/fontScale2: значения/подписи/числооценок не перекрываются; semantic association label/value. 0/29/30/364/365дней и большаяцифра, не называть parse/store чтение unit-pass |
| Review226–259 → автор/рейтинг/текст/дата | Полный текст, author maxLines1, five14dp stars, optional shortDate | DESIGN-008 ниже; longauthor+fontScale2 в Row без weight авторского Text237 требует layout: рейтинг не потерян и не наложен. Не считать noninteractive14dp icon нарушением touch48dp |

### DESIGN-008 — оценка отдельного отзыва отсутствует в семантике

Экран / состояние: DriverProfileScreen с непустыми модерированными reviews и stars1…5. Правило AGENTS §4.5 — доступность/TalkBack и понятность информации; число звёзд определяет оценку, должно оставаться доступным при озвучивании, а не только по цвету. Это статически подтверждённое отсутствие конкретных данных в доступном представлении; фактическую речь TalkBack и новый semantic-tree я не могу подтвердить без исполнения.

Однозначное доказательство: `DriverReviewCard` 226–259 принимает stars; значение используется только в tint248 (`n<=stars`). Все пять Icon244–250 имеют `contentDescription=null`247, никаких semantics/stateDescription на star Row/Card нет. В соседних Text представлены author237–240, text254 и shortDate255–256; числового/словесного рейтинга нет. Общая средняя оценка в StatCell174–182 относится ко **всему водителю**, не восстанавливает значение этого отзыва. Цвет/количество закрашенных иконок не создают текстовый узел семантики.

Влияние: человек, который пользуется чтением экрана, узнаёт автора/слова/дату, но не оценку1…5 конкретного отзыва. Минимальный кандидат владельца — одно двуязычное описание оценки на группе звёзд/карточке, сохранить чтение автора, текста и даты; не добавлять пять одинаковых озвучиваемых значений. Новые строки через appText, BA-черновик root в tasks. Визуальная форма и CanonStar менять не требуют: эта пара уже проходит рассчитанный в R6 порог3:1.

Точное воспроизведение: настоящий DriverProfileScreen с синтетическим id и локальным ответом `/drivers/{id}/public`200, один review{author:"Тестовый А.",stars:1,text:"Синтетический отзыв для проверки",created_at:"2026-09-30T12:00:00"}; дождаться запроса/данных и снять merged/unmerged semantic-tree. Ожидается доступное число/описание оценки1из5, автор и текст; исходная карточка содержит только автор/текст/дату. Следующий case stars5 с той же общей средней rating проверяет, что озвученная **отдельная** оценка меняется; RU/BA, empty список и fullscreen Back соседние. После фикса сохранить RED→GREEN на реальном пути, semantic-tree/хэши, затем отдельное исполнение TalkBack. Счётчик хороших тестов не увеличивается до результатов.

Прочие риски R7 остаются гипотезами:

- Key113 составлен из author+createdAt+text.take(12); DTO6481–6486 не имеет собственногоid. Он не гарантирует уникальность для двух разрешённых схемой отзывов с одним сокращённым именем, временем и началом текста. Требуется synthetic response с двумя такими разными отзывами → настоящая LazyColumn/прокрутка → результат без duplicate-key crash. Не утверждается, что производственный ответ уже вызвал падение; не вводить удаление второго отзыва ради уникальности.
- Старые данные не очищаются при новом load, но loading/error ветки скрывают их; отмена LaunchedEffect(driverId,reloadKey) и истинный ответ требуют concurrency-проверки. Чтение без устройства не доказывает отсутствие позднего ответа.
- Date helper вынесен в CouponsScreen.kt, его реализация этим проходом ещё не разобрана; не заявляется корректность всех timezone/полуночных случаев только по вызову shortDate.

Поиск `rg -n 'DriverProfileScreen|DriverProfileContent|DriverHeaderCard|DriverReviewCard|DriverStatsRow' android/app/src/test -g '*.kt'` не нашёл прямых content assertions этих компонентов. Это ограниченный поиск по именам, **не доказательство отсутствия всех косвенных/инструментальных сценариев**; перед добавлением owner проверит существующие маршруты/coverage. Root получил DESIGN-008 и точные новые критерии, единственный писатель docs/audit-design-review.md остаётся этот агент. Ближайшее исполнение R4-A/B остаётся за root; новых фоновых процессов/замков здесь нет.

## DESIGN-SCAN-R8-20261001 — купоны и общая дата

Дата / ответственный: 01.10.2026, независимый `/root/design_continue`, B07/B09. Прежние branch/HEAD/local changes; source этого участка не изменён. **CouponsScreen.kt 1–728 прочитан целиком**, SHA256(LF) `125afa67661c8ff90bd770f566f803642980fd9bce8aa046c6c3037cfde69646`, совпал с исходным перечнем. Все helper/Composable/обработчики и ветви разобраны: category fallback/RU-BA, kopToRub signed/cent/Int.MIN, shortDate, list/detail/code navigation, tabs/city filters/profile city, list load/error/empty/stale/pull refresh, keys/couponCard/badges, own codes/status/copy, detail preview/freshGET/activate/report/dialog, activated code/done.

Прочитан целиком `MoneyFormatTest.kt` 1–115, SHA256(LF) `93522a2cf06622babd70ce89dd1b496639e0b31ad5acc02640b8a1fdaae74da4`: assertions подтверждают именно проверки денег/очереди в исходнике, **не** shortDate/купонов. Прочитан `SensitiveClipboardGuardTest.kt` целиком: купонный код явно разрешён для обычного буфера, причина — показ/передача этого кода. Поэтому отсутствие copySensitive у coupon-code не объявлено новой утечкой без понимания принятого правила. Эти тесты данным проходом не запускались; текущая безопасность буфера на устройстве не принята чтением guard.

Связи прочитаны по месту: `architecture.md` 1768–1769/2709 (пассажир, бизнес, city filter),879–880 (историческая обработка отказа report); `ApiClient.kt` 3723–3762/3951–3954 — GET `/coupons` с enc городом/маршрутом, GET `/coupons/{id}`, auth activate/my/report; `backend/app/routers/coupons.py` 237–257/347–441 — public fields/visibility404, locking/idem reserved/check dates/limits/commit/code. Эти backend-диапазоны прочитаны, но **не проверены тестом SQLite/PostgreSQL**; FOR UPDATE/idem комментарий не доказывает отсутствие гонки. Для совместного пути нужны passenger activation → business redemption → passenger status с единым кодом и ожидаемым счётом/историей, synthetic separateDB без платной подписки/рабочих купонов. Такой путь ещё не исполнен этим независимым агентом.

| Функция / путь | Прочитанное поведение | Измеримый незакрытый критерий |
|---|---|---|
| CouponsScreen134–154 | Настоящие переходы list→detail→activation code→myTab; локальные remember-состояния | Обычный Back, system Back и process death на каждом шаге, возвращение из auth к исходной активации, существующий код без второй брони |
| CouponTab180–190 / FilterChip279–291 | active меняет bg/border/textcolor, fixed48dp, но не задаёт selected/Role.Tab | Дополнительные потребители **DESIGN-002**, не принятые R1: настоящий click→tree при RU/BA, selected текущей вкладки/города;320/fontScale2 TextLayoutResult не обрезан. Отсутствие selected по коду не считается новой device-речью |
| NearbyCoupons196–276 | me().city→filter; отдельно launched reload в scope; loading/empty/error/stale и pull refresh есть; списочные keys=id | Несколько city changes с ответами в обратном порядке, delayed me после ручного «Все города», repeated refresh; финальные cards относятся к выбранному filter. Из LaunchedEffect(cFilter) запускается другая scope.launch212, отмена эффекта сама её не отменяет — это concurrency-risk, ещё без воспроизведения |
| MyCoupons354–440 | Начальный load, pull refresh и stale при старом статусе; reserved показывает code/copy, прочие статусы code скрывают | Реальный бизнес погасил→refresh→redeemed безстарого code; offline/stale/error→retry с сохранением list; копирование реально кладёттоткод, touch≥48dp/BA/крупныйшрифт |
| Detail462–630 | preview сразу; freshGET; loading guard/Accent AppButton; actError имеет настоящую retry через повторный клик; report Toast успех/отказ | DESIGN-009 ниже; late activation after Back, doubletap, lostresponse и auth return отдельные; failure report Toast сохраняет смысл ошибки/неутверждаетуспех. AppButton-loading остаётся общим R4-A |
| ReportDialog634–671 | reason≤500, blank disabled, trimmed callback, placeholder без постоянногоlabel | Настоящий ввод/IME/fontScale2 и доступноеназвание заполненного поля; повтор после serverfailure не теряетведённуюжалобу без объяснения. Пока runtime-risk, не «500символов защищены» по take |
| ActivatedCode676–728 | Прокручиваемыйsuccess, full code34sp, copy/done→myTab; название/code/date |320/fontScale2/RU/BA: код читаем полностью, элемент не вылезает за ширину, Copy/Done достижимы; реальныйвозврат иstate. LazyColumn сама не доказывает адаптив каждого внутреннего Text |

### DESIGN-009 — детальный купон молча остаётся прежним после отказа свежего запроса

Экран / состояние: CouponsScreen → CouponDetailView, preview из уже показанной витрины, fresh GET `/coupons/{id}`500/networkfailure или404 (купон стал недоступен после загрузки списка). Правило AGENTS §4.5: понятно различать актуальные данные, отказ/нет сети и дать повтор; соседний Nearby/MyCoupons уже использует AppStaleStrip для ровно этой проблемы. Подтверждено однозначным отсутствием ветви отказа в коде. Новый screenshot/Compose RED ещё не исполнены.

Доказательство: detail465 хранит preview; LaunchedEffect473–475 выполняет `ApiClient.getCoupon(couponId).onSuccess { coupon = it }`, без onFailure/error/loading/retry. Затем478–591 показывает срок/остаток/CTA из прежнего coupon. `ApiClient.getCoupon`3737–3738 возвращает Result failure для неуспешного ответа; backend348–354 действительно использует404, если купон/партнёр больше не доступен. actError467/572/584 относится только к **последующей активации**, не сообщает, что freshGET уже отказал. Глобальный ConnectionBanner не объясняетHTTP404/500 и не даёт повтора конкретной детали.

Влияние: человек принимает старую скидку/срок/остаток за свежие, может ехать в заведение по данным, которые не удалось подтвердить или сервер уже снял. В этой записи не утверждается, что activation обходит серверную проверку: прочитанный backend её выполняет, но отказ посленажатия не исправляет скрытую неопределённость детали.

Точный UI RED владельца: настоящий CouponsScreen с local synthetic API, me.city empty/публичный GET list200 с id701/условиями A → настоящий tap card → getCoupon701500 → ожидаются error/stale и Retry свежегозапроса, preview A можно сохранить только явно помеченным. Retryбезвыхода/сменыid→200 с условиями B → отображаетсяB/снятerror. Отдельный404 сообщает, что купон недоступен, даёт понятныйвозврат/повтор; не утверждает, что он доступен. 200normal, delayed/Back/citychange, RU/BA и320/fontScale2 — соседние cases. Не проводить активацию/платёж/телефон на рабочем сервисе.

Минимальный кандидат: сохранить preview и самостоятельное состояние freshGET с failure + явный retry; использовать существующиеобщие состояния, различать недоступность404 и временныйотказ, не подменять alertобщейошибкой активации. Новые тексты appText, BAчерновики вtasks. Послефикса нужны actual list→tap→HTTP→state RED/GREEN и текущиехэши/кадры, затем активация/возврат отдельно. Доэтого DESIGN-009 не считается исправленным, B09 остаётся частичным.

### R8-A — общий shortDate не проверяет действительность даты

Теперь вызванный в R7 helper прочитан, остаток «его реализация не разобрана» снимается только в части **чтения**. `CouponsScreen.kt`125–128: trim().take(10), длина10/дефисы4/7, перестановка substring. Нет проверки цифровыхсимволов, границмесяца/дня, високосногофевраля. Однозначное вычисляемое поведение исходника: `xxxx-yy-zz` соответствует двум guard и даёт `zz.yy.xxxx`; `2026-02-31` даёт `31.02.2026`, хотя комментарии115–120 обещают invalid→null. Kotlinhelper в этом проходе не запускался; живой сервер формирует ISO из datetime250, поэтому поступление таких malformedзначений от настоящегосервера **не подтверждено**. При corrupted-response/форматномдрейфе UI всёже рисует строку как дату.

Передан root конкретный purehelper RED: null/empty/space/короткий/недвадефиса/`xxxx-yy-zz`/`2026-00-01`/`2026-13-01`/`2026-02-31`/`2025-02-29`→null; valid `2024-02-29`, обычнаяISOдата, trimmedISOdatetime→ожидаемаяDD.MM.YYYY. Сначала определить принятое правило timezone: нынешнийконтракт форматирует calendarprefix, а смена на localtimezone без решения может изменить видимый срок купона. Минимальный кандидат после RED — валидировать существующийdateprefix через ужеимеющийся java.time, не менять зависимость/SDK и непереносить timezone молча. Нужен именно тест настоящегоshortDate, не Pythonпереписываниеформулы и неassertотсутствиякраша. Пока новый DESIGN-id этому кандидату не присвоен; не смешивать чистуюдату/визуальнуюприёмку.

Передача R8: source728/hash, DESIGN-009 и purehelper/selected/concurrency probes переданы root. Документ сохраняет первоначальные результаты и ограничение исполнения. Нет новых device/PG/Robolectric PASS, нет изменённойпалитры/источников/замков; ближайшее исполнение R4-A/B по-прежнему владелецroot. Послеполучения результатов независимый агент проверит affected diff/tree/layout/PNG, а этот перечень чтения не заменит полный B09.

## DESIGN-REVIEW-R9-20261001 — AppButton loading и обязательное обновление

Дата / независимый ответственный: 01.10.2026, `/root/design_continue`; B09, probes R4-A/B. Root воспроизвёл/исправил/выполнил тесты; этот агент самостоятельно прочитал исходники и тесты, разобрал XML/JSON/log, открыл сохранённые PNG через view_image. Сам не делал adb/сборку/нажатия. Branch/HEAD прежние, только локальные изменения. Исторические R4 риски теперь уточнены новыми доказательствами, не стёрты.

Окружение исполнения root, источник [текущая передача журнала](audit-journal.md): Windows11, собственный emulator-5580/PID26000/API35, экран1080×2340,440dpi, системный font_scale2, active default network отсутствует; отдельный test Activity/StorageAuditRunner, реальных провайдеров/сервера/БД нет. Instrumented ForceUpdate внутри настоящего Compose-контейнера320×568dp, density2.75 (440/160); PNG880×1562px соответствует `320*2.75` × `568*2.75`. Тест отдельно переопределяет LocalDensity.fontScale1/2 и LocalAppLanguage. Device-case RU/light и BA/dark — две пары, **не** весь декартов набор2языка×2темы. JVM Robolectric API34/Application: loading4style×2языка, Force6cases с320×568/обычным/крупным/emptyURL; theme в JVM Force-тесте явно не задана.

Независимо прочитаны новые тесты целиком: `LoadingButtonAccessibilityTest.kt`1–67, SHA256(LF) `92af3748d96ee4844c8e5050b4c2a33b26e4fad38e4341819680d2d9dd46db42`; `ForceUpdateAccessibilityTest.kt`1–80, `d0c50bc288c4d33514554bb026e638a4ba02e0ea3224f09eb1f3ed48db549e30`; `UpdateAndLoadingInstrumentedTest.kt`1–134, `01c1aabfba61580617d4eac9a88a1b7959a3b5468d54a7c0cc5b6d97f12098ec`. Хэши — эта версия чтения до возможного усиления критериев root. UiKit текущий577строк `741ae5f8287c9e03801ba6ccc0ab1ae6a270f1e0a35480cfd6bb61228bb1b7aa`; ForceUpdate текущий134строки `544b33fbb62dcc1415aff502a19f673b21e0eabd56dfa958f1943fb0d844a1e7`. Diff UiKit/ForceUpdate прочитан, ForceUpdate134строки повторно целиком прочитан из-за изменения; неизменённые огромные UI-файлы повторно не читались.

### DESIGN-010 — loading AppButton терял доступное имя действия

Подтверждённый дефект из R4-A: во время loading все4стиля удаляли единственный Text, индикатор не нёс имени исходного действия. Правило: доступность AGENTS §4.5; пользователь должен узнать, какое действие выполняется. Доказательство RED: [XML8/8failed](../test-results/audit-loading-label-red-repaired-20261001-junit/TEST-com.yuldash.app.LoadingButtonAccessibilityTest.xml), failure каждого style/RU/BA — `lost its accessible action name: []`;8tests/8failures/0errors/0skips. Это реальный компонент, не source-regex и не отдельная подмена кнопки. Сохранён [UiKit до фикса](../test-results/audit-uikit-before-loading-label-20261001.kt),574строки/SHA359bb425… совпали с R4.

Минимальный root diff: imports `contentDescription`/`semantics`, на родительском base `.semantics { if (loading) contentDescription = text }`. Используется уже переданный двуязычный text; видимый spinner, прежний disabled/realEnabled и callbacks не заменены. Новая видимая строка не добавлена. Idle-ветка сохраняет Text без дублированного ContentDescription.

После исправления: [первый XML8/8GREEN](../test-results/audit-loading-green-force-red-20261001-junit/TEST-com.yuldash.app.LoadingButtonAccessibilityTest.xml),0failures/errors/skips; повтор в [общем текущем профиле8/8](../test-results/audit-update-loading-green-20261001-junit/TEST-com.yuldash.app.LoadingButtonAccessibilityTest.xml) нужен из-за итоговой UI-интеграции, не считается ещё8уникальными проверками. Tests проверяют имя через Text/ContentDescription, disabled, min48dp, один ProgressBarRangeInfo, реальный touch во время loading не вызываетcallback, переключениеfalse восстанавливает text/enabled и один click. Device loading2/2 (внутри каждого4style) независимо сверены по [baseline log](../test-results/audit-update-baseline-device-20261001.log) и [финальному log](../test-results/audit-update-green-device-20261001.log): `loadingRuLight`/`loadingBaDark` status0, у финального общего профиля OK6.

Ограниченная приёмка: **имя/disabled/progress/callback общего AppButton при loading принято** в этой версии source. Ограничения исходного R4-A сохраняются: helpers начинают с loading=true, затем false; настоящий idle→userclick→loading ещё не выполнен ими. Role.Button не утверждён отдельным assertion; merged/unmerged tree-файл не сохранён (loadingJSON[] — пустой массив metric save, не semantic-tree). TalkBack не запускался. Из результата общего AppButton не следует исправление custom Button Login/AppReview. Device loading PNG просмотрены; BA/dark Column не задаёт CanonBg, поэтому белый фон этого стенда не является доказательством внешнего вида полного тёмного экрана.

### DESIGN-011 — обязательное обновление при крупном шрифте теряло описание и CTA

Условия: настоящий ForceUpdateScreen, синтетическая `https://example.invalid/update`, узкий320×568dp, fontScale2, после entry. Правило AGENTS §4.5 — увеличенный шрифт/малый экран, системные insets и достижимость единственного разрешённого действия. Влияние: заблокированный обязательным обновлением пользователь не читает всё объяснение/не достигает нормальной кнопки обновления.

До исправления [source128строк](../test-results/audit-force-update-before-scroll-20261001.kt), SHA256(LF) `2a1683611d9931bc2f5de8fe0f645b990fe6ef3aa2c1cdc74591698c73da581d`; без scroll. Независимо просмотрены4 baseline PNG (RU/light,BA/dark × font1/2) и все7непустых baseline update stepJSON:

- [RUfont2 PNG](../test-results/audit-update-baseline-device-20261001-images/audit-update-ru-light-font2.0-before.png) и [step1 JSON](../test-results/audit-update-baseline-device-20261001-images/audit-update-ru-light-font2.0-step1.json): body layout759px при paragraph888px,8строк,overflowtrue, последний emoji/CTA не видны.
- [BAfont2 PNG](../test-results/audit-update-baseline-device-20261001-images/audit-update-ba-dark-font2.0-before.png) и [step2 JSON](../test-results/audit-update-baseline-device-20261001-images/audit-update-ba-dark-font2.0-step2.json): CTA Textheight0px при paragraph194px/2строки,overflowtrue; видна тонкая полоска вместо нормальной кнопки.
- Baseline [device log](../test-results/audit-update-baseline-device-20261001.log) содержит6tests/4failures/13.306с, из них2loadingpassed. **Нельзя считать все4failures дефектами продукта**: normal-font title failures разобраны ниже как неправильный width-критерий.

Ошибка первоначальной проверки сохранена отдельно: [RUfont1PNG](../test-results/audit-update-baseline-device-20261001-images/audit-update-ru-light-font1.0-before.png) и [BAfont1PNG](../test-results/audit-update-baseline-device-20261001-images/audit-update-ba-dark-font1.0-before.png) показывают полный заголовок. JSON title natural Text.width494/558px, paragraphWidth704px, getLineRight599/631px и hasVisualOverflow=true. Сравнение getLineRight с natural Text.size.width в этих разных координатах давало ложное сообщение обрезания. Root заменил его на `layoutInput.constraints.maxWidth+1`, **сохранил** `!didExceedMaxLines`, полную paragraph-height≤layout-height+1px, последний lineEnd==text.length, доступность после scroll/CTA≥48dp/viewport bounds. Это обоснованное исправление метода по независимым PNG, а не разрешение скрыть настоящий large-height RED. Normal-font обрезание в реестр подтверждённых дефектов не переносится. JVM6initialfail в [старом XML](../test-results/audit-loading-green-force-red-20261001-junit/TEST-com.yuldash.app.ForceUpdateAccessibilityTest.xml) тоже сохраняет этот предел.

Root diff текущего source134: BoxWithConstraints вместо Box; verticalScroll перед heightIn(min=maxHeight), verticalpadding24dp и Arrangement.Center. Внешний viewport остаётся ограниченным, контент получает возможность вырасти и прокрутиться, короткий центрируется. insets, двуязычные тексты, существующие Canon/CTA и entry-анимация сохранены. Это минимальное изменение механизма layout; обработка отказа ACTION_VIEW по-прежнему runCatching без обратной связи и **не закрыта** этим фиксированием высоты.

После исправления независимо сверены:

| Вид доказательства | Фактический результат | Предел |
|---|---|---|
| JVM [Force XML](../test-results/audit-update-loading-green-20261001-junit/TEST-com.yuldash.app.ForceUpdateAccessibilityTest.xml) |6/6,0fail/errors/skips;RU/BA normal/large +large emptyURL |RobolectricAPI34, не настоящийstore/полный navigation;themematrix не полный |
| JVM текущие4класса |6Force+8Loading+8SelectedControls+10UiKitButton=**32/32**, все0fail/errors/skips |Число получено суммой tests этих4XML, не процент исправности Android |
| [Сборка](../test-results/audit-update-loading-green-20261001.log) | BUILD SUCCESSFUL48с, assembleDebug иassembleDebugAndroidTest |Debug/testAPKs, не release/обновление черезstore |
| [Эмулятор](../test-results/audit-update-green-device-20261001.log) | OK6/6,14.556с:4update+2loading; APK install logs Success |ComponentActivity/StorageAuditRunner, синтетическаяссылка, realprovidersнет; не физическийтелефон |
| [RUlarge step2JSON](../test-results/audit-update-green-device-20261001-images/audit-update-ru-light-font2.0-step2.json) | Body888/888px,CTA194/194px,конец текста достигнут assertions |PNGstep2 до финальногоmergedButton-scroll, см.ниже |
| [BAlarge step2JSON](../test-results/audit-update-green-device-20261001-images/audit-update-ba-dark-font2.0-step2.json) | Body666/666px,CTA194/194px |Та же границакадра, всеparagraph-height прошли |

Визуально независимо просмотрены финальные normalRU/BA step2, largeRU step1(весьbody сemoji)/step2(CTAtext),largeBA step2 и2loading PNG. Normal-контент целый/центрирован. Large-body/двухстрочныйCTAtext теперь есть и доступны прокруткой. Но test сохраняет step2 **внутри loop после `useUnmergedTree=true` Text.performScrollTo**, а затем отдельный merged `action.performScrollTo` выполняется **без следующего сохранения PNG**. В имеющихся [RUlarge step2](../test-results/audit-update-green-device-20261001-images/audit-update-ru-light-font2.0-step2.png) / [BAlarge step2](../test-results/audit-update-green-device-20261001-images/audit-update-ba-dark-font2.0-step2.png) нижнийкрайфигурыButton срезан scrollviewport. Это промежуточныйкадр, **не доказательство нового окончательного дефекта**, но и не основание сказать, что финальнаявсякнопка визуально принята. Root получил конкретный критерий: final PNG/полные bounds **после** финального Button-scroll.

Приёмка R9: восстановление полной layout-высоты текста и scroll-достижимость CTA принято по данным/strict assertions текущей версии; окончательная целая кнопка на сохранённом финальном кадре остаётся открытой. Другие обязательные остатки: R4-A полный переход/tree/TalkBack, R4-B широкие/смешанныеязык-тема/emptyURLdevice и controlled ACTION_VIEW failure/реальныйstore, systembars/fullroot/navigation; R4-C/D; DESIGN-006…009; остальные96Screen. Нет вывода «весь интерфейс проверен» или «B09 закрыт».

Передача R9: единственный writable file агента — этотдокумент; rootисточники/Gradle/эмулятор, никаких новых locks/backgroundprocesses. Новые тесты/versions после усиления снимут актуальность их старых хэшей; исторические RED/ошибка width/GREEN сохраняются, новые evidence добавляются отдельно. Следующий точный независимый шаг — принять final PNG/Buttonbounds и усиленный loadingcycle/tree, затем следующий доступный пункт **по audit-blocks**, не по конкурирующейочереди.

## DESIGN-REVIEW-R10-20261001 — финальная приёмка DESIGN-010/011

Дата / ответственный: 01.10.2026, независимый `/root/design_continue`, B09/R4-A/B. Контрольная точка root [audit-update-loading-checkpoint-20261001.json](../test-results/audit-update-loading-checkpoint-20261001.json) записана в 06:41:57 UTC (09:41:57 МСК); ветка `audit/full-technical-20260930`, HEAD `6fa0595d88bea4fcd59d4a1e2139c2d128cf6821`, локальные изменения присутствуют. [Итоговый diff](../test-results/audit-update-loading-source-20261001.patch) прочитан. Этот агент проверил материалы независимо, но не запускал команды root повторно, не менял исходники и не использовал adb.

Повтор имеет конкретную причину: R9 не сохранял кадр после последней прокрутки родительской кнопки и не проверял исходный idle→click переход. Root усилил настоящие тесты и добавил два device-случая с пустой ссылкой. UiKit/ForceUpdate остались прежними; предыдущие результаты сохранены как история, а отпечатки изменённых тестов R9 больше не описывают финальные тесты.

| Файл / объём прочитанной версии | SHA256 с нормализацией LF |
|---|---|
| `UiKit.kt`, 577 строк; весь файл ранее разобран, изменённый механизм AppButton и diff повторно проверены | `741ae5f8287c9e03801ba6ccc0ab1ae6a270f1e0a35480cfd6bb61228bb1b7aa` |
| `ForceUpdateScreen.kt`, 1–134 целиком | `544b33fbb62dcc1415aff502a19f673b21e0eabd56dfa958f1943fb0d844a1e7` |
| `AppText.kt`, 1–22 целиком | `91cbe8ee02bce3a921d0f71a558c24f47e7144d0a8a29ab822e9b6fc42a00306` |
| `LoadingButtonAccessibilityTest.kt`, 1–74 целиком | `a9ece48b013c9e11c2d1e728442a57ada793d00789db291c8a4e9629d59ce90f` |
| `ForceUpdateAccessibilityTest.kt`, 1–80 целиком | `c275b91067502e0fdc36a29d62dfa50380918b89bb49ed5fc81130826c8b7f4a` |
| `UiKitButtonTest.kt`, 1–138, ранее прочитан; версия не изменилась | `43797c84588fa6fc0b6f5593e38da485ebc9554bfa1dbcc1095339f6a65a3051` |
| `SelectedControlsAccessibilityTest.kt`, 1–179, ранее прочитан; версия не изменилась | `8faa14335c3254e880898d84be637888e99d167fddaf23ddc07a2f193a0b1aac` |
| `UpdateAndLoadingInstrumentedTest.kt`, 1–152 целиком | `af011e35b58d05b4664f228ddd97941d554e748f841cc8e03175eeb1516efa6c` |
| `StorageAuditRunner.kt`, 1–14 целиком | `84062f43dc76d01610094cc9d558291fecd0fd5e20aefd3e3363f97dd418440a` |

В контрольной точке независимо пересчитана каждая запись с `path` и `sha256_lf` либо `sha256_binary`: **91 запись, 91 уникальный путь, 0 несовпадений** на момент чтения. Для `sha256_lf` применяется алгоритм в начале документа; для binary — SHA256 исходных байтов. Это проверка соответствия сохранённой версии, не 91 функциональный тест. APK также совпали: debug `504d00a298386c5d9f9db20fb8ed2a4872fbc556a1afbe7f1f9829636991094c`, test `466e670c91720063d90453d5ea2e35075ec74b0f86486055e117826543715f44`. Последующая сборка B01 может заменить APK по этим же путям; исторический результат R10 относится к этим отпечаткам.

### Фактические результаты и воспроизведение

| Доказательство | Фактический результат / способ получения |
|---|---|
| [Force XML](../test-results/audit-update-loading-final-green-20261001-junit/TEST-com.yuldash.app.ForceUpdateAccessibilityTest.xml) | 6 tests, 0 failures/errors/skipped; LF SHA `ca093ed6c20f74b046ba293dfeb8391be07edc915250c303f710afede2d104c9` |
| [Loading XML](../test-results/audit-update-loading-final-green-20261001-junit/TEST-com.yuldash.app.LoadingButtonAccessibilityTest.xml) | 8 tests, 0 failures/errors/skipped; LF SHA `76b3ba236149263ce3387adc9bfd8359362ef63c6b207c4359fca66bbf0b2c06` |
| [SelectedControls XML](../test-results/audit-update-loading-final-green-20261001-junit/TEST-com.yuldash.app.SelectedControlsAccessibilityTest.xml) | 8 tests, 0 failures/errors/skipped; LF SHA `6597c5b90af86f9ab21b98a3bc3bb456758365cb3531acabfac8a24f3ea04401` |
| [UiKitButton XML](../test-results/audit-update-loading-final-green-20261001-junit/TEST-com.yuldash.app.UiKitButtonTest.xml) | 10 tests, 0 failures/errors/skipped; LF SHA `cdbc6808db7565887d35c903597fb02fb2657c048bc311932f548ac421fde612` |
| [Финальная сборка](../test-results/audit-update-loading-final-green-20261001.log) | `BUILD SUCCESSFUL in 1m 6s`; debug и debugAndroidTest; JVM **6+8+8+10=32/32**. Повтор усилил критерии и интеграцию, не добавляет ещё 32 уникальных случая |
| [Финальный device log](../test-results/audit-update-final-device-20261001.log) | `OK (8 tests)`, `Time: 32.69`; по каждому status 0: 4 update normal/large, 2 update large без ссылки и 2 loading; без подмены компонента |

Команды root воспроизводимы из `android/` после проверки замков, свободного serial и JBR из AGENTS; они здесь не выполнялись:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests com.yuldash.app.LoadingButtonAccessibilityTest --tests com.yuldash.app.ForceUpdateAccessibilityTest --tests com.yuldash.app.UiKitButtonTest --tests com.yuldash.app.SelectedControlsAccessibilityTest :app:assembleDebug :app:assembleDebugAndroidTest '-PstorageAuditRunner=com.yuldash.app.StorageAuditRunner' --no-daemon
adb -s emulator-5580 shell am instrument -w -r -e class com.yuldash.app.UpdateAndLoadingInstrumentedTest com.yuldash.app.test/com.yuldash.app.StorageAuditRunner
```

Стенд по сохранённому environment: Windows, emulator-5580/API35, 1080×2340px, density440dpi, системный font_scale2, fallback locale en-US, без active network; `ComponentActivity`/обычный Application через `StorageAuditRunner`. Язык задаётся LocalAppLanguage, тема — YuldashTheme/ThemePrefs с восстановлением, fontScale ForceUpdate — явно 1/2. Реальный сервер, БД, MapKit, FCM, магазин и завершение процесса не проверялись. TestBaseUrl — loopback:1, данные синтетические и не персональные. ForceUpdate viewport 320×568dp, сохранённый PNG 880×1562px: коэффициент `440/160=2.75`, размеры `320×2.75` и `568×2.75`.

### DESIGN-010: имя, роль и полный цикл кнопки

Финальный JVM-тест теперь начинает с idle, нажимает настоящую AppButton и через её callback меняет loading. Для каждого из 4 стилей × RU/BA проверены доступное имя, **Role.Button**, disabled, размер не меньше 48dp, единственный progress-индикатор и отсутствие второго callback от touch во время loading. После возвращения в idle проверены enabled, исходный Text, отсутствие `ContentDescription` (название не дублируется), исчезновение progress и второй реальный click. Это результат утверждений настоящего компонента, не отдельной модели состояния.

Device-тест выполняет этот переход для набора 4 стилей, сохраняет состояние loading. Независимо прочитаны [RU JSON](../test-results/audit-update-final-device-20261001-images/audit-loading-ru-light.json) и [BA JSON](../test-results/audit-update-final-device-20261001-images/audit-loading-ba-dark.json): у каждого стиля записаны ожидаемое имя, `Button`, `disabled=true`. Независимо просмотрены [RU/light PNG](../test-results/audit-update-final-device-20261001-images/audit-loading-ru-light.png) и [BA/dark PNG](../test-results/audit-update-final-device-20261001-images/audit-loading-ba-dark.png). Стенд теперь задаёт CanonBg, прежний белый фон тёмного loading-кадра R9 устранён в стенде.

**Приёмка DESIGN-010:** минимальный фикс общего AppButton принят по этим критериям. JSON сохраняет проверенные semantic properties, **не полное merged/unmerged tree**. Голосовое чтение, порядок фокуса TalkBack, анимация и реальный сетевой путь отдельной формы этими результатами не подтверждены. Контраст всех disabled-индикаторов здесь отдельно не измерялся. Custom-кнопки Login/AppReview не входят в эту приёмку.

### DESIGN-011: окончательное положение кнопки и пустая ссылка

Финальный инструментальный тест сначала проверяет каждый Text: полный paragraph-height, `!didExceedMaxLines`, конец последней строки и ширину относительно доступного constraint. Затем прокручивает **родительскую merged Button**, проверяет `getUnclippedBoundsInRoot`, её размер/положение и сохраняет `*-final` уже после этой операции. Независимо прочитаны все 6 final JSON и просмотрены соответствующие 6 PNG:

| Случай / финальный кадр | Фактическая геометрия и наблюдение |
|---|---|
| [RU/light, font1](../test-results/audit-update-final-device-20261001-images/audit-update-ru-light-font1.0-final.png) / [BA/dark, font1](../test-results/audit-update-final-device-20261001-images/audit-update-ba-dark-font1.0-final.png) | Полные заголовок, описание и кнопка; короткий контент центрирован. Action top412.36365 / bottom466.54544dp |
| [RU/light, font2](../test-results/audit-update-final-device-20261001-images/audit-update-ru-light-font2.0-final.png) | Body888/888px, CTA194/194px; финальная кнопка целиком, включая нижнюю закруглённую грань. Unclipped action top457.45456 / bottom544dp |
| [BA/dark, font2](../test-results/audit-update-final-device-20261001-images/audit-update-ba-dark-font2.0-final.png) | Body666/666px, CTA194/194px; те же полные границы кнопки. Unclipped action top457.45456 / bottom544dp |
| [RU/light, font2, пустая ссылка](../test-results/audit-update-final-device-20261001-images/audit-update-ru-light-font2.0-no-store-final.png) | Fallback Text369px/3 строки; top409.81818 / bottom544dp, весь текст доступен |
| [BA/dark, font2, пустая ссылка](../test-results/audit-update-final-device-20261001-images/audit-update-ba-dark-font2.0-no-store-final.png) | Fallback Text492px/4 строки; top365.0909 / bottom544dp, весь текст доступен |

Для large CTA нижний запас вычислен из final JSON: `568−544=24dp`. Верхние части длинного содержания уходят за верх viewport при прокрутке к CTA — это ожидаемое положение длинного прокручиваемого экрана; предыдущие шаги и Text assertions проверяют их полную высоту/достижимость. На final PNG отсутствует прежний срез нижней грани кнопки из промежуточного R9 step2.

Normal-font `hasVisualOverflow=true` в сыром TextLayoutResult сохранён; его прежняя ложная интерпретация не превращена в дефект. Полный видимый заголовок независимо подтверждён PNG, а width теперь сравнивается с доступным constraint704px, где paragraph/right помещаются. Проверки высоты, количества строк и конца текста сохранены строгими.

**Приёмка DESIGN-011:** исправление layout/scroll и достижимость целой CTA приняты для этих 320×568dp/языковых пар/font1–2, включая device emptyURL. Запрос минимальной версии и переход YuldashApp→ForceUpdate, обработка controlled ACTION_VIEW failure, реальный магазин, system bars полного root, более широкий экран и полный набор RU/BA×light/dark не приняты. Для этих пунктов нужны отдельные актуальные доказательства; я не могу их подтвердить.

Передача R10: R9 пробелы полного loading-перехода и окончательного кадра закрыты **новыми** тестами/артефактами. Общий B09 остаётся частичным. DESIGN-006…009 ожидают воспроизведения на уровне UI и исправления владельцем; R4-C/D, остальные экраны, TalkBack и физический телефон остаются по реестру. Этот агент изменил только данный документ, не держит сборку/устройство и остаётся доступным для повторной независимой проверки следующих изменений.

## DESIGN-SCAN-R11-20261001 — «Мои данные»: состояния, выгрузка и документы

Дата / ответственный: 01.10.2026, 09:53 МСК, независимый `/root/design_continue`; B01/B09. Branch/HEAD прежние, локальные изменения root сохранены. `MyDataScreen.kt` **1–633 прочитан целиком**, SHA256(LF) `86105cbaedc9b07e12bd44adeb9bc72a38466014d5532bb8f00b36079d27de47`, совпал с первоначальным перечнем. Разобраны `MyDataScreen`, внутренний load, обе корутины действий, все when/if/ошибки, `DataRow`, `LocationUsageCard`, `LocationDataRow`, `DriverDocsCard`, `FadeInCard`, count/selfDelete helpers, file/share helpers. Исходники не изменялись.

Маршрут прочитан по местам вызова, без объявления выполненного пользовательского пути: ProfileScreen917–931 показывает строку только вошедшему; `HomeScreen` передаёт callback в ProfileScreen (`YuldashApp.kt`2429); `AppNavHome.kt`64–86 задаёт настоящий переход `screen=Screen.MyData`; ветка `YuldashApp.kt`1400 рисует экран и передаёт goBack. Существование промежуточной вынесенной HomeRoute проверено; default пустой callback в сигнатуре сам по себе **не является** недостижимостью экрана. Back/вход/восстановление процесса runtime ещё нужны.

Связи с сервером прочитаны: `data/ApiClient.kt`547–586 — auth GET `/me/data`, POST `/me/driver-docs/delete`, auth GET `/me/export?lang=…`; `backend/app/routers/auth.py`900–1078 — счётчики собственного uid, retention из cleanup, читаемая двуязычная выгрузка, limit300 на раздел, постоянное имя `yuldash-my-data.txt`; `routers/drivers.py`346–380 — собственный профиль, отказ404/409, очистка документов/верификации и commit. Прочитанное `auth.py` не означает проверку прав другим аккаунтом или исполнение этих запросов.

Прочитанные тесты и предел их доказательств:

| Файл / LF SHA / объём | Что действительно утверждает тестовый код | Что он не доказывает |
|---|---|---|
| `AuditBe29MyDataLocationTest.kt`, `7a9e108264042bfc62ef87fac1f02ca2e1079208e23a34bd3dfefa4f720e4147`, 1–170 целиком | Настоящий MyDataScreen + ApiClient/MockWebServer; RU/BA разделяют live, route и SOS; третий тест проверяет defaults DTO. GET/path assertions есть | Стенд w411dp-h2600dp/API34 проверяет текст в большой высоте; не узкий экран/font2, пользовательский вход из Profile, export/delete/401/5xx/process death/телефон |
| `backend/tests/test_export_my_data.py`, `a60cd79b049098e7740d9c5c11df998bffa257f5f1a5bb4cc61a008620aa64c3`, 1–109 целиком | 7 функций: читаемый text/filename, свои профиль/поездки/сообщения, отсутствие чужих поездок/сообщений, BA, описание, отсутствие auth | Реальное создание/чтение файла Android, FileProvider/chooser, реальные300+records, нераспространение всех иных видов данных и производительность. Условный assert имени при отсутствии `name` сам не подтверждает поле |
| `backend/tests/test_driver_docs_can_be_deleted.py`, `62279d9f0b8c4a0ac79218c7f16c75c308f4eeba77db8f8dbe0c9d2792009ba1`, 1–97 целиком | 6 функций: состояние/badge/gender после удаления, запрет на линии/проверке,404,повтор, иной аккаунт со своей auth | Удаление реального бинарного файла: helper задаёт URL, но файл на диске не создаёт. UI подтверждение/ошибки/двойное действие и Postgres-конкуренция отдельно |

Эти тесты **этим проходом не запускались**, актуальный PASS не присвоен. `rg` по оставшимся backend tests нашёл проверки счётчиков/retention/BE29; без чтения их полного кода и текущего исполнения они не включены в число принятых проверок.

| Функция → пользовательский шаг → запрос/изменение | Разобранные ветви | Конкретный незакрытый критерий |
|---|---|---|
| load95–99 → вход в экран → GET `/me/data` → отображение данных | Начальные loading; error+настоящий retry; успешные нули являются валидными данными, не пустой экран | Profile→tap→GET, 500→error→retry→200; 401/повтор/Back/late response/смена аккаунта; сохранение правильного HomeTab после Back |
| DataRow388–405 → счётчики/сроки | CanonSurface/Mint/Text/Muted/Green2, weight у title/note, unweighted value; selfDeleteText605–610 использует server days | RU/BA ×320dp/font2: длинный value и большой счётчик не отнимают всю ширину title; настоящий TextLayoutResult/PNG. Нулевой/отсутствующий срок основных счётчиков не должен обещать заведомо не проверенное «через0дней»; нужно согласовать контракт значения с сервером |
| LocationUsageCard413–504 → live/routes/SOS | Отдельные назначения/сроки; отрицательные значения clamp0; live archive true/false; days>0/else; длинные данные вертикально | Обе ветви live archive и days0/positive, zero/max counts/openSOS; font2/BA/320/тема, порядок чтения и реальное сравнение с server retention |
| DriverDocsCard535–584 → card/removable → dialog316–383 → POSTdelete → reloadGET | При count0 карточки нет; removablefalse пояснение; запрос блокирует dismiss/buttons, успех закрывает/reloads, failure сохраняет dialog+docsError | Настоящий click→Cancel безPOST;Confirm→200→reload и карточка исчезла;409online/pending/404/5xx сохраняют объяснение/повтор;lost response/retry не утверждает двойное удаление. На сервере профиль/badge изменяются только владельцу |
| export257–295 → click→GETexport→write619–624→share626–633 | AppButton Secondary/loading/disabled; IO отдельно; write failure→двуязычное сообщение; GETfailure→error; chooser ACTION_SEND text/plain с READ grant | Синтетический Unicode/BA dump записан ровно; URI читается допустимым получателем; create/write/FileProvider/launch failure понятны пользователю; retry/repeatedtap/Back/accountchange/lateresponse; после отмены/выхода нет необоснованного старого файла |
| FadeInCard588–596/Scaffold/LazyColumn | Есть entry fade/slide, CanonMotion, padding от Scaffold и bottom24; декларативная прокрутка | Фактические system bars/клавиатура при dialog, focus/TalkBack, плавность измерением и process death. Наличие LazyColumn/анимации в коде не доказывает эти свойства |

### R11-A — выгрузка: отказ системного окна и жизненный цикл личного файла

Кандидаты переданы root без нового DESIGN-ID: до runtime-пробы и проверки принятого lifecycle-правила не объявлена подтверждённая утечка или crash пользователя.

1. **Контролируемый отказ chooser.** В `shareMyDataFile`626–633 `startActivity` находится вне runCatching, а вызов270 — в success-ветке корутины после `exporting=false`. Внешняя операция с возможным отказом не имеет собственной ветви UI-ошибки. Критерий: настоящий MyDataScreen, synthetic GETdata и GETexport200, разрешённая локальная подмена только Activity launcher, которая бросает контролируемую SecurityException/ActivityNotFoundException. Проверить отсутствие uncaught, понятный RU/BA exportError и доступный повтор без потери остального экрана. Успешный chooser/отмена пользователем — соседние случаи. Такой отказ ещё не воспроизведён; успешный GET не доказывает успешную выдачу файла.
2. **Cache и смена аккаунта.** `writeMyDataFile` создаёт личный текст в `cacheDir/shared/`; actual backend всегда возвращает постоянный filename. `ApiClient.kt`466–527 очистку token/prefs/stores/voice содержит, очистку shared в этих функциях — нет. Targeted `rg` по main/test нашёл writer MyData/MyStats, но не удаление shared. Это доказательство прочитанного локального механизма, **не** доказательство доступа другого приложения без grant и не гарантия отсутствия иных lifecycle-обработчиков. FileProvider `exported=false`, `grantUriPermissions=true` (Manifest96–104), [file_paths.xml](../android/app/src/main/res/xml/file_paths.xml)1–5 ограничен `shared/`, SHA `9ed45ca88dbdfa4705a4c5a797aa43fc2c3e224d03fddbaff5d90151b931baef`.
3. **Точный lifecycle probe.** Synthetic аккаунт A→настоящий export click→доказать содержимое собственного файла→logout/reset→аккаунтB: проверить существование/доступность прежнего локального текста и прежнего URI по принятому правилу. Отдельно задержать IO после полученного200, выйти/сменить аккаунт, отпустить запись: прежний пользователь не должен незаметно создать/отправить файл в новой сессии. Не копировать реальные номера/сообщения; внешнюю сознательно сохранённую получателем копию не путать с кешем приложения. Сначала определить, что именно должно сохраняться при продолжающемся пользовательском share, чтобы исправление не ломало уже разрешённую передачу.

Имя файла из JSON используется клиентом без собственной проверки; но endpoint1078 возвращает фиксированное имя. Поступление traversal filename от действующего сервера **не подтверждено**; локальный corrupted-response тест возможен, новый remote-exploit не заявляется. Кеш удаляется системой по необходимости, но точный срок удаления и очистку после logout этим комментарием619 не подтвердить.

Передача R11: новый полный semantic-read одного собственного UI-файла и трёх test-файлов зафиксирован отдельно от исполнения. DESIGN-010/011 остаются принятыми в R10; новых source изменений нет. Root получил lifecycle/launcher/layout критерии, ближайшее исполнение определяет реестр и B01. Следующий доступный независимый участок — соседний экран способа расчёта/его состояния и настоящие переходы; source/Gradle/adb остаются у root. Замков/фоновых процессов агента нет.

## DESIGN-SCAN-R12-20261001 — способ расчёта и вход из активного заказа

Дата / ответственный: 01.10.2026, `/root/design_continue`, B03/B07/B09. `PaymentMethodsScreen.kt` **1–458 прочитан целиком**, SHA256(LF) `291ad9f1d3c57dfdeb35d866e6ddf934e0021993574e5c8039fe7440ec42e409`, совпадает с первоначальным перечнем. Разобраны PayMethods constants/title/short/icon, весь PaymentMethodsScreen и callbacks/локальные состояния, PaySectionTitle/Divider/Note, PayMethodRow/FailedRow/SoonRow. Ветки выбора до заказа и в активном заказе разделены явно; действующая договорённость не является проведением платежа.

Необходимые правила прочитаны из `decisions.md`4538–4564 и5137–5153: выбор видят обе стороны, смена разрешена до завершения, галочка после ответа, вход из суммы активной поездки. `lessons.md`8206–8230 объясняет историческую проблему fixture и влияния общей БД; это причина не засчитывать прежний результат как текущий и не запускать тесты на общих данных без владельца. Архитектура3139–3150/4015–4022 прочитана по связи.

Прочитаны связанные места: `AppNavHome.kt`146–147 → открытие выбора перед заказом; `YuldashApp.kt`1403–1413 → activeOrderId из NavSignals/current/prefs callback и1436 → Settings; `InstantOrderScreen.kt`1649–1675 → activeTaxiTrip signal,4907–4960 → driver видит текущий method/«Понял»,699–744 → promo-сумма; `TaxiTripScreen.kt`123–164/198–284/394–595/873–921 → полноценная/компактная карточки и вход из суммы; `data/ApiClient.kt`2673–2682 → set/ack; `backend/app/routers/instant.py`1439–1517 → allowed method, owner/status gate, запись, changed/ack, уведомление. **Эти огромные UI/backend файлы не заявляются целиком прочитанными** данным узким проходом. Версии: TaxiTrip1531 строк SHA `6c1bd07caa5e496b339c6f547b920b8fe15051c13726aabaf771d4804aaa2c8c`; InstantOrder7913 строк SHA `7c5d3fc91554f91728c969476360d500ee442b1d9fea5969f0c658f1c4ab6b81`.

| Функция / существенное состояние | Разобрано | Незакрытое доказательство |
|---|---|---|
| выбрать131–147, activeOrderId<=0 | Настоящий onPick без HTTP: preference будущего заказа | Click всех трёх методов→selected/prefs→create POST содержит выбранный method; unknown сохранённый method не выдаётся как доступный CARD/CORPORATE |
| выбрать, activeOrderId>0 | Guard при pending; все rows disabled; spinner у отправляемой; success вызывает onPick, failure оставляет previous и retry | Реальный delayed POST/click/doubletap→ровно один запрос, previous selected до200, обновление current/prefs после200;500/409/401/networkfailure→понятная ошибка/доступный retry, payload/все существенные побочные действия |
| PayMethodRow320–380 | CanonTaxiBg/CanonTaxi/CanonTaxiInk/Canon tokens; check имеет RU/BA CD «Выбрано»; title1line/subtitle2line с Ellipsis; min64dp | Реальное merged tree при selection/pending и TalkBack RU/BA. Отсутствие `selected` не объявлено отдельным defect: выбранность уже выражена описанием, необходимо проверить объединение/озвучивание.320/font2: названия способов/смысл не обрезаны |
| PayFailedRow389–430 | CanonDangerBg, явный retry min48dp, error text max3line; weighted text соседствует с unweighted retry |320/font2/BA: пояснение и «Повторить» помещаются/доступны. Lost response после записи — отдельный критерий ниже, не приравнивать failure к отсутствию записи |
| PaySoonRow433–458 / wanted | CARD/CORPORATE нажимаются только как пожелание; current не меняется, Analytics события | Не создают оплату/заказ/привязку карты; обещание записи/последующего сообщения согласовано с реальным механизмом Analytics/уведомлений. Не проверено на настоящем Firebase |
| payMethod above screens | Disk ключ очищается по SessionKeys; значение root хранится в remember без owner key | Реальный переход A→logout→B в той же композиции: UI/будущий заказB не получают habitA. Стирание prefs не доказывает сброс живого remember; нужен runtime repro |
| Passenger price→PaymentMethods→POST→DriverPayMethodCard→ack→Passenger | Части пути существуют, promo-ветка отличается | Совместный синтетический путь двух участников с сервером/изолированной БД, late response после смены order, ack/type/status; DESIGN-012 ниже. FCM/настоящий GPS отдельные |

### DESIGN-012 — промокод удаляет вход к смене способа расчёта из суммы поездки

Экран / состояние: `TaxiTripScreen`, активный passenger order со скидкой (`InstantOrderDto.hasPromoDiscount` означает `promoDiscountKop>0`), подробное состояние шторки с «К оплате водителю». Нарушенное правило — действующая навигация кнопок/меню и решение5137–5153: эта строка открывает смену договорённости во время поездки. Подтверждение **однозначное по коду**, UI RED пока не исполнен.

Доказательство: `TaxiTripScreen.kt`267 всегда вызывает `TripPriceRow(order, onOpenPayments)` в body. В `TripPriceRow`873–877 ветка `if (order.hasPromoDiscount)` вызывает `TaxiPromoPayRow(order, forDriver=false)` и немедленно return. Callback больше не используется. `TaxiPromoPayRow` (`InstantOrderScreen.kt`699–744) имеет только order/forDriver/modifier, Surface без onClick, внутри нет navigation/callback. У обычного заказа нижняя ветка TripPriceRow использует `Surface(onClick=onOpenPayments)`, отображает PayMethods.title и стрелку с двуязычным «Сменить способ». В promo-ветке пользователь видит сумму/скидку, но тап этого блока ничего не открывает, текущий способ в нём отсутствует.

Граница вывода: пропадает **конкретно вход из суммы подробной promo-поездки**, не объявляется недостижимость PaymentMethods всеми путями. Компактный `TripCompactPaymentSafety`517–560 тоже не имеет payment callback, но это другое состояние: его наличие не восстанавливает потерянный вход подробной ветки. Settings/другие маршруты не заменяют предусмотренный tap во время активного заказа.

Влияние: скидка меняет доступность важного пользовательского действия; пассажир, открыв сумму для смены наличных/СБП, остаётся в прежнем состоянии без обратной связи. Деньги не списываются самим тапом; несогласованность оплаты двух участников этим чтением ещё не воспроизведена.

Критерий RED владельцу передан: настоящий TaxiTripScreen, synthetic order/statusonboard и `promoDiscountKop>0`, `enableLiveTracking=false`, mapContent как разрешённая подмена только внешней карты, реальный tap по «К оплате водителю…» после раскрытия/прокрутки → ожидается единственный onOpenPayments и открытие выбора с этим activeOrderId. Без скидки — соседний контроль. После перехода выбрать другой method→POST200→driver payload/ack — отдельное продолжение server-path, не подменять сразу готовым PaymentMethodsScreen. Проверить RU/BA,320/font2 и сохранение всех цен/скидки.

Минимальный кандидат: сохранить promo-суммы/объяснение и провести callback именно пассажирского активного блока; показать понятный текущий способ/признак изменения, переиспользуя PayMethods/appText/Canon. Другие потребители TaxiPromoPayRow (driver/чек) не должны внезапно стать кнопками смены. Исходники исправляет root, здесь не менялись. После исправления нужна независимая приёмка diff и actual click RED/GREEN/PNG; DESIGN-012 ещё не считается исправленным.

### R12-A — неоднозначный отказ, ответ сервера и пожелание «скоро»

- `setInstantPaymentMethod` возвращает **фактический** method из JSON, однако выбрать139 использует только isSuccess, затем onPick(requested). Backend1474 нормализует неизвестный/закрытый метод к negotiate. Current UI отправляет только три открытых константы, поэтому расхождение на действующем normal path не подтверждено. Конкретный contract probe: ответ200 содержит другое разрешённое server method → UI/prefs согласованы с ответом, не просто желанием; malformed/missing200 не утверждает подтверждённый метод без принятого контракта.
- PayFailedRow говорит «не дошло до сервера / водитель ждёт прежнего». Потеря ответа после `session.commit()`1485 может дать failure клиенту при уже изменённом заказе; этот сценарий не исполнялся. Критерий: настоящий synthetic server сохраняет сбп, затем соединение/ответ теряется; клиент не утверждает недоказанное состояние водителя, повтор/refresh согласует обе стороны без второй операции/уведомления. Для2одновременных способов и late ack нужна PostgreSQL-конкурентная проверка, простое чтение `changed:false` не доказывает идемпотентность гонки.
- `Analytics.kt`1–26 прочитан целиком, LF SHA `569f0e977481432dfe594f39cb8a939a694d356f1818e6bb63a14e4cc1f70a8d`: fa null→return, logEvent в runCatching; никакая запись подписки в этом helper не выполняется. В PaymentMethods240/250 только Analytics.log+wanted; текст261 обещает «Записали. Сообщим…». Targeted rg по Android/backend для двух событий нашёл только эти два вызова. Реальную внешнюю Firebase-автоматизацию/рассылку этим поиском **не подтвердить**; не объявлена новая ошибка сервера. Нужен разрешённый stand-in failure/no-Firebase probe и подтверждение продуктового механизма, прежде чем менять обещание пользователю.

### Существующие тесты: прочитанное и выполненное разделены

| Полностью прочитанный тест / SHA256(LF) | Реальный объём его assertions |
|---|---|
| PaymentAndMappingTest58 строк, `b871591133b16eb2931a97e6cec6d40b09307a2aa90341b1358e650be557ba8b` | 5 pure helpers sberPayLink/rideTypeMeta. Несмотря на имя Payment, не тестирует PaymentMethodsScreen/выбор/POST |
| backend test_payment_method123 строки, `2f52ab776e95c817af9cb4bc2276793024a72225a855842afbf10b5846845a87` | 6 функций: create method/default/disabledCARD, настоящие accept→arrived→onboard→change и чтение водителем, запрет послеdone и чужим аккаунтом; нет Android tap/потери ответа/конкуренции |
| backend test_payment_seen_by_driver155 строк, `264e45b90e96e95767f119868320c16bccdac80a06e61ea393774311536263eb` | 4 функции overdue/driver-none/30–90s/ack и payload; локально задают timestamps напрямую, не проходят реальный payment→ack HTTP путь |
| TaxiPassengerJourneyTest161 строк, `a391b4e4d4da246ef01a31dd799223cc0614c47acc5dfd564a44ea77db338bd3` | 2 Compose-пути создания из адреса/HTTP503 retry и fake driver statuses→rate/receipt; mapping/payment_methodcash проверяются, выбора метода/промоскидки/перехода из суммы нет |
| TaxiTripAdaptiveGuardTest69 строк, `2a67b8e284f85b00e3c912439432fa252b999c677a41f54df3b4839e0ef817aa` | 4 проверки **наличия source-строк**. Не исполняют Compose, не измеряют bounds/tap/карту и не ловят потерю callback promo-ветки |

Все эти тесты здесь только прочитаны; новых PASS нет. Поиск прямых `PaymentMethodsScreen`/`PayMethodRow` test refs ничего не нашёл; это ограниченный поиск по имени, а не доказательство отсутствия всех косвенных сценариев. Числа функций выше вычислены из реально прочитанных `@Test`/`def test`, не покрытие приложения.

Передача R12: source458/hash и связанная потеря callback DESIGN-012 переданы root с точным критерием. Неподтверждённые R12-A/lifecycle/layout случаи не названы новыми подтверждёнными дефектами. Единственный изменённый файл агента — этот документ, никаких новых device/server/DB проверок, замков или процессов. Следующий независимый source-участок определяется полнотой таблицы96 и очередью реестра; сейчас B01 и исходники остаются у root.

## DESIGN-SCAN-R13-20261001 — доверие, приглашения и согласия

Дата / независимый ответственный: 01.10.2026, `/root/design_continue`, B01/B08/B09. `TrustScreens.kt` **1–769 прочитан целиком**, SHA256(LF) `ba14a3f920bd5f052825fd98231b54d439282018ccfa0a4d86aba3e9c4f93d1c`, совпадает с первоначальным перечнем. Разобраны все 3 экрана и их вспомогательные функции: reload/API/callback/errors, уровни/следующий шаг, ввод/create/redeem/share, grant/document/date. Пользовательские тексты проверялись в обеих существующих ветвях appText, новые переводы не добавлялись.

Целиком прочитаны необходимые связанные `backend/app/routers/trust.py`1–163, LF SHA `e4cb7314d48cd82c81b92089a53e4f0a2c4e37a9f7e1ffd23914134838a76e27`, и `backend/app/trust_service.py`1–218, LF SHA `c48cf8fca29bfc14b909ab6a8585488d7f823a3017d9771524d3c9388bf6f84c`. API methods в `data/ApiClient.kt`1087–1145 и Consent model1662–1670 прочитаны по месту, весь ApiClient/models не заявляется разобранным. Исторические правила: `architecture.md`1811–1814/2080–2084 и lessons о различии server consent/local flag, времени и реальном UI. Из чтения не следует юридическая оценка законности обработки или содержание внешних документов; реальные terms/privacy страницы не открывались.

Маршруты по коду: AppNavHome196/198 открывают Trust/Consents через защищённую навигацию; Settings→Consents1435; YuldashApp1610–1618 — Trust с Invites/Consents/Profile/Verify callbacks и самостоятельные Invites/Consents. Реальный путь guest→login→исходный экран ещё не выполнялся этим агентом.

| Функция → действие → сервер / результат | Существенные разобранные варианты | Незакрытые критерии |
|---|---|---|
| TrustScreen96–185 →GET `/me/trust` | initial loading/error/retry/data; d.next1→Profile,2→Verify,elseInvites;canInvite callout и постоянные Invites/Consents | Настоящие taps на каждом уровне/возврат после profile/admin verification, auth restoration; refresh/error с прежними данными ниже |
| TrustLevelCard190–224 / Ladder228–260 / Next274–295 | L0 объяснение;L3 GoldInk;benefits пустые/непустые;4 сегмента animate;nextnull/action по уровню | 320/font2/RU/BA: каждый title/benefit/action помещается, 4 labels не отрезаны maxLines2; фактическая палитра/порядок чтения/нажатия/анимация; malformed level/title отдельно |
| Invites324–535 →GETtrust→GETmine, POSTcreate/redeem | trustfail initial error;insider vs input;canInvite vs объяснение;код trim/uppercase/take12;blank callbackreturn;redeem success/msg;create error сохраняется | DESIGN-013; неактивная кнопка при blank input/доступное имя поля после ввода/IME;Unicode/длинный код;doubletap/retry после потери ответа;back/accountchange;сырые серверные ошибки RU/BA |
| InviteCodeRow537–564 →share chooser | used<=0 скрывает share,active→bounceClick;inv.code Lazy item key;title/status;chooserrunCatching | Начальный active→другой участник redeemed→refresh→used, отказ chooser не молчит;48dp/fullcode/BA/font2;семантика Share icon+Text не повторяется. Без внешнего share call это не приёмка отправки приглашения |
| Consents574–671 →GETconsents→POSTkind→reloadGET | initial loading/error/retry,saveErrornotice;offer/privacy/geo;grantedAtnull→AppButton,nonnull→date/check; document link при2kind | Настоящие click→POST→authoritative grantedAt→UI;postfail retry/repeatedtap;POSTsuccess затем GETfailure;разные kind одновременно;refresh/accountswap/Back/processdeath;пользователь видит actual server result |
| ConsentRow682–752 / prettyDate761–769 | Column действий не сжимает title справа;Canon tokens/заголовки;documentmin48;Buttonmin48/spinner;parseISO→deviceCalendar |320/font2/BA/light/dark/keyboard/systembars;TalkBack name/state/date;UTC/offset/naiveISO→local date при переходе через полночь. Историческое исправление даты не означает текущий timezone PASS |

### DESIGN-013 — отказ загрузки собственных приглашений показан как отсутствие кодов

Экран / состояние: `InvitesScreen`, успешный GET `/me/trust` с canInvite=true, последующий GET `/invites/mine`500/networkfailure/403; либо POSTcreate200 и отказ последующего списка. Правило AGENTS §4.5: loading/empty/error — разные состояния, отказ требует понятного текста и повторного действия.

Доказательство кода: invites332 стартует `emptyList`; trust success349–352 ставит loading=false и зовёт `getMyInvites().onSuccess { invites=it }` **без** onFailure/отдельного состояния списка. Затем474–480 `invites.isEmpty()` рисует «Пока нет кодов. Создай первый…». Таким образом unsuccessful list и успешный empty200 неразличимы. POSTcreate496–497 игнорирует возвращённый InviteDto и лишь reload++; если следующий GETmine падает, новый созданный код пользователю не предъявляется. Если прежний список непустой, он остаётся без признака свежести/ошибки.

Влияние: пользователь получает ложную пустую картину вместо своих действующих кодов; после успешного создания может нажать «Создать» ещё раз, потому что не видит результат. Backend имеет quota5, поэтому повтор может потратить следующие разрешённые коды; фактическое число повторных create этим чтением не измерялось. Не объявляется утечка чужих кодов или server quota bypass.

Критерий воспроизведения root: настоящий InvitesScreen+ApiClient/локальный synthetic server: trust200 canInvite=true, mine500→ожидаются error/retry собственного списка, **не утверждение empty**;retry без POSTcreate→mine200 `[{"code":"LOCAL1","uses_left":1,…}]`→код показан. Соседний mine200[]→настоящая empty подсказка. Второй сценарий POSTcreate200 сLOCAL2→reloadGETmine500: успешный код/смысл результата не теряется и не требуется создавать ещё один;retry200→тот жеLOCAL2. Проверитьrequestcounts,existingcodes/stale,403/401/Back,RU/BA и320/font2. Реальный Firebase/приглашение рабочего человека не используется.

Минимальный кандидат: самостоятельные состояния loading/error списка и доступный retry, сохранить прежние данные с явной свежестью; учитывать authoritative InviteDto успешного create вместо бесследной потери результата при следующем GETfailure. Использовать существующие AppError/AppStale/Canon и appText; новые BA записывает владелец вtasks. UI RED и исправление пока не выполнены; DESIGN-013 остаётся открытым, source не изменялся.

### R13-A — refresh/согласия/доступность: точные непринятые варианты

- **Trust refresh.** `loading=data==null`109 и `refreshing=loading&&data!=null`123 не дают индикатору статьtrue при сохранённом d. Error144 показывается только при dnull; иначе отказ очередного GET скрыт за прежней карточкой. Это однозначное поведение ветвей, но gesture/PNG/временной порядок ещё не воспроизведены. Probe: trustA200→pull gesture→delayedGET→visible progress, затем500→явный stale/retry без потериA→200B. Не менять время ожиданий ради PASS.
- **Consents after POST.** Успешный grant656 устанавливает savingKindnull и reload++, не применяет возвращённый ConsentDto. GET failure при loadedtrue оставляет прежние consents и игнорирует error625; пользователь может снова видеть «Отметить». Probe: начальный GET безgeo→clickgeo→POST200(grantedAtT)→GET500→UI ясно показывает принятие либо незавершённую сверку с retry, не молчит;следующийGET200T→дата/галочка, первое время сервера не меняется.
- **Два разных kind.** savingKind — одно nullableString582;при втором grant первое имя заменяется, completion любого обнуляет весь флаг. Другие AppButton не выключаются. Probe с задержанными offer/privacy, ответами в обратном порядке: соответствующий progress/disabled/action result остаётся связанным со своим запросом, repeatedtouch не создаёт неконтролируемый duplicate. Поведение не объявлено исполненной race; защита server consent ниже отдельно.
- **Документ и Share.** Consent onOpenDoc643–647 и shareCode366–367 используют runCatching без UI onFailure. Доступность сайта/chooser не доказана отсутствием crash. Controlled launcherfailure→понятное объяснение/повтор, обычныйACTION_VIEW/chooser→ожидаемый intent/документ. Внешние страницы не запускались.
- **Ввод/геометрия.** Код имеет только placeholder и uppercase().take(12), пустая кнопка разрешена и callback молча возвращает;системный шрифт/IME/semantic name заполненного поля нуждаются в реальной проверке. `InviteCodeRow` использует локальный RoundedCornerShape14dp и bounceClick;48dp измерять фактическим узлом, не складывать размер и padding как доказательство. Других правок ради субъективного вкуса не предлагается.

### R13-B — серверные критерии согласованности, не пройденные чтением

Полное чтение service/route обнаружило необходимые probes, переданные root. Они относятся к B01/B05 и не добавляют здесь новый подтверждённый DESIGN-ID:

1. **Advertised canInvite при паузе.** `trust_service.py`105–123 `can_invite` отдельно проверяет account_paused и отказывает. `trust_level`81–102 сохраняет base verified L2 даже при замороженном дарованномL3. Но `trust_summary`192 возвращает `level>=MIN_INVITER_LEVEL`, а `routers/trust.py`49–51 gate вызывает can_invite. Для verified пользователя, у которого реальный account_pausedtrue, эти ветви дают несовместимые разрешения: summary обещает invitation, create запрещает. Это не обход gate и не призыв ослабить его. Нужен настоящий GETtrust/POSTcreate на synthetic paused аккаунте, control до/после паузы;обе части используют одно продуктовое правило. UI callout/BG становится проверяемым следствием ответа.
2. **Одновременный grant.** record_consent199–215 делает select-first→insert, при concurrent двух запросах нет здесь owner/kind lock. ModelConsent1662–1670 не задаёт unique пары; целевой поиск миграций нашёл только два nonunique index в p4_trust74–75, полноценная схема фактической БД ещё не исследована этим агентом. Существующий test_trust236–243 выполняет два POST последовательно. Требуется изолированный PostgreSQL RED дваPOST одногоowner/kind одновременно→одна запись/одно grantedAt, rollback и стабильный first time;обычные разныеkind/другойowner controls. Дубли/повреждённое доказательство не объявлены реально воспроизведёнными.
3. **Create/redeem concurrency.** Router читается как quota-select→create и redeem rowlock+conditionalUPDATE; это разные механизмы. Нужны concurrent createquota и дваredeem одного/разныхкод одномуuser с проверкойuses/trust/invitedBy/отката, lostresponse/retry. Наличие `with_for_update` не заменяет PostgreSQL испытание. Акаунты/коды синтетические, внешние сообщения запрещены.

### Прочитанные тесты и ограничения результата R13

`backend/tests/test_consent_on_login.py`1–64 полностью прочитан, LF SHA `2ea2d3dc9e09972fe69782095fd6fbdde13c2b52e4d7a48a0d8796324f45dbd0`: 4 функции исполняют SMS request-code→verify, повтор с неизменным временем, GETconsents и age18POST. Комментарий о всех способах входа не означает, что этот файл проверяет Telegram/store — таких путей его assertions не содержат. Он в этом проходе не запускался; актуальный результат других root auth профилей находится в журнале B01.

Прочитаны **только** consent-раздел `backend/tests/test_trust.py`227–263 и auth/refresh-раздел `ApiClientHardPathsTest.kt`34–130: sequence/idempotency/owner/auth и bearer-refresh assertions; не Consents/Trust/Invites UI. EndpointContract239–240 просмотрен как reference вызовов, не принятие полного контракта. Поиск прямых UI symbols не дал тестов этих экранов; это ограниченный поиск по именам, не отсутствие всех косвенных сценариев. Новых JVM/PG/device PASS, screenshot и TalkBack этого участка нет; я не могу подтвердить их исполнение.

Передача R13: semantic-read файла769 и связей сохранён; DESIGN-013 и конкретные probes сообщены root. Следующий владелец исходников должен сначала получить актуальное UI воспроизведение нужного случая, затем минимальный fix/test/новыеhash/кадры и независимую приёмку. Порядок/текущий статус — только в реестре. Этот агент изменяет только данный документ; сборка/устройство/БД/root source не используются, новых процессов/замков нет.

## DESIGN-SCAN-R14-20261001 — публикация попутки и конфиденциальность адресов

Дата / независимый ответственный: 01.10.2026, `/root/design_continue`, B02/B06/B09 и связанные правила хранения B01. Ветка `audit/full-technical-20260930`, HEAD `6fa0595d88bea4fcd59d4a1e2139c2d128cf6821`; исходники содержат ранее сохранённые локальные изменения других владельцев, агент их не присваивает и не меняет. `CreateRideScreen.kt` **1–1158 прочитан целиком**, включая импорты, валидацию, wrapper, чистую форму, PrivacyScreen и все helpers. LF SHA256 `e61f945748d5e84ee2b059cc8a88023ba9e14fdc4a47fefc37c213c61609f98e`. Разбор не равен новой сборке, screenshot или проверке физического телефона.

Прочитаны необходимые связи: `data/ApiClient.kt`868–927 (publishRide payload/result) и4690–4722 (recent places GET/delete/clear); `backend/app/routers/places.py`159–235 (собственные recent, дедуп/ограничение списка/удаление); `MainActivity.kt`757–776 (нативные date/time dialogs). Большие ApiClient/MainActivity и весь серверный файл здесь не заявляются целиком разобранными. Поведение публикации самого backend/параллельных броней этим фрагментом не доказано.

| Функция → действие → данные / результат | Разобранные состояния | Конкретное остающееся доказательство |
|---|---|---|
| createRideValid274–279 → доступность публикации | Непустые города и price.trim().toIntOrNull в1…100000; date/seats не входят в этот predicate | Пустой/Unicode/пробел/длинный маршрут; границы цены и сидений на настоящей форме/сервере. Успех helper не доказывает принятие server payload |
| CreateRideScreen282–412 → заполнение → publish POST → callback/back | Введённые поля преимущественно rememberSaveable, publishing guard, failure сохраняет ввод, successful id применяется к локальному Ride; optional routes/partners/price hints | Actual guest/login restoration и поездка владельца после публикации; повтор после потери ответа, Back/account swap во время запроса; server/backend effects и другие участники отдельно |
| Form451–821 → адреса/тип/дата/условия/остановки/публикация | LazyColumn;5 типов;hospital partner;4 recurrence;seats/price/fuel hints;map/dropPin;parcel/cargo recipient;до4 остановок;quiet/noMinors/onlyTrusted;error/loading/cancel | RU/BA/light/dark/320/font2/IME/insets; labels/tap/selected tree; реальные map permissions и маршрут. Наличие LazyColumn не доказывает все размеры/достижимость |
| Helpers959–1158 → тип/популярный маршрут/price/fuel/точка посадки | Предложения с ключами;price clickable min interactive size;pickup debounce350ms, loading/error/retry и selected point | Старыe pickup suggestions при изменении города до ответа: нельзя выбрать точку другого города; delayed response/back/смена owner. Фактическая touch area и выбранность измеряются runtime, не только внутренними40dp |
| Privacy824–956 → разрешение геолокации / список / очистка | launcher result пишет sharingEnabled;switchoff false;switchon проверяет FINE или запускает запрос;GETcount;clear confirmation/disabled pending | Runtime deny/revoke/approximate/fine, поздний permission callback после ухода/смены аккаунта и фактическая остановка гео. DESIGN-014 ниже;320/font2 Column без scroll — кандидат на геометрическую проверку |

### DESIGN-014 — отказ очистки недавних адресов не объясняется пользователю

Экран / состояние: PrivacyScreen («Конфиденциальность»), synthetic GET `/places/recent`200 с собственными адресами; пользователь открывает подтверждение и нажимает «Очистить», DELETE `/places/recent` возвращает500/403/timeout. Правило — AGENTS §4.5 и обязательная проверка результата действия: ошибка требует понятного текста и доступного повтора. Это **подтверждённая ветвь кода**, device RED пока не исполнялся.

Доказательство: lines938–942 вызывают `ApiClient.clearRecentPlaces().onSuccess { recentCount=0 }`, затем при любом Result ставят `clearing=false`, `askClear=false`, увеличивают reload. Ветви onFailure/clearError/пояснения нет. Отказ скрывает диалог, затем GET839 тоже обрабатывает только success. Начальный отказ GET оставляет count0 и скрывает сам вход очистки; success-empty и неизвестное состояние не разделены. Backend clear227–235 ограничен собственным user_id и commit; этот факт не заменяет Android error-path.

Влияние: человек не получает ответа, почему его адреса не удалились; при недоступном повторном GET остаётся прежнее представление без свежести. Здесь **не заявляется ложный toast «очищено»** — такого toast код не содержит, и не утверждается физическое удаление данных при failed DELETE. Потеря ответа после server commit — дополнительный отдельный случай: результат следует сверять GET, а не автоматически считать адреса существующими.

Точное воспроизведение владельцу root: настоящий PrivacyScreen и ApiClient с локальным synthetic server, GET200 содержит1 собственный адрес → tap «Очистить…» → tap confirm → DELETE500. Ожидаются ясный RU/BA результат отказа и доступный retry, адрес не выдаётся как доказанно удалённый, cancel не выполняет DELETE. Затем retry DELETE200 + GET200[] → card/count согласованы с сервером. Проверить request counts (один исходный DELETE и один явный retry), двойное нажатие в pending, initial GET500→retry200, delayed GET/DELETE и Back; отдельный сценарий server commit + потеря ответа проверяет сверку без ложного утверждения. Эмулятор/БД этим агентом не использовались.

Минимальное исправление-кандидат: разделить загрузку/ошибку списка и результат clear, сохранить понятный контекст при failure и явный повтор; применять existing AppError/AppStale/Canon/appText. Новые BA заносит владелец в tasks как черновики. После исправления нужны RED/GREEN на actual screen, актуальные LF hashes и RU/BA узкий/font2 кадр, затем независимая приёмка. Исходники здесь не исправлялись; замечание остаётся открытым по единому реестру.

### R14-A — публикация: варианты, которым нужно воспроизведение

1. **Не выбранное время.** defaultTime339 говорит «Сегодня, 18:00»/«Бөгөн,18:00»; onPublish384–387 при пустом или неразбираемом dateTime отправляет now+3h. Локальный callback Ride391 сохраняет defaultTime, не вычисленное отправленное время. В позднее время суток возможен переход на следующую дату. Это явное различие двух представлений, но фактическое отображение после публикации ещё не воспроизведено: обычный путь из Cabinet перезагружает server rides, не обязательно показывает локальный callback. Probe: реальная форма без открытия picker с контролируемыми часами до/после21:00→POST departing_at/сервер→reload/list/detail согласованы по дате/времени; invalid prefill/date и offset/midnight отдельно. Не править продуктовый default без принятого правила.
2. **Остановки и восстановление.** Большинство вручную введённых полей291–321 saveable; `waypoints`309 — только remember, несмотря на комментарий о сохранении введённых человеком полей284–290. Probe: заполнить настоящую форму (города/дата/price/2 Unicode остановки), actual saved-state restoration→все остановки и POST сохраняются; отдельно настоящий process death и owner A→B. Compose restore или Activity recreation не называть process death. Existing restoration tests, просмотренные по месту, не исполняют этот вариант CreateRide.
3. **Нативный picker BA.** Callback368 всегда передаёт `"ru"` в openDateTimePicker; helper создаёт locale context native DatePicker/TimePicker. Probe: приложение BA→реальный picker→системные подписи/кнопки в допустимом языке, итоговое время разбирается одинаково. Наличие/качество BA системного перевода на устройстве не подтверждено; не добавлять зависимость или смену SDK ради гипотезы.
4. **Клиники.** Optional getMedicalPartners314 обрабатывает только success; partners.empty579 рисует «Список клиник загружается…» даже при завершившемся empty200/failedGET. Форма без клиники допустима по комментарию, поэтому недоступность справочника не должна бесконечно выглядеть загрузкой. Runtime probe: выбрать hospital, delayedGET→loading, empty200→понятное empty/продолжение,500→ошибка/retry/optional explanation, successful list→select id передаётся только hospital. Новый отдельный DESIGN-ID до проверки связанного server/product случая не добавлен.
5. **Точка посадки и поздние предложения.** Ручная смена pickup сбрасывает pickupPointId, но соответствие сохранённых lat/lng новому тексту и смене `from` надо подтвердить фактическим payload. Pickup suggestions сохраняют прежние points до debounce/следующего результата. Probe: городA с выбранной точкой→городB/изменённый текст→тап/submit до и после ответа→id/координаты относятся к текущей договорённости, не к прежней. Никакая новая утечка геолокации по этому чтению не заявляется.

Selected RideTypeChip, clinic/pickup chips и текстовые поля ещё требуют актуального semantic-tree/TalkBack. Null contentDescription у декоративной иконки само по себе не ошибка. Минимальную touch target нельзя доказать одним height40dp: учитывается реальная interactive area Compose. Column Privacy без scroll — достаточный критерий для narrow/font2 RED, но не уже измеренное обрезание.

### Прочитанные тесты R14 и проверка их качества

`CreateRidePublishJourneyTest.kt` **1–212 прочитан целиком**, SHA256(LF) `62226a1ee86ae1aa5f43007e6751d18828df48b25387d771ff1d72949038ab4d`.3 функции @Test84/98/116 используют actual CreateRideScreen/native dialogs/API и полный Cabinet путь: entered route/date/seats/price, отказ503 с сохранением/повтором payload, publish→возврат→reload list. Все выбирают конкретную2030 дату в RU; blank date/default mismatch, BA picker, остановки/restore и Privacy error не защищены этими assertions. Assertions даты проверяют chosen local date, не весь выбор time/offset/midnight. Здесь файл не запускался — новых PASS не добавляется.

`backend/tests/test_places.py` **1–301 прочитан целиком**, SHA256(LF) `406dc48a484b243dc0a872ca16da1738a1bc9d7db848175e432f9e827acd2cca`. Тесты проверяют CRUD/upsert/home-work порядок/used/owner/дедуп cap10/удаление одной/всех своих строк/account cleanup; не actual PrivacyScreen и не потерянный ответ. Комментарий «свайп» не означает выполненный жест Android — test делает HTTP DELETE. Проверка cleanup вызывает delete_user_account в Session напрямую, не полный UI/account deletion route.

Проверка исходного AST через stdlib Python (не pytest, БД не открывалась) обнаружила **18 определений тестовых функций на верхнем уровне /12 разных имён**; шесть имён объявлены дважды. Для каждой пары `ast.dump` тела без координат исходника одинаков. При исполнении Python последнее определение заменяет прежнее имя;18 нельзя выдавать за18 независимых собранных/исполненных тестов. Фактический `pytest --collect-only` этим агентом не запускался,12 не заявляется как его полученный результат.

| Повторённое имя | Строки двух определений |
|---|---|
| test_saved_order_home_work_then_used |66,121|
| test_saved_used_foreign_is_404 |92,147|
| test_saved_new_place_is_on_top |103,158|
| test_recent_delete_one |202,244|
| test_recent_delete_foreign_is_404 |215,257|
| test_recent_clear_all_touches_only_me |231,273|

Воспроизводимый read-only способ: прочитать Path файла, `ast.parse`, сгруппировать top-level FunctionDef с name.startswith("test_"), посчитать len определения/группы и сравнить `ast.dump(ast.Module(body=fn.body,type_ignores=[]),include_attributes=False)`. Использован bundled Python `C:/Users/Bayra/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe`; команда не импортировала app/test и не исполняла pytest. Root получил этот результат отдельно от UI defect. Удаление дублированного тестового кода выполняет владелец после оценки необходимости, child ничего не удалял.

Передача R14: narrow semantic-read завершён, DESIGN-014 и конкретные probes отправлены root. Изменён только `docs/audit-design-review.md`; новых Gradle/device/server/PG запусков, screenshots, замков и процессов нет. Следующий технический шаг владельца — actual Privacy GET200→DELETE500 RED с указанными counts/side effects либо приоритетная проверка другого пункта единого реестра. Дизайн-агент остаётся доступным для независимой приёмки исправления. Полный B09 и все96 экранов этим фрагментом не закрыты.

## DESIGN-SCAN-R15-20261001 — статистика, награды и передача открытки

Дата / ответственный: 01.10.2026, `/root/design_continue`, B02/B03/B04/B09, связи хранения/аккаунта B01. Ветка/HEAD прежние, локальные изменения без коммита. `MyStatsScreen.kt` **1–569 прочитан целиком**, LF SHA256 `7d92ca2f615e05e8fdd1e4400c0e6780f521f76761a2414c67a3fc9f24d8ef99`. Разобраны load/retry/statsnull/нулевые данные, карточка/плитки/прогресс/числа и плюралы, обоих языков shareCaption, полный Canvas renderer/write/FileProvider/launch и отдельная секция achievements. **Новый подтверждённый DESIGN-ID не добавлен**; критерии ниже требуют исполнения или принятого правила.

Связи: Profile838 →onMyStats AppNavHome80→Screen.MyStats→YuldashApp1598; actual callback прочитан, defaults без callback не объявлены недостижимым экраном. API3417–3432 отображает `/me/stats`,5110–5129 — `/me/achievements`; DTO7422–7436/7538–7550 прочитаны. `backend/app/routers/stats.py` **1–226 прочитан целиком**, LF SHA `dec59854b8fb352e7349047a6dd6869f5db913047a372e21d2d7015c31dbfac9`: owner-auth, completed booking/ride/instant aggregation, coordinate/geocode fallback с memo внутри запроса, rank boundaries, отдельные savings/co2 коэффициенты и achievements. Принятое правило `decisions.md`4201–4209: такси добавляет trips/km, но не savings/co2. Это оценка экономии, не комиссия/списание денег. Производительность большого объёма истории не измерялась; memo и пакетный SELECT не доказывают p95.

| Функция / действие | Разобрано | Требуемая актуальная проверка |
|---|---|---|
| MyStats94–213 →GET→loading/error/retry/data | initial AppLoading, AppErrorState+retry, нули через rank «Новичок»;profile name remember;LazyColumn | Реальный profile tap/login restoration/back, HTTP500→retry200, denied401, malformed200;RU/BA2темы320/font2/большое имя, числа и единицы. API opt defaults не считать доказательством валидности пустого `{}` |
| StatsShareCard218–299 /StatTile302–324 | Canon shapes;открытка имеет намеренно постоянный собственный градиент и централизованные Stats* colors;простые плитки Canon;3 minitiles | На реальном градиенте проверить точные bounds/контраст мелких labels. Systemfont2 и100+ символов имени не должны скрывать статистику/CTA;начальный viewport и scroll отдельно |
| RankProgress327–361 →rank/nextAt/trips | Null nextAt скрывает;clamp0…1;animation;remaining/plural | Пороги0/1/9/10/29/30/74/75, разныеRU/BAtitles, TalkBack progress/value/длинный heading;NaN/inconsistent negative входы — contract probe |
| Share194–203→Canvas404–476→PNG478–483→chooser485–502 | IO drawing/write защищены runCatching;на failure выбирается text fallback;intent/file grant;фиксированный файл | Actual click→верный bitmap/caption/URI;write failure→правильный text share;launcher failure/двойной click/cancellation/ownerchange ниже. GET200 не доказывает export success |
| Achievements512–569→GETowner→earned/goal/value | При failure/пустом items секция молча не отображается (комментарий явно разрешает optional decoration);earned check/colour,unearned «ЕщёN» | Реальный no-badges/failedGET не ломает основную stats;выбранное продуктовое правило об optional пояснении;earned state понятен без цвета в merged tree/TalkBack;ownerchange. Отсутствие error всей страницы из-за optional decoration не объявлено автоматически дефектом |

### R15-A — share: конкретные непринятые lifecycle/визуальные случаи

- **Смена пользователя / отмена IO.** Share194 запускает scope,196 runCatching охватывает `withContext(IO)`, после его failure запускается text fallback203; guard владельца/active и busy тут не присутствует. `saveSharePng`479–481 использует `cacheDir/shared/my_yuldash.png`. Launcher обеих share-функций вне runCatching. Прочитанный механизм похож на R11 export, root уже ведёт QA-B01-021. Критерий: syntheticA→actual click→контролируемая задержка IO→Back/logout/accountB→releaseIO; никакой поздний chooser/captionA не открывается вB, CancellationException не превращается в fallback share. Отдельно doubleclick→число writes/choosers/URI и одновременная overwrite; controlled launcher SecurityException→понятная ошибка/повтор. **Crash/утечка здесь ещё не воспроизведены**, UI source не менялся.
- **Границы картинки.** Canvas рисует rank/name/km/3 числа одиночным `drawText` без измерения/переноса/ellipsis (424/434–436/445/465–468); размеры1080×1350, pad80, одна minitile `(1080-160-48)/3`. Экранная Compose карточка способна переносить Text, это другой renderer. Probe: actual share с длинным синтетическим башкирским/русским именем и large numbers→сохранённыйPNG+measureText/bounds подтверждают отсутствие выхода за карту/соседнюю плитку и сохранение смысла. До чтения настоящего bitmap дефект обрезания не объявлен измеренным. Не тестировать только screen preview вместо экспортируемого изображения.
- **Два представления.** Compare PNG/caption/screen на0/1/11/21 поездках, rank максимального уровня, decimal km/co2 и1000+/1000000+. Russian caption содержит знак приблизительности savings,BAcaption его не содержит;оба языка страницы объясняют оценку. Нужно проверить понятность actual shared output, не объявлять новую денежную ошибку по одному отличию символа. `fmtInt` зависит от defaultLocale, остальные decimals используют US;localeRU/BA/systemEN и grouping проверяются фактическими строками/PNG.
- **Память.** Созданный bitmap не recycle в этом helper. Современное освобождение системной памяти/фактическую утечку не доказать отсутствием recycle. Нужны измерения repeated share и память/выход/возврат, особенно двукратный click до завершения IO;случай не пройден статическим чтением.

### R15-B — расчёт контраста: границы, не новый дефект

Непрозрачные StatsCardTop `#0B6B3A`,Bottom `#063A20`;StatsMint `#CDEBD9`;MiniStat фон StatsGlassSoft white alpha31/255;badge StatsGlassStrong alpha51/255. Использована та же sRGB формула документа: `blend=a*white+(1-a)*base`, linearized luminance, `(Lmax+.05)/(Lmin+.05)`. Для StatsMint на самом светлом **непокрытом** конце градиента5.185340, на тёмном10.087452;на Soft поверх крайних цветов3.973616 и7.028933 соответственно. Белое число на Soft соответственно5.063126/8.956168. Эти величины получены stdlib Python по constants, а не измерением pixels.

**Это диапазоны по краям, не доказательство фактического low-contrast label:** MiniStat расположен ниже верхней точки градиента, его реальный background зависит от высоты/расположения. Критерий — actual screenshot/layout с sampling под собственными label и учётом compositing;отдельно rendererPNG. Декоративный trophy нельзя выдавать за единственный источник rank, потому что rank также есть текстом. Ни новая Canon палитра, ни слепая замена Stats* этим расчётом не обоснованы.

### Прочитанные тесты и их ограничения R15

| Полностью прочитанный файл / LF SHA | Содержательные assertions; границы |
|---|---|
| test_stats_edges.py1–124, `38b1d1c14c55146eb2f77a42db854d18320445dd08664c9e8e84a18f72e3218a` |6 test functions: zero/rank, passenger+driver aggregate, незавершённые, дваowner,auth,rank thresholds. Fixtures напрямую пишут completed rows, не actual journeys/Android. Distance expected использует **тот же** haversine_km helper: это тест aggregation, не независимый oracle геометрии |
| test_stats_counts_taxi.py1–101, `2e400f7baf555cad4671b8a21d5a9aac8e852986258a54338fd399975860aa6a` |6 функций: passenger/driver completedtaxi добавляетtrips/km;не меняет savings/co2;status controls;rank/badge. Synthetic fixtures,не actual create→assigned→onboard→done |
| test_achievements.py1–102, `62db2d0b2177b62602a22cd1fbb755cf0bd8a2b5de497e86390e747ff43b45c3` |7 функций: verified/newbie,donebooking/10rides/5parcels/400days,unverified,unearned progress. Нет UI tree/TalkBack,accountswap/cancel/share |

Числа функций получены AST верхнего уровня и сопоставлены прочитанным assertions; **19 исполненных тестов не заявляется**, здесь pytest не запускался. Целевой rg MyStatsScreen/StatsShareCard/drawStatsBitmap/saveSharePng/RankProgressCard по Android test/androidTest не нашёл прямых UI/bitmap проверок; EndpointContract322 содержит только endpoint вызов. Поиск по именам не исключает всех косвенных тестов. Уточнение качества money rounding: production использует unrounded share_km для savings, один expected сначала round(total,1); нужны boundary fixtures по принятому правилу, а не подгонка assertion под случайный sample. Реальная ошибка округления денег здесь не объявлена: речь об оценочной статистике.

Передача R15: source и связанная серверная логика разобраны, lifecycle критерий share сообщён root для согласования с QA-B01-021;новых source fixes/PASS/screenshots/измерений памяти нет. Единственный изменённый файл child — этот документ, source/Gradle/эмулятор/PG принадлежат root. Агент доступен для приёмки актуального diff/evidence;полный B09 и физический TalkBack остаются открытыми. Порядок следующих actual tests — только audit-blocks.md.

## DESIGN-SCAN-R16-20261001 — обращения поддержки, чат и закрытие

Дата / независимый ответственный: 01.10.2026, 11:00 МСК, `/root/design_resume`; B01/B05/B08/B09. Ветка `audit/full-technical-20260930`, HEAD `6fa0595d88bea4fcd59d4a1e2139c2d128cf6821`, локальные изменения имеются; этот агент не присваивает чужие изменения и не пишет исходники. Применимые AGENTS/index/architecture, текущая передача журнала и реестр прочитаны; исторические статусы не повышались.

### Карта чтения и актуальность R16

SHA256 нормализует CRLF/CR в LF без других изменений. Команды: `Get-Content -LiteralPath <path>` целиком либо `Get-Content -LiteralPath <path> | Select-Object -Skip <start-1> -First <count>` для указанных диапазонов; поиск связей — `rg -n 'SupportTickets|SupportTicket|ChatFeedBubble|closeSupport|postSupportMessage' <явные пути>`. Хэши рассчитаны локальным Python3.12 через `hashlib.sha256(Path(path).read_bytes().replace(b'\r\n',b'\n').replace(b'\r',b'\n')).hexdigest()`; сборка и тесты здесь не запускались.

| Файл | SHA256(LF) / диапазон смыслового чтения |
|---|---|
| `android/app/src/main/java/com/yuldash/app/SupportChatScreen.kt` | `d62261334b13742dd65c6a4c8277301c1831546d06a711618d9b626fa47a9090`, **1–459 целиком**, совпал с первоначальным табличным снимком этого документа |
| `backend/app/routers/support.py` | `81b7969fd97ec425a50b06bbd1be47490e1a2a35d08b4de5ad59ea282c6ce586`, **1–369 целиком** |
| `backend/tests/test_support.py` | `74be1f990ad31d6fd5ae580defbd777e23f8608892e45bab20deea773e4916f6`, **1–114 целиком** |
| `backend/tests/test_support_queue_has_a_ceiling.py` | `053921dcddb5ce14bed02470d36935edcf6e2c159e5218c6803f1fe85baf76c1`, **1–104 целиком** |
| `android/app/src/main/java/com/yuldash/app/RidesRequestsChatScreens.kt` | `f44e2d651bcc5927699f98c7e9eee6a0eade34ec8c394544211de1ac3bd6f9ea`, **1849–2111**: ChatContent/filter/keys/composer/ChatFeedBubble/ChatFlagPlate; остальной файл этим продолжением не объявлен прочитанным |
| `android/app/src/main/java/com/yuldash/app/data/ApiClient.kt` | `b3d3b34665d5cf339eecabef5a7b27ddd6bab01ea7567fac9b8b1d45bc3e0c70`, **1792–1858,7122–7178**: support DTO/parser/methods. Этот снимок учитывает ещё проверяемые root QA021 изменения общего файла, не означает их приёмку |
| `android/app/src/main/java/com/yuldash/app/YuldashApp.kt` | `92883a6d253da875ddec50a229f2d565e937cb7051556613bf5c1d353a34487f`, **1446–1457,1489–1511**: Help callback и обе ветки Support |
| `android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt` | `335684e9e57c5aa625c089f3e708de55bce02c95d5c8d59501ba319ccac63b1f`, **2326–2445**: Help→support, unread badge и соседний callback; остальной файл не принят |
| `android/app/src/main/java/com/yuldash/app/CanonTokens.kt` | `91cd5231e9948d9fc4ba6ec013e010562d0ba9ca07f7c5d8d856ff8a11416687`, **51–143**: реальные пары цвета, системный font multiplier |
| `android/app/src/test/java/com/yuldash/app/ChatContentTest.kt` | `546978170f0d7207b2b5a76986ea048cefdd225e64f2b1d25c575be0e0273c42`, **1–185 целиком** |
| `android/app/src/test/java/com/yuldash/app/RidesRequestsChatDeep3ContentTest.kt` | `dd5ebafdcb8d25b212b7a873a9991026d8cfb4bc7db23a966690ee44d1157b02`, **295–404**: удалённый/голосовой пузырь и два соседних prefs-теста |
| `android/app/src/test/java/com/yuldash/app/data/ApiClientEndpointContractTest.kt` | `801ab537fdb4d511c31e4049c35c2581f56c78158aef0a723c4f355f6ca3fd23`, **1–65,249–275,421–479**: назначение профиля, support address cases, проверки error/empty response; таблица и остальные поля не приняты целиком |

Окружение чтения — Windows, ОС/устройство/сервер/БД не используются. RU/BA рассмотрены в существующих appText-ветвях. Нет новых screenshot, измерения UI/CPU/памяти, TalkBack, настоящих provider/server/device PASS. Я не могу подтвердить runtime этих экранов.

### Связка функций и непроверенных состояний

| Функция → действие → экран → запрос → обработка → данные → результат сторон | Прочитано / конкретный непринятый критерий |
|---|---|
| Help→«Поддержка»→SupportTickets→GET `/support/tickets`→own user_id/list/order→feed/unread→список пользователя | Callback Help1454 передаёт `openProtectedScreen`, YuldashApp1497/1501 открывает реальный экран. Loading/error+retry/empty/data есть; повтор401 после прежнего feed, guest→login→исходное действие, Back/возврат списка и настоящий счётчик после ответа администратора требуют исполнения |
| «Новое обращение»→NewTicketForm→POST `/support/tickets`→CreateTicketIn/throttle→ticket+first message→новый тред и очередь администратора | subject/body trim, pending gate, server error и cancel разобраны. Серверные лимиты200/4000 есть, UI ввод не ограничен этими числами. Unicode/BA, 200/201 и4000/4001, retry после потерянного ответа, двойной тап, cancellation/lifecycle и геометрия формы с IME/font2 не проверены |
| Выбор тикета→SupportTicket→GET `/support/tickets/{id}`→owner404→thread→свои/официальные сообщения | applyThread sender user/admin и SUPPORT_ADMIN_ID, первая ошибка с retry, закрытый banner, RESUMED polling/delay8000 прочитаны. 8сек — константа кода, не измеренная частота на устройстве. Настоящие два участника/push→правильный тикет и остановка polling при STOPPED/Back ещё нужны |
| Ввод→send→POST `/support/tickets/{id}/messages`→owner/reopen/message commit→GET thread→optimistic/corrected feed у пользователя, новое сообщение у администратора | sending guard/сбросinput/rollback/toast есть. Late GET/polling порядок, ответ послеlogout/другогоticketId, реальный timeout после принятой записи/повтор без дублей, POST200+followupGET500 и оба участника ещё требуют выполнения |
| «Закрыть обращение»→POST `/support/tickets/{id}/close`→owner/idempotent status commit→`status="closed"`→баннер/очередь администратора | Только onSuccess на439. Отказ, pending/doubletap и race одновременно с admin_reply не имеют доказательства UI; DESIGN-015 ниже фиксирует однозначный пробел обработчика |

Серверные own-ticket404 и admin-only403 присутствуют в коде и тестах, но новый чужой-account HTTP прогон этим агентом не выполнялся. `_last_messages`84–96 читает все сообщения выбранных тикетов, выбирая первое поdesc-id; отсутствие N+1 не доказывает приемлемую память/p95 при длинной истории. Create-ticket156–164 имеет два commit: первый тикет и затем первое сообщение — отказ второго commit и orphan является **кандидатом** на fault probe, не исполненным результатом. Валидация min_length до `.strip()` и отсутствие ключа идемпотентности сообщения/создания также требуют содержательных DB/повторных HTTP probes в соответствующем блоке.

### DESIGN-015 — отказ закрытия обращения не получает обратной связи

Экран/состояние: `SupportTicketScreen`, открытый тикет с непустой перепиской, после нажатия «Закрыть обращение». Условия: own synthetic ticket7, GET200/status=open/messages≥1; POST `/support/tickets/7/close` отвечает500 либо controlled transport failure. Место: **SupportChatScreen.kt437–444, особенно439**. Оператор обрабатывает только `.onSuccess { status="closed" }`; нет `.onFailure`, отдельного closing state, UI ошибки или disabled во время закрытия. Правило AGENTS4.5/design.md — понятная ошибка и повтор, состояние загрузки изменяющего действия.

Однозначный вывод из кода: failure Result не изменяет никакое отображаемое состояние; повторные клики запускают отдельные scope.launch. Влияние: пользователь не узнаёт результат нажатия и может повторять его, пока сервер недоступен. **Количество HTTP/поведение интерфейса ещё не воспроизведено**, screenshot нет. Минимальное предлагаемое исправление владельцу: closing guard, отдельная понятная RU/BA ошибка с повтором; статус closed только по принятому ответу, сохранение сообщений/ввода и возможность продолжить переписку. Не менять правило сервера о переоткрытии по новому сообщению.

Проверка после исправления: настоящий SupportTicketScreen, входной GET вместо заранее готового content; tap→delayed POST500→видимая двуязычная ошибка и доступный повтор→POST200/closed; счётчик1 запроса при быстрых повторах во время pending, точный путь/ticket_id/сохранённые сообщения; control GET closed скрывает повторную CTA; отправка в closed возвращает open. RED→GREEN, merged/unmerged tree, source SHA, отдельный кадр/font2/BA и два участника должны быть сохранены владельцем. Эта проверка пока не принята.

### DESIGN-001 — дополнительный неисправленный потребитель в общей ленте

Это продолжение существующего ID, **не новый конкурирующий статус**. SupportTicket426 использует настоящий `ChatContent`, который1961 вызывает `ChatFeedBubble`. В2041/2046 у собственного voice/text задан `Color.White`, фон2028 — `CanonGreen2`. Вdark CanonGreen2=`#27A463`; формула R1 даёт **3.1931289916194636:1**, ниже принятого4.5 для14/16sp текста. Подстановка существующего CanonOnFilled=`#0F1613` даёт **5.746389670196543:1**. Числа рассчитаны тем же sRGB luminance алгоритмом R1 из актуальных токенов; белая send-icon2007 имеет тот же3.193129, но превышает порог3 для значащей графики и здесь не объявляется нарушением текста.

Точное runtime-воспроизведение владельцу: synthetic GET ticket7 с user-message «Синтетический текст», текущий myId, тёмная тема; сохранить фактический TextLayoutResult color/Surface фон и screenshot. RU/BA/own voice label — соседние component cases; own deleted использует CanonMuted/Surface и не должен поменяться, admin/fromAdmin и чужой текст сохраняют CanonText. Root исправляет оба фактических text-потребителя, затем независимый агент принимает новые evidence. Ранее R1/R2 принятый другой `BookingActiveTripScreen.MessageBubble` не закрывает этот consumer.

Для SupportStatusChip246 фон CanonMint и цвет CanonGreen2: рассчитанные значения light **5.874006852294215:1**, dark **5.11123299910326:1** превышают4.5. Из расчёта этих двух пар не следует приёмка всей карточки/темы.

### R16-A — договор ответа и UI-кандидаты, требующие исполнения

1. **Форма ответа POST message:** backend support211/231 объявляет/возвращает `TicketThreadOut` (id тикета, status, messages), тогда как ApiClient1846–1849 разбирает `toSupportMessageDto` (id сообщения, sender, body, created_at). Это однозначное расхождение прочитанного договора; SupportTicket377–380 игнорирует результат и делает ещё GET, поэтому успешный следующий GET маскирует его. Runtime последствие пока кандидат: closed→POST200/thread{open,new message}→followupGET500 оставляет старый closed banner и temp message, authoritative ответ потерян. Проверить настоящий ApiClient по синтетическому server-shaped ответу, затем UI путь с обоими участниками; не объявлять готовность договора поEndpointContract.
2. **Малый экран/клавиатура/большой шрифт NewTicketForm:** Column268–311 без verticalScroll, фиксированное полеbody160dp/subject/title/paragraph/две кнопки. IME/font2 может вытеснить CTA, но фактический TextLayoutResult/кнопки ещё не сняты. Нужен вход SupportTickets→new, ввод→IME,320×568 и обычный контроль,RU/BA; full-height готовый form-test не доказывает достижимость. Состояния sending/error/длинного4000-текста тоже включить.
3. **Сообщение/тред и порядок событий:** два независимых GET (load/polling) и followupGET после POST могут применить старый снимок после нового. Создание scope не отменяется автоматически при смене ticketId внутри прежнего composable; нужны контролируемые HTTP-latches, точная корреляция ticketId/session и отсутствие данныхA после новогоB. Никакой race этим чтением не объявлен воспроизведённым.
4. **Неиспользуемый renderer/параметры:** CircularProgressIndicator импорт Support45 в файле не используется напрямую; read-only наблюдение, код не удалялся. `myId=remember{...}`322 не привязан к ticketId/session; необходимость пересоздания при account switch проверить actual navigation, а не удалять remember только по отсутствию ключа.

### Прочитанные тесты и границы R16

ChatContentTest1–185 проверяет ready Content/пусто/RU-BA/текст/40-message scroll/disabled callback/emptyinput и отсутствие пустой заглушки при loading. Не проверяет SupportTicket HTTP/lifecycle/full idle→send transition и фактический цвет; тест loading185 не утверждает наличие spinner. Deep3 удалённые/голосовые4 cases295–373 показывают labels RU/BA напрямую в Content; callback/refill/shared-state/provider не выполняют. EndpointContract support266–270 утверждает путь/метод и generic success/error/empty response; конкретный `TicketThreadOut`→message поля не проверяет.

Server test_support7 определений проверяют создание/чужой404/admin403/ответ+unread/close-reopen/adminfilter/order; queue_has_a_ceiling4 definitions проверяют лимит пяти новых тикетов, сохранность десяти ответов/SOS и системный тикет. Эти11 definitions получены чтением `def test_...`, **не число вновь выполненных PASS**. Существующий положительный серверный тест закрытия не принимает молчаливую failure ветку Android. Сборок/тестов/эмулятора/настоящего сервера/БД этим агентом не запускалось; я не могу подтвердить их новое исполнение.

Передача R16: изменён только данный документ, все новые source находки и точные критерии переданы root; DESIGN-015 и дополнительный consumer DESIGN-001 ещё без UI RED/исправления/кадров. Следующее независимое чтение — AdminSupportScreen325 и его реальные callbacks/API, вторая сторона того же диалога; исполнения идут только по единому реестру. QA021 generation/UUID source и новые тесты принадлежат root/назначенному автору; независимая design приёмка ждёт freeze/evidence, initialcleanup device2GREEN исторический. Окно adb/Gradle/БД, новые замки или фоновые процессы этому агенту не принадлежат.

## DESIGN-SCAN-R17-20261001 — поддержка со стороны администратора

Дата / независимый ответственный: 01.10.2026, 11:05 МСК, `/root/design_resume`; B05/B08/B09. Ветка/HEAD/локальное окружение прежние R16, source read-only; Gradle/adb/БД не запускались. Не повторяется принятие R10/неизменённых server support/test R16: их чтение/хэши находятся в предыдущем разделе, исполняемые тесты всё ещё не заявлены.

### Чтение, связи и существенные состояния

| Файл | SHA256(LF) / фактически разобранные диапазоны |
|---|---|
| `android/app/src/main/java/com/yuldash/app/AdminSupportScreen.kt` | `ba9b6749e587ca2783cf0ff1ff554d89445ff8a1fdb2836727724535550e1163`, **1–325 целиком**, совпал с первоначальной картой |
| `android/app/src/main/java/com/yuldash/app/AppNavAdmin.kt` | `362a6591b8ffca1128fac98412bce7f8bfc3e606f6f8c966c0095c98359a3843`, **1–73 целиком**; перенос21 admin веток, callbacks и else разобраны без исполнения |
| `android/app/src/main/java/com/yuldash/app/ui/theme/Theme.kt` | `1eabf99357b62d6dcfcd2e68fc03fde51291b051db38b28fe3285060ab2790b3`, **1–87 целиком**: palettes/findActivity/systembars/MaterialTheme |
| `SecondaryScreens.kt`, SHA R16 | **1668–1726**: AdminCabinet/строка Support/callback; это не полное чтение SecondaryScreens |
| `YuldashApp.kt`, SHA R16 | **1099–1143**: SaveableStateProvider/groupAdmin→AdminNav; admin entry permissions/restore/deeplink этим чтением не приняты |
| `ApiClient.kt`, SHA R16 | **1858–1897**: admin list/thread/reply/close; mapper ThreadOut соответствует прочитанному server return в отличие от user post из R16-A |
| `RidesRequestsChatScreens.kt`, SHA R16 | **1204–1247**: фактический NearbyFilterChip/внешний modifier и selected цвет; значение семантики/площади на устройстве ещё не проверено |

Команды/алгоритм хэша R16 сохранены; дополнительные запросы `rg -n 'adminSupport|admin/support' android/app/src/test android/app/src/androidTest` не нашли прямых named references. Это ограниченный поиск по именам/путям, не доказательство отсутствия всех косвенных тестов. По прочитанным source/test не обнаружено нового UI-теста настоящего AdminSupportScreen, но я не могу подтвердить полноту неизвестных косвенных проверок.

Связка по коду: SecondaryScreens1693 «Обращения в поддержку» → callback AppNavAdmin32 → `Screen.AdminSupport`→AppNavAdmin51→AdminSupportScreen → GET `/admin/support/tickets?status=open|all` → admin guard/list≤200 → row waitUs поopen+lastSender=user → tap → GET thread → admin reply/close → server commit/push/ref_kind=support → новый authoritative ThreadOut/очередь. Android потребляет свежий ThreadOut в этих two success handlers; другая сторона/push/store/runtime ещё не выполнены. Наличие server403 не является доказательством фактически выполненного foreign-role запроса.

Разобраны loading/error/retry/empty/list, переключениеopen/all, row name/subject/lastMessage/date/waitsUs, thread subject/body/sender/date, editable text лимит4000, busy reply/close и запрет повторногоclose поstatus. Thread существует внутри экрана; верхняя Back90 возвращает список при thread!=null. LazyColumn/ключи обеих лент присутствуют. Кнопки reply/close минимально48dp в source; это статическое ограничение, фактические touch bounds/BA/font2 ещё не измерены. Системные панели и клавиатура/landscape/TalkBack ещё требуют отдельных кадров/исполнения.

### DESIGN-016 — подготовленный ответ поддержки теряется при отказе

Экран/состояние: AdminSupportScreen→выбранный тикет7→поле ответа с синтетическим текстом, после tap «Ответить». Условия: настоящий GET list200/own admin, GET thread200; controlled POST `/admin/support/tickets/7/reply`500/transport failure. Источник: **AdminSupportScreen.kt295** вызывает `onReply(text.trim())` и сразу `text=""`; **113–118** включает busy и обрабатывает только `.onSuccess { thread=it; reloadKey+=1 }`, затем снимает busy. Не сохраняет draft дляfailure/не показывает ошибку/не даёт retry исходного текста. Это нарушение сохранности ввода и состояния ошибки (AGENTS4.5/design.md).

Однозначное доказательство кода: draft очищается независимо от результата; failure ветка отсутствует, прежний draft ни в screen-state, ни в ticket.messages не возвращается. Влияние: текст, который администратор написал человеку, исчезает при сетевом/серверном отказе, пользователь ответа не получает. **Не заявляется исполненный UI RED или конкретное число потерянных серверных сообщений** — HTTP/визуальное подтверждение ещё нужно владельцу.

Минимальное предлагаемое исправление: сохранять draft до подтверждённой отправки; показывать RU/BA failure и разрешать явный повтор исходной попытки, не затирать новый ввод поздним ответом. Parent должен управлять pending/error/success; передачу authoritative server thread сохранить. Не менять server reopening/limit и не очищать все поля экрану ради retry.

Точная защитная проверка: настоящий AdminSupportScreen→GET list→tap row→GET thread→ввод «Синтетический ответ һынау»→tap→delayedPOST500; пока pending нет второго POST/новый ввод не теряется, послеfailure текст остаётся и есть понятная ошибка; retry→200 с messageid44/senderadmin/bodyисходный/statusopen, ровно одно новое сообщение в synthetic authoritative thread и отсутствие отрицательного temp/double send. Соседние500→Back→другойтикет, empty/all/open, unicode4000/4001, closed→reply→open, close→closed. Сохранить RED→GREEN/tree/counts/тело запросов и sourceSHA; отдельные RU/BA/светлая/тёмная/font2/IME кадры. Передан root; после его исправления нужна независимая повторная проверка.

### R17-A — другие однозначные пробелы и непринятые сценарии

- Открытие обращения104 обрабатывает только success, без loading/failure. Close121–126 тоже не имеетfailure сообщения. Неуспех не меняет никакую видимую ошибку. Это соседние места DESIGN-015/016, отдельный runtime критерий: listtap→GET500→понятная ошибка/повтор; closePOST500→draft/threadсохранены+error,200→closed. Root должен закрепить общий механизм, а не только кнопку reply.
- Parent `busy=true` не защищён `if(busy)return` в callbacks111/120; disabled задан дочерней кнопкой после recomposition. Doubletap с настоящим callback/pending и серверная идемпотентность response ещё нужны; по одному source отсутствие guard не названо уже созданным дублем.
- Async open/reply/close scope продолжается при `thread=null`/Back внутри прежнего AdminSupportScreen; позднийsuccess может вновь присвоить старый thread. Кандидат: controlled HTTP latch длятикета7, Back→list→ticket8, release7;8 должен остаться. Root source/generation guard в call не заменяет корреляцию двух разных тикетов в той же сессии.
- Длинное имя в Row214 не ограничено/не имеетweight; label «яуап көтә» может не помещаться на320dp/font2. Обе CTA вдвое делят ширину, длинный текст/клавиатура требуют реальныхbounds, не вкусового предположения. Весь UI не объявлен адаптивным по наличию LazyColumn.

### Дополнительные потребители DESIGN-001/002 и границы цвета

AdminSupportList163–168 использует **NearbyFilterChip**1213–1243. Активный12sp-текст1233 остаётся `Color.White` над darkCanonGreen2, контраст R16 **3.193129:1 <4.5**; код не содержит selected/selectable/Role выбора. Это дополнительные потребители существующих DESIGN-001/002, не новые ID. Значимая icon15dp имеет3.193129>3; отдельно ей нарушение контраста здесь не приписывается. Высота default Row padding16dp+icon15dp изsource — около31dp при стандартном шрифте; Compose layout/minimum touch handling требует измерения фактических hit/semantic bounds перед объявлением точного дефекта48dp.

У admin Reply294 default `ButtonDefaults.buttonColors(containerColor=CanonGreen2)` не задан contentColor, но реальная YuldashTheme использует dark onPrimary=`#052612`; контраст этой пары с#27A463 по формулеR1 **5.088559582110932:1**, lightwhite/#0B6B3A **6.607087170045344:1**. Поэтому этот consumer без собственного Color.White **не** объявлен контрастным нарушением только из-за отсутствующего contentColor. Разница theme.primary и CanonGreen2 является наблюдением двух фактических токенов; runtime фактический Button цвет/rootTheme и весь экран ещё не приняты.

Передача R17: изменён только design-doc. DESIGN-016/source потеряdraft и точные R17-A probes сообщеныroot. Server/support тесты R16 не повторялись и не превращались в новыеPASS. Ветка/HEAD те же, без source/Gradle/adb/БД/GitHub/production действий. Критерии полноценного двухстороннего чата/настоящего push/TalkBack/физического устройства открыты; следующая source-проверка определяется непрочитанными экранами карты96, ближайшая независимая приёмка — актуальный QA021 export screen после freeze/evidence.

## DESIGN-SCAN-R18-20261001 — настройки, тема и разрешения

Дата / независимый ответственный: 01.10.2026, 11:11 МСК, `/root/design_resume`; B01/B06/B09. Ветка/HEAD/локальные изменения прежние, владелец source/build/device/root; этот агент только read-only source и writer design-doc. Настоящие устройства/провайдеры/сервер/БД не использовались.

### Дополнительная карта смыслового чтения R18

| Файл | SHA256(LF) / фактические диапазоны |
|---|---|
| `SecondaryScreens.kt` | `335684e9e57c5aa625c089f3e708de55bce02c95d5c8d59501ba319ccac63b1f`, **707–1019,1281–1306**: Safety/Settings/обе picker-dialog/labels/AppPrefs; не весь файл2490 |
| `CanonTokens.kt`, SHA R16 | **1–54,51–143**: ThemePrefs/appIsDark и FontScalePrefs/пары токенов; повтор ранее прочитанных51–143 здесь вызван новой проверкой сохранения, не новый уникальный PASS |
| `MainActivity.kt` | `79c7865bb0a17ae2d1d5ac1ef6268aadd8cebedeb422c99e887d7444d70ebabe`, **236–314**: restoretheme/font/motion/rootTheme/intent start; остальной1007-файл не принимался |
| `MapScreen.kt` | `b7d83366da56899b8454e85535d9e8ca9bdec9eb7d7b360cd335b42e89d28bc7`, **305–330,919–944**: initial verifiedOnly и maptheme save; остальной файл2292 не принят |
| `Permissions.kt` | `255573dc8f89cf65e6aa6d544a94bc5f071e74426417ef1d1239fbfc19dd5785`, **1–197 целиком**: gate/explain/denial/systemsettings/fallback/notifications/fullscreen |
| `data/FcmService.kt` | `0e00a602f5befa6d36b446d7f0e8c8f0098d54ce7ac21558b4feaba8f0d20005`, **1–167 целиком**: recipient/auth/prefs gate, channels, private/publicversion, extras/key/sounds; delivery/runtime/systemFCM не принято |
| `BookingActiveTripScreen.kt` | `b161ac19ca66a198f60c8cbcb4abee9f8722e3d9a0d90938d559978ff545d31f`, **1336–1402**: SettingsGroup/SettingsNavRow/SettingSwitchRow; не весь3817-файл |
| `YuldashApp.kt`, SHA R16 | **1414–1448**: Settings/Safety callback и logout→endSession→Login; выполнение complete navigation/cleanup от этой записи не следует |
| `SecondaryDeep4ContentTest.kt` | `9f93c57b7b95bbc3a6aa1001d2b85661f0afb8a69a90e7a78cc724091d6d8230`, **1–136**: fixture/5theme picker tests; полный378-файл не принят |
| `LinePromiseIsHonestTest.kt` | `e82a8eba3eca3f4082ea824fd0696dbc074f210ea5110fbb452b63e6ad8343a5`, **1–114 целиком**: четыре helper/notifier/source checks; не реальные Settings/UI/FCM |

Команды те жеR16; дополнительный `rg -n 'dark_override|ThemePrefs' android/app/src/main/java/com/yuldash/app` выявил restore в MainActivity269 и write в Map938, никаких других обнаруженных writers. Отсутствие строковых references не заменяет весь compile/runtime, но Settings809 и ThemePrefs30–32 не вызывают дисковое сохранение напрямую. Хэши вычислены из реальных bytes сLF-normalization; арифметика и source reading не являются успешной сборкой.

Функции→пути: Profile/Settings→themeDialog→onPick→ThemePrefs→appIsDark/rootTheme/Map; FontPicker→FontScalePrefs.set→`yuldash_prefs/font_scale`→MainActivity270/292→base.fontScale*multiplier; notifications/sounds→AppPrefs→FcmService/TaxiOfferNotifier; Settings→confirmlogout→ApiClient/logout/endSession→Login; Safety→SOS/TrustedContacts/Rules/Blocklist/Report/verifiedOnly→Map nearby. Callbacks реальны YuldashApp1419–1444, но actual user journey этим source чтением не запущен. Back настройкам передан, отдельной ScreenTopBar вSettings отсутствует — наличие `onBack` без прямого использования не равно unreachableBack: системная общая навигация ещё должна быть проверена.

Разобраны условия isAdmin, isBashkir, три theme значения, триfont выбора, logoutconfirm/dismiss, notifications локальное/системное разрешение и sounds, Safety проверенные только карта/помощь. Модельные видимые RU/BA тексты сохранены, новые переводы не вносились. Уведомления/звуки действительно читаются FcmService36/61; ложный inert-toggle им не приписывается. `verifiedOnly=remember` Map312 и prefFilterinitial требуют actual return/navigation trace, прежде чем объявлять toggle ineffective.

### DESIGN-017 — выбор темы настроек теряется при холодном запуске

Экран/состояние: Settings → «Тема» → светлая / тёмная / как в системе. Нарушение однозначно по исходникам: **SecondaryScreens809** только присваивает `ThemePrefs.darkOverride=it`; **CanonTokens30–32** хранит это в памяти `mutableStateOf(null)`. **MainActivity268–269** при холодном запуске читает отдельные настройки **`yuldash_theme/dark_override`**. **MapScreen936–938** сохраняет boolean в этот ключ; Settings не записывает выбранный boolean и не удаляет ключ для null. Правило: настройка удобства должна применяться после настоящего нового запуска; общий механизм не должен возвращать прежний выбор другого экрана.

Влияние: после выбора темы в настройках новый процесс снова применяет ранее сохранённую map-theme либо системныйdefault. В частности выбор «Как в системе» не снимает прежний manualoverride на диске. **Это не результат выполненного process-death теста**, кадра нового процесса нет; статический разрыв сохранения подтверждён, runtime граница открыта.

Точное воспроизведение root: синтетические настройки `yuldash_theme/dark_override=true`, настоящий вход Settings → нажатие «Тема» → «Как в системе»; сразу themeOverride=null и интерфейс по системной светлой теме; выйти на Home, действительно завершить процесс (PID до/после различаются), запустить → ожидать null / отсутствие ключа override / системную светлую тему. Контроль: null → Dark → холодный запуск с true; Dark → Light → холодный запуск с false; Map toggle ↔ Settings value и свежая установка без ключа. Activity.recreate в том же PID не принимает этот критерий. Для UI RED сначала проверить диск после onPick, затем отдельно сохранить инструментальное доказательство нового процесса; не подменять экран вызовом setter из теста.

Минимальное предлагаемое исправление root: один общий ThemePrefs load/set с тем же существующим `yuldash_theme/dark_override`; set true/false сохраняет, set null удаляет ключ; Map и Settings вызывают общий механизм. Палитру/версии/SDK менять не требуется. Проверить переключение системной и ручной темы и слой LocalNightRide: временная ночная поездка не должна сохранять постоянный override. Отказ записи / нехватка места / настоящий disk commit и физический телефон остаются отдельными условиями; базовый фикс не принимать как обработку всех I/O.

### Дополнительные потребители DESIGN-001/002

- ThemePickerDialog948–966 и FontScalePickerDialog992–1013 рисуют radio-icon с null description / выбранным цветом, но не дают selected semantic value / Role.RadioButton. Это новые конкретные потребители прежнего DESIGN-002; фактическая озвучка TalkBack / merged tree пока не исполнены. Критерий: настоящий Settings → dialog → selected=true ровно у одного system/light/dark либо normal/large/extralarge; после выбора состояние меняется; сохранить labels RU/BA и исключить дублированную озвучку радио.
- `SettingsNavRow`1370–1375 показывает unread badge `Color.White` 12sp на тёмном CanonGreen2. Реальный Help supportUnread из R16 вызывает этот badge; рассчитанная пара 3.193129<4.5 — дополнительный потребитель DESIGN-001. Это код, не снимок экрана; приёмка других чипов R1 не принимает этот потребитель.

### R18-A — конкретные непринятые проверки

1. **Возврат из системных уведомлений:** Settings852 вычисляет проверку при composition; явного RESUMED observer / tick здесь нет. Реальная политика NotificationManager меняется вне Compose. Кандидат: AppPrefs notifications=true + system denied → tap → system settings allow → return; subtitle/switch должны обновиться без ручного reload; затем отозвать разрешение при открытом экране и вернуться. Вызов API в source не доказывает своевременную recomposition. Отказ launcher отдельно проверить контролируемой ошибкой; openNotificationSettings143–153 вызывает fallback openAppSettings.
2. **Системный шрифт / оба picker:** FontScaleOption 1.0/1.15/1.3 × system scale 2 означает итог 2/2.3/2.6 в исходниках, формула Main292–294. Фактический dialog viewport / CTA / длинный BA / font preview ещё не измерены. minHeight52 у option не принимает весь dialog при font2.6 /320×568 /landscape; требуются composition/Layout/device evidence.
3. **Safety SOS / layout:** Row739–756 содержит icon column / text / SOS button; text weight и LazyColumn не доказывают достижимость кнопки на320dp/font2. Help / toggle caption / обещание скрытого телефона связывать с реальными правилами показа данных B02…B04, не принимать по надписи.
4. **Сброс аккаунта:** настоящий Settings confirmation824 → root ApiClient1439 / endSession1440 имеет существующий cleanup путь. QA020/021 probes отдельны; Settings → logout → accountB / отсутствие A на экранах и в кешах не выполнены этим агентом. Локальные настройки не объявляются personal data без разбора назначения.
5. **Доставка push:** чтение FcmService подтверждает local gates / private/public / extras/key, но не работу настоящего FCM / фоновых уведомлений / системного канала / OEM / permissions. LinePromise/Push пробы на Robolectric не заменяют обязательный внешний критерий.

Качество прочитанных тестов: SecondaryDeep4 использует высокий стенд411×2600 / готовый ThemePicker / current parameter;5 cases проверяют labels/callback/dismiss, а не сохранение через настоящий Settings / холодный процесс / selected semantic. LinePromise4 функции проверяют helper/notifier на Robolectric и text guard по одной строке source; отсутствие своей проверки и positive control содержательны узко, но string assert не доказывает lifecycle GUI. Числа5/4 — определения прочитанных@Test, **новых9PASS не заявляется**. Тесты/сборки/эмулятор этим агентом не запускались.

Передача R18: подтверждённый разрыв сохранения Theme и конкретные read maps / probes переданы root. Точное имя prefs исправлено после чтения MainActivity/Map; `yuldash_prefs` относится к font, `yuldash_theme` — к теме. Изменён только дизайн-журнал, без source/Gradle/adb/server/PG/provider/commit/GitHub. Общий B09 /96 Screen /визуальная матрица /физический TalkBack остаются открыты; ближайшая независимая приёмка — QA021 новый экспортный экран по актуальным freeze/evidence.

## DESIGN-REVIEW-R19-20261001 — независимая приёмка участка QA-B01-021 и адаптация MyData

Дата/агент: 01.10.2026, 11:27 МСК, `/root/design_resume`. Блоки B01/B09, идентификатор продуктовой проверки QA-B01-021. Цель: принять изменённый экспортный путь по новой generation/UUID реализации, отдельно проверить фактические кадры и границы тестов. Критерий: настоящий MyDataScreen загружает синтетические данные, пользователь нажимает доступную кнопку выгрузки, реальный Android FileProvider выдаёт правильный UTF-8 текст; смена сессии до позднего HTTP/IO не публикует выгрузку аккаунта A, выход удаляет собственные копии и отзывает проверенное право внешнего UID, нейтральный файл сохраняется. Полный экран и вся визуальная матрица этим критерием не принимаются.

Ветка `audit/full-technical-20260930`, HEAD `6fa0595d88bea4fcd59d4a1e2139c2d128cf6821`, локальные изменения есть. Владелец продуктовых исходников, сборки и эмулятора — root; этот агент только читает исходники/артефакты и пишет данный документ. Чужие изменения не присвоены. Основание повторного чтения — изменённые source/test files и новая обязательная проверка на Android вместо Windows Robolectric FileProvider. Прежний initial device2GREEN относится к заменённой реализации и не использован для приёмки текущей версии.

### Прочитанный исходный код и контрольные суммы

| Файл | SHA256 LF / разобранные границы |
|---|---|
| `MyDataScreen.kt` | `331ed35c48429c88ae2aa2b6ab407cbfedd4b823639d7567feba04d900493de2`;642строки. Текущие181–313: cardStored/выгрузка/поколение/IO/chooser/error/finally;392–433: DataRow;432–642: location/docs-часть, кроме участка усечённого первого вывода, не объявляемого новым полным чтением. Полный diff с `audit-MyDataScreen-before-export-20261001.kt` прочитан; историческое целиковое чтение сохранено в R11 |
| `data/PersonalDataExports.kt` | `ba913bbb5c5730009b0fc996be6059737bde3e6722e684fe3658c0fa1477b547`; **1–55 целиком**: уникальное имя, generation до/после IO/provider, discard, allowlist legacy/UUID, revoke/delete, нейтральные файлы |
| `data/ApiClient.kt` | `b3d3b34665d5cf339eecabef5a7b27ddd6bab01ea7567fac9b8b1d45bc3e0c70`;7946строк, **частично60–119/225–321/412–545/553–608**: session generation/monitor, init, logout/associated data cleanup, GET me/data/export. Остальной файл этой записью не принят |
| `MyDataExportJourneyInstrumentedTest.kt` | `06e29bcf8d753b148ae59fd33e6dda88add5764a607459b675e21169543bce7f`; **1–380 целиком**: настоящий экран/HTTP/FileProvider, recorder, пять сценариев, latch/calibration, teardown |
| `data/MyDataExportPrivacyInstrumentedTest.kt` | `dc3c22e33669439cf6bb164a41ea139f234f34e665554b682a1d4b67a6c6e9ea`; **1–140 целиком**: три external UID grant/read/revoke пробы, positive/negative/neutral controls, query URI и quoting-калибровка |
| `StorageAuditRunner.kt` | `27a6114a00137b79f5458c2ec4da98c349599e782fa415567697f9a41ef770e8`; **1–14 целиком**: обычный Application без YuldashApplication hooks, сам по себе не сетевой запрет |
| `res/xml/file_paths.xml` | `9ed45ca88dbdfa4705a4c5a797aa43fc2c3e224d03fddbaff5d90151b931baef`; **1–5 целиком**, shared cache root |
| `AndroidManifest.xml` | `3022b7aecc3a48c0bc787ade2c0d8d7c32216a0456bc855a49664a967434e569`;106строк, **97–106**: FileProvider exported=false/grantUriPermissions=true/paths; весь B10 не принят |

Новая видимая строка, версия Kotlin/Compose/AGP/SDK и вёрстка MyData в privacy-diff не менялись. Уникальное дисковое имя отличается от displayName сервера: FileProvider передаёт исходное имя пользователю, содержимое берётся из авторизованного HTTP. `runIfCurrentSession` ограничивает короткую публикацию; файловый IO не помещён под общий session monitor. В `finally` не опубликованная или устаревшая подготовка удаляется. Это разбор кода; выполненные критерии ниже имеют отдельные доказательства.

### Версии доказательств, окружение и исполнение

[Checkpoint](../test-results/audit-mydata-export-checkpoint-20261001.json), LF SHA256 **`cc0ba2bee6b95d98205807685f25817f3a5972d98d5ecf7314e6b6cdbe804a1b`**, независимо сверен чтением bytes: десять source entries,100artifact entries,2APK entries —112 сравниваемых отпечатков, ни одного несовпадения. Дополнительно десять source-копий в [snapshot](../test-results/audit-mydata-export-source-snapshot-20261001) имеют те же отпечатки. Арифметика112=10+100+2; это число сопоставленных файлов, не число тестов и не процент покрытия. [Root-verify](../test-results/audit-mydata-export-checkpoint-root-verify-20261001.json) согласуется с собственной сверкой. Артефакты существуют; их хэши не объявляются прочтением каждого исторического файла.

Windows; эмулятор `emulator-5580`, Android API35,1080×2340px, density2.75, системный fontScale2, светлая тема, RU/BA. Ширина приблизительно392.73dp рассчитана1080/2.75; это не проверка320dp. Синтетические аккаунты/токены и HTTP на `127.0.0.1` с временным портом, настоящего FastAPI/PostgreSQL здесь нет. Ограниченный ServerSocket выполняет реальные запросы клиента, проверяет path/lang/auth/число обращений; SMS/платежи/provider не вызываются. Обычный Application в runner отключает startup hooks; запрет внешней сети и отключённые радиоинтерфейсы относятся к подготовке стенда root, этим агентом adb не проверялись.

Точная воспроизводимая команда сохранена в [final run JSON](../test-results/audit-mydata-export-current-device-final-20261001.json):

```powershell
adb -s emulator-5580 shell am instrument -w -r -e class com.yuldash.app.MyDataExportJourneyInstrumentedTest,com.yuldash.app.data.MyDataExportPrivacyInstrumentedTest com.yuldash.app.test/com.yuldash.app.StorageAuditRunner
```

Команда приведена для воспроизведения владельцем свободного устройства, этим агентом не запускалась. Первичный [final device log](../test-results/audit-mydata-export-current-device-final-20261001.log), LF `c930f2fd8c6140ffee70e7c411ad4bf4950cad33cdad4f1478d3b85f42fb18cb`: восемь started и восемь status0, `OK (8 tests)`,30.455с. [Final markers](../test-results/audit-mydata-export-current-device-final-markers-20261001.txt), LF `ed252bcefa3cf0c2c07fa6555dd4b5138a9d83daa6b1e2b9390795115664bd05`: PID3510, actual external UID2000, правильный positive read до выхода, permission DENIED/запрет read после, neutral control остаётся доступен.

Сохранён первый combined run: journey5 прошли, grant3 упали до logout из-за кавычек fixture в `UiAutomationConnection/Runtime.exec`, а не обнаруженного нарушения очистки продукта. В исправленном fixture отдельно проверяется ошибочная quoted форма со stderr и рабочая форма команды. Повтор final8 обоснован этим изменением стенда; прежний сбой не стирается и не входит в число новых уникальных сценариев.

JVM [XML-каталог](../test-results/audit-mydata-export-io-fix-neighbors-green-20261001-junit) независимо разобран:12reports,62tests,0failures/0errors/0skips;62=3+3+2+5+9+6+3+14+2+2+9+4. Включены2 MyDataExportSession и2 PersonalDataExportCleanup плюс58 соседних проверок. Это не62 UI-теста. [Общий JVM/debug build log](../test-results/audit-mydata-export-io-fix-neighbors-green-20261001.log) заканчивается `:app:assembleDebug`, `BUILD SUCCESSFUL in 2m 19s`. [Final test APK build](../test-results/audit-mydata-export-current-device-final-build-20261001.log) — successful27с после изменения fixture. Сборка debug не принимает release.

Две выборочные мутации выполнены root в изолированной копии; [metadata](../test-results/audit-mydata-export-mutation-20261001.json) и оба первичных XML сверены: old-writer даёт1failure/2tests в `logoutAfterHttpSuccessBeforeFileWriteDoesNotRecreateOldExport`; cleanup-noop даёт1failure/2tests в `logoutErasesAllOwnCopiesAndPreservesUnrelatedSimilarNames`. Независимое чтение в этой записи принимает факт падения соответствующих XML и совпадение актуального source со snapshot; выполнение и восстановление копии записаны root. Это контроль способности тестов заметить потерю guards/cleanup, не доказательство любой возможной гонки.

### Принятые критерии и пределы

1. Реальный MyDataScreen получает GET `/me/data`, выводит синтетические данные; тест находит и прокручивает до export CTA и действительно нажимает. В RU/BA запрашивается соответствующий lang, auth принадлежит A, GET export ровно один. Создан ровно один собственный файл, UTF-8 dump совпадает целиком; реальные provider read/query возвращают содержимое/displayName. Обнаружен один ACTION_CHOOSER с SEND `text/plain`, content-authority и READ flag. **RecordingContext перехватывает startActivity**: ОС chooser/его получатель этим journey не запускаются.
2. Выход после успешной выгрузки удаляет собственный файл/закрывает provider read; аккаунт B не наследует A. Поздний HTTP после выхода/B не создаёт файл и не публикует intent. Настоящий IO вход через `getCacheDir` остановлен latch после HTTP200; logout/B выполняется до release, затем старый файл/intent не появляются. HTTP/IO latch проверяет timeout, не скрывает гонку произвольным sleep. Проба не принимает принудительное завершение процесса.
3. Отдельные три Android grant теста обращаются к реальному provider от shell UID2000, отличного от app/root; положительный read до выхода исключает ложный GREEN изначально недоступного URI. Проверены legacy path, reuse того же имени в B, neutral shared file и новая подготовка с displayName query. Право на own export после logout=-1/read запрещён, нейтральное право остаётся. Это **explicit grant**, не фактически выданный chooser grant живого стороннего приложения.

**Scoped functional acceptance дано** для перечисленного QA-B01-021 пути и актуальных отпечатков. Это не общий design PASS MyData/B09, не независимая приёмка полного auth/storage аудита. Profile→Settings/MyData→back/Login restoration, настоящий server export/migration, реальная внешняя программа и её уже открытый дескриптор/сохранённая копия, cold process death во время IO, отказ delete/revoke/диска, force-stop, реальный телефон/OEM, release, dark/320dp/large landscape/IME, TalkBack и весь96-screen обход остаются открыты. Я не могу это подтвердить. Отмена chooser, launcher failure и быстрые повторные нажатия также не покрыты восемью критериями.

### DESIGN-018 — башкирское пояснение данных разбивает слова при крупном шрифте

Экран/состояние: MyData, загруженные данные, `cardStored=false`, BA, светлая тема, API35/1080×2340/density2.75/fontScale2; скролл к нижней карточке и кнопке выгрузки. Независимо просмотрены реальные [RU PNG](../test-results/audit-mydata-export-device-ru-20261001.png) и [BA PNG](../test-results/audit-mydata-export-device-ba-20261001.png), оба1080×2340. SHA256 **raw** RU `64701486fb20e22229bd59bcbc303d2f254c781d40bb2fd537a9d960167da04c`, BA `5d12666f63486d220c9bc17300af35e900393f51ae5f8a625a2cc06365afbe25`;152000/142220bytes. [Происхождение](../test-results/audit-mydata-export-device-first-screenshots-20261001.json): кадры первого combined run новой продуктовой версии, journey5successful/grant3fixturefailure. Кадры не названы изображениями final run; layout/product после них не менялся.

На BA кадре пояснение банковских данных переносит внутри слова **«беҙҙән» как «беҙҙә»/«н»**, **«шоферға» как «шофе»/«рға»**; заголовок «мәғлүмәттәре» тоже вынужденно занимает много узких строк. Это наблюдаемое нарушение адаптации длинного башкирского текста к системному шрифту по AGENTS4.5, не вкусовое предложение. Точная ширина TextLayoutResult/число строк этим визуальным доказательством не измерены и не выдуманы. RU в показанном состоянии и CTA в обоих кадрах видны полностью; это не утверждение обо всех картах/положениях скролла.

Причина, подтверждённая исходником **MyDataScreen403–421**: icon+две spacer12dp+неограниченный `Text(value)` справа измеряются без weight, подпись title/note остаётся в остатке `Column(weight1)`. При `value="Һаҡламайбыҙ"` и font2 этот остаток оказывается очень узким. Комментарий о weight, предотвращающем уход за край, не гарантирует читаемость: фактический screenshot показывает разрыв слов. Влияние: пользователь вынужден читать смысл хранения/передачи банковских данных по кускам; карточка растёт в высоту и затрудняет просмотр.

Минимальное предложение root: дать заголовку/пояснению полную доступную ширину и расположить значение ниже либо использовать обоснованный адаптивный layout; соседний LocationDataRow уже имеет подходящий вертикальный смысл. Не уменьшать системный шрифт и не подменять тексты ради прохождения. Конкретную форму проверяет владелец исходников; это предложение реализации, а дефект переносов подтверждён.

Защитная проверка после изменения: настоящий screen→GET cardStored=false→BA/font2→скролл к DataRow; получить реальные `TextLayoutResult` title/note/value и доказать, что слова не дробятся из-за нехватки ширины, текст не обрезан и значение/CTA достижимы. Соседние RU/BA × light/dark ×320/393/large dp ×system1/2, cardStored true/false и длинные значения counts; сохранить RED→GREEN, layout measurements/PNG/currentSHA и actual tap export. Точная проверка не должна принимать экран напрямую в final content вместо GET-пути. Исправление и повторная приёмка **не выполнены**, DESIGN-018 открыт; scoped privacy acceptance не закрывает его.

Передача R19: root получил принятую границу экспорта и DESIGN-018. Единственный изменённый файл этого агента — данный дизайн-журнал; никаких source/Gradle/adb/БД/production/GitHub/commit, фоновых процессов/замков устройства нет. Следующий независимый шаг — завершить уже начатый разбор NotificationsScreen279–551, прочитать конкретные getNotifications/markRead/parser и серверные обработчики/тесты, записать failure/optimistic read/переходы как отдельные критерии. Исполнение/очередь определяет только audit-blocks; исправление DESIGN-018 и runtime остальных новых DESIGN-ID выполняет назначенный root владелец.

## DESIGN-REVIEW-R20-20261001 — центр уведомлений, сохранение прочтения и переходы

Дата/агент: 01.10.2026, 11:33 МСК, `/root/design_resume`; B09/B05/B01, новая source-проверка NotificationsScreen из прежней карты96 экранов. Ветка/HEAD/локальные изменения — R19. Цель: разобрать центр уведомлений от GET до фильтра/нажатия/серверной пометки/перехода, сопоставить Android, PWA и существующие тесты. Критерий чтения: перечисленные функции/ветви и связи разобраны; критерий исполнения отказа/семантики/двух участников пока не выполнен. Windows/read-only, эмулятор/Gradle/БД этим агентом не использованы, новых PASS нет. Размер экрана/язык/тема/TalkBack для этого нового участка не измерены. Я не могу это подтвердить.

### Источники и фактическая область чтения

| Файл | LF SHA256 / прочитано |
|---|---|
| `SecondaryScreens.kt` | `335684e9e57c5aa625c089f3e708de55bce02c95d5c8d59501ba319ccac63b1f`;2490строк, **279–551**: NotificationsScreen, markRead/markAll/deepLink/filter, notifIcon/time/row. RouteWatches552 далее ещё не принят |
| `data/ApiClient.kt` | `b3d3b34665d5cf339eecabef5a7b27ddd6bab01ea7567fac9b8b1d45bc3e0c70`; **1770–1799/6784–6810/7101–7125**: feed GET/POSTread, parser, DTO; полный файл не принят |
| `YuldashApp.kt` | `92883a6d253da875ddec50a229f2d565e937cb7051556613bf5c1d353a34487f`; **1350–1394**: реальные callback назначения center→booking/completed/request/support/parcel/instant/ride/incident/driver/apply/partner/ad |
| `RidesRequestsChatScreens.kt` | `f44e2d651bcc5927699f98c7e9eee6a0eade34ec8c394544211de1ac3bd6f9ea`; **495–522** SegmentedTabs; остальные прочитанные диапазоны см.R16 |
| `MainActivity.kt` | `79c7865bb0a17ae2d1d5ac1ef6268aadd8cebedeb422c99e887d7444d70ebabe`; **874–881** bounceClick; click/animation, явного role/selected нет |
| `backend/app/routers/notifications.py` | `df3e31f4f4723551af13bf5002fd1b5de805c94152883c2e39bf8ef6daed5cdb`; **1–135 целиком**: schema, ownership/query/count, legacy batch enrichment, read/commit/recount |
| `backend/app/models.py` | `cc3be8a5fa7208f0f5eacba65081177a5c86759b527ad1a778f784e844fe0477`;2069строк, **1097–1123** NotificationType/Notification; остальные модели не приняты |
| `backend/tests/test_notifications.py` | `95b5a50405402de9032f7bca4cfe92888ebbdf91c70341470190616b463fbde6`; **1–175 целиком**,10test definitions |
| `backend/tests/test_legacy_completion_notification.py` | `14de328fa9e82ebe5095771ce303b811f3c9e67b3d6dc2b3fa268cb9c60aae43`; **1–149 целиком**,5definitions, две параметризации по7 значений; это не новая collection/PASS |
| `NotifTypesGuardTest.kt` | `702f68acdf8c284d373831d5698fe21f9cd855e8b34f46b07be01d62b26d1927`; **1–91 целиком**,3@Test source guards |
| `PushRoutingChainTest.kt` | `d13a70e75d13b5f18455f4ccafb140a41fd337b5a2bc4ce0c8ba7fcf69d1e040`; **1–118 целиком**,9@Test FcmService→Notification→handleNavIntent/signal checks |
| `PushRecipientTest.kt` | `5380923abed07d602f368932f7e79c086cc4303add333333ee2d0092c5e583f5`; **1–68 целиком**,6@Test current/foreign/missing/malformed/offer recipient checks |
| `data/PushNotificationTest.kt` | `549c0cd9811bd59d244d6f3aa3f2d207d3f78a31910ed79a6755efaa72c34a9e`; **1–212 целиком**,14@Test channels/dedup/prefs/private public version/malformed cases |
| `RequestWatchInboxTest.kt` | `8d24bc938c4f9fe748dc10da912de2a7f60a32c86b9d88c240d26566c9bb5c8f`; **1–75 целиком**,1@Test actual NotificationsScreen GET/taps watcher/legacy/request/completed callbacks |
| `SecondaryScreensContentTest.kt` | `a3520eb95bf800d9bebc4ba43afad2105341710f94bbe06025b94285b34a6019`; **1–116 целиком**,6@Test; только2 относятся к готовому NotificationRow,4к PaymentStep/Person |
| `webapp/src/screens/NotificationsScreen.tsx` | `831c89664fdecd6f6f4779230df85ccd2348371a5350ebacfc2320cb7e1b2037`;295строк, **175–206**, только соседний onTap/readAll rollback; полный PWA экран не принят |

Хэши вычислены из реальныхbytes с CRLF/CR→LF, как инвентаризация. Команды чтения: `Get-Content -LiteralPath <path> | Select-Object -Skip <start-1> -First <count>`; связи `rg -n 'NotificationsScreen|notifTimeAgo|markNotificationsRead' android/app/src/test android/app/src/androidTest`, server tests через `rg -n ... backend/tests -g 'test_notifications*.py' -g '*completion*notif*.py'`. Python ast подсчитал определения серверныхtest функций, Kotlin count — аннотации@Test; ни то ни другое не число исполненных тестов. Прежний FcmService167 полностью прочитан в R18, неизменённое чтение не повторялось.

Функции→путь: событие другого участника/сервера → сохранённый Notification с user_id/языками/ref → current_user GET `/notifications` → parseNotifDto/NotifFeed → выбранная категория/NotificationRow → markRead → POST `/notifications/read` по владельцу/commit/актуальный unread → callback/refKind → root назначения. Обе серверные операции ограничивают Notification.user_id==current_user; read уже прочитанные не меняет, GET legacy enrichment не переписывает записанный ref. Это прочитанные guards/тесты; запрос от реального чужого аккаунта данным агентом не выполнен.

Разобраны loading/empty/error/retry, все четыре фильтра, BA fallback наRU, title1/body2 ellipsis, unread фон/точка, ISO OffsetDateTime→naiveUTC fallback→invalid empty, future clamp, request_watch shortcut, refId null, все14ветвейrefKind и неизвестный ref. Видимые строки используют appText, icon decorative null descriptions не объявлены отсутствием описания клика. LazyColumn/Row keys есть; реальные navigation/back/loading восстановления и все destination экраны этим исходным read не принимаются. `onBack` не используется непосредственно, но global back требует собственной проверки, это не доказательство недостижимости.

### DESIGN-019 — отказ пометки прочитанным скрыт за ложным состоянием

Экран/состояние: NotificationsScreen, GET вернул два непрочитанных события; нажать «Прочитать всё», POST `/notifications/read`500/transport refusal. **SecondaryScreens338–341** присваивает unread0/read=true до сетевого результата; возвращённый `Result<Int>` игнорируется. **401–405** скрывает action сразу при unread0. Ни failure state, ни rollback, ни повтор этой попытки отсутствуют. **330–336** одиночный markRead имеет то же игнорированиеResult; повторный tap уже локально read не отправляет новый запрос. КонтрактApiClient1785–1790 возвращает authoritative unread, но consumer его также не использует.

Это однозначное доказательство ошибки обработки отказа из кода: при failure сервер не подтверждает прочтение, UI остаётся прочитанным и не сообщает о несохранённом действии. Нарушены AGENTS4.5/error+retry и достоверность результата. Влияние: отметки и счётчик не соответствуют сохранённым данным; при новом GET непрочитанные возвращаются. **Runtime UI RED/число реальных рассогласованных записей не заявляется**, нужна controlled-проба владельца.

Минимальное предлагаемое исправление: явно обработать pending/success/failure и reconcile с authoritative unread; оптимистичный UI допустим с корректным восстановлением и понятным RU/BA повтором. Не возвращать устаревший snapshot поверх новых уведомлений/аккаунтаB; navigation не должна зависеть от успеха прочтения. Изменение owner/status/API контракта не требуется. Root получил точные места, source не исправляется этим агентом.

Точное воспроизведение/критерий: actual NotificationsScreen→GET synthetic items[id7 unread,id8 unread]/unread2→tap readAll→delayed500; сохранить request body `{all:true}`/одинPOST, UI-tree и server state. Послеfailure интерфейс не утверждает подтверждённый unread0, есть действие повторить; retry200/unread0 и authoritative read_at у обоих → UIread0/noCTA. Одиночный id7failure сохраняет retry/unread, id7success не затрагивает id8. Соседние частичная страница/серверный unread>items, новая запись во времяPOST, 401/logout/B/позднийответ, Back с coroutine cancellation, doubletap. Проверять и состояниеUI, и authoritative данные, не только200. Сохранить RED→GREEN/currentSHA/кадрыRU/BA и выборочную мутацию failure-handler при реализации.

Android/PWA сравнение: web readAll184–199 сохраняет prevItems/prevUnread и восстанавливает их вcatch; Android338 этого не делает. [architecture](architecture.md)872 «NotificationsScreen.readAll исправлен» находится в разделеPWA; историческая запись не принимает Android. PWA одиночный markRead179 имеет catch-empty; это отдельный текущий source-пробел, не выполненная browser-проверка и не причина автоматически менять web в данном Android lane.

### Дополнительные потребители DESIGN-001/002 и непроверенные варианты

- Center432 использует SegmentedTabs495–522. Выбранный14sp-text `Color.White` над darkCanonGreen2#27A463: прежний расчёт3.193129:1<4.5, дополнительный неисправленный потребитель DESIGN-001. Selected/Role.Tab отсутствуют, только цвет/weight — дополнительный DESIGN-002. RoundedCornerShape14 в shared helper вместо общегоshape нарушает буквальное правило использования Canon-shape, но runtime геометрия и единообразие требуют оценки общего компонента; отдельный вкусовой DESIGN-ID не создаётся.
- Непрочитанность NotificationRow отображается фоном/точкой9dp, но явного accessibility stateDescription нет; markAll Text использует bounceClick безRole.Button. Настоящий merged/unmerged tree и TalkBack должны подтвердить различимость read/unread и action. Это конкретные семантические probes; измеренный runtime defect не заявлен.
- Header Row34sp с weight1/action14sp, relative-time справа безweight и title/note ellipsis могут уменьшить место на320dp/font2/BA; actual layout/вырез/IME/largelandscape ещё не измерены. Комментарий «48dp через padding» для markAll не является доказательством hit bounds: нужны фактические размеры и системное расширение touch target.
- Ошибка загрузки показывается только при feed.items.empty;401 выставляет error=false, прежний feed не очищается этим handler. Late sessionResult/Back и новый аккаунт требуют actual root path; ApiClientgeneration guards не принимают весь local feed reset автоматически. Существующий GET при долгом открытии не poll/refresh, дата относительно now не имеет своего ticker. Изменение времени/данных при возврате, сеть и stale result — отдельные пробы.
- API всегда получает default50, сервер limit1…200 безcursor; UI не имеет load-more. Нельзя объявить все исторические уведомления достижимыми. Продуктовое требование к глубине истории/пагинации ещё нужно связать с B05. `refId=null`/неизвестный ref корректно не вызывает callback, но значимые неизвестные сообщения должны проверяться конкретным serverpayload, не по одному fallback.
- Server mark_read перебирает все непрочитанные свои строки в ORM перед commit. Объём/память/время/lock/PG-concurrency этим чтением не измерены; limit GET не ограничивает all-read. Права на конкретный чужой id и повтор той же пометки следует проверить отдельно: test_notifications175 проверяет outsider all-read, не targeted foreignid.

Качество существующих тестов: RequestWatchInbox использует настоящий Screen и GET/scroll/click, но fixtures `read=true/unread0`, поэтому POST/failure/rollback недоступны в этой пробе. NotificationRow Content2 проверяет только готовые title/subtitle, безGET/markRead/BA/semantic unread/time. PushRouting9 проверяет локальный RemoteMessage→Notification.savedIntent→reflection handleNavIntent→NavSignals/DeepLink, а не настоящий FCM/OS tap/полное открытие destination. PushRecipient6 имеет positive current-user и foreign/malformed controls; это содержательная проверка local gate, не external delivery. PushNotification некоторые cases проверяют только отсутствие падения, а off=true/каналы/private public-version/dedup проверяют конкретные side effects. NotifTypes3 source regex guards могут пропустить динамическийservertype; строковое наличие не принимает runtime рендер/переход. Серверные10defs проверяют реальные API effects в fixture и две роли, legacy5defs — no-write/foreign/ambiguity/idcollision/batch-one-query и PGsequence guard; текущий engine и исполнение не запускались этим агентом.

Передача R20: root получил DESIGN-019/точные строки338–341/330–336 и подтверждённое Android/PWA различие; исправления/RED/повторная приёмка ещё открыты. Изменён только этот документ. Ближайшее самостоятельное чтение — RouteWatchesScreen552–706, новая/повторнаяподписка и отказ удаления через существующие API/server/tests. Очередь и текущие статусы остаются только audit-blocks, весь96-screenB09/physical/providers/runtime матрица не закрыты.

## DESIGN-REVIEW-R21-20261001 — форма и обе стороны подписки на маршрут

Дата/агент: 01.10.2026, 11:40 МСК, `/root/design_resume`; B09/B02/B05, исходники и тесты связанных функций. Ветка/HEAD/локальные изменения — R19. Цель: продолжить следующий ещё не разобранный соседний экран RouteWatches, связать create/list/delete с matching нового рейса, приватностью и уведомлением. Критерий чтения выполнен только в указанных границах; фактический UI/server/PG/FCM путь не запущен, поэтому новых runtimePASS нет. Windows/read-only; Gradle/adb/эмулятор/БД/рабочий сервер этим агентом не используются. Новые строки и исправления в source не вносились.

### Проверенные исходники и тесты

| Файл | LF SHA256 / разобранные функции и условия |
|---|---|
| `SecondaryScreens.kt` | `335684e9e57c5aa625c089f3e708de55bce02c95d5c8d59501ba319ccac63b1f`; **552–706**, RouteWatchesScreen/RouteWatchRow: prefills/saveable поля, bothWays, reload, validate/create/success/failure, list/load/error/empty/delete, row |
| `data/ApiClient.kt` | `b3d3b34665d5cf339eecabef5a7b27ddd6bab01ea7567fac9b8b1d45bc3e0c70`; **1938–1972/7179–7190**, create/list/delete/DTO; **3488–3505**, успешный JSON array wrapper. Полный клиент не принят |
| `YuldashApp.kt` | `92883a6d253da875ddec50a229f2d565e937cb7051556613bf5c1d353a34487f`; **1394–1405**, Screen.RouteWatches/prefill/back; entry Notifications R20 |
| `backend/app/routers/route_watch.py` | `4816aa7723ca3643669b662bae5c61254cb90222a2ecb09887d959a64412fc53`; **1–125 целиком**: input bounds/type/regex, own active lookup/duplicate/20limit/TTL14d, list/delete ownership |
| `backend/app/models.py` | `cc3be8a5fa7208f0f5eacba65081177a5c86759b527ad1a778f784e844fe0477`; **1065–1096**, RouteWatch fields/key/owner/direction/kind/date/notified/expiry; остальные2069строк не приняты |
| `backend/app/services.py` | `060566080a682cf8f913213f8839de54335ddc710093011d7e2441ac423b7981`;2110строк, **764–893**, norm/city variants/async/notify_route_watchers: direction/date/kind/expiry/self/24h/visibility/notification/commit/background/exception; notify_request_watchers894далее ещё не разобран здесь |
| `backend/app/timeutil.py` | `c009e96305aee38db51a2f1aff2ff3a30e5ab2d77ada477e79de004c567548a3`; **1–105 целиком**: naiveUTC, clientoffset/local, localdate/month/week и None. Это прочитанные helper, не запуск всей серверной схемы |
| `backend/app/config.py` | `5ca09de8521c471cd0e63bf92985ea81bb91b90aca7bdfce4e070d14dfa6411a`;1158строк, **507**, defaultlocal_tz_offset_hours5. Секреты и runtimeenv не открывались, настоящий effectiveoffset не подтверждён |
| `backend/app/routers/rides.py` | `25f42371e6ee6456e922afa8b6dc279a40108b346b61e9279d29781948e627c9`;861строка, **263–275**: entrypublish→notify_route_watchers/refreshcomments; весь publish endpoint этим диапазоном не принят |
| `backend/tests/test_route_watch.py` | `5746c7c2ece393e5963f18c6081215780c344d857366ed8f156dec7dc3fd7735`; **1–154 целиком**,10test definitions CRUD/foreign/duplicate/match/self/reverse/spam/expiry/day/invaliddirection |
| `backend/tests/test_route_watch_push_destination.py` | `272ca94203d0e8f26f16790713baaff678be234a0c497405ca6ba23161e80965`; **1–34 целиком**,1definition ×2directions; real backgroundworker/transportcapture/inboxdestination в test source |
| `backend/tests/test_route_watch_respects_privacy.py` | `7f69ce8d461cf1613e4bc1b7fedba03d9ad395ac66132610f127fa530ec7cb4b`; **1–90 целиком**,3defs blocked/trusted/ordinarypositive |
| `backend/tests/test_watch_hears_both_spellings.py` | `db1a5bba337677354a026c822e0eb1b92d231936790eb94b4ff7853e0c45c378`; **1–95 целиком**,4defs RU↔BA/requests/foreignroute |
| `data/ApiClientHardPathsTest.kt` | `7c2d95c20261bc4c843429f392382c7fd3b849e60e0729556a99e40acdd582f9`;275строк, **205–223**, один route create/delete path/body test; остальнойtest файл не принят |

Команды чтения и расчёта — R20; поиск вызовов: `rg -n 'createRouteWatch|getRouteWatches' android/app/src/main -g '*.kt'`; UI tests: `rg -n 'RouteWatchesScreen|addRouteWatch|deleteRouteWatch' android/app/src/test android/app/src/androidTest`; backend: `rg --files backend/tests -g '*watch*'`. Найдены прямые create/list вызовы в RouteWatches и endpoint tests. Отсутствие других прямых имён не доказывает отсутствие косвенных сценариев. Контрольная сумма полного файла не означает его полного чтения. Числа10/1/3/4 — определения тестов, не18 новых PASS; параметризация не добавлялась к числу исполненных тестов.

Связка участников: пользователь A открывает Notifications→RouteWatches, вводит from/to/bothWays, отправляет POST с default watch_kind=rides. Сервер проверяет current_user, ввод, повтор, владельца и срок; затем возвращается актуальный собственный список. Водитель B публикует подходящий рейс; services проверяет приватность, автора, вид подписки, направление, дату и антиспам; создаётся Notification route_watch/ref_kind=ride/ref_id, выполняется commit и фоновая отправка. В центре A нажатие запускает R20 callback/DeepLink.pendingRideId. После DELETE собственной подписки/commit запись исчезает, последующий matching не должен включать A. Это карта исходников и назначения тестов, **не исполненный сквозной путь двух реальных клиентов**.

Разобраны текст формы/Toast RU/BA, from/to/direction, пустые и пробельные значения, ожидание запроса, success с очисткой полей/reload, failure с сохранением полей/serverSaid/Toast; GET loading/empty/error/retry, собственный список/key. Удаление меняет список только после success; failure объясняется. Молчаливое удаление этому экрану не приписывается. AppButton loading связан с принятым общим компонентом R10; это не отдельная проверка всей формы на устройстве. Switch имеет checked у компонента; его объединённое описание и TalkBack ещё требуют проверки. Отсутствие description декоративной иконки не равно дефекту.

Контракт JSON array сервера и getRouteWatches.items согласуется: **ApiClient3498** оборачивает JSONArray в JSONObject.items. Этот слой проверен перед выводом, ложное замечание о контракте не создано. Android DTO не читает watch_kind/created_at/expires_at, UI не показывает watchDate и истечение; влияние на записи других клиентов остаётся конкретной пробой. Android форма дату не выбирает и createRouteWatch не передаёт kind; новые функции без принятого объёма продукта не добавлялись.

### R21-A — дата повторной подписки: подтверждённое различие source, API/PG probe открыт

Сервер create_route_watch в ветви повтора **74** делает `w.watch_date=body.watch_date`; в ветви создания **89** — `watch_date=client_dt_to_utc(body.watch_date)`. Helper timeutil приводит клиентскую дату к UTC без tzinfo, local_date затем добавляет настроенный сдвиг. Идентичная дата проходит разное преобразование при повторе. Это однозначное различие кода; сохранение конкретного значения и потеря matching в настоящей БД **не воспроизведены** этим агентом.

Точное синтетическое условие: изолированный сервер со сдвигом5 часов, ввод без пояса `2030-05-01T23:30:00`, один owner/route/direction/kind. Создание переводит23:30 местного времени в18:30UTC, местный день1 мая; повторное raw23:30 downstream трактует какUTC и получает04:30 2 мая. Расчёт23:30−5h=18:30 и23:30+5h=04:30 следующего дня основан на прочитанных helper, не на production environment. При повторе возле полуночи день может измениться при одинаковом действии и дате. Endpoint допускает datetime, не только00:00.

Переданный root критерий: POST создания→GET/сохранённая дата/id→точный повтор POST→GET/сохранённая дата→публикация подходящего синтетического рейса1 мая→собственная лента/перехват исходящего push. Должны сохраняться id, выбранный день и существенные побочные действия. Контроли:00:00/23:30 без пояса, Z/+05:00/отрицательный сдвиг, None, изменение даты, последовательные/параллельные операции и истечение срока; PostgreSQL для сохранения и типов времени. Возможное минимальное исправление — client_dt_to_utc в обеих ветвях после RED и проверки совместимости. Backend этим агентом не изменён, новый DESIGN-ID не создан: проба передана владельцу блока.

### R21-B — ещё не исполненные варианты и качество тестов

1. **Сохранение ввода:** from/to/bothWays доступны при ожидании ответа. Success633 очищает текущие поля, даже если после отправки пользователь ввёл следующую подписку. Кандидат: настоящий UI вводA→задержанный POST→вводB→ответ200A; B не должен исчезнуть без принятого правила. Loading кнопки не выключает поля. Поздний Back/аккаунтB/ответы reload в другом порядке требуют связи ответа с попыткой и сессией; AppButton этого сам не доказывает.
2. **Дубли/лимит20:** callback628 не проверяет submitting перед запуском; disabled loading применяется после recomposition. Сервер lookup+for+insert не имеет обнаруженного unique/owner lock; последовательный duplicate test не принимает две конкурентные транзакции/21 активную подписку. Созданная двойная подписка не заявляется. Проверять настоящий tap, два одновременных POST, потерю ответа и rollback на изолированном PG. Сброс last_notified_at при повторе/продлении описан как правило; повтор после timeout требует критерия идемпотентности, не незапрошенного ограничения.
3. **Ввод:** server bounds1…120 проверяются доstrip, Android длину не ограничивает. Unicode/RU/BA/пробелы/400/422/пустой успешный id0/повреждённый ответ/отказ GET после успешного POST должны иметь наблюдаемые state/draft/result. Свободное название города сопоставляется через справочник/variants; длинный текст/неизвестный город не названы дефектом без правила.
4. **Адаптация:** fullWidth inputs/Scaffold/LazyColumn есть, IconButton удаления соответствует нормальному исходному компоненту. BA/font2/320dp/IME/dark/landscape, описание switch, длинный маршрут и достижимость submit/delete ещё не измерены. `RoundedCornerShape14` у поля — дополнительное отклонение от Canon shape правила; итоговая геометрия требует общего решения о компоненте. Новый вкусовой ID не создан.
5. **Надёжность отправки:** matching перебирает все активные подписки, дешёвые фильтры идут до trust DB. may_be_notified импортирована, но её внутренний код здесь не разобран. Есть commit, daemon отправки и catch с return0. Отказ worker, завершение процесса до FCM, повторы, отказ одного transport с остановкой хвоста, PG locks/очередь/длительная нагрузка не доказаны. `_push_async` в quiet_hours возвращает без отправки, inbox сохранён. Комментарий «без звука» не принимается как фактическая тихая отправка: в прочитанном условии transport не вызывается. Различие передано как проба.

Тесты RouteWatch10 проверяют HTTP CRUD и данные; foreign DELETE404 плюс сохранённая собственная подписка — содержательный контроль. Matching/direction/self/spam/expiry/date заменяют send_push. Destination1×2 ждёт Event настоящего worker и проверяет точный payload/refId, но не внешний FCM. Privacy3 имеет обычный положительный контроль, но вызывает notifier напрямую, без публикационного пути; RU/BA4 используют HTTP publication/notifications side effects и отрицательный чужой маршрут. Android HardPaths проверяет путь/части body, но не result id/список/submit UI/rollback/повтор. Прямое getRouteWatches UI journey покрытие поимённым поиском не найдено. Исторические отчёты не перечитывались и не объявлялись актуальными GREEN.

Передача R21: root получил различие даты и конкретные PG/UI критерии. Исправлений или новых source/device/build PASS этим агентом нет. Сохранены чтение и незакрытые условия, очередь audit-blocks не изменена. R19 приёмка экспорта актуальна в своей границе и для указанных source hashes; DESIGN-018/019 и предыдущие015/016/017 требуют исправлений/RED/GREEN/независимой повторной приёмки. Следующее чтение — PricingInfoScreen1140–1280 и связанные тарифы после проверки прежних read maps; DESIGN-018 принимается только по изменённым исходникам/тестам/PNG/геометрии от root.

## DESIGN-REVIEW-R22-20261001 — объяснение цены и фактический онлайн-платёж

Дата/агент: 01.10.2026, 11:56 МСК, `/root/design_resume`; B09/B07/B02. Ветка/HEAD/наличие локальных изменений — R19. Цель: продолжить следующий непрочитанный экран PricingInfo, прочитать соседний PaymentInfo и связать обещания/CTA оплаты с capability и авторитетной суммой сервера. Чтение и расчёты выполнены; UI/БД/provider/платежи не запускались. Windows/read-only, собственной сборки/устройства нет. Production состояние провайдера и действующие значения env не открывались. Я не могу это подтвердить.

### Источники, контрольные суммы и прочитанные границы

| Файл | LF SHA256 / область чтения |
|---|---|
| `SecondaryScreens.kt` | `335684e9e57c5aa625c089f3e708de55bce02c95d5c8d59501ba319ccac63b1f`; **1062–1271**: PaymentInfo/PricingInfo/PricingBlock/PricingWhereRow/PaymentStepRow. FilterPrefs1273 далее пока не принят |
| `PayOnlineCard.kt` | `91c056f9197ffbe0bc0e363d845027e446b79f211b8f1a7550eabba38b633c09`; **1–257 целиком**: OnlinePayGate, startPay/checkPayment, Idle/Waiting/Paid, метод/amountKop/URI/browser/ошибки503/остальные, оба sharedheader |
| `data/ApiClient.kt` | `b3d3b34665d5cf339eecabef5a7b27ddd6bab01ea7567fac9b8b1d45bc3e0c70`; **2666–2676/4619–4638/7260–7268**: health capability, payBooking/payInstantOrder/DTO/isPaid. Остальной клиент не принят |
| `BookingActiveTripScreen.kt` | `b161ac19ca66a198f60c8cbcb4abee9f8722e3d9a0d90938d559978ff545d31f`; **1644–1659/2224–2241**: details.payAmount и done/passenger onlineCard; весь экран не принят |
| `RideshareCompletedScreen.kt` | `f9dfb99316abcfd3671fbcc280af627bf99b2684842d477639d8635a7b4002f9`;938строк, **167–176/263–278**: receipt.amount/fallback и call PayOnlineCard/payBooking |
| `TaxiReceiptScreen.kt` | `a15d8315c47b1679642d21fc09fea7156d69528da82f273cddab77d070f181ff`;989строк, **759–774**, passenger/unpaid/amountKop/payInstantOrder; полный чек не принят |
| `YuldashApp.kt` | `92883a6d253da875ddec50a229f2d565e937cb7051556613bf5c1d353a34487f`; **1402–1415**, PaymentInfo→PricingInfo и back; прочие ветви не приняты |
| `backend/app/routers/health.py` | `515ab52eb83ee911f3eb65f6b108fbe057bc6bdaea0012abc3c5eff0f84ad4ab`;137строк, **70–83**: payments capability; остальные health/probes не приняты |
| `backend/app/routers/wallet.py` | `fde65b2b7b18e69cd01e29dd49968b7c39903761732f5ce939384fabc63478bb`;342строки, **1–97/125–183**: schema/method/current owner/done/paid/amount/cash/online and provider guard. Строки98–124/ledger/payouts не приняты |
| `backend/app/routers/bookings.py` | `29ba92c7f7e6fd8a2e702f978e8233cc6485c1807d7930cb9b4fb8aa06eb2d3a`;895строк, **67–93/376–429**, allowed agreed amount/set_pay_agreement/receipt owner/status/amount; полное бронирование не принято |
| `backend/app/config.py` | `5ca09de8521c471cd0e63bf92985ea81bb91b90aca7bdfce4e070d14dfa6411a`; **306–320**, defaultsurge_max_k1.5; runtime override не прочитан |
| `backend/app/instant_service.py` | `6ec14313f8ca897749f598f40e0e602c8dab02771b31339c630110ef6b52274a`;3989строк, **1479–1488**, quote.pricing_cap_k из настроек; весь расчёт цены этим диапазоном не принят |
| `SecondaryDeep4ContentTest.kt` | `9f93c57b7b95bbc3a6aa1001d2b85661f0afb8a69a90e7a78cc724091d6d8230`;378строк, **182–245**:4 PaymentInfo tests RU/BA/шаги/back, часть последующего Filters не принята |
| `PaymentAndMappingTest.kt` | `b871591133b16eb2931a97e6cec6d40b09307a2aa90341b1358e650be557ba8b`; **1–58 целиком**,5helpers SBPlink/rideType, не PayOnline UI |
| `RideshareCompletedGuardTest.kt` | `da148a3409292ddbbbc63af8bec0c3c4e7acbb5554edbc59eca93a573a6c12df`; **1–52 целиком**,3source guards |
| `TaxiPostTripGuardTest.kt` | `cfbfc1ae50b4a4012011b02d97c11dec83b40c2cb11ba1659cd90b4e6a4122f7`; **1–50 целиком**,2source guards |
| `backend/tests/test_health_says_about_payments.py` | `7721cd3d6d3fd76e734efbd257dc8bf84e5460b2fa6dd74401443a78b708023f`; **1–43 целиком**,3defs mock/off/yookassa/public; manual/pending/client UI не проверяются |
| `backend/tests/test_the_wallet_works_and_the_ride_is_free.py` | `34761ea6367c39cd33078255a9f2e8147cbca4ab117efa62f56a6365ca97216b`;290строк, **1–101**, helpers/два commission assertions. Вывод первых180 содержал усечение; весь файл и остальные11defs не заявляются принятыми |

Все hashes LF рассчитываются как inventory. Команды чтения — R20; поиск связей `rg -n 'PayOnlineCard\(' android/app/src/main/java/com/yuldash/app -g '*.kt'`, тестов `rg -n 'PayOnlineCard|OnlinePayGate|paymentsOnlineEnabled' android/app/src/test android/app/src/androidTest`, серверного адреса `rg -n '/bookings/\{booking_id\}/pay|/instant/orders/\{order_id\}/pay' backend/app -g '*.py'`. Наименование платёжного endpoint найдено в wallet.py, не предположено по payments.py. Последний814строчный payments.py лишь индексирован; по нему не заявляется семантическое чтение.

Разобраны статические RU/BA объяснения попутки/такси/надбавки/комиссии/СБП; на PaymentInfo настоящий callback открывает PricingInfo, кнопка back передана обоим. Для static content отсутствие GET/loading/error само по себе не дефект; достоверность динамических обещаний требует связи с сервером. PayOnlineCard связывает actual backend health, сумму/метод, POST pay, confirmation URL, status check и локальный state. Paid-предикат учитывает paid/already_paid/succeeded, positive amount печатается kopToRub и в CTA; unknown amount не передаётся в POST как доверенная сумма. Авторитетная цена определяется сервером, поэтому текст CTA должен точно с ней совпадать.

### DESIGN-020 — отказ запуска браузера скрыт за ожиданием оплаты

Экран/состояние: completed booking/taxi receipt → PayOnlineCard → successful POST возвращает synthetic pending/payment_id/confirmation_url, Android ACTION_VIEW launcher отказывает ActivityNotFound/SecurityException. **PayOnlineCard119** оборачивает startActivity в runCatching и игнорирует результат; **120** безусловно включает Waiting. Пользователь видит **209–218** «Заверши оплату в открывшемся окне», хотя окно не открылось. ConfirmationUrl не сохраняется в состоянии, отдельного повторного открытия/объяснения нет. Это подтверждённая source ошибка failure-state по AGENTS4.5; runtime RED ещё не выполнен.

Влияние: пользователь оказывается в ожидании не начатого внешнего шага, действие «Проверить» не открывает страницу оплаты. Не утверждается, что деньги уже потеряны или реальный провайдер недоступен. Минимальный кандидат исправления: сохранить pending payment/URL, обработать запуск и дать понятное RU/BA действие повторить открытие той же оплаты. Не объявлять новый paid, не создавать новый платёж при каждом launcher retry.

Проверка: настоящий completed/receipt → actual CTA → synthetic pending200/paymentid44/HTTPS fakeURL → controlled throwing LocalContext; ожидать сохранённый pid/URL/error/reopen, отсутствие Paid и второго POST. Повтор с успешным launcher открывает ту же ссылку; status pending остаётся Waiting, succeeded только по серверу становится Paid. Соседние URLempty/idnull/unknownstatus, cancelled, logout/B/late response и browser return. Настоящий внешний provider не нужен для RED, реальные платежи не разрешены.

### DESIGN-021 — доступность онлайн-оплаты не совпадает с режимом сервера

Условие: реализованный settings.is_prod=true/payments_provider=sbp_manual. **health79** возвращает payments="sbp_manual"; **ApiClient2670–2672** считает любой вариант≠off включённым. PayOnlineCard показывает «Карта или СБП — через ЮKassa» и активную CTA. **wallet76–77** безналичный trip платёж в production для любогоprovider≠yookassa отклоняет503. Неправильная доступность подтверждена кодом контракта; состояние рабочего сервера этим агентом не проверялось и не заявлено.

Дополнительное source условие: OnlinePayGate первоначально unavailable=false; **84–87** asked=true выставляется до результата health, обрабатывается толькоsuccess. Пока healthpending либо послеfailure остаётся активнаяCTA, повторная прямая проба в этом процессе не предусмотрена. Комментарий «спрашиваем до показа» не соответствует ветви pending/failure. Runtime UI/probe ещё открыты.

Минимальное направление root: capability должна отражать конкретную разрешённую trip-online операцию и её pending/error, а не произвольный общий тип provider. Не отключать manual-пополнение/донат/долг, которым sbp_manual разрешён отдельно. Production/dev/mock/ yookassa/manual/off/неизвестныйprovider и health400/500/timeout/emptybody проверить синтетически. Значение, ожидаемое для каждого окружения, должно совпадать с wallet guard; root принимает решение по существующим правилам без новых provider/зависимостей.

Защитный путь: actual screen→GEThealth delayed→до разрешения CTA не обещает доступный эквайринг→healthmanual вsyntheticprod→tripCTA отсутствует/объяснение; positive yookassa→CTA/tap и capture pending mock HTTP без внешнего вызова; healthfailure→error+явныйretry→yookassasuccess. Сохранить count/path/state/отсутствие POST в отрицательном случае и RED→GREEN. Server tests43 подтверждают только mock/off/yookassa/public; это не full capability matrix.

### DESIGN-022 — указанная сумма попутки отличается от онлайн-платежа

Приоритет: B07/деньги, перед визуальными предложениями. **BookingActiveTrip2234** показывает в PayOnlineCard `(payAmount ?: ride.price)*100`; **RideshareCompleted173–175/271–274** использует receipt.amount/fallback; **bookings425** возвращает receipt amount=booking.pay_amount иначеbooking.price. **bookings384–385** позволяет участнику изменить отдельныйpay_amount, не price; _clean_pay_amount72–84 разрешает корректную сумму400. Однако **wallet169** online pay_booking берёт `int(booking.price)*100`, игнорируя pay_amount.

Однозначное source воспроизведение: разрешённая booking.price1000 ₽ и pay_amount400 ₽, done/own passenger/unpaid. CTA и receipt показывают400 ₽/40000коп, сервер рассчитывает1000 ₽/100000коп. Разница600 ₽=1000−400, коэффициент2.5=1000/400; это арифметика синтетического случая, **не сумма реального списания**. Runtime/provider/БД RED не выполнены. Условие валидно по прочитанным поля/cleaner/setter/receipt, поэтому подозрение о несовпадающих источниках суммы подтверждено кодом. Бизнес-решение о том, какая сумма должна быть платёжной, ещё не подменяется вкусовым выбором дизайнера.

Влияние: кнопка обещает другую сумму, чем рассчитает сервер для оплаты, и receipt остаётся на иной сумме. Минимальное требование исправления — авторитетная онлайн-сумма должна явно совпасть в UI, запросе провайдера и квитанции; договорённость как отдельную запись сохранить. Нельзя молча менять финансовую базу, скидки/ledger/комиссии или доверять присланной клиентом сумме. Root получил проблему и владеет исходниками/проверкой.

Точный локальный RED: изолированный синтетический driver/passenger создаёт/бронирует1000 → actual pay-agreement400 → разрешённый done переход → actual completed/receipt/CTA → POST pay с заменённым `_start_yookassa`/outbound capture, без денег и FCM. Зафиксировать booking.price/pay_amount, displayed amountKop/текст, calculated Payment.amount_kop/provider request и receipt после подтверждения. Все значения платёжной суммы должны совпасть согласно принятому правилу; новая ledger запись только в syntheticDB. Соседние pay_amountnull/0/равная/меньше/больше/Unicode-invalid/input limit, repeat/две стороны/подмена чужого owner, pending re-open/status/paid/cash, два места показа Card и completed fallback. PostgreSQL для транзакций и concurrent payment; UI fake response не принимает serveramount.

### Измеренные пары и другие непринятые условия

На Pricing surgebg CanonTaxiBg текст CanonText/CanonMuted. Из текущих CanonTokens103–104/126–127 расчёт тем же sRGB/WCAG методом R16: lightMuted4.532925767765066, lightText15.059596880303442; darkMuted5.18586148025669, darkText11.656381569272678. Все≥4.5 для данных исходных цветов; новые контрастные дефекты этим парам не приписываются. Это расчёт токенов, не реальная pixel/device matrix.

PayOnlineCard Idle174–189 использует NearbyFilterChip с minHeight48dp: это дополнительный потребитель старых DESIGN-001/002 (белый12sp над darkGreen/нетselected), runtime bounds/озвучка ещё нужны. AppButton loading общаяR10 проверка не принимает весь Card. BA/320dp/font2/light/dark/IME/landscape/суммы с длинным форматированием и оба метода требуют измерений; анимация source есть, плавность не профилирована.

Pricing1184/1187 жёстко обещает max×1.5; это совпадает с default config313, новый текущий price defect не объявляется. Quote1484 содержит actualpricing_cap_k, статическая страница его не читает; изменение настроек требует отдельной пробы/согласованности обещания. Сравнение с «×3 у больших сервисов» не проверено внешними источниками. Я не могу это подтвердить. Тексты PaymentInfo1129/Pricing1223 «скоро оплата картой» нужно проверить при разрешённомyookassa capability, поскольку реализованная Card уже существует; это отдельный conditionalcontent probe, не утверждение об активном production.

Качество тестов: PaymentInfo4 готовым экраном проверяет заголовки/шаги/back, не tap→Pricing/тарифные данные/paymentcap. PaymentAndMapping5 проверяет чистые ссылки/метки, не платёжный flow. Sourceguards3/2 проверяют присутствие PayOnlineCard/слов, не отказ launcher/charge amount/health. Прочитанные два commission test напрямую вызывают ledger на synthetic records, проверяют0fee ride и positivefee taxi; не actualCard→walletpay→provider. Все эти числа — определения прочитанных tests; новые PASS не заявляются, исторические отчёты не перечитывались. Полный wallet/ledger/provider security audit остаётся у root/B07.

Передача R22: root получил DESIGN-020/021/022, точные wallet169 и receipt425, read maps и критерии. Сохранены source доказательства и ограничения без source changes/Gradle/adb/DB/production/платежей/GitHub/commit. R19 scoped export принят отдельно; это не приёмка этих новых финансовых условий. Следующий доступный статический участок — FiltersScreen1306–1350/сохранение/потребители после проверки прежних maps. Приёмка новых исправлений — только по конкретному diff, первичным тестам и новым кадрам/геометрии в своей области; весь B09/96 экранов и обязательные внешние проверки остаются открыты.

## DESIGN-REVIEW-R23-20261001 — повторная приёмка DataRow после DESIGN-018

Проверка DESIGN-018/B09, связанная QA-B01-021. Дата 01.10.2026, 12:08 МСК, независимый ответственный `/root/design_resume`; исходники и устройство — root. Ветка `audit/full-technical-20260930`, HEAD `6fa0595d88bea4fcd59d4a1e2139c2d128cf6821`, рабочая копия содержит локальные изменения. Этот агент меняет только данный документ. Повтор обоснован изменением DataRow: прежняя приёмка R19 относилась к другой версии MyDataScreen и сохраняется как история, а не доказательство новой вёрстки.

Цель: устранить разрывы коротких башкирских слов в карточке отсутствующих карточных данных при крупном системном шрифте, сохранив значение, пояснение, доступную выгрузку и прежние гарантии смены аккаунта. Принята именно проверенная карточка `cardStored=false` и исходное воспроизведение; весь MyDataScreen и весь адаптив не объявляются проверенными.

### Изменённые исходники и прочитанные связи

Самостоятельно прочитан полный diff старого [MyDataScreen до layout](../test-results/audit-MyDataScreen-before-layout-20261001.kt) против текущего файла. В DataRow403–420 `Alignment.CenterVertically` заменён на `Top`; title, value и note помещены в одну Column с weight1; удалены наружный spacer и конкурирующий по ширине value. Иконка, отступ, типографика, цвета Canon, бизнес-условия и тело выгрузки не менялись. Чтение прежнего полного файла R11 и экспортного изменения R19 дополнено полным текущим diff и разбором изменённой функции; отдельный полный read-map другого reviewer не присваивается этому агенту.

Полностью прочитан [MyDataFontScaleLayoutTest.kt](../android/app/src/test/java/com/yuldash/app/MyDataFontScaleLayoutTest.kt), 1–171: настоящий MyDataScreen начинает с GET `/me/data` в MockWebServer, затем прокручивается до карточки. Синтетические данные, localhost HTTP, БД нет. Восемь случаев вычислены как 2 языка × 2 fontScale × 2 ширины (393/411dp). Проверяется реальный TextLayoutResult: отсутствие потери символов/выхода строк за размер Text, полный набор выбранных слов и отсутствие разрыва внутри них. Значение проверяется дополнительно. Подмена готового MyDataContent вместо HTTP-пути отсутствует.

Текущие хэши LF, самостоятельно сверенные с checkpoint и всеми восемью snapshot-копиями:

| Файл | Строки | SHA256 LF | Основание чтения |
|---|---:|---|---|
| `MyDataScreen.kt` | 641 | `191f70d185c06448c823c538e3b954d8852521457ebc55282041a36ca3167eba` | R11/R19 и полный текущий diff; DataRow403–420 |
| `data/PersonalDataExports.kt` | 55 | `ba913bbb5c5730009b0fc996be6059737bde3e6722e684fe3658c0fa1477b547` | Полное чтение R19; источник не изменился |
| `data/ApiClient.kt` | 7946 | `b3d3b34665d5cf339eecabef5a7b27ddd6bab01ea7567fac9b8b1d45bc3e0c70` | Экспортные/session диапазоны R19; полный файл этим reviewer не заявляется |
| `test/MyDataFontScaleLayoutTest.kt` | 171 | `50b18757e408831b81df06f1b2085061e0077a4384b6408200897a2a6e3677a7` | Полностью прочитан новый тест |
| `test/MyDataExportSessionTest.kt` | 222 | `82613db6782351a2791afd5a2b28dc26f1c614ba53049838071c18fe649d8ffd` | Прежняя экспортная проверка R19; текущий хэш сверён |
| `androidTest/MyDataExportJourneyInstrumentedTest.kt` | 380 | `06e29bcf8d753b148ae59fd33e6dda88add5764a607459b675e21169543bce7f` | Полностью прочитан R19, без изменений |
| `androidTest/data/MyDataExportPrivacyInstrumentedTest.kt` | 140 | `dc3c22e33669439cf6bb164a41ea139f234f34e665554b682a1d4b67a6c6e9ea` | Полностью прочитан R19, без изменений |
| `androidTest/StorageAuditRunner.kt` | 14 | `27a6114a00137b79f5458c2ec4da98c349599e782fa415567697f9a41ef770e8` | Полностью прочитан R19, без изменений |

Пути тестов в таблице сокращены относительно `android/app/src`; настоящие пути, raw/LF хэши, bytes и snapshots находятся в [checkpoint](../test-results/audit-mydata-layout-checkpoint-20261001.json). Самостоятельный read-only Python пересчёт SHA256 raw/LF и bytes подтвердил все восемь sources, 24 artifact-записи, две JUnit-записи и debug APK без расхождений. Эти группы могут содержать одну и ту же запись XML: их сумма не является числом уникальных проверок. Debug APK raw SHA256 `fc7d1d4e5c757b77738ec6f7222f6745903b1008c3c02798edbe8163d745bbc6`, 178618921 bytes. Checkpoint LF SHA256 `34bace007c57e50149ba6f2937cac49ce8e05f0be9610161dd584dbaa9bad42b`.

### Первичные тесты и сборка

Самостоятельно разобраны JUnit XML, failure messages и system-out:

| Доказательство | Фактический результат | Граница вывода |
|---|---|---|
| [Первоначальный RED](../test-results/audit-mydata-layout-red-20261001-junit/TEST-com.yuldash.app.MyDataFontScaleLayoutTest.xml) | 8 tests, 5 failures, 0 errors/skips | Первоначальный oracle сравнивал paragraph constraint с intrinsic Text width; все пять не объявляются пятью ошибками продукта |
| [Исправленный RED](../test-results/audit-mydata-layout-oracle-red-20261001-junit/TEST-com.yuldash.app.MyDataFontScaleLayoutTest.xml) | 8 tests, 2 failures, 0 errors/skips, 16.815с | BA/font2, 393/411dp: реальные разрывы «Карта», «мәғлүмәттәре», «беҙҙән», «шоферға» в старой Row |
| [Layout GREEN](../test-results/audit-mydata-layout-green-20261001-junit/TEST-com.yuldash.app.MyDataFontScaleLayoutTest.xml) | 8 tests, 0 failures/errors/skips, 14.951с | Новый DataRow, два языка/два размера/две ширины, выбранные короткие слова |
| [Соседние late-export GREEN](../test-results/audit-mydata-layout-green-20261001-junit/TEST-com.yuldash.app.MyDataExportSessionTest.xml) | 2 tests, 0 failures/errors/skips, 1.033с | Поздний HTTP и завершение HTTP до IO при смене аккаунта |
| [Сборка и команда](../test-results/audit-mydata-layout-green-20261001.log), [args](../test-results/audit-mydata-layout-green-20261001.json) | `BUILD SUCCESSFUL in 50s`, присутствует `:app:assembleDebug` | Debug, не release; log LF `abf8310f4042d4b519aa22e5f62e2be12f2c0ae57a41cd23a06639cdf630db42` |

Воспроизводимая команда root: `gradlew.bat -p android :app:testDebugUnitTest --tests com.yuldash.app.MyDataFontScaleLayoutTest --tests com.yuldash.app.MyDataExportSessionTest :app:assembleDebug --no-daemon`, Java/config профиля root. Этот reviewer команду не запускал, а проверил сохранённые args и первичный вывод. Исправленный RED относится к тестовой версии170; GREEN171 дополнительно проверяет value. Oracle title/note исправленного RED сохранён, расширение value не ослабляет прежние условия. Сбой ошибочного первоначального oracle не стёрт и не отнесён к продукту.

На API34 Robolectric используется контролируемая Density; это не доказывает одинаковое Android API35 нелинейное масштабирование текста. Проверка `assertIsDisplayed` доказывает присутствие видимой части узла, а не полную видимость пиксельных границ. Box height1100dp и выбранные короткие слова не принимают маленькую высоту/весь экран/все будущие длинные значения. Отдельный критерий actual width requested Box не измеряется. Тест проверяет результат переноса текста, а не наличие Row/Column в исходнике.

### Повтор устройства и самостоятельный просмотр новых PNG

Root использовал `emulator-5580`, API35, 1080×2340px, density2.75 (440dpi), системный fontScale2, светлую тему, RU/BA; ширина в dp равна 1080/2.75≈392.73. Локальный synthetic loopback, сетевые внешние сервисы и БД не используются. [Команда combined11](../test-results/audit-layout-stats-device-baseline-20261001.json): `adb -s emulator-5580 shell am instrument -w -r -e class com.yuldash.app.MyDataExportJourneyInstrumentedTest,com.yuldash.app.data.MyDataExportPrivacyInstrumentedTest,com.yuldash.app.MyStatsExportSessionInstrumentedTest com.yuldash.app.test/com.yuldash.app.StorageAuditRunner`.

Самостоятельно разобран [первичный device log](../test-results/audit-layout-stats-device-baseline-20261001.log), включая все завершающие status codes, не только adb exit0. Все пять MyData journey и три настоящие grant/revoke проверки получили status0. MyStats три получили -2: два подтверждённых failure и один fixture wait timeout по отчёту root; этот reviewer не выполнял полный QA022 разбор и не принимает его исправление. **Combined11 не GREEN**: `Tests run:11, Failures:3`, 55.925с. [Markers](../test-results/audit-layout-stats-device-baseline-20261001-markers.txt) содержат старые и новые PID; к новым кадрам относятся записи PID4074 в08:58:30.631/08:58:38.566 UTC. LF log `7bfa482732d294b386461385d2480bcb472649945e487693e0b51eeab3aead75`; markers LF `169681a00adfec1e754e6b3a8c466d99e8ae8670b02bb49345d704c5b6566052`.

Просмотрены через `view_image(detail=original)` оба новых настоящих PNG; PNG dimensions и raw SHA256 вычислены самостоятельно. [Extraction](../test-results/audit-layout-stats-device-extraction-20261001.json) сохраняет точные `exec-out/run-as/cat` команды root без stderr; кадры сняты actual Journey после GET и прокрутки к карточке/CTA.

| Язык | PNG | Размер/bytes/raw SHA256 | Наблюдение |
|---|---|---|---|
| RU | [Новый русский кадр](../test-results/audit-mydata-layout-device-ru-20261001.png) | 1080×2340, 184646 bytes, `2b4a7c5a9ce874a4f9e76acdbeba30c0ab5ad04880382583ce09f22c6be1fc11` | «Данные карты», «Не храним» и пояснение читаются; «Скачать» целиком видна |
| BA | [Новый башкирский кадр](../test-results/audit-mydata-layout-device-ba-20261001.png) | 1080×2340, 174622 bytes, `cc3acb57c451153eb6e76fb2ec8c13cdf61bb9c414e810c7752ba5a277f7d1e2` | «мәғлүмәттәре», «Һаҡламайбыҙ», «беҙҙән» и «шоферға» целые; «Йөкләргә» целиком видна |

В BA заголовок страницы занимает две строки; видимый верх предыдущей карточки обрезан текущим положением скролла. Это само по себе не новое замечание: снимок намеренно снят после прокрутки к действию. Новый кадр не доказывает всю предыдущую/следующую карточку и полный viewport. Внешний chooser в journey перехвачен RecordingContext; настоящий получатель проверяется отдельными explicit shell-UID2000 grants. Кадры и runtime8 принимают именно этот путь, не root Profile/Login навигацию.

**Результат независимой приёмки:** минимальное исправление DESIGN-018 принято для исходного воспроизведения cardStored=false/RU+BA/font2/light/API35≈393dp и проверенной JVM-матрицы393/411dp/font1/2. Защитный тест ловит прежнее дробление слов, новые кадры подтверждают устранение; выгрузка/сессия повторно прошли восемь device-проверок на изменённом MyData source. R19 MyData source `331ed…`, старые PNG и прежний checkpoint остаются историческими; другие неизменённые доказательства не переобъявляются новыми PASS. Исходный AndroidTest APK combined11 не был отдельно заморожен до диагностической пересборки: текущий test APK нельзя приписывать этому запуску. Это ограничение сохранено в checkpoint.

Открыты: 320dp/другие высоты/ландшафт, dark, cardStored=true, остальные счётчики DataRow/большие значения, driver-doc/delete/loading/error состояния, истинный process death, внешний chooser и копии получателя, physical/TalkBack/release/performance, настоящий backend и весь B09/96 экранов. Я не могу это подтвердить данным профилем. Независимый дизайн-аудит продолжается, очередь и статусы остаются только audit-blocks.

Передача R23: root получает scoped acceptance DESIGN-018 и актуальность MyData8, с явным combined11 failure. Следующий приоритет — доказательства исправления QA022/MyStats либо контролируемая денежная проба DESIGN-022 от назначенных владельцев; собственное независимое чтение продолжается с FiltersScreen1306–1350/FilterPrefs/nav. Только данный документ изменён этим reviewer; Gradle/adb/БД/production/GitHub/commit, фоновые процессы и замки не использовались.

## DESIGN-REVIEW-R24-20261001 — фильтры и доступность следующих поездок

DESIGN-023/B02/B09, 01.10.2026, 12:15 МСК, независимый `/root/design_resume`. Прежняя ветка/HEAD и локальные изменения. Исполненных тестов/устройства/сервера этим reviewer нет; это чтение и однозначное доказательство условий исходного кода. Историческая карта96 экранов и текущая очередь audit-blocks сохранены.

Функциональная связка: Settings → Filters → семь локальных тумблеров → SharedPreferences → при открытии MapScreen загрузка defaults → GET `/rides/near?limit=5` → серверная страница и count → client shownNearby → карточки/пины/filtered/More → открытие выбранной поездки. Фильтры не пишут серверные бизнес-данные; изменение списка участников/броней происходит в следующем пути, здесь не принимается.

### Прочитанные исходники и границы

Контрольные суммы LF пересчитаны самостоятельно; общие файлы прочитаны только в указанных диапазонах, не объявляются полностью разобранными.

| Файл | SHA256 LF / строки | Разобранный участок |
|---|---|---|
| `SecondaryScreens.kt` | `335684e9e57c5aa625c089f3e708de55bce02c95d5c8d59501ba319ccac63b1f` /2490 | FilterPrefs1273–1282; Filters1304–1333; PersonRow1335–1347; navigation row900 как связь R18 |
| `MapScreen.kt` | `b7d83366da56899b8454e85535d9e8ca9bdec9eb7d7b360cd335b42e89d28bc7` /2292 | 271–388: defaults, fetch/date/radius/page/reset, poll/socket, predicate/pins;515–565 chips/reset;569–631 states/More;1190–1194 constants |
| `YuldashApp.kt` | `92883a6d253da875ddec50a229f2d565e937cb7051556613bf5c1d353a34487f` /2618 | 816–834 history;917–921 Back;1417–1437 Filters/Settings wiring |
| `BookingActiveTripScreen.kt` | `b161ac19ca66a198f60c8cbcb4abee9f8722e3d9a0d90938d559978ff545d31f` /3817 | SettingsGroup1342–1345/SettingSwitchRow1383–1400; общий helper уже читался R18, связь здесь уточнена |
| `data/ApiClient.kt` | `b3d3b34665d5cf339eecabef5a7b27ddd6bab01ea7567fac9b8b1d45bc3e0c70` /7946 | getNearbyRidesPaged776–801, query/DTO/count/auth; соседние751–775/803–819 для исключения другого пути |
| `RidesRequestsChatScreens.kt` | `f44e2d651bcc5927699f98c7e9eee6a0eade34ec8c394544211de1ac3bd6f9ea` /3535 | NearbyMoreCard593–618: label/disabled/callback; NearbyRideCard621–630 только связанная оболочка |
| `backend/app/routers/rides.py` | `25f42371e6ee6456e922afa8b6dc279a40108b346b61e9279d29781948e627c9` /861 | Полный rides_near575–666: route/date/geography, PG/fallback, visibility/count/page/serialization |
| `test/SecondaryDeep4ContentTest.kt` | `9f93c57b7b95bbc3a6aa1001d2b85661f0afb8a69a90e7a78cc724091d6d8230` /378 | 42–58 setup/clearPrefs;233–286 четыре Filters-теста |
| `test/BookingActiveTripDeep3ContentTest.kt` | `a5d4673463a106958fbc103f4c1b7fd48ec0f8b59d7904288467e0878ed2d5ed` /182 | 1–50 setup;101–152 два Switch и один Group тест |
| `test/data/ApiClientRidesTest.kt` | `3d6b9e13de218cdd3a238e30558d6dd8de0d600344f4451d415d7996d89dd605` /334 | 1–48 isolated MockWebServer setup;104–131 два paged API-теста |
| `test/RidesRequestsChatDeep2ContentTest.kt` | `4e2acb805033985e2bb90c17d5429045d71e3328e2e267f5ec4d6a047e3f1a34` /583 | 218–292 шесть More/Empty готовых компонентных тестов |
| `androidTest/MapScreenInstrumentedTest.kt` | `a4b467876e3a324091fb011ca41a2dcdd72620a29a5ae34e2b518829811e2d94` /160 | 1–160 целиком: six tests/chrome/backing callbacks/реальные sleeps, без controlled paging proof |

Source paths сокращены относительно `android/app/src/main/java/com/yuldash/app`, тесты — относительно `android/app/src`; серверный путь указан полностью. PersonRow — отображение имени/кнопки с callback, никакого собственного HTTP/хранилища; соответствующие callers blocklist/moderation будут читаться следующим участком. Номера/отпечатки не заменяют semantic чтение полного общего файла.

FilterPrefs.load делает `.toSet()` для собственной копии, а Filters.toggle синхронно обновляет sel и вызывает apply; функция не меняет in-place возвращённый SharedPreferences Set. Семь ключей: women/child/pets/baggage/ac/nosmoke/quiet; составление условий в Map370–383 использует AND, women допускает d.womenOnly либо d.driverIsWoman, nosmoke инвертирует smoking. Пины386 и список620 основаны на одном shownNearby. Временный сброс/чипы карты меняют prefFilter только RAM, defaults сохраняет только отдельный FiltersScreen: это соответствует описанию «при открытии карты», потеря default от временного reset не приписывается.

Settings1437 открывает Filters1418, onBack вызывает общий history goBack; единый эффект824–834 добавляет прошлый экран. Наличие wiring не является actual Settings→Filters→Back device proof. При сохранении нового default уже смонтированная карта использует remember: независимый путь повторного открытия и сохранения экземпляра ещё требует проверки, статическая ошибка не объявляется без подтверждения lifecycle.

### DESIGN-023 — фильтры скрывают подходящие поездки за первой страницей

Условие: сервер имеет шесть разрешённых активных поездок с местами на одном маршруте; в порядке boost_then_depart первые пять quiet=false, шестая quiet=true. Пользователь выбирает quiet в default Filters либо в чипах карты. Данные синтетические, реальные поездки не меняются.

Однозначное доказательство по коду:

1. Map1194 задаёт `NEARBY_PAGE=5`; fetch331 и Api777–799 отправляют limit5 без quiet/прочих prefFilter.
2. Server rides_near576–587 вообще не принимает эти preferences;647–649 считает total6 и возвращает только первые пять; count666 означает полный разрешённый маршрут, не число quiet matches.
3. Client370–383 применяет quiet к полученным пяти, shownNearby пуст; state576 становится filtered, Text597 обещает «Нет поездок с такими условиями».
4. В filtered595–618 доступен лишь сброс фильтра. В list624 More создаётся только при `prefFilter.isEmpty() && nearby.size < nearbyTotal`; ни filtered, ни nonempty preferences не дают следующую страницу. Poll/WS повторяет прежний limit, увеличение626 недостижимо с активным фильтром.

Следовательно, подходящая шестая недостижима при сохранении фильтра. Речь не о вкусе: отрицательная выдача и инструкция снять необходимые условия не соответствуют данным. Source failure подтверждён; настоящий screen/HTTP/БД RED ещё не выполнен, новый PASS не заявляется. Соседний случай verifiedOnly при пустом prefFilter и пустом shownNearby также попадает в filtered без More:626 находится только list-ветви; проверить отдельно.

Минимальное направление владельцу: сохранить доступ к ещё не исследованной странице в обоих состояниях filtered/list и не объявлять отсутствие подходящих поездок, пока источник не исчерпан. Возможен серверный фильтр до pagination либо корректное управляемое продолжение клиентского поиска; не загружать бесконтрольно все записи и не обходить server cap200. При серверном варианте count должен описывать тот же авторизованный/отфильтрованный набор. Порядок boost/depart, date/route/radius/blocked/trusted visibility и прежние defaults сохранить.

Защитный RED→GREEN путь для root: сохранить actual default quiet через настоящий FiltersScreen switch → открыть actual MapScreen с локальным controlled GET и response `{count:6,items:first5}` → проверить, что пустые matches не запирают поиск и не означают исчерпанный источник → действие More/продолжить с сохранённым quiet → запрос limit10 либо offset5 согласно реализации → sixth quiet ride видна и её actual onBookRide получает sixth id. Нужны точные paths/query/count/содержимое показанных карточек, отсутствие forbidden matches и доступность дальнейшего действия, не только Text/200. Отдельно case первые пять содержат один match, но ещё один находится за страницей; verifiedOnly, комбинации двух условий, все/никакие matches, count≤5,200 cap, смена фильтра/route/date пока запрос pending, failure/429/retry и duplicate tap без лишних запущенных запросов. Для server filter — отдельный настоящий API тест до page slicing, роль/блокировки и PG по влиянию. MapKit/outbound-провайдеры должны быть изолированы; существующий MapScreenInstrumentedTest может запускать внешнюю сеть и не является безопасным controlled RED-стендом.

### Качество существующих тестов и непринятые варианты

Четыре Filters-теста проверяют RU/BA labels, один switch→prefs и Back callback. Перед каждым очищаются обе prefs; тест включения не доказывает off/все семь ключей/холодный restart/Map result. Два paged API-теста проверяют count17 parsing и limit5 query, не page2/предпочтения/последующее UI. Шесть More/Empty тестов создают готовые компоненты; они не проверяют условия вызова More в настоящем MapScreen. Six MapScreenInstrumentedTest проверяют видимый хром и onDriver; проверки живой карты/данных после Thread.sleep не проверяют конкретные native view/pins/подходящую поездку и допускают network failure. Ни одному из прочитанных определений не присваивается новый исполненный PASS; воспроизведение DESIGN-023 они не защищают.

UI uses Canon*/CanonItemShape; семь switches используют общий SettingSwitchRow. Это не замер доступности. Отдельный кандидат R24-A: Switch1399 не связан явно с sibling title/subtitle (Row без mergeDescendants/label); controlled semantic tree должен доказать, что каждый из семи switch имеет различимое имя и корректное checked state, затем actual TalkBack RU/BA. До дерева/озвучки новый подтверждённый accessibility-ID не создаётся. Не выводить размер tap-target из padding: Material Switch имеет собственную минимальную интерактивную область, actual bounds не измерены. Ряд целиком не clickable — возможное UX-предложение, не нарушение установленного требования.

Map NearbyFilterChip остаётся потребителем известных DESIGN-001/002, это не новые уникальные замечания. Шрифты/BA wrapping/системные bars/dark/узкие экраны/семь switches scroll/persistence failure и отказ диска не измерялись. Server radius PG exception/fallback прочитан, но PG план/реальные latency/index/логирование исключения и конкуренция не принимаются этой source проверкой. Внешнее сравнительное утверждение комментария380–381 этим агентом не подтверждалось и не используется как основание дефекта.

Передача R24: root получил DESIGN-023 с шестью синтетическими поездками/точным limit5 и условиями actual RED. Очередь остаётся audit-blocks, source исправляет назначенный владелец. R23 scoped DESIGN-018/MyData8 приняты отдельно; QA022 и денежная DESIGN-022 проверка ожидаются от владельцев. Следующее чтение — DocImage1350–1386/AdminDrivers1387–1527 и endpoint/ошибки/права/тесты после проверки актуальности прежних maps; этот reviewer пишет только данный журнал, общих процессов/замков нет.

## DESIGN-REVIEW-R25-20261001 — независимое чтение денежного исправления, PG ожидается

QA-B07-002/DESIGN-022, 01.10.2026, 12:23 МСК, `/root/design_resume`. Ветка/HEAD прежние; локальные изменения сохранены, wallet/test писатель rollback_probe, общие docs/БД root. Никаких tests/PG/провайдера/Gradle/adb этот reviewer не запускал. Историческое source доказательство R22 дополнено первым настоящим локальным API-воспроизведением владельца; итоговая приёмка после PostgreSQL ещё **не объявляется**.

Самостоятельно проверены полный diff текущего wallet против [raw before wallet](../test-results/audit-booking-pay-amount-before-wallet-20261001.py), байтовое равенство bookings с [before bookings](../test-results/audit-booking-pay-amount-before-bookings-20261001.py), новый test1–246 полностью, первичные XML/failure messages/stdout captured observations и изолированные mutation-копии. [Собственный подробный review JSON](../test-results/audit-booking-pay-amount-independent-design-review-20261001.json) содержит read-map, raw/LF fingerprints, testcase names, первичные наблюдения и ограничения; LF SHA256 `6fc8012b7809b4e5f79396c7a368b235aba1c6659c2abfc722cc5ce386947686`.

Source read-map: wallet69–121/154–179 + полный diff (343 строки всего); bookings72–94/376–393/425–443; payments132–200; ledger404–429; services231–241; conftest fixture/env/isolation1–125 и128–151. Непрочитанные части общих файлов не объявляются разобранными. Хэши LF: current wallet `42adc9e31a223e974abc0a5f5f7beb10b5a1dc972ad729736b25f562ca48f225`; raw wallet `f5fc5ea47e99789b8946637eb137a240891a1548bc88d84d2c6c90f8d93557ef`; before LF `fde65b2b7b18e69cd01e29dd49968b7c39903761732f5ce939384fabc63478bb`. Новый test `c0c22e81af0a3ed3424d107a01a42aa341a834a188af55f3d74a4282117e8c0b`,246 строк. Bookings source прежний `29ba92c7f7e6fd8a2e702f978e8233cc6485c1807d7930cb9b4fb8aa06eb2d3a`; остальные точные current хэши сохранены в собственном JSON.

Минимальный production diff169–170: брать `booking.pay_amount`, когда оно не None, иначе price; затем целые рубли ×100. Прежние owner/done/paid/method/amount<=0 guards и payment path не меняются. Явное zero не заменяется price, что соответствует receipt425 и существующей проверке допустимой договорённости0. Никаких новых видимых строк/переводов/версий/зависимостей. Это исправление выбора суммы новой операции; состояние старого pending/позднего изменения agreement отдельно ниже.

### Настоящий локальный путь и результаты

Новый тест проходит actual FastAPI publication → booking1000 → driver agreement400/1200 → детали обоим участникам → confirm → driver-status done → receipt обоим → passenger pay → настоящий persisted Payment → actual activation/ledger/balance → повтор pay/already_paid. Синтетические аккаунты; departure UTC+10мин, что находится внутри существующего early-grace, clock/статусы для этих случаев не подменяются. Только launcher `_start_yookassa` заменён capture, который сохраняет переданный Payment.amount и возвращает succeeded. `_activate_payment`/settle_booking/SQL-записи/receipts остаются действующими. Provider wire/create_payment/вебхук/настоящие деньги не выполнены.

Запуск root/rollback_probe через сохранённые PowerShell runner scripts создаёт child Python без shell и внешних credentials: ENVdev, SMS/paymentmock, payoutsfalse, Redis/FCM/Telegram/geocoder/VAPID/YooKassa credentials пусты. До импорта приложения; тест дополнительно проверяет effective settings. Conftest выбирает SQLite файл по PID и удаляет собственный файл; sessionDB одна на профиль, user_factory создаёт уникальных участников каждого case. Это не отдельная БД каждого testcase и не PG. Fixture выключает лимитер/демо/digest/night/quiet clocks; данные и outbound synthetic. [GREEN runner](../test-results/audit-booking-pay-amount-run-green-20261001.ps1) содержит точную команду `backend/.venv/Scripts/python.exe -m pytest tests/test_booking_payment_agreement_amount.py -q -s --tb=short --junitxml=../test-results/audit-booking-pay-amount-green-20261001.xml` с workingDirectory backend; это проверенная сохранённая команда, reviewer её не запускал.

| Первичный профиль | XML результат | Что подтверждено |
|---|---|---|
| [RED](../test-results/audit-booking-pay-amount-red-20261001.xml), [raw stdout](../test-results/audit-booking-pay-amount-red-20261001.log) | 14 tests,5 failures,0 errors/skips,4.283с | 400/1200 ×card/sbp рассчитывались от price1000; zero создавал положительный платёж |
| [GREEN](../test-results/audit-booking-pay-amount-green-20261001.xml), [raw stdout](../test-results/audit-booking-pay-amount-green-20261001.log) | 14 tests,0 failures/errors/skips,5.402с | Новые операции согласованы; repeat/guards/zero/legacy controls |
| [Соседи](../test-results/audit-booking-pay-amount-neighbors-20261001.xml), [runner](../test-results/audit-booking-pay-amount-run-neighbors-20261001.ps1) | 54 tests,0 failures/errors/skips,5.810с | agreement7 +receipt4 +ledger20 +pending-dedup1 +doubletap5 +latepayment4 +free-ride13 =54 |
| [Price mutation](../test-results/audit-booking-pay-amount-mutation-price-20261001.xml) | 7 tests,5 failures,0 errors/skips,3.363с | Возврат к booking.price пойман четырьмя amount cases иzero |
| [Truthiness mutation](../test-results/audit-booking-pay-amount-mutation-truthiness-20261001.xml) | 7 tests,1 failure,0 errors/skips,3.510с | `pay_amount or price` ошибочно заменяет zero; test падает |

Число14 получается как4 agreement cases (2суммы×2метода) +2legacyNull +4status +2owner +zero +missing. Onboard-case напрямую задаёт model status, поскольку public endpoint для этого состояния в тесте не найден; это guard control, не настоящий UI-path. LegacyNull посеян как старое допустимое значение; прочая операция исполняется actual endpoint. SQLite paid-repeat последовательный, не два параллельных devices/PG blocking.

Самостоятельно разобраны все семь captured observations каждого RED/GREEN/mutation stdout (prints могут иметь начальный pytest '.', поэтому поиск только startswith был бы неполным). До fix обе400-квитанции показывали400, persisted Payment/provider launcher/earn/balance100000коп; обе1200-квитанции1200, финансовые значения100000коп. После fix четыре соответствующие значения40000/120000коп совпадают с обеими квитанциями400/1200 и repeatalready_paid, финансовые rows неизменны после повтора. Legacy1000→100000коп сохранён в двух методах. Zero после fix HTTP409, providerCalls[], paidfalse, Payment[]/ledger[]; до fix HTTP200 и100000коп. Это наблюдение локального synthetic charge, не реальное списание.

Guard cases требуют403 для driver/outsider,409 дляpending/confirmed/onboard/cancelled,404 для missing и неизменные пустые финансовые rows/providerCalls. Тесты проверяют содержимое/баланс/обе стороны/побочные действия, а не только200. Ожидания задаются входной agreement суммой и независимыми receipt/Payment/ledger наблюдениями, не дублируют expression169. Mutations подтверждают чувствительность к исходной ошибке иzero fallback. Изолированные copies содержат по одному изменённому правилу; test байтово одинаков с root; root wallet SHA совпал с mutations-summary. Исторические ошибки сохранены.

54 соседних XML independently parsed, но весь состав этих файлов этим reviewer заново не читается и не присваивается; каждый существующий профиль имеет свои mocks. Metadata neighbor-run «only wallet._start_yookassa captured» не принимается как универсальное описание всех соседних тестов. Реальные provider serialization и авторитетное amount на Android runtime/physical device ещё не наблюдались; Android source R22 остаётся отдельным доказательством отображения receipt/payAmount, не новой device проверкой.

### Соседние денежные критерии ещё открыты

R25-A: bookings.set_pay_agreement376–393 не проверяет paid или существующий pending; services.booking_and_ride_for_user231–241 только проверяет принадлежность. Receipt425/434 берёт изменяемый pay_amount. Поэтому отдельный actual probe: успешная оплата400 → participant agreement1200 → repeat/receipt обеим сторонам и persisted Payment/ledger/balance. Source допускает изменение receipt после уже выполненного расчёта; runtime пока не выполнен. Приёмка14 не доказывает неизменность квитанции после оплаты.

R25-B: pending400 с providerId → participant agreement1200 → repeat pay. Wallet80–104 возвращает существующую pending Payment без сверки новой amount, а receipt показывает текущую agreement. Проверить captured provider amount и подтверждение старого payment/receipt/ledger; не отменять уже принятый провайдером платёж и не создавать второй charge ради совпадения UI. Финансовое правило старой pending суммы должно быть явно определено владельцем, не выдумано reviewer. Runtime/PG reproduction пока открыты; root получил оба конкретных соседних шага.

Итог R25: минимальный source fix и ограниченные SQLite14/54/mutations подтверждены. **Окончательная scoped приёмка QA-B07-002/DESIGN-022 ожидает root PostgreSQL доказательство и freeze/hash.** Whole money, pending change/paid change, cash/client malicious amount/full boundary/max/two devices/real YooKassa/Android UI не приняты. Нет внешних money/provider действий. Продолжаю независимое чтение DocImage/AdminDrivers, пока новые PG/QA022 доказательства у владельцев; единственный writer данного документа, только собственный independent-design JSON добавлен в разрешённой evidence-зоне.

## DESIGN-REVIEW-R26-20261001 — документы и принятие решения модератором

B02/B09 и связь B01/B11, 01.10.2026, 12:35 МСК, `/root/design_resume`. Прежние branch/HEAD/local changes; только чтение исходников и вычисление контраста, без Gradle/adb/БД/реальных документов. Это отдельное продолжение карты96, не приёмка MyStats/денег. Единственный writer данного документа; product source у root.

Связка: Settings/isAdmin → AdminCabinet → AppNavAdmin.AdminDrivers → SecureWindow → GET pending → список/два защищённых фото/OCR-подсказка/подтверждение gender → approve/reject POST → server docs-verdict и gender flag → Toast/reload → результат водителю и публичный badge. Полная обе-стороны runtime связка не выполнена: список и POST, driver status/feed и реальный документ требуют нового evidence, а не отдельных старых зелёных helpers.

### Прочитанные исходники и тесты

| Источник | SHA256 LF / строки всего | Самостоятельно прочитано |
|---|---|---|
| `SecondaryScreens.kt` | `335684e9e57c5aa625c089f3e708de55bce02c95d5c8d59501ba319ccac63b1f` /2490 | 1350–1521 целиком: DocImage/screenTrace/AdminDrivers wrapper/content/AutoCheckRow |
| `SecureImageRequest.kt` | `cfc24b74ba5168067aa581c4fbaff4ef5c91d7b4443f9d137919a5c9520a02c7` /68 | 1–68 целиком: host/scheme/port, relative/scheme-relative, model/header |
| `SecureWindow.kt` | `2e5a913bcd985780ea71e1dc2a00db6d1a86e53a157fbffc664f1a78a879c49c` /61 | 1–61 целиком: depth/FLAG_SECURE/dispose/findActivity |
| `data/ApiClient.kt` | `b3d3b34665d5cf339eecabef5a7b27ddd6bab01ea7567fac9b8b1d45bc3e0c70` /7946 | 1414–1429 endpoints;6639–6643 DTO;6758–6762 parse;495–515 cleanup как связь |
| `AppNavAdmin.kt` | `362a6591b8ffca1128fac98412bce7f8bfc3e606f6f8c966c0095c98359a3843` /73 | Полный R17 read не повторяется; current branch48/AdminDrivers уточнена |
| `CanonTokens.kt` | `91cd5231e9948d9fc4ba6ec013e010562d0ba9ca07f7c5d8d856ff8a11416687` /497 | 83–114 цвета/поверхности, общий R16 read-map сохраняется |
| `backend/app/routers/drivers.py` | `e19c1eb3fb82f43daf1f3727f972014993955bd4e335ad53afa48f218cc2d2b9` /648 | upload/private-doc178–206; pending DTO/admin/moderate569–648 |
| `backend/app/services.py` | `060566080a682cf8f913213f8839de54335ddc710093011d7e2441ac423b7981` /2110 | secure_docs_url93–98; verdict344–359 |
| `test/SecondaryDeepContentTest.kt` | `c5a7948492dc8fdda06faf630a8c6103c37cb348a92c83d755c75bc59ed6dd38` /202 | 1–202 целиком,13 готовых Content tests |
| `test/SecondaryDeep3ContentTest.kt` | `e1a0ae4988f3e81ddc0e0305cb88196f676b1692ed07ea78228fe61b19924d10` /419 | 279–356 семь OCR display/badJSON cases |
| `test/AdminScreensIntegrationTest.kt` | `5fd5656bad1359eae551e5c9f9548cf9fb4886c9ddd9f96d9e18addc8df6d28b` /498 | 195–263 setup/dispatcher;266–293 cleanup;304–320 wait;435–465 два actual GET wrapper tests; остальные комментарии/экраны не принимаются |
| `test/SecureImageRequestTest.kt` | `ee9fd37a7844b273a192134c7ea472796ecff5e2de4aa815b0806149406b058a` /88 | 1–88 полностью,10 helper tests |
| `test/SecureWindowGuardTest.kt` | `ac65a5aa6e019b20f575831a0339a921827213e7abe3a721cdb7e76cd1c40e9d` /83 | 1–83 полностью,3 source-string guards |
| `backend/tests/test_car_and_gender_proof.py` | `5ce74d39aa45eb7074c8c8f4b5b5e1c65d3ff988d986144f274a4568bb761ec7` /171 | 97–171 шесть moderator/gender/queue tests; helpers/предыдущие cases пока не объявлены fully read |
| `backend/tests/test_drivers_edges.py` | `b7e9235b98f2b17943846b142a01c691b12d1a0803cfbc82f01e06f30b3e91cd` /146 | 136–146 missing/reject test |

Android paths сокращены относительно `android/app/src/main/java/com/yuldash/app`, test относительно `android/app/src`; серверные пути полные. SHA256/lines пересчитаны read-only Python; не означают полного обхода общих файлов. Исторические тестовые результаты не запускались и не суммируются как новый PASS.

Wrapper отделяет loading/error/empty/list: GET failure показывает понятное loadErr+ListedError retry, action failure выводит serverSaid/Toast, success выводит approved/rejected и перезапрашивает список. Поэтому замечание «вся модерация молча игнорирует сетевую ошибку» не создаётся. Нет per-item busy и approve/reject можно отправить параллельно; root должен проверить double tap/разные admin actions/reload order/late reply и обе стороны. Backend role-admin проверяется в обеих ручках588/626, target missing404; это чтение guards, не исполненный запрос outsider403. Pending GET возвращает array, клиентский общий list wrapper R21 уже разобран: отсутствующий `{items}` на сервере не новый contract defect.

Gender remember(d.userId) начинает с DTO.genderVerified, показывается только при заявленном gender; approve передаёт boolean либоnull, отсутствие claim не снимает подтверждение. Server verdict344–359 меняет docs-status/verified и гасит gender при отказе; moderate635–639 дополнительно применяет explicit gender к заявленным female/male. Для reject сexplicittrue и race/stale gender нужны отдельные server probes; текущий Android reject не отправляет поле. AutoCheck score не отображён: считать это ошибкой без product requirement нельзя. Blank result скрывает OCR, malformedJSON не падает, неизвестный verdict просит ручную проверку.

### DESIGN-024 — модератору недоступен полный снимок документа

Условие: реальный допустимый снимок прав/машины имеет пропорции, отличные от preview. DocImage1356–1361 задаёт fillMaxWidth/height180dp и **ContentScale.Crop**, никакого onClick/fullscreen/Fit/zoom/pan в функции или обоих call sites1462/1464 нет. Crop заполняет контейнер с обрезанием области исходника при несовпадении аспектов; fixed preview нельзя считать просмотром всего документа. Это однозначное условие кода, не измерение реального снимка: UI reproduction ещё открыт.

Влияние: инструкция «Проверь права»/«Сверь с фото прав» требует увидеть фото, а края с нужными данными/лицом могут быть недоступны. Отсутствие увеличения ещё ухудшает чтение мелких данных; конкретный текст/лицо реального пользователя в аудит не загружался. Предложение минимального исправления: Fit-preview либо отдельное открытие полного изображения с масштабированием/закрытием, сохранив текущую авторизацию и SecureWindow; не выносить документ во внешний общедоступный viewer.

Защитный RED→GREEN: synthetic rectangle image с четырьмя различимыми угловыми метками/центром, портрет/ландшафт и актуальный защищённый HTTP URL → actual AdminDrivers GET/DocImage → доказать доступность всех четырёх краёв в полном просмотре, увеличения/закрытия и возврата в ту же карточку. Измерить viewport/image bounds, а не проверить присутствие AsyncImage по коду. FLAG_SECURE не отключать ради получения кадра настоящего защищённого screen: использовать согласованный synthetic component evidence/semantic measurements; реальные private images/скриншоты не публиковать. RU/BA/font1/2/узкий/широкий/landscape/IME и Back проверяются по влиянию нового viewer. Исправление/устройство пока не выполнены.

### DESIGN-025 — отказ загрузки фото не объясняется и не восстанавливается

Условие: nonblank собственный URL документа возвращает403/404/500, disconnect или повреждённый файл. DocImage1356–1361 не задаёт loading/error/success state, placeholder/error painter, обработчик результата либо retry. Единственная локальная ветка1352–1354 объясняет **blank URL** как «нет файла»; сетевую/decoder ошибку она не покрывает. Кнопки approve/reject1481–1482 не получают информацию о состоянии фото. Нарушение §4.5 измеримое по отсутствующему failure path: человеку нужен понятный отказ и путь повторить, а пустая область не отличает pending/запрет/исчезнувший файл.

Подтверждена структура необработанного отказа; конкретный screenshot/runtime RED ещё не снят. Минимальный fix — отображаемые loading/error/retry состояния DocImage с appText и сохранением auth/host/cache policy. Не вводить автоматически новый запрет административного действия без понимания сценария ручной модерации. Test: настоящий список с synthetic own URL → delayed body показывает loading → broken/404 показывает причину+retry → retry200 decodable image отображён; проверить второй request, тот же URL/auth и отсутствие approve side-effect без нажатия. 403/expired token, новая сессия, malformed image и non-own modelnull отдельно, без исходных документов/токенов в логах.

### DESIGN-026 — контраст OCR-подсказки ниже принятой нормы

AutoCheckRow1508 создаёт `Surface(color=color.copy(alpha=.10f))` поверх CanonSurface карточки1456; label1510 имеет тот же непрозрачный color и14sp, распознанные поля1517 — CanonMuted12sp. Порог проекта4.5:1. Значения токенов взяты непосредственно из CanonTokens84/103/107/109, расчёт sRGB/WCAG из R16 с source-over `background=.1*statusColor+.9*CanonSurface`:

| Тема/состояние/текст | Передний цвет | Результат смешивания RGB 0–255 | Контраст | Порог |
|---|---|---|---:|---:|
| Dark/pass/label14sp | `#27A463` |26.4,48.8,38.7 |4.355752338855512 |4.5 |
| Dark/reject/label14sp | `#F25A4D` |46.7,41.4,36.5 |4.321455054512239 |4.5 |
| Light/pass/recognized12sp | `#686F66` |230.6,240.2,235.3 |4.458332811056055 |4.5 |
| Light/reject/recognized12sp | `#686F66` |249.9,233.7,232.7 |4.432216308961264 |4.5 |

Остальные рассчитанные label пары: light pass5.686874329559031/reject4.584572595143127/error4.560906890769698; dark error5.2655857716552985. Они не объявлены нарушениями. RGB-округление blend к8bit даёт dark label4.348052/4.335499, то есть первые два нарушения не исчезают из-за округления. Математика source, не pixel измерение/скриншот; фактическое растеризованное отображение нужно проверить отдельно. Это иной механизм, чем белый текст DESIGN-001, поэтому сохранён отдельный ID.

Воспроизводимое вычисление, выполненное локально в Python без тестового стенда:

```python
def rgb(h): return [int(h[i:i+2],16)/255 for i in (0,2,4)]
def lum(x):
    return sum(w*(v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4)
               for w,v in zip([.2126,.7152,.0722],x))
def contrast(a,b): return (max(lum(a),lum(b))+.05)/(min(lum(a),lum(b))+.05)
for base,green,red,muted in [('FFFFFF','0B6B3A','CC2A20','686F66'),
                           ('192420','27A463','F25A4D','9BA49D')]:
    for fg in (green,red,muted):
        bg=[.1*a+.9*b for a,b in zip(rgb(fg),rgb(base))]
        print(contrast(rgb(fg),bg),contrast(rgb(muted),bg))
```

Минимальное направление: существующие CanonMint/CanonDangerBg или другая уже предусмотренная canonical пара, с отдельным расчётом обоих label и recognized text; общую палитру не переписывать. Защитный тест фактического AutoCheckRow для pass/reject/error/unknown RU/BA × light/dark должен ловить нужную пару foreground/background, а не только наличие правильного токена в source. Затем synthetic UI с font2/длинными recognized fields, pixel/bounds/retry и доступность. Исправление, GREEN и повторная независимая приёмка пока открыты.

### Ограничения и качество доказательств

SecureImageRequest68 проверяет собственные host/scheme/port и отклоняет external model до Coil; обнаруженного обхода/утечки токена этим чтением не доказано. SecureWindow61 ставит/снимает FLAG_SECURE через depth; фактическое устройство/Recents/физический screenshot не проверялись. SecureImageRequest10 helper tests не наблюдают реальные redirect/header/cache/delete; SecureWindow3 guards проверяют строку в файле, а не наличие защиты именно каждого mounted экранного пути. Private-doc endpoint190–206 требует админа либо владельца имени, отсутствующий файл404, remote-storage redirect; full auth/storage/redirect/signing/cache audit остаётся отдельным B01/B11 критерием. Document memory/disk cache после logout/смены пользователя должен иметь отдельную actual read/render пробу; доказательство отсутствия старого личного изображения не получено здесь.

Тринадцать SecondaryDeepContent tests проверяют готовые состояния/оба языка/approve-reject/gender и сознательно имеют **пустые URLs**, исключая AsyncImage; название «все состояния» не принимает загрузку фотографий. Семь OCR tests проверяют текст/verdict/JSON, не контраст/ширину/перенос/expiry validity. Два AdminDrivers integration tests выполняют wrapper GET против MockWebServer, но не tap moderate/документы и используют пустые URL; setup failFast+reset клиента/изоляция диспетчера разобраны. 20sec ожидание semantics не является performance измерением. Server gender tests97–171 проверяют итог badge/смену gender/reject/legacy field/queue, но не реальную визуальную проверку изображения; test136–146 — missing/reject. Ни один новый прогон этих определений не запускался и не заявлен PASS.

Передача R26: root получил DESIGN-024/025/026 и точные criteria; три source доказательства сохранены отдельно от runtime/предложений. Деньги R25 пока ждут PostgreSQL/freeze; QA022 текущий PNG baseline и ещё не переданный fix не приняты этим reviewer. Следующее чтение — AdminReports1528–1670 и сервер/API/reportCategory/error/action/back; прежний AdminCabinet R17 read-map не повторять. B09/96 экранов, physical/TalkBack/dark/large/small полнота остаются открыты, shared docs/source/resources не изменены.

## DESIGN-REVIEW-R27-20261001 — приёмка источника суммы новой оплаты на PostgreSQL

QA-B07-002/DESIGN-022, 01.10.2026, `/root/design_resume`. Branch/HEAD/local changes как R25; reviewer не запускал БД/провайдера/Gradle/adb. Независимо перечитаны первичные PG XML/stdout, полный runner, preflight/port-owner/server-start records; перепроверены текущие wallet/test hashes. Полные R25 read-map/test/diff/mutations не повторяются без изменений. Это новый этап поверх сохранённой истории R25, а не исправление прежнего результата задним числом.

**Принимается только минимальное исправление источника суммы новой операции**: non-null `booking.pay_amount` (включая0), legacy null→`booking.price`. SHA256 LF wallet `42adc9e31a223e974abc0a5f5f7beb10b5a1dc972ad729736b25f562ca48f225`, нового test `c0c22e81af0a3ed3424d107a01a42aa341a834a188af55f3d74a4282117e8c0b` совпали с R25; исходники не менялись между R25 и этой приёмкой. Полный diff ровно две строки169–170, guards/repeat/cash/receipt не переписывались. Две изолированные мутации R25 подтверждают, что тесты ловят старый `price` и неверный `or` при zero; original hash сохранён.

[Первичный PG XML](../test-results/audit-booking-pay-amount-pg-20261001.xml):68 tests,0 failures/errors/skips, time6.955s, timestamp12:30:06.402257+03:00. Расчёт68 = новый amount-test14 +agreement7 +receipt4 +ledger20 +pending-dedup1 +doubletap5 +latepayment4 +free-ride13 =14+54. Testcase names/classnames сверены, повторные прогоны не считаются дополнительными уникальными проверками. SHA256 XML LF/raw `e8f3f1b65ff08b7308123d5b493504fff0fc032ecb6bf208a6868df74d34a6c5`; [stdout](../test-results/audit-booking-pay-amount-pg-20261001.log) LF `49e864fa59d97633ec1c349bd97dc3a15c60bf272d8a01a49e5d94b48d236f09`. Конечная строка68passed/1warning6.96s: Starlette TestClient/httpx deprecation warning сохранён, это не ошибка продукта и не основание менять зависимости.

В stdout независимо разобраны **семь** marker records: четыре400/1200 ×card/sbp, два legacy-null ×card/sbp, zero. Для всех шести оплат совпали захваченная сумма outbound границы, фактически сохранённые Payment.amount_kop и единственное earn, driver balance и обе квитанции в рублях×100: соответственно40000/120000/100000. Repeat возвращает `already_paid` без новой строки. Zero возвращает409, provider=[],paid=false,Payment=[],ledger=[] — проверены существенные побочные действия, а не только HTTP. Guards проверены исполненными14 cases и определениями R25; PostgreSQL не превратил подменённую outbound границу в настоящий YooKassa.

Стенд подтверждён [preflight](../test-results/audit-pg-pay-amount-preflight-direct-20261001.txt), [run metadata](../test-results/audit-booking-pay-amount-pg-run-20261001.json), [полным runner](../test-results/audit-booking-pay-amount-pg-20261001.ps1) и [server log](../test-results/audit-pg-pay-amount-start-20261001-server.log): PostgreSQL16.15/Windows64,127.0.0.1:55431, serverPID13956, fresh synthetic DB `audit_pay_amount_20261001_8b985b4a`, pytestPID31708/exit0/wall10.3935025s. Runner сначала делает owned preflight/createdb, затем child Python c ENVdev/SMSmock/paymentmock, payoutsfalse и пустыми provider/FCM/Redis/Telegram credentials. Эти значения прочитаны как конфигурация стенда; секреты не публиковались. Занятый55430 принадлежал другому Codex13532 по owner record и не использовался. Server log содержит recovery после прежнего некорректного завершения и последующий ready: это история запуска стенда, не доказательство отказоустойчивости продукта.

Точная сохранённая команда runner, workingDirectory `backend`, executable `.venv/Scripts/python.exe`:

```text
-m pytest tests/test_booking_payment_agreement_amount.py tests/test_pay_agreement.py tests/test_trip_receipt.py tests/test_ledger.py tests/test_rc73_hardening.py::test_pay_dedups_pending_no_double_charge tests/test_payment_double_tap.py tests/test_late_payment_is_not_lost.py tests/test_the_wallet_works_and_the_ride_is_free.py -q -s --tb=short --junitxml=../test-results/audit-booking-pay-amount-pg-20261001.xml
```

[Новая independent acceptance запись](../test-results/audit-booking-pay-amount-independent-design-pg-acceptance-20261001.json), SHA256 LF/raw `f638743a3c69c725b19e0fd1079fdcbdd5f32db236b82d028942ead8325e1e1a`, хранит fingerprints,68 cases/classcounts,семь primary observations/run metadata/read-map и границы. Предыдущий pending-review JSON R25 сохранён неизменённым. Reviewer самостоятельно читал полный новый14-case test; тела всех54 соседних tests повторно не читались, их утверждение в этом этапе ограничено фактическим исполнением XML.

Остатки: actual Android CTA/browser/оба UI/physical/real provider JSON/wire/webhook, cash/full malicious fields/max/refund/cancel/reinvoice, concurrency/different devices и полный B07/B09 не приняты. Последовательный PG прогон не доказывает все блокировки/гонки. R25-A/B agreement edits после paid/pending не закрываются этой приёмкой: root передал новое отдельное QA-B07-003 actual RED6/4failure/2controls, предложение минимальной политики и test ещё разбираются. После изменения wallet/bookings нужно получить новые scoped hash/PG/регрессии — текущий R27 относится строго к указанной frozen версии. Следующий конкретный шаг: независимо оценить QA003 тест/primary observations/lock-order и затем продолжить AdminReports1528–1670; root остаётся владельцем source/build/device/PG.

## DESIGN-REVIEW-R28-20261001 — расхождение квитанции после начала/завершения оплаты

QA-B07-003, продолжение открытых R25-A/B, 01.10.2026, 12:49 МСК, `/root/design_resume`. Критерий: обе квитанции должны честно отражать один выданный счёт и фактические Payment/earn, история денег не переписывается и repeat не создаёт дублей. Это отдельный обязательный денежный сосед, не отмена подтверждённого исправления источника **нового** счёта R27. Branch/HEAD/local changes как R25. Reviewer ничего не исполнял на БД/провайдере и не изменял product source.

Самостоятельно прочитан [test_pay_agreement_after_charge.py](../backend/tests/test_pay_agreement_after_charge.py)1–221 полностью, SHA256 LF `b58bd9afc28d6f45942106f3ca548ff801c40e6d3d8047de9699f000713e19f0`; полный [RED runner](../test-results/audit-pay-agreement-after-charge-run-red-20261001.ps1), primary XML/stdout и [сохранённый отчёт](../test-results/audit-pay-agreement-after-charge-20261001.json). Existing conftest/per-process SQLite/FK/synthetic users из R25; actualHTTP publish/book/agree400/confirm/done/pay/details/receipt/edit/repeat. Только outbound `_start_yookassa` и `fetch_payment` заменены synthetic invoice400/status pending→succeeded; `_sync_provider_status`/activation/ledger остаются настоящими. Fixture дополнительно проверяет dev/mock/fee0/пустые outbound credentials. Android/device/настоящего listening server/провайдера/реальных денег нет.

[RED XML](../test-results/audit-pay-agreement-after-charge-red-20261001.xml) SHA256 LF `0b85d3e6ea75f688ea1541085df8e4d1d8702341587259bf4cc93ce8578d1cf4`:6tests/4failure/0errors/skips/time9.884s. Четыре — driver/passenger×pending/paid edit1200; два controls — повтор того же400×pending/succeeded. [Raw stdout](../test-results/audit-pay-agreement-after-charge-red-20261001.log) LF `84765421ea0cc695ce8a4d91865294efb65ea4891bd4a5e53198653e5391f87d` содержит шесть independently parsed `QA_AFTER_CHARGE` наблюдений до assert, с actual database rows и обеими views. В четырёх отказах setter возвращает200/pay_amount1200, обе details/receipt меняются на1200, фактический invoice/Payment/единственный earn остаётся40000коп. Repeat не создаёт дубль, paid становится/остаётсяtrue. Failure — `paid receipt must match actual charge/earn:1200*100 !=40000`. Controls400 проходят. Подмена provider не рассчитывает amount вместо приложения: она захватывает фактическую Payment и фиксирует единственную invoice; данные db/settlement не подменены.

Before/version: wallet LF `42adc9e31a223e974abc0a5f5f7beb10b5a1dc972ad729736b25f562ca48f225`, bookings LF `29ba92c7f7e6fd8a2e702f978e8233cc6485c1807d7930cb9b4fb8aa06eb2d3a`, как R25/R27. Перечитаны setter371–393, wallet69–179, payments167–204/352–390, ledger404–429; общие файлы не объявляются прочитанными полностью. Существующая [политика ledger](payments-ledger-backend.md)9–18/29–33 требует append-only и равенство суммы Payment/earn; фиксировать историю ложной текущей квитанцией нельзя. Exact новая политика запрета edit после invoice раньше документом не была установлена: reviewer не выдаёт её за исторический контракт.

Предложение root разобрано как минимальное восстановление согласованности: effective amount =pay_amount при non-null, иначеprice; до paid и active pending/succeeded invoice сохраняется возможность договориться; **изменение effective amount** после этой границы отклоняется409 с понятным двуязычным объяснением, без изменения старого400/Payment/ledger/receipt. Идемпотентное повторение того же amount и изменение поля способа без денежного эффекта не запрещать автоматически. None в PayAgreementIn означает «не менять», legacy null fallback должен сохраниться. Метод платёжного счёта/фактической оплаты не переписывать полем договорённости. Canceled/отказ создания без providerId/поздний paid/refund_due/старые несогласованные строки требуют explicit tests и границы, а не изобретения refund/reinvoice действия. Входной guard должен выполняться до присвоения pay_method, чтобы rejected combined request не оставлял частичный side effect. После server отказа клиентская форма должна сохранить введённые данные и показать причину; actual Android путь ещё не принят.

**Предупреждение о порядке блокировок передано root до исправления.** Existing payments._sync_provider_status372/activation179 блокирует Payment, затем ledger.settle_booking406 блокирует Booking. Новый wallet, удерживающий Booking при чтении amount и затем проходящий existing pending sync, может ждать Payment, пока webhook удерживает Payment и ждёт Booking. Это конкретный source lock-order риск, не воспроизведённый deadlock. Agreement Booking-lock плюс plain Payment existence query не требует обратного Payment-lock, но wallet/sync/activation/commit границы нужно согласовать. `_sync_provider_status` документирует внешний HTTP без DB-lock; нельзя считать это по-прежнему истинным, если caller теперь держит Booking. Новые locks должны иметь PG контролируемую edit/pay и repeat/webhook проверку, не только SQLiteGREEN.

Итог: ошибка квитанции подтверждена actual RED, предложенное ограничение суммы принято как направление минимального исправления с указанными контролями; **исправление/актуальный GREEN/PG/гонки пока не приняты**. Root получил read-map, результат и lock-order риск. Не предлагается менять уже принятый провайдером invoice или проводить возврат в рамках этой проверки. Следующее независимое чтение — AdminReports1528–1670; повтор QA003 только по новому diff/freeze/результату владельца. Все source/shared docs/resources остаются у владельцев.


## DESIGN-REVIEW-R29-20261001 — жалобы, предел списка и состояния разбора

B02/B09/B11, 01.10.2026, /root/design_resume. Windows/source+расчёт; branch/HEAD/local changes как R25. Реальный сервер/БД/устройство не запускались. Критерий: найти каждую требующую разбора жалобу, выполнить действие, увидеть подтверждённый итог/отказ и проверить результат у обеих сторон без раскрытия автора цели. Полная runtime цепочка остаётся открытой.

Связка: Settings/isAdmin→AdminCabinet→AppNavAdmin49→GET/admin/reports→DTO/category/status/card→resolve/reject/quality pause/unpausePOST→Report/DriverProfile/commission/notifications→reload/уведомления. Wrapper1539–1550 различает GETfailure и empty, имеет ListedError+retry; action1553–1556 сообщает Toast success/serverSaid failure иreload. Silent action error не объявляется. Per-itembusy/sequence guard reload отсутствуют; doubletap/разные админы/Back/sessionchange — конкретные runtime probes, не подтверждённые этим чтением баги.

### Прочитано и отпечатки

SHA256 нормализован LF; строки всего не означают полного разбора общих файлов. Android source относительно android/app/src/main/java/com/yuldash/app, test относительно android/app/src.

| Источник | SHA256 LF / всего строк | Read-map |
|---|---|---|
| SecondaryScreens.kt |335684e9e57c5aa625c089f3e708de55bce02c95d5c8d59501ba319ccac63b1f /2490 |1528–1663 целиком;2173–2205categories |
| data/ApiClient.kt |b3d3b34665d5cf339eecabef5a7b27ddd6bab01ea7567fac9b8b1d45bc3e0c70 /7946 |1446–1472;6655–6663;6765–6776;4448–4464 связь |
| AppNavAdmin.kt |362a6591b8ffca1128fac98412bce7f8bfc3e606f6f8c966c0095c98359a3843 /73 |31/49, полныйR17 не повторён |
| CanonTokens.kt |91cd5231e9948d9fc4ba6ec013e010562d0ba9ca07f7c5d8d856ff8a11416687 /497 |R16/R26tokens неизменны; новый .12 blend |
| backend/app/routers/safety.py |d85ab8ad178d4561d255b86ed827113670f7ec3239c145013869373eb2814507 /1433 |421–455/459–474 schema;522–557GET;724–843resolve;846–913reject/out/pause;916–920restrictions |
| backend/app/quality.py |5eacad57e9445b678f4f8c2805ee51deebe387e3a58cfa90f24594c62fca233f /528 |1–63category/правила;170–297pause/tell/unpause/обе стороны;346–402ladder/release |
| test/SecondaryDeep2ContentTest.kt |d74779936f357382f5c88a02888848a5fbd9f68030f418fad0138f90eb4c67a7 /140 |1–140 полностью,8 готовых Contentcases |
| test/AdminScreensIntegrationTest.kt |5fd5656bad1359eae551e5c9f9548cf9fb4886c9ddd9f96d9e18addc8df6d28b /498 |467–498 два wrapperGET;setup/cleanup/waitR26 |
| test/data/ApiClientNetworkTest.kt |f47ab68c67c6727928e1c04c3476d30029fb2e46385cb11736afd8e6d2d7e794 /173 |1–41setup;68–78parser;157–163detailerror |
| test/data/ApiClientEndpointContractTest.kt |801ab537fdb4d511c31e4049c35c2581f56c78158aef0a723c4f355f6ca3fd23 /479 |1–67setup;252–255actions;407–479loops/500/empty/tableguard; не всятаблица |
| backend/tests/test_quality.py |dabf0475cb8e3f3ba7ad3fd360d60283c166696afaf80ba14d6fefc3bdcda965 /454 |214–227GETrights/filter;355–425severe/keep/reject/rights/missing; не helpers |
| backend/tests/test_driver_learns_about_the_pause.py |91a5babec77f927506482586377d8a5830ec2900e65cd1f7627b695c9b1c2ee5 /103 |1–103 полностью,4 notificationtests |
| backend/tests/test_complaint_gets_an_answer.py |e602da341d6ffef20953f229f86c8c416f889297b5d14341114a81956216bc82 /111 |1–111 полностью,4 decisiontests/Report seededDB |
| backend/app/routers/parcels.py |ab74fcf78717d7dd97f966ce4a82753b2101c1ad58fa568ad017df4b4051b739 /2054 |1742–1779 dispute→Incident, только связь |

Ни один test этим этапом не запускался. Existingresults не названы актуальным PASS; source/read/сборка/исполнение/устройство — разные доказательства.

### DESIGN-027 — старые открытые жалобы недостижимы из списка

Условие:201 syntheticReports; самая старая new/reviewing dangerous_driving, следующие200 имеют любые статусы, включая resolved/rejected. Server admin_reports535 сортирует iddesc и применяет limit200 после optional status/category, offset/page отсутствуют. Android ApiClient1446–1453 вызывает GET без этих фильтров; DTOбезtotal/next; Content1604–1663 без фильтра/поиска старогоID/перехода дальше. Поэтому старой жалобы вообще нет в данных UI и выбрать её в этом Android пути нельзя. Это однозначное source-условие; actual201 HTTP/UIRED ещё открыто.

Влияние: важная жалоба остаётся недоступной разбору Android-администратором, связанная пауза «до разбора»/notification могут ждать решения.201=200+1 из фактического лимита, **не** найденный размер productionDB. Все клиенты/API не объявляются заблокированными: API отдельно позволяет status/category, web не проверялся.

Минимальное направление: честная очередь открытых и согласованная пагинация/история. Молча удалить cap и грузить всюБД нельзя; только фильтр открытых не покрывает201 открытыйReport. RED→GREEN: synthetic201→actualGET/обычныйAdminReports→найти первую открытую черезpage/filter→resolve/rejectPOSTправильногоID→serverstate/обеnotificationviews. Затем errorpage/retry/стабильнаясортировка/нетдублей/удалённаяцель/rights403 и границы200/201. Исправление/сборка/GREEN/дизайн-приём пока открыты; root получил criteria.

### Дополнительные контрастные потребители

Categorybadge1624–1625:12spBold, foregroundCanonRed при severe иначеCanonGreen2, Surface fg.alpha.12 поверхCanonSurface1619. Source-over и формулаR26 дают:

| Пара | foreground | BlendRGB0–255 | Контраст |
|---|---|---|---:|
|Light/severe |#CC2A20 |248.88,229.44,228.24 |4.4400834043777895 |
|Dark/normal |#27A463 |26.68,51.36,40.04 |4.225870030680085 |
|Dark/severe |#F25A4D |51.04,42.48,37.4 |4.212568917068566 |

Три пары ниже4.5, light/normal5.513510632607948 проходит. Это тот же alpha/status механизм DESIGN-026, новый ID не создаётся.12spBold не крупный текст с меньшим порогом. Actualpixels/device не измерены. Проверить обе подписи/status/categories/themes/lang/font2/длинный текст после canonicalfix.

ApproveButton1646–1647 с containerCanonGreen2/TextбезcontentColor — дополнительный открытый consumer DESIGN-001; R10 его не менял. heightIn44dp само по себе не доказывает touch-дефект: Material может расширять интерактивную область до48dp, нужны actualbounds. HeaderRow1620–1640 category/status/date на узком/font2/длинном БА — кандидат на измерение, не объявленный source overflow. Subjective предложения отделены от рассчитанного нарушения.

### Состояния, права и границы тестов

ServerGET/четыреPOST требуют adminrole; missingReport404/missingdriverProfile404/hours1…8760 validated. Deletedtargetnullable→targetUserId0 иpausebuttonsскрыты. GET batches users, не queryperrow. Resolve сначалаcommitReport, потомjournal/quality/commission/notifications; часть побочных ошибок catchlog. Repeatedresolve/reject старогоresolvedstatus/sideeffectпотеряответа/race остаются отдельными probes, атомарность денег/notifications не подтверждена чтением. Android передаёт resolution="" иkeepPauseboolean, severeимеет оба решения; отсутствие редактора причины — предложение/неустановленное требование.

Qualitypause толькоудлиняется иуведомляет,unpause такжеуведомляет; author узнаёт итог, target узнаёт rejection безidentityавтора; ladder/release учитывают другиеopen severe. НастоящиеFCM/targetUI/всеprivacyboundaries не проверены. Четыреpause ичетыреdecisiontests подменяют pushdelivery и проверяют настоящие Notificationrows; complainttests seedReport, не экрансоздания. Test_quality214–227 проверяет admin/filter одногонабора, неcap200;355–425severe/keep/reject/pause/rights. EightContentcases проверяют readyloading/error/retry/emptyRU/BA/list/blankfields, **не** resolve/reject/keepPause/72h/categories/status/contrast/font2. Twointegrationcases actualGET/list/empty, **не**tapPOST; sampledefaultother/new. Networkparser/detailerror иgeneratedendpointtable не доказываютbody/sideeffect/пользовательскийпуть; таблица из реализации требует независимыйservercontract.

Не создан ложный DESIGN-ID по parcel_dispute: исторический комментарий ReportOut472 называет Report, но current parcel_dispute1742–1779 создаёт Incident другой системы, Android4448–4464 использует этот endpoint. Legacyrows/unknowncategorymigration требуют отдельной проверки; текущая parcel-функция не доказывает её попадание в AdminReports.

Передача R29: source/hashes/вычисления и открытые runtimecriteria сохранены; root получил027/026/001. Следующий приоритет — independent новыйQA003diff, затем AdminPaymentRequests1721–1910/API/денежныеadminactions. CabinetR17 не повторяется. QA022поfrozenеvidence. ВесьB09/96screens/physical/TalkBack/матрицаlarge-small-dark остаются открыты, sharedsource/docs/resources не тронуты.


## DESIGN-REVIEW-R30-20261001 — новое денежное ограничение и повтор после canceled

QA-B07-003,01.10.2026,/root/design_resume. Самостоятельно прочитаны полный нормализованный diff against [rawbefore](../test-results/audit-after-charge-before-fix-20261001),currentwallet69–139/174–205,currentbookings371–407,helper231–241/get_session93–95 и актуальные тестовые изменения. Wallet368LF f603cfc900d54ff0cce7601f6488ca4a3831207356d0c9469b900424d47a1e75;bookings908LF80a468626532e06271827ec16f443cd13cfafa264f11680e5328cd9cbc9a73c3. Source не изменялся reviewer; новая версия снимает актуальность R27 как доказательства текущего wallet, история R27 сохранена.

Amountedit получает freshBookingFORUPDATE,проверяет effectivechangedamount/paid/plainpending-succeededPayment до любых полевых присвоений; None/same/methodonly разрешены. НовыйpayBooking держитBooking до durablependinginsert,initialmethodyookassa сохраняет незавершённую границу передoutbound. Existingpending освобождаетBooking commit передsync/start; прежний Payment→Bookingorder не инвертируется. Reviewer нашёл в initialfix blindINSERT после canceledsync при двухretry; root устранил его до приёмки: freshBooking+paid/done/amount и возврат в commonpendingdedup под повторнымlock. Эта ветка самостоятельно перечитана. Root получил оба замечания,controlledPG пока нужен: sourceисправление не равно доказательствуlock/гонок.

[Свой независимый reviewJSON](../test-results/audit-booking-pay-amount-independent-design-after-charge-review-20261001.json) LF/rawbdc2eef9e99db274999670189e40019900415020b85dd60e1a479b706772bebd сохраняет readmap/currenthashes,old74cases/primaryobservations/failures и границы новых тестов. PrimaryXML [first74](../test-results/audit-after-charge-sqlite-green-20261001.xml) имеет6fail/74/time13.897; все6 — internalPayment.methodyookassa против прежней fixturecard/sbp, amount40000/120000/100000 совпадал. Это не шесть новыхamountдефектов. [SQLitev2](../test-results/audit-after-charge-sqlite-green-v2-20261001.xml)74/0/time8.680, [PG74](../test-results/audit-after-charge-pg-green-20261001.xml)74/0/time8.805;74=originalaftercharge6+amount14+neighbors54. XML PG LFcae156fb50d8fab06f7b84a44ed0661000b4b3f5b0dbbe692556dfd62cc85531; SQLitev2 LFe99732af1a62033764169a90bd0264f86f981fee7fbb2b6477e7dffdb8b77fc8. Первичные sixafterchargePGmarkers проверены:1200edit409bilingual, обеviews400;same400200;actualinvoice/Payment/единственныйearn40000 безdup. FreshPGDB audit_after_charge_2966ad4ada/localhost55431,pytestPID9752/wall12.063; [runner](../test-results/audit-after-charge-run-20261001.py) полностью прочитан:dev/mock/пустыеcredentials/freshcreatedb/childбезshell. Реальногоoutbound нет.

Currentafterchargetest285LF56c067184f098e7108bebebccd6ac02ad5bbfc103c3883a50006c4e175b049de:первые221line LFhashb58bd9afc28d6f45942106f3ca548ff801c40e6d3d8047de9699f000713e19f0 **точно совпали** с originalR28, добавлены пятьcases222–285, прочитаны полностью. Combinedmethod+changedamount409 pending/paid проверяет nofinancialchanges/обеviews/paymethodsbp;methodonly/sameeffectiveamount legacyNone/non-null проверяет повтор того жеPayment/noearn;cashpaid проверяет freeze/repeat безPayment/ledger/provider/balance0. Currentamounttest248LF7de1261b44b8de453173cdb19bb90d3409da9207829b347fe4c95ade027eadee: fulltargetdiff меняет internalexpectedtag наyookassa и misleadingkeyrequested_method наstored_method; actualcard/sbpHTTPparameterization и всеfinancialasserts сохранены. Это поправка oracle внутреннегохранения, не ослабление суммы. Realprovidercard/SBPwire этимtagне доказан.

На моментR30 этипятьcases/currentwholetest ещё не приняты; root позднее сообщилsequential79/79finalSQLite/PG. Новый79primaryreview иraceprofile будут отдельнымследующимэтапом, предыдущая74историянепереписывается. Sourceguard принят к runtimevalidation; финальнаяQA003приёмка ещё ждётcontrolledPG/freeze. Existingисторическиеmismatchedpaid/pendingrows не ремонтируютсяэтимpreventfix; этотостаток rootоставилвреестре. ActualAndroidagreementform409/draft/realprovider/wholemoney/physical не приняты. НовыйBAerrortextнуженnative review. Reviewer не запускалБД/эмулятор/сборку/провайдера.

## DESIGN-REVIEW-R31-20261001 — административная очередь оплат и списание долга

B07/B09/B11,01.10.2026,/root/design_resume. Sourceonly/Windows,прежниеbranch/HEAD/localchanges;эмулатор/БД/деньги неиспользовались. Прочитан весьAdminPaymentRequests1721–1910,цель — проверить доступность очередей/частичныеошибки/точные суммы/состояния действия/контекст обеихсторон.

Связка:AdminCabinet/AppNavAdmin52→триGETsummary/debts/pending→aggregateDebt/paymentcards→confirm/reject/forgivePOST→Payment/CommissionDebt/ledger/boost/notifications→Toast/reload/driverstatus. Busykeys защищают повтор одногоaction; противоположныеpay-ok/pay-no/debt-ok/debt-no/debt-forgive имеют разныеkeys, поэтому разныеactions однойстроки могут пересекаться. Root должен проверить серверныепереходы/двухадминов/таймаут/ответпозжеBack; buttonsperaction не доказательство serverидемпотентности.

### Read-map/SHA256LF

| Источник | Отпечаток / всего строк | Прочитано |
|---|---|---|
| SecondaryScreens.kt |335684e9e57c5aa625c089f3e708de55bce02c95d5c8d59501ba319ccac63b1f /2490 |1721–1910 полностью |
| data/ApiClient.kt |b3d3b34665d5cf339eecabef5a7b27ddd6bab01ea7567fac9b8b1d45bc3e0c70 /7946 |2346–2432;5094–5095;6848–6854;6928–6932;7206–7232 |
| AppNavAdmin.kt |362a6591b8ffca1128fac98412bce7f8bfc3e606f6f8c966c0095c98359a3843 /73 |52уточнена,полныйR17неповторяется |
| backend/app/routers/payments.py |ff43f24ec5720487807b550bbe3a174942b9e6f707144aa81bbe24ffa053a670 /814 |708–794pending/confirm/reject/summary;activation167–204изR28;не весьфайл |
| backend/app/routers/debt.py |88db9cf5bb3071a284bd33881de15a3e46a663fe9043f7029ef9fefc1f2c797d /277 |122–277GETgroups/confirm/forgive/reject/schema |
| test/data/ApiClientMoreTest.kt |8880a8aa021ff174d4a6837677cd4b58adbe839ba4076f255aff0ac0edd52513 /586 |465–551семьpaymentAPIcases;не весьtest |
| backend/tests/test_driver_money_gaps.py |0737df87e5a3bcbf8ac87eadbde813dc611bf95fdfb75b4d45f6fecf2d900d3e /145 |94–111singleforgive |
| backend/tests/test_money_holes_audit.py |db78a29adc3c9b63ccceac47fb35a123169190a8b7c3d36a485ebe5a3978e3ef /331 |258–284singleforgive/accounting |
| backend/tests/test_payment_answers_the_payer.py |0e9af0c98d93855670c182b310f7906055e021a4fe4c9556b41aff3c925d3236 /96 |1–96 полностью,4notificationtests |

Androidpathsотносительноandroid/app/src/main/java/com/yuldash/app,testsотносительноandroid/app/src. Никакиеtests этогораздела неисполнялисьreviewer. СемьAPIcases проверяют parser/null/empty/twoPOSTpaths/summary/500failure, не wrapperpartialGET и неdialog/draft/batch. Singleforgiveness servertests доказывают своимиasserts intent однойзаписи,неaggregatedUI. FournotificationtestsseedPayment/pushmock,неliveprovider/FCM/userjourney. Structuralrg не нашёл AdminPaymentRequests UItests в test/androidTest поназванию/новымactions; отсутствие найденного имени не доказательство полного отсутствия косвеннойпроверки.

### DESIGN-028 — ошибки двух очередей скрываются за пустым/устаревшим результатом

reload1744–1752 принимает толькоonSuccess дляsummary иdebts, onFailure только pendingpayments. При initialsummary500/debts500/pendingpayments200[] loadingfalse/errornull/initialdebts[], UI1828–1832 выводит «Нет заявок на оплату», включая долги, хотя их отсутствие не установлено. При повторе прежниеsummary/debts сохраняются без отметки отказа, ихactions доступны; ошибку иустаревание человекнеразличает. Sourceветви подтверждены,actualHTTP/UIREDещёнеисполнен.

КритерийRED→GREEN:controlledactualwrapperGETsummary200/debts500/pending[]→показать debtfailure/retry,неутверждать empty; retrydebts200syntheticgroup→строка/сумма/actions. Разныеfailureкомбинации/401/timeout/lateorder/стараястрока/перезапускбезdraftбезреальныхаккаунтов. Summaryfailure отдельно понятен,partialsuccess виден; соседниеуспешныеочереди не прятать безпричины. Fixминимальногорезультатногоstate у владельцаsource,сборка/защитныйUItest/PNGpending.

### DESIGN-029 — после отказа списания введённая причина теряется

Dialog1880–1899 позволяетввестипричину до300символов. onClick1890–1895 сохраняетлокальноreason, **закрываетforgiveTarget ДОPOST**,failureToast1898 не возвращаетдиалог. Повторныйopen1811–1813 сразуforgiveReason="" — набранноенеостаётсявпользовательскомпути. Ошибка сообщенаToast,еёнеобъявляемsilent;теряется именноdraft. Sourceоднозначен,actualREDещёнеисполнен.

SyntheticactualGETgroup→tapforgive→ввестидлинныйRU/BA/Unicode→POST500/timeout→ожидается reason/targetсохранён сяснымretry,ниspurioussuccess/ниduplicatePOST. Retry200→success/reload/actualdeclaredsideeffect;cancel неPOST. Busy/cancel/pending/differenttarget/accountchange проверяютсянастоящимwrapper. Минимальноочищать/закрывать послеподтверждённогоуспеха,анешибки;новыеUIстрокиappText/nativeBAreview. Sourceнеправился.

### DESIGN-030 — общая сумма долга показывается, списывается одна запись

AdminDebts122–151 группируетpendingпоdriver,amountKop=SUM,representativedebtId=latest;Android2401–2419/1800 показываетSUM. Forgivedialog1872–1876 повторяетэтуSUM иобещает «Долг исчезнет,таксиразблокируется». Api5094 посылает толькоrepresentativeID/reason. Serveradmin_forgive215–244 загружаетОДИНCommissionDebt,ставитpaid этойзаписи/возвращаетforgiven_kopэтойзаписи;остальныеpendingнеменяет. Confirm/rejectдругиеметодызакрываютбатч,ихнельзяприписатьforgive.

Минимальныйsyntheticпример:двеpendingодногоdriver1000+2000коп →UIamount3000коп=30₽/latestid;forgivelatest→forgiven_kop2000коп=20₽,1000коп=10₽остаютсяpending. АрифметикаизSUM/singlewrite,а неactualБДрезультат;actualAPI/UIREDещёоткрыт. Полноеразблокированиетаксизависитотостальныхдолгов/порогов/сроков иэтимчтением не подтверждено;обещанноеисчезновениевсейпоказаннойSUMуже противоречитsinglewrite.

Не менятьнаwholebatchвслепую:прощениеможетбытьнужнодляоднойпоездки. ВыбранныйUI/контрактдолженпоказыватьправильныйобъём:singledebtсразбивкойлибоexplicitbatchсsnapshot/amountguard/history/причиной/двойнымиadmins/race. RED→GREENactualGET2debts→dialogtap→POST→обеDBrows/forgivenamount/remainingdriverdebt/notification/taxigate/новаяUIстрока;безреальныхденег/production. Existingsinglecase3300test незащищаетэтумismatch.

### DESIGN-031 — подтверждение отменённого перевода отвечает ложным успехом

Manualpendingсprovider_id="" иmethodнеyookassa →adminreject/canceled→adminconfirmтогожеID. Confirm741–756 пропускает canceledчерезmanualguard,вызываетactivation; activation179–191 длянеpendingсразуreturn; **confirmвсегдавозвращаетstatus=succeeded**,непроверяясохранённыйрезультат. Android2355.map{} +1854Toast «Оплатаподтверждена» объявляют успех. Это source-confirmed statemismatch,actualAPI/DBREDещёнеисполнен. Неphantomearn: pendingguard какразпредотвращаетактивацию;серверврёторезультате.

Воспроизведениеowner:syntheticmanualdonate/boostpending→GETcard→reject200canceled→confirmstaleID→сравнитьbody/capturedDBstatus/boost/ledger/notifications иUItoast;затемoppositefasttap/differentadmin иpending/succeeded/providercontrols. Fixтребуетразрешённогоперехода/честногоresponseподlock,анеповторнойактивацииcanceled;client долженотображатьреальныйитог. Serverproviderpaymentsизmanualqueue исключены(708–720),confirmprovider409,новыйbookingdurabletagR30помогаетнепопастьвручнуюочередь. Необходитьproviderpolicy радипроверки.

Дополнительно:GETmanualpending708–738неограничен ипоследовательночитаетpayer/adпоrow — perf/N+1probeпомечен,неизмереннаянагрузка. Sumrubцелочисленный//100/DTOamountint неиспользуеткопейкиmanualpayments;нецелыесуммыдляданноговиданеобъявленыдефектомбезпроверкиcreators. Donate19spBold/Debt19spBold,sharedGreenButton/QRclickсемантика/longnames/phone/privateWindow/IME/font/theme — runtimeдизайнматрицаещёоткрыта,необщийPASS.

ПередачаR31:rootполучил028–031,исправлений/новыхruntimeдоказательствещёнет. СледующеечтениеAdminRequest1914–1963/AdminResponses1967–2037,apicalls/author/results. QA003sequential79/PGrace иQA022frozenUIпринимаютсяпопервичнымрезультатам отдельнымэтапом. Общиеисточники/доки/устройство/БДнеизменены;единственныйписательdesign-doc.

## DESIGN-REVIEW-R32-20261001 — независимая приёмка QA-B07-003 в заданных границах

Дата: 01.10.2026; ответственный /root/design_resume. Связь: B07/QA-B07-003, сосед DESIGN-022; текущий статус и остатки ведёт root только в [audit-blocks.md](audit-blocks.md). Ветка audit/full-technical-20260930, HEAD 6fa0595d88bea4fcd59d4a1e2139c2d128cf6821, локальные изменения присутствуют. Чужие изменения не присваиваются. Это независимое чтение исходников и сохранённых первичных результатов владельца стенда; reviewer не запускал БД, провайдер, сборку или устройство.

Критерий: договорённость, новый счёт, Payment, единственное начисление, баланс и квитанции обоих участников сохраняют одну сумму. Изменение суммы после pending/paid отклоняется до записи других полей; повтор той же суммы и запись способа сохраняют прежнее допустимое поведение. Параллельная оплата не создаёт второй действующий счёт; обработка webhook не образует взаимную блокировку с повтором.

### Версия, чтение и воспроизводимость

Независимо пересчитаны все 56 записей frozen checkpoint [audit-after-charge-checkpoint-20261001.json](../test-results/audit-after-charge-checkpoint-20261001.json), SHA256 LF 1af49f24e7fb8e0fa7a8ccaad3f07710ef91f68631ef5da8bdf063966eb25c08: 14 текущих source/test файлов, 14 идентичных raw snapshots и 42 артефакта. Расхождений нет. Снимок файла не означает прочтение всего файла.

Текущий wallet.py: 368 строк, SHA256 LF f603cfc900d54ff0cce7601f6488ca4a3831207356d0c9469b900424d47a1e75. Прочитан весь diff относительно сохранённого QA003 before и существенные ветви 69–139/174–205. Bookings.py: 910 строк, 8e36a71eb07d7fed744def2d434702c08a8a78cb0561fb01bfe91333a2f7cca2; полный diff, 72–82/377–408/420–458. Последнее отличие от версии R30 — только исправление ошибочного описания функции; исполняемый код неизменён. SQLite79 относится к версии до этого исправления комментария; итоговый PG85 выполнен на текущем снимке.

Новый after-charge test1–285 разобран полностью: прежние 221 строка совпадают по prefix SHA с RED R28, дополнительные пять проверок прочитаны отдельно. Concurrency test1–420 прочитан полностью; проверены реальные SQL-блокировки, события управления чередованием, проверки обеих сторон и финансовых строк. Amount test1–248: прежний полный разбор R25 дополнен полным diff; HTTP card/sbp и проверки сумм не ослаблены. Полный read-map, отпечатки, команды, первичные записи и границы — в собственном [independent-design-after-charge-final JSON](../test-results/audit-booking-pay-amount-independent-design-after-charge-final-20261001.json), SHA256 LF bf05bef6c52adc62391ec6aabf0b03bc4651f3aca227750203e46e320feaf8b0. Исторический JSON R30 сохранён.

Связи проверены в пределах: payment activation167–204/sync352–390; ledger404–429; services231–241; get_session93–95; Booking503–566/Payment1155–1178/LedgerEntry1189–1203; errors1–13. Корректно: новое блокирование Booking предшествует чтению суммы; setter проверяет только изменение расчётной суммы, до записи способа; первая pending строка имеет yookassa до внешнего вызова; повтор освобождает Booking перед Payment→Booking activation; после canceled повторно получает свежий Booking и повторяет общий поиск существующего счёта. Выявленные reviewer риски обратного порядка блокировок и второго INSERT после canceled устранены до приёмки.

Воспроизводимая команда pytest и безопасная конфигурация записаны в каждом runner JSON и [audit-after-charge-run-20261001.py](../test-results/audit-after-charge-run-20261001.py); их содержимое прочитано. Окружение владельца: Windows11, Python3.12.14, PostgreSQL16.15, изолированные новые БД на 127.0.0.1:55431, синтетические участники. Реальные FastAPI TestClient запросы, права, SQL, webhook, квитанции и ledger; outbound create/fetch заменены, ключи внешних сервисов пусты, комиссия0. Отдельный HTTP-сервер/socket E2E и Android API/размер/язык/тема в этом профиле не проверяются.

### Первичный результат и качество тестов

Итоговый [PG integration XML](../test-results/audit-after-charge-pg-integration-20261001.xml): 85 тестов, 0 failures/errors/skips, 11.383 секунды pytest; runner PID24620, 14.469 секунды wall, свежая БД audit_after_charge_28e1ee7b59. SHA256 LF XML 12322818713c6a208a97d2a5189e5cebf05db1938b952a2c67fed29667e117e5; log45556830d659453f59e8db0b03fa7ac5d3069c8d1c5c197cb28d872221ca8f1b. Формула из имён testcase: 11 after-charge +14 amount +54 соседних +6 PostgreSQL concurrency =85. Прежние74/79/6 и повтор этих случаев на другой БД не прибавляются как новые уникальные проверки.

Ранее независимая проверка первичных XML также подтвердила SQLite79/79, PG79/79 и PG6/6. История первоначальных шести ошибок ожидания внутреннего method при74 сохранена R30: реальный durable tag стал yookassa, ошибочное имя captured requested_method заменено stored_method; фактические card/sbp запросы и финансовые критерии сохранены.

В финальном log независимо разобраны и сопоставлены 6 AFTER_CHARGE, 6 AGREEMENT, 1 ZERO, 11 PG_RACE записей состояния, 6 LOCKS и 1 COMMITTED_GAP. Это 11 записей промежуточного состояния, а не 11 concurrency-тестов. Проверены обе квитанции/договорённости, provider capture, реальные Payment/earn/balance, количество строк и статусы. Четыре edit1200 после pending/paid получили409 и сохранили400/40000; same400 получил200. Zero получил409 без счёта/начисления/внешнего вызова. Пять дополнительных контрольных случаев проверяют отсутствие частичной записи method при409, legacy None/sameamount/method-only и наличную paid бронь без online ledger.

| Проверенное чередование PostgreSQL | Сохранённый результат |
| --- | --- |
| Изменение суммы получает Booking первым | Оплата ждёт реальную блокировку; новый счёт120000коп, обе квитанции1200₽, одно начисление |
| Оплата получает Booking первой | Изменение получает409; счёт40000коп и обе квитанции400₽ |
| Первая pending строка уже committed, outbound ещё не завершён | Два запроса используют один Payment ID; признак yookassa сохранён до outbound |
| Повтор pending одновременно с настоящим HTTP webhook | pg_blocking_pids подтверждает ожидание Booking при удерживаемом Payment; завершение без deadlock, одно начисление |
| canceled синхронизирован, участник изменяет сумму до нового Booking lock | Новый snapshot1200₽, старый canceled40000коп сохранён, один новый счёт120000коп |
| Два повтора отменённого счёта | Оба ответа имеют один replacement ID; одна canceled история и один действующий счёт/earn |

Concurrency fixture использует события после фактического SQL, отдельного наблюдателя pg_blocking_pids и ограниченные ожидания, а не задержку для скрытия гонки. Участники/БД синтетические; реальных денег нет.

### Четыре выборочные мутации

Прочитаны оба runner, сохранённые изменения source, XML/log и manifest; копии восстановлены, root совпадает с frozen snapshots. Bookings-копия восстановлена к исполняемой версии до последнего исправления docstring; проверенная разница только в описании функции.

- Удалён guard изменения суммы: 11 случаев, 7 ожидаемых failures, 4 контрольных проходят. Тесты ловят изменение paid/pending и частичную запись; это не ослабление критериев.
- Убрано освобождение Booking перед sync: один случай упал с фактическим PostgreSQL DeadlockDetected. Повтор текста ошибки в trace не означает несколько отдельных deadlock.
- Убран первоначальный yookassa tag: один случай падает на сохранённом method=card вместо yookassa. Эта мутация доказывает регрессию durable tag, а не создание второго счёта.
- Убран повтор общего dedup после canceled: один случай падает на разных replacement ID2 и3 — воспроизведён второй счёт.

Первоначальный harness после ожидаемого PG deadlock пытался прочитать UTF8 log через Windows cp1251 и остановился. История/manifest/log сохранены; V2 выполнил два оставшихся случая, не повторяя первый. Это ошибка стенда после полученного RED, отдельно от дефекта продукта.

### Решение и ограничения

Принимается только предотвращение нового расхождения суммы и описанные шесть чередований на frozen source. Исторические pending/paid строки с уже различающимися суммами не исправлены автоматически и остаются отдельным открытым пунктом; production не менялся. Настоящий провайдер/СБП, Android pay CTA/browser, две реальные устройства, комиссия не0, авария процесса, нагрузка, release, весь B07 и независимый дизайн всех96 экранов здесь не принимаются. Я не могу это подтвердить. Новая BA ошибка требует проверки носителем у владельца tasks.

Следующий точный шаг reviewer: связи AdminRequest1914–1963/AdminResponses1967–2037, API/server/tests и поздние ответы при смене request ID; QA022 UI принимается после независимого просмотра новых frozen PNG/XML/source. Собственных build/device/DB процессов или shared locks нет.

## DESIGN-REVIEW-R33-20261001 — заявка за пользователя и административные отклики

Связи: B09, существующие экраны AdminRequest/AdminResponses и записи паритета QA-B09-P112/P113. Их статусы не изменяются этим документом; единственный реестр — [audit-blocks.md](audit-blocks.md). Дата01.10.2026, агент /root/design_resume; ветка/HEAD/dirty как R32. Тип доказательства — чтение source/test и детерминированные ветви; новых запусков UI/БД/сервера нет. API, устройство, экран, тема, язык реального runtime не проверены. Я не могу это подтвердить.

### Чтение и связи

| Файл и SHA256 LF | Разобранная область |
| --- | --- |
| SecondaryScreens.kt2490, 335684e9e57c5aa625c089f3e708de55bce02c95d5c8d59501ba319ccac63b1f | Полностью AdminRequest1914–1963 и AdminResponses1967–2037; R17 AdminCabinet не перечитывается |
| ApiClient.kt7946, b3d3b34665d5cf339eecabef5a7b27ddd6bab01ea7567fac9b8b1d45bc3e0c70 |1371–1422/1470–1485; ResponseDto6693–6718; parser6969–6987/7013 |
| requests.py1148, 0482956ce6056ecf22576b48d4612b5db0322ddfda23f82e28029c8c527f9ac4 |1–201/278–321/668–794/915–1103; create/phone/turn/output/accept/close |
| models.py2069, cc3be8a5fa7208f0f5eacba65081177a5c86759b527ad1a778f784e844fe0477 |RideRequest411–440/RequestResponse778–797; Booking ранее R32 |
| security.py343, 423fe46a26d32150ef4f58e7aa347cd2596d4a59f8e56a3264259d604cd35ceb |normalize_phone34–65 |
| auth.py1336, aeb91716151fa1207dee29497538ad3d6646359da73ff93098ed9ffe23e6ccdb |request_code265–276/verify301–317; только граница нормализации |
| automatch.py154, 14cef02a5efd703583389c65d865d8c76722c8c1b935f4b1372da1cbd3972a23 |Полностью1–154, включая entrypoint |
| ApiClientAdsTest.kt297, 5aed2c5feb765e0333af39dfe0aced469096b0bb7fd0af20b3c9a1fb84b97875 |1–50/244–269; shape запроса, не ID/путь UI |
| ApiClientActionsTest.kt222, 16137e8ad362c1a390a206f471f4827a7cc34e6235f2adf173ef18b2b65a7c2a |1–70/158–170/203–215; booking_id/parser/400 |
| ApiClientNetworkTest.kt173, f47ab68c67c6727928e1c04c3476d30029fb2e46385cb11736afd8e6d2d7e794 |Новые90–106; fixture/error ранее R29 |
| test_requests_edges.py314, 95ce9820c0cdfb65706e65028a98e97c302f92fef6df01f0ab36adda3ae00907 |Полностью1–314 |
| test_bargain_rounds.py244, 03a6b47446ac68853ecc27f2bb9cc38e9d0dd2d36d567ea74d57dcbd7609d372 |Полностью1–244 |
| test_admin_actions_leave_a_trace.py102, cad122f159e645bc8e0a674469bbb9d8acc4049d842eccc6529f80cf1d4d6807 |Полностью1–102; след без телефона |
| webapp/AdminRequestScreen.tsx184, f290766faed5b70b7da39747cf3bf6a9a17a00980aaaadd3d72a2a3a0a9aac03 |Полностью1–184 |
| webapp/AdminResponsesScreen.tsx166, b9bc1e0bd66bb2b3a61c1697c1a0f1eaffea2d3ff794209c6d99594fa377508b |Полностью1–166 |
| webapp/api/admin.ts1029, 3f420e41c2eb68cf8a71dea340c90cf589f65129b1ec8b2fdd3b0bc290a326ee |1–46, три endpoint |
| webapp/api/requests.ts208, 599e72d8e45ded9e504db23a199effe70dc28bb99ec283f20f5ff83c1d3e9b8b |80–110, ResponseItem/торг |

Пути: Android source/test — android/app/src/main/java/com/yuldash/app и android/app/src/test/java/com/yuldash/app; API/tests в data; backend source — backend/app, router — backend/app/routers, тесты — backend/tests; webapp screen — webapp/src/screens, API — webapp/src/api. Навигационный AppNavAdmin53/54 и enum MainActivity483/484 повторно найдены структурным поиском; полный Nav разбор R17 сохраняется, не новый PASS.

Цепочка AdminRequest: AdminCabinet → форма phone/name/from/to/seats/comment → sending guard → POST admin/request-for-phone → admin403/phoneblank400/ограничения длины и seats1–8 → поиск либо создание target → RideRequest active → Toast/onBack. Failure сохраняет форму и снимает sending. ID/datetime с Android не сохраняются. Учёт duplicate/retry/lost-response и атомарность target/request не доказаны.

Цепочка AdminResponses: ввести request ID → GET → право owner/admin, отсутствующая404 → батч drivers/profiles/rating/trips → список driver/историческая цена/comment → POST responseID/accept → проверка ролей/хода/blocked/paused → fresh Request FOR UPDATE + условный active→matched UPDATE → Ride/Booking/accepted в одной транзакции → push водителю/закрытие остальных откликов → Android Toast и повторный GET. Получатель брони — пассажир заявки, не админ. Правило сервера не подменяется отсутствием кнопки.

### DESIGN-032 — админ подтверждает текущую цену, видя историческую

Экран AdminResponses, offered после торга. Условия: request seats1; price500/current_price450/last_offer_by=driver. Secondary2008 рисует r.price=500; parser6969–6977 корректно получает обе цены, ResponseDto.onTable6716 уже существует. Server price_on_table676–678/accept1010–1019 создаёт Ride450 и Booking450×seats. Это однозначное различие source: показанная и принимаемая цена разные. Реальные400→450 движения и создание450 закреплены test_bargain_rounds93–101, но исполнение этого теста/Android данного прохода не объявляется.

Влияние: админ звонит участникам/принимает за пассажира, видя неверную сумму; два места увеличивают непонятную разницу (Booking равна цене×места). Нужно показывать текущую цену и понятный объём цены/мест, сохраняя legacy fallback; API/бизнес-правило не менять ради UI. Проверка после fix: настоящий publish/request/respond→500→400→450→admin GET→карточка450→tap→Ride450/Booking450×seats и обе стороны; отдельно legacy/current0, без торга, цена0, несколько мест, declined. actual UI/API RED→GREEN ещё открыт. Web AdminResponses143 имеет тот же исторический it.price, замечание касается обоих потребителей.

### DESIGN-033 — результат относится к прежней заявке, но действие остаётся активным

Secondary1978–1999: ID фиксируется только локально перед GET; success безусловно заменяет общий resps. Кнопка Open1994 не блокируется при loading, поле ID редактируется, нет loadedId/generation. При B error старые карточки A сохраняются. При GET A удержанном и GET B завершённом поздний A снова заменит B. Карточки не показывают request ID/маршрут, accept отправляет их r.id. Следовательно запрос будет законно принят для A, хотя последним открывалась B; сервер не получает ожидаемый request ID и не может проверить это намерение. После accept load использует текущий ввод, который мог снова измениться.

Влияние: оформляется поездка для другого пассажира; server admin access тут разрешён, это нарушение намерения и связи экран→данные, а не доказанный обход прав. Source ветви подтверждены, actual параллельный UI RED ещё открыт. Критерий: связать displayed list/actions с загруженным request ID; игнорировать устаревший ответ и сохранять согласованное состояние error/loading/ready. Проверка: синтетические A/B с разными водителями, два управляемых GET без sleep; B200→A200 и B403/404/500; edit ID/Back/accept во время запроса; HTTP POST должен относиться только к явно показанной заявке либо не посылаться.

Web load35 блокирует второй Open при loading, состояние error скрывает прежние cards, loadedId42 сохраняется, поэтому точная Android двухGET/error ветвь не переносится на веб. Но не пустые карточки не подписаны loadedId, input продолжает меняться; остаётся отдельный критерий ясного выбранного контекста, не общее PASS.

### DESIGN-034 — предлагается принятие уже недоступных откликов

Android2011–2030 различает только accepted/остальные. GET requests744–745 скрывает лишь withdrawn, значит declined/closed возвращаются; при них всё равно показывается активная CTA «Принять». Offered с lastOfferBy=passenger тоже получает CTA, тогда как admin принимает за passenger и server1068–1073 вернёт409. Внешний success здесь не объявляется: failure показан Toast, бизнес-guard работает. Нарушена ясность состояний/действий, §4.5 AGENTS.

Проверка исправления должна сохранить легальное admin принятие driver offer и объяснить unavailable/declined/closed/ожидание хода; затем повтор/два администратора/поздняя отмена. Нельзя просто положиться на текущий canAccept: _response_out770–781 для постороннего admin получает роль None и false, хотя accept1062 разрешает admin действовать как passenger. Требуется согласованное право в DTO/представлении; скрытие всех admin CTA сломает разрешённый путь. Web146–158 также различает только accepted, хотя additionally disables при alreadyAccepted. actual UI/API RED и fix пока отсутствуют.

### DESIGN-035 — результат Android create не позволяет продолжить по номеру

POST возвращает полный RideRequest с id, но ApiClient1474–1477 превращает ответ в Unit. AdminRequest1951 показывает общий Toast и выходит; дальнейший AdminResponses1989/1992 требует № заявки из Telegram. В данном admin-create318 ответе нет notify_admin_telegram, а Android не сохраняет/не показывает id. Следовательно именно этот путь не передаёт созданный номер в следующий шаг. Другие способы узнать номер вне этих экранов не объявляются невозможными.

Веб сохраняет createdId51 и показывает #id84, не теряя результат. Проверка fix: реальное synthetic POST200{id}→устойчивый ID результата/переход к откликам той же заявки; отказ/Back/retry не показывают чужой/нулевой ID. Нужны actual UI proof и сохранение существующей навигации, без выдуманных номеров. Android lacks desired_at, web49 принимает local time→ISO; это известное различие полей по comment153, не молча закрытый паритет.

### Серверные probes и другие открытые состояния

AdminRequest phone294–301 использует только trim и точное сравнение User.phone; auth272/314 применяет normalize_phone. Два представления8… и+7… способны попасть в разные target records по source; действующее runtime воспроизведение ещё не проведено. Критерий owner: существующий synthetic canonical user/DeviceToken → два формата admin create → одна target identity и доступ к своей заявке после входа; invalid/blank/foreign/tg placeholder по принятому правилу, blocked/paused, параллельный create. Не объединять реальные аккаунты или менять историю автоматически.

Admin endpoint создаёт каждый RideRequest без regular-create173–175 дедупа; новая target сначала отдельно committed, затем req. Отдельные probes lost-response повтор/два admin/ошибка второй записи/uniqueness same phone, без утверждения уже исполненной потери. Regular-create геокодит/notify_map_changed/watchers165–190, admin-create нет; radius-discovery и уведомления admin-created заявки проверять отдельно. Автоподбор смотрит DeviceToken и offered, вызывает общий atomic accept; source1–154 прочитан, no runtime/таймер/real push PASS. Ошибочная нормализация способна изменить выбор no-app ветки, это критерий проверки, не исполненное присвоение поездки.

Доступные тесты API проверяют wire/parser/400, server tests — обычный request/bargain/Telegram/automatch/след. Поиск AdminRequestScreen/AdminResponsesScreen в src/test и src/androidTest не обнаружил прямых UI-тестов; Cabinet тесты не заменяют путь формы. Ни один прочитанный testcase не связывает историческую цену с настоящей admin карточкой или поздний A/B список. Исторические green запуски не присваиваются новой версии.

Неизмеренные UI критерии: RU/BA, light/dark, узкий экран/font2, driver name+rating+price в Row2002–2008 без weight у имени, IME/системные панели, touch/контраст inherited DESIGN-001 consumer, TalkBack, Activity/настоящий процесс, offline/401/cancel/Back. Формы имеют двуязычный текст и Canon tokens; это не доказательство визуального качества всех состояний. Новых изображений/измерений нет.

Передача R33: DESIGN032–035 и probes сообщаются root для единственного реестра/воспроизведения/исправлений. Следующее непрочитанное — Blocklist2041–2130 с server blocks/публичностью данных/ошибками; затем Report. QA003 R32 принят ограниченно; QA022 frozen UI ещё ожидается. Другие исходники/документы не менялись.

## DESIGN-REVIEW-R34-20261001 — чёрный список и жалобы

B09: прежние Blocklist/Report экраны и их существенные состояния; B08 связи приватности/прав остаются у владельца единственного реестра. Дата01.10.2026, /root/design_resume; ветка/HEAD/dirty как R32. Это самостоятельное чтение source/test, без исполнения нового UI/HTTP/БД. Снимков, Android API/размера/темы устройства в этом этапе нет. Я не могу это подтвердить. R29 категории и качество разбора не перечитывались для числа проверок; точечные связанные ветви открыты по новым findings.

### Прочитанная область и версии

- SecondaryScreens.kt2490 SHA256 LF335684e9e57c5aa625c089f3e708de55bce02c95d5c8d59501ba319ccac63b1f: полностью2041–2170/2206–2333. Категории2173–2205 уже разобраны R29. PersonRow1336–1347 — прежнее чтение R24, файл не изменился.
- ApiClient.kt7946 LFb3d3b34665d5cf339eecabef5a7b27ddd6bab01ea7567fac9b8b1d45bc3e0c70:1270–1295/1310–1338/6633–6640; reportable GET1479–1483 ранее R33. API reportUser не имеет parcelId; это не доказательство отсутствия других механизмов доставки.
- safety.py1433 LFd85ab8ad178d4561d255b86ed827113670f7ec3239c145013869373eb2814507: новые476–521/558–724/916–1014; ReportIn421–455/quality170–297/346–402/административный разбор — предыдущая R29 область. Body validation, counterparty auth, duplicate/rate/create/side effects разобраны отдельно.
- safety_logic.py892 LF330a3f4185710b393cdfdbd58c19876ae05ee318e5d9f861669af871f8f83f8e:have_met828–892 полностью.
- models.py2069 LFcc3be8a5fa7208f0f5eacba65081177a5c86759b527ad1a778f784e844fe0477:Block739–750; остальные модели по R32/R33.
- SecondaryDeep3ContentTest.kt419 LFe1a0ae4988f3e81ddc0e0305cb88196f676b1692ed07ea78228fe61b19924d10:fixture1–43, Blocklist тесты45–174, ReportList176–277; дополнительные279–297 частично, не весь OCR.
- ApiClientRidesTest.kt334 LF3d6b9e13de218cdd3a238e30558d6dd8de0d600344f4451d415d7996d89dd605:1–42/292–313, shape/raw-array GETblocks. ApiClientAdsTest297 LF5aed2c5feb765e0333af39dfe0aced469096b0bb7fd0af20b3c9a1fb84b97875: новые210–242/288–297, fixture ранее R33.
- test_blocklist_is_not_a_phonebook.py122 LFa2a486160da9537ff2b4275cca56a90666b13cc91e0110ad917220e684529cde и test_safety_edges.py121 LF97cd1af10879feef15e3a21fe7c1578e638e9fe6bd25f2af59400beb469f4dcd прочитаны полностью. test_quality.py454 LFdabf0475cb8e3f3ba7ad3fd360d60283c166696afaf80ba14d6fefc3bdcda965: новые117–213, старые214–227/355–425 R29.
- webapp BlocklistScreen.tsx129 LFb380f3d049c38d899b985187348a482d829622f315f3b542781d4f87d9668a01, ReportScreen.tsx285 LF6f12a586e0af99eb6e9c6eb8f7e78dcc30e83580356a73fd9be7decb3288b98b: оба полностью. api/safety.ts284 LF5765e8eb210e37980add7feb2f46be3fc505503bd495706185e59ab10a0d215c:54–103.
- Связанные onSend: InstantOrderScreen.kt7913 LF7c5d3fc91554f91728c969476360d500ee442b1d9fea5969f0c658f1c4ab6b81,5014–5048; TaxiDriverCancelledScreen.kt596 LF72d0decd4c04a2b9155da5a8746547550c69c1a42aeed374b1ee780c0c5a2837,244–270; TaxiReceiptScreen.kt989 LFa15d8315c47b1679642d21fc09fea7156d69528da82f273cddab77d070f181ff,832–860. Не прочитаны эти большие экраны целиком. TaxiPostTripGuardTest.kt50 LFcfbfc1ae50b4a4012011b02d97c11dec83b40c2cb11ba1659cd90b4e6a4122f7 — полностью.

Пути/префиксы как в R33: Android main/test, backend/app/routers/safety.py, backend/app/safety_logic.py и backend/tests; webapp/src/screens и src/api. YuldashApp1416/1425 Blocklist reachability повторно найдена структурно; полный навигационный runtime-путь не выполнен.

Цепочка Blocklist: Settings/Safety→GET blocks и reportable-users→filter blockedIds→список/пусто/кнопки→POST либо DELETE→owner rows→reload. GETblocks failure даёт ListedError/retry, action failure Toast; действия не имеют отдельного busy guard. POST self400/absent404, DELETE ограничен owner и повтор допустим. Список чужих blocks через этот GET не возвращается.

Цепочка Report: GET reportable-users→target→category/details→POST reports→валидный closed category и reason≤1000→counterparty auth для order/booking/parcel либо explicit target→self/missing guard→duplicate/лимит10 новых в час→Report committed→quality/severe/carphoto/anonymous notification→Toast. Общая форма отправляет explicit target без ссылки на поездку, что сервер намеренно допускает для совместимости. Реальный push/анонимность всех последующих действий не принимаются этим чтением. В source+тестах права привязки и отсутствие reporter в ответах проверяются; новый запрос чужим аккаунтом reviewer не исполнял.

### DESIGN-036 — сбой списка попутчиков скрывает возможность защиты

Blocklist2055–2057 обрабатывает failure только getBlocks; getReportableUsers failure игнорируется. Сценарий: blocks200[] + reportable-users500/401/timeout → loading=false,error=null, partners=[] → «Чёрный список пуст», секции/повтора попутчиков нет. При повторной загрузке старый partners сохраняется без обозначения устаревшего результата. Это подтверждено условием source, а не исполненным UI RED.

Влияние: нельзя выбрать человека для блокировки и понять причину отсутствия списка. Критерий исправления: отдельно объяснить отказ этой части/дать retry, не объявлять её пустой и не прятать успешно загруженные собственные blocks. Проверить настоящий путь GET200/500 и восстановление, обратный partial failure, обе ошибки,401/Back/поздний ответ, существующую блокировку и повтор POST. BlocklistContent готовые9 тестов не запускают два реальных GET и не защищают этот разрыв.

### DESIGN-037 — список выбора исключает участников других реализованных услуг

Server reportable_users999–1014 собирает только Booking↔Ride. Ни InstantOrder, ни ParcelDelivery, ни RequestResponse в этом GET нет, тогда как have_met845–892 считает эти связи знакомством и list_blocks968 использует их для допустимого имени. Android Report2142 и Blocklist2056 используют этот один picker.

Исходные условия: synthetic user имеет только завершённое такси/только доставку/только торг без Booking. Ветка GET возвращает[], Report2316–2322 сообщает «Пока не на кого жаловаться», Blocklist не показывает человека в addable. Это отсутствие в данном выборе подтверждено source; actual API/UI воспроизведение не исполнено. Альтернативные прямые taxi ReportCategoryDialog реально найдены и разобраны; нельзя объявлять вообще все жалобы/блокировку недостижимыми.

Критерий owner: список только собственных допустимых контрагентов всех принятых услуг/ролей, без глобального справочника и дубликатов; включить отменённые/незавершённые случаи по реальному правилу защиты, recipient роли доставки изучить отдельно. Проверки: разные synthetic strangers/trip/taxi/parcel/response, обе стороны, пусто, повтор, named/anonymous privacy, UI выбор→правильный target POST→результат/участники. Existing safety_edges106–121 проверяет лишь попутку и stranger, не этот набор. В web picker та же серверная область, но Report имеет отдельно booking/order/user query context; полный паритет не пройден.

### DESIGN-038 — описание жалобы исчезает до подтверждения отправки

Report2151–2156 запускает POST и немедленно убирает target; ReportCategoryDialog selected/details2218–2219 живут только в remember. При500/401/timeout показывается Toast, но выбранная категория и до1000 символов описания уже уничтожены. Повторное открытие начинается с пустого состояния. Та же доказанная последовательность: InstantOrder5030, TaxiDriverCancelled254, TaxiReceipt844; их failure feedback различается, сохранения черновика нет.

Влияние: человек после происшествия вынужден повторно описывать подробности; сообщение может не дойти. Минимальный критерий: сохранять target/category/details до подтверждённого success, показывать busy/error/retry и блокировать повтор одной операции; не объявлять отсутствие HTTP-ответа доказательством отсутствия серверной записи. Проверить GET списка→выбрать→Unicode/BA1000/otherrequired→send500→черновик неизменён→retry200→одна Report/feedback. Отдельно lost successful response,401/logout, dismiss/Back, четыре потребителя и соседний no-details категория. До1000 — граница actual ReportIn.reason и REPORT_DETAILS_MAX, не число утраченных символов в исполненном кейсе. Новый RED/fix/GREEN ещё открыт.

Web Report94–130 на отправке не очищает reason/category и возвращает submitting=false приfailure, поэтому эту потерю ему не приписываем. Семантика Android категорий2238–2255 — дополнительный consumer DESIGN-002: onClick Surface/цвет/CheckCircle cd без selected role radio/group. Веб opt-chip226 имеет aria-pressed. Визуальная и TalkBack проверка Android open; наличие48dp min/source check-icon не заменяет её.

### DESIGN-039 — веб скрывает отказ дополнительной блокировки

Web Report checkbox257/265 просит также заблокировать. После успешной жалобы blockUser109 может отказать403/500/timeout; catch110–112 ничего не сообщает, далее status=sent114. Пользователь видит только успешную отправку и не знает, что выбранная защита не включилась. Жалоба должна сохранять успех; нельзя превращать её в ложный failure или автоматически посылать повторную жалобу. Требуется отдельный результат блокировки/повтор, сохранение честного feedback.

Критерий RED→GREEN: synthetic complaint200 + block500, real DBReport одна, Block отсутствует, UI объясняет частичный успех и позволяет повторить только Block; then block200→одна Block; both failure/alsoBlockfalse/context path. Новый runtime не исполнен. Web Blocklist DELETEfailure63–64 откатывает список, но молча;404/405 GET41–43 сообщает пусто — отдельные source-паритет probes, не доказанная пустая реальная БД.

### Приватность, гонки и границы

Block POST заранее незнакомого разрешён намеренно. Прочитанный test_blocklist_is_not_a_phonebook защищает это право и выдачу имён только при have_met; нельзя «исправлять» UI запретом предварительной защиты или раскрытием чужого имени. Generic fallback «Пользователь» из safety971/1014 показывается как b.name/p.name и в BA не переводится; это отдельный язык-критерий общего API placeholder. Два неизвестных с одинаковым label могут быть неразличимы; безопасные различимые собственные подписи — предложение, без раскрытия имени/телефона и без объявления actual ошибки выбора.

Concurrency probe: create_block933–940 SELECT-before-INSERT без lock, Block742–746 не задаёт unique(user_id,blocked_user_id); fresh schema/миграции ещё не проверены на стенде. Два параллельных POST могут дать duplicate rows, а Compose key2114 — одинаковый blockedUserId. Это source риск с точным воспроизведением owner: fresh PG, оба SELECT удержаны доINSERT, два auth POST→rows/GET→Compose. Sequential safety_edges89–93 доказывает лишь последовательный повтор; нового исполнения/краша не было.

Report duplicate621–648 также SELECT без сериализации автора/уникального ключа; проверки rowcounts/notify/quality при двух POST и сохранении одной логической жалобы остаются серверной пробой. Pending unused reason difference, привязка parcel_id в duplicate helper и модель времени суток требуют отдельного договора, не молчаливого новых правил. Оба write-пути owner/реальная БД/пуш не запускались reviewer.

list_blocks953–969 батчит User, но вызывает have_met по каждой строке, вплоть до четырёх SQL existence checks на блок. Это N+1 механизм, не измеренная задержка/нагрузка. Политика видимости feed/chat/map после блока, её прекращение, контакты/GPS и обратные роли не закрыты одним экраном или прочитанными7 privacy cases.

Все Source states RU/BA/Canon/lazy keys описаны, но маленький/большой экран, font2, dark, IME, Touch/TalkBack, настоящие process death и physical device open. Shared PersonRow/кнопки проверяются по старым findings и actual матрице, не общим «выглядит хорошо». TaxiPostTripGuardTest лишь ищет строки source, не выполняет форму/POST/draft, отсутствие регрессии этих сценариев им не доказано.

Передача R34: root получил036–039 и перечисленные probes; одно новое общее утверждение «пройден B08/B09» не создаётся. Далее Help2336–2490/ссылки/поддержка; QA022 актуальные PNG/XML/source ещё ожидаются. Единственный writer этого документа, общие source/resources не менялись.

## R35 — Help: поддержка, FAQ и связь с SOS

Дата / ответственный: 01.10.2026, 14:00 МСК, /root/design_resume; B08/B09, связи B01/SOS. Ветка audit/full-technical-20260930, HEAD 6fa0595d88bea4fcd59d4a1e2139c2d128cf6821; локальные изменения других владельцев сохранены. Цель — разобрать все ветви Help и проверить, что обещания экрана соответствуют вызванным действиям и серверу. Критерий принятия полного сценария включает реальные переходы, controlled ответы/побочные действия, RU/BA, размеры/тему и доступность; текущее чтение не закрывает эти runtime-критерии.

### Read-map и SHA256LF

| Файл / длина / SHA256LF | Прочитанное в R35 |
| --- | --- |
| android/.../SecondaryScreens.kt2490 /335684e9e57c5aa625c089f3e708de55bce02c95d5c8d59501ba319ccac63b1f | Полностью Help2335–2468, ExpandableHelpRow2470–2490 |
| android/.../YuldashApp.kt2618 /92883a6d253da875ddec50a229f2d565e937cb7051556613bf5c1d353a34487f | Help1447–1458; SOS896–901/1344–1359; общий Back1055–1057 |
| android/.../data/ApiClient.kt7946 /b3d3b34665d5cf339eecabef5a7b27ddd6bab01ea7567fac9b8b1d45bc3e0c70 | callback1227–1240; boarding1549–1557; остальные связи support R16, не перечитаны |
| android/.../SosVerifyScreens.kt1188 /5171d7484dfa75d2feb2dc39aa290633ffd02144a59cdbfcdde3ac41a5449906 | 283–474: wrapper, гео/таймаут/разрешение/cleanup/dial/send;479–708: презентация SOS, каналы/кнопки/ошибки. Это не полный файл |
| backend/app/routers/safety.py1433 /d85ab8ad178d4561d255b86ed827113670f7ec3239c145013869373eb2814507 | CallbackIn405–419; связь SOS205–264/303–328: commit события, контакты, SMS-канал/кеп. Остальной safety в предыдущих read-map |
| backend/app/services.py2110 /060566080a682cf8f913213f8839de54335ddc710093011d7e2441ac423b7981 | notify_admin_telegram987–1012 полностью; соседний SMS962–984 |
| backend/app/middleware.py346 /8862d5682fca3ba5c5e5ed1f25db604d24ca3af31e1316d9be6174b543098a73 | 1–55: strict prefixes включают /callback и /api/v1/callback. Отсутствие limiter на самой ручке не объявлено отсутствием защиты |
| backend/app/routers/bookings.py910 /8e36a71eb07d7fed744def2d434702c08a8a78cb0561fb01bfe91333a2f7cca2 | 411–418: boarding-code доступен участникам через booking_and_ride_for_user; текущая денежная защита принята отдельно R32 |
| android/.../SecondaryDeep4ContentTest.kt378 /9f93c57b7b95bbc3a6aa1001d2b85661f0afb8a69a90e7a78cc724091d6d8230 | 1–59 fixture;288–378 все семь Help tests |
| android/.../data/ApiClientAccountTest.kt223 /1d8461df119fd0a200f5eb47095dc44b604e15f40fb935049e03a0c86febdd59 | 1–50 fixture;200–223 callback wire/success/error |
| backend/tests/test_safety_edges.py121 /97cd1af10879feef15e3a21fe7c1578e638e9fe6bd25f2af59400beb469f4dcd | Полностью уже R34;62–73 дополнительно сопоставлен callback-тест |
| backend/tests/test_support_queue_has_a_ceiling.py104 /053921dcddb5ce14bed02470d36935edcf6e2c159e5218c6803f1fe85baf76c1 | Полностью1–104: support rate ceiling/существующий разговор/SOS/system refund ticket |
| webapp/src/screens/HelpScreen.tsx164 /512ebfddb1e0e6ccc9550e5896b8d58fe990b479533b51f7e1dcf31b4cbdee62 | Полностью1–164 |
| webapp/src/screens/CallbackHelpScreen.tsx126 /11e40d03b502226ce3ea92b5aaaf2de908f16f33bd196495a23a796c26f6b5f6 | Полностью1–126 |

Окружение reviewer: Windows, чтение source и тестовых определений; Gradle/adb/server/БД/Telegram/112 не запускались. API34/411×2600dp относится к fixture Robolectric, не к реально исполненному устройству. Я не могу подтвердить фактический звонок, доставку SMS/Telegram, рабочий график поддержки и произнесённые фразы TalkBack.

Цепочки: Help→onSupportChat→root openProtectedScreen(SupportTickets)→auth/restore→GET support feed→ticket path R16; Help→callback request→current_user→единственная отправка Telegram→HTTP result→Toast; Help→Telegram ACTION_VIEW→внешний Activity; query→фильтр текущего языка по заголовкам→key title→ExpandableHelpRow→AnimatedVisibility. Help onBack не используется внутри screen, но общий root BackHandler1055–1057 существует; это не доказательство невозможности возврата. Badge supportUnread намеренно best-effort и failure не принимается за отсутствие самой поддержки.

### DESIGN-040 — просьба о звонке теряется, а клиент подтверждает успех

Экран/условие: авторизованный Help→«Попросить звонок», controlled notifier возвращает False, либо callback web. Правило: состояния ошибки должны быть честными, существенное действие не должно теряться (AGENTS4.5; B08/B09). Safety413 игнорирует результат notify_admin_telegram и419 всегда отдаёт ok:true. Services996–1012 возвращает False, когда нет конфигурации, Telegram отвечает не-2xx или выбрасывает исключение. Safety412 прямо говорит, что запись не хранится; альтернативная очередь в данном обработчике отсутствует. Поэтому в этой ветви просьба не сохранена и не доставлена, а Android2441 показывает «Заявка отправлена — мы свяжемся», web35/57/60 — «Скоро перезвоним»/«Мы получили твою просьбу».

Это однозначная source-цепочка; controlled HTTP RED ещё не исполнен reviewer. Тест safety64 подменяет notifier append(), который возвращает None, и проверяет лишь200/ok/list-not-empty: реальное принятие отправки он не проверяет. Минимальная коррекция должна определить принятие просьбы: подтвердить реально принятый notifier либо сначала надёжно сохранить заявку; выбор принадлежит владельцу backend с учётом повторов/потери ответа, без выдуманной доставки. Критерий: notifier=False/non-2xx/exception→честный RU/BA error или доказанное сохранение в очередь, успех control→одна просьба, guest401, ≤500/501note, повтор/потеря ответа и client loading/retry. Не обращаться к живому Telegram.

Дополнительный пока не исполненный probe: Android2439 допускает несколько scope.launch, busy/dedup нет; strict IP-budget middleware42 существует, но это не защита одной логической просьбы от дублирования.

### DESIGN-041 — внешняя поддержка молчит при отказе запуска

Help2447 оборачивает ACTION_VIEW в runCatching без onFailure; сценарий ActivityNotFoundException/запрет launch оставляет экран без сообщения о неудаче. Это нарушение состояния ошибки AGENTS4.5. Help JVM Telegram-click test проверяет только, что после тапа Help не упал: успешный запуск либо обратную связь он не доказывает. Минимально — existing bilingual feedback pattern, как SOS dial410–411; сохранить доступный внутренний support CTA. Критерий: controlled launch failure→видимое RU/BA сообщение, launch success→правильный URL, внутренняя поддержка остаётся работоспособной. Android URL t.me/bairas_ntv отличается от web t.me/yulbash150; без подтверждённого назначения контактов это наблюдение паритета, не утверждение о неверном адресе.

### DESIGN-042 — FAQ не передаёт доступности состояние раскрытия

ExpandableHelpRow2475 меняет expanded, но Column.clickable не задаёт роль и stateDescription раскрыто/свёрнуто; стрелка2482 имеет null-description и меняет только изображение. В source нет свойства, выражающего состояние раскрытия, хотя оно существенно для действия. WebHelp124 задаёт aria-expanded; сравнение source отдельно от реального browser/TalkBack.

Влияние — доступный узел не содержит явно заданного expanded-состояния и роли; точную фактическую озвучку я не могу подтвердить. Минимально — понятная двуязычная семантика состояния/действия без дублирования вопроса и нормальная роль Button, затем Compose semantic tree до/после клика и TalkBack RU/BA. Существующие семь tests проверяют видимый текст и раскрытие, а не эти свойства.

### DESIGN-043 — инструкция SOS обещает объединённое действие

Help2377–2378 говорит, что после красной SOS-кнопки откроется звонок и контактам уйдёт SMS с координатами. Реальная последовательность: openSos896–901 только открывает экран;112-button532 вызывает только onDial→ACTION_DIAL409, непосредственный вызов человек подтверждает в звонилке. Уведомление близких требует отдельного onSendSignal704→ApiClient.sos439; guest421 уходит в login. Safety235–245/261 проверяет существование контактов, возможность SMS-канала и кеп. Наказания нет: канал/ограничение являются существующим поведением, не новым пожеланием reviewer.

Влияние: человек может считать, что близкие уведомлены после одного SOS/dial тапа, хотя POST sos не был вызван; это source mismatch инструкции и фактического пути. Минимально — уточнить RU/BA FAQ: сначала открыть SOS, отдельно набрать службу и отдельно отправить сигнал, SMS зависит от контактов/канала; не обещать доставку. Критерий: UI test реальным entry-path с безопасным dial spy показывает ноль SOS POST при open/dial и один при send; guest/без контактов/без GPS/канал off/кеп/success в изолированном стенде, без экстренного звонка и real SMS. Полный экран SOS/сервер/TalkBack ещё не принят.

### Паритет, ограничения и следующий шаг

Android FAQ пять, web семь; web поиск включает вопросы/ответы обоих языков, Android ищет заголовки текущего языка. Android placeholder «Поиск по вопросам» соответствует его фильтру: поиск по ответам — предложение, не подтверждённый дефект. WebFAQ утверждает отсутствие комиссии на попутки; после online wallet/commission API это требует сверки действующего тарифа/правил. Здесь не утверждается, что всегда берётся комиссия, или что всё web-описание неверно.

FAQ о коде посадки не объявлен ошибкой: bookings413 реально выдаёт код обоим участникам. Фраза «документы видны только модератору» не описывает исключение владельца своих документов из private-docs R26; это точность объяснения, не обнаруженная новая утечка. Расписание9–21 и заявленное качество «проверенных людей» нельзя доказать source; operational evidence остаётся открытым.

Семь Help tests используют прямой setContent с пустыми ads/default callbacks, не настоящий вход в экран и не action-chain callback. Два APIcallback tests — заменитель MockWebServer, не серверный результат доставки. Support rate ceiling tests не доказывают callback accepted notification. Все эти определения прочитаны; новых запусков/PASS не было. Фактическая геометрия FAQ/крупный шрифт/тёмная тема/клавиатура, unread поздний ответ после смены аккаунта, реклама impressions/click/promo, release, process-death и весь SOS требуют отдельных доказательств.

Передача R35: root получил040–043 с критериями; общие исходники не изменялись. Далее scoped QA022 по замороженному checkpoint/PNG, если актуальны, либо VerifyDriver762–1188 и private docs по существующей карте96экранов. Весь B09/96screen и внешние обязательные проверки открыты.

## R36 — QA022: независимая ограниченная приёмка выгрузки статистики

Дата / ответственный: 01.10.2026, 14:05 МСК, /root/design_resume; QA-B01-022/B01/B09. Повторное review обосновано MyStats source change после R15 и новым текущим checkpoint v2; неизменённые MyData чтение/UI8 и определения прежних JVM70 заново не присваиваются. Root сообщил frozen v2 и отдельную storage-review приёмку. Reviewer проверил сам изменённый diff, actual export PNG и первичные device assertions. Ветка audit/full-technical-20260930, HEAD6fa0595d88bea4fcd59d4a1e2139c2d128cf6821; незакоммиченные изменения всех владельцев сохранены.

### Актуальность и первичные доказательства

Самостоятельно сверены все поля raw/LF SHA manifest [checkpoint-v2](../test-results/audit-mystats-export-checkpoint-v2-20261001.json): 14sources+62artifacts+13JVMXML+2APK=91 записи, у14sources дополнительно каждый raw snapshot сравнен байт-в-байт; итого14current+14snapshot+62artifacts+13XML+2APK=105 файловых проверок. Нулевое число несовпадений получено фактическим чтением/пересчётом. Единицы «91 записи» и «105 файловых проверок» различаются; это не105 тестов, не покрытие и не число проверенных пользовательских путей.

Read-map: MyStatsScreen.kt587 SHA256LF a3da2bf2a44280d21e1148500f4fc2410d7fd4e69152ef513729173b6a26adbc — полный unified diff против raw before-fix569/7d92ca2f615e05e8fdd1e4400c0e6780f521f76761a2414c67a3fc9f24d8ef99, новый owner-generation91–103 и share194–235, renderer/caption416–500 и chooser503–518 как связанная граница. Неперестроенный экран/Canvas уже читался полностью R15; не объявляется новым прочтением каждого неизменённого участка.
PersonalDataExports.kt67/b0cd8f2ab1cdf23acbdab60c0c53e027ef6887c49f5dfe8e644b84395f1ba78e — весь diff против прежнего55/ba913bbb5c5730009b0fc996be6059737bde3e6722e684fe3658c0fa1477b547; prepareFile guards/write/finally/own regex/legacy paths/cleanup рассмотрены. MyStatsExportSessionInstrumentedTest.kt582/0e2f06118cc08aa08c4fc7be49c7ae8f5b45a9090d5c00de9528f6a89526e69d прочитан полностью1–582: шесть actual UI tests, gate/calibration/recipients/HTTP/auth/source snapshots и cleanup. Это отдельное независимое чтение важного storage fix, а не новый исполненный набор reviewer.

[Первичный финальный instrument log](../test-results/audit-mystats-export-final-integration-20261001.log) содержит14 distinct successful status0 и OK(14),51.723s. Формула8MyData_text+6MyStats_PNG=14, PID6879; wall54.562s — число родительского launcher, не сумма тестовых времен. Сами13 XML independently parsed:3+3+2+5+9+6+3+14+2+2+8+9+4=70; failures/errors/skipped0. JVM70 предшествовали comment-only product change и добавлению шестого instrumented test; актуальный замороженный APK проверен финальным14, source/scoped distinction сохранён.

Окружение первичного device результата: emulator-5580/Pixel_API35, API35,1080×2340px,440dpi/density2.75,fontScale2,light. RU/BA synthetic account/ranks; настоящие Activity/Compose/Canvas/FileProvider и OS grant/read shell UID2000; ServerSocket loopback HTTP, не FastAPI/БД и не MockWebServer. JVM neighbours использовали MockWebServer. Ошибка original metadata/пустой filter markers сохранена в original checkpoint; v2 повторного теста не добавляет, содержит фактически извлечённые QA021_JOURNEY/QA022_STATS [markers](../test-results/audit-mystats-export-final-integration-markers-v2-20261001.txt).

### Что доказано в изменённом механизме

Source: один share-busy gate и mount-generation запрещают запуск со старым владельцем; PNG создаётся с UUID filename, compression проверяется, IO находится вне session monitor, publication внутри runIfCurrentSession; recycle Bitmap выполняется finally. CancellationException не превращается в text SEND; непубликуемый/cancelled/obsolete export удаляется. Локальное cleanup учитывает старое my_yuldash.png и новые UUID PNG, отзывая grants отдельно от удаления; чужие файлы не удаляет.

Шесть current tests действительно нажимают share после HTTP-loaded screen и контролируют результат/побочные действия:
- actual BA PNG/два разных account payload: shell реально прочитал A до logout, grant отозван послеlogout, ни A, ни B не читаются через old URI; unrelated file/grant остаётся.
- настоящий IO gate после rendering при account switch: jobcompleted, никаких новых файлов/image либо text intent.
- actual Back callback снимает MyStats composition и отменяет настоящий launch; это unmount/coroutine cancellation, не process death; после gate release нет orphan/text SEND.
- реальное исключение getCacheDir после rendering: один актуальный RU text/plain caption без EXTRA_STREAM/partial file, кнопка сноваenabled.
- загруженный A остаётся на экране без remount подB: сохранён тот же product scope, повтор share не начинает export A; проверка не использует фиктивно отменённую UI-scope.
- физические повторные касания disabled busy node при held writer: одна логическая image SEND, один добавленный файл, остальные file hashes сохранены, кнопка сноваenabled.

Loopback fixture отделён от production; network response не подменяет Canvas/FileProvider. Chooser startActivity перехвачен RecordingContext: настоящий OS recipient grant выдаётся отдельно shell, реальная передача grant через системный chooser/обычный мессенджер этим тестом не доказана. Latches синхронизируют actual writer; ожидание именно held operation Job откалибровано и исключает независимый interaction collector. Временной timeout не принят за success.

Самостоятельно прочитаны primary мутации: old-screen3/2fail — orphan my_yuldash.png после unmount и late account IO; privacy-case остаётсяcontrolGREEN из-за уже действующего shared cleanup. tap-owner1/1fail — old A запускает новый my-yuldash UUID подB; png-cleanup2/1fail — legacyPNG survives logout. Первичные [old-screen log](../test-results/audit-mystats-export-mutation-old-screen-device-20261001.log), [tap-owner log](../test-results/audit-mystats-export-mutation-tap-owner-device-20261001.log), [cleanup log](../test-results/audit-mystats-export-mutation-png-cleanup-20261001.log) проверены; restored copy/root/APK equality из manifest и final105 проверок согласованы. Ошибки продукта/прежний fixture timeout остаются в historical log; успешный повтор их не стирает.

### Самостоятельная проверка actual PNG

[BA экспорт A](../test-results/audit-mystats-export-baseline-actual-A-20261001.png), raw SHA256 e428aa106168d53b69432233287d67a16bab7aff5a1fe96d98f72a84f7628e7a,1080×1350, просмотрен в original resolution. Это сохранённый baseline exported image, не новый fullscreen screenshot. Финальный QA022_STATS receipt из PID6879 содержит тот же AHash, а renderer diff не менялся: current повторный A дал идентичный export content. [Receipt](../test-results/audit-mystats-export-current-receipt-20261001.json) из отдельного device этапа содержит тот же hash; final marker отдельно подтверждает его повтор.

Наблюдаемое в конкретной открытке: башкирские буквы читаются, синтетические rank/name полностью видимы;120км/7/350₽/12.5кг совпадают с payload, подписи трёх плиток и footer не пересекаются и не обрезаны. Это bounded визуальное наблюдение одного synthetic PNG; длинные имена/звания, миллионы/нулевые данные, RU PNG и фактические pixel contrast иной palette state здесь не исполнялись. Числа из неё не являются измерением экономии настоящих пользователей. Наличие readable PNG не заменяет Compose/TalkBack/целый экран.

Результат R36: изменённый export UI/privacy механизм принят в указанных device/source границах. Весь MyStats-screen visual GREEN не заявляется: актуальной fullscreen RU/BA пары нет в checkpoint, полный96-screen/B09/TalkBack/font/viewport/dark/keyboard/state matrix открыт. Также открыты Profile/login restore, настоящий FastAPI+изолированнаяБД E2E, cold process death duringIO, disk-full/physical delete-revoke failure, chooser/recipientcopies/openFD, физическийтелефон/FCM/MapKit/GPS/release/performance и old loaded screen privacy presentation. Неустранённые предполагаемые overflow и launch exception требуют отдельного controlled воспроизведения, а не расширения приёмки exportfix.

Reviewer не запускал устройство, Gradle, БД/сервер/внешнийprovider. Root получил105-vs91 единицы и bounded acceptance. Следующий source экран — VerifyDriver/private-docs по существующей карте96; shared docs/source остаются владельцам.

## R37 — VerifyDriver: фото, модерация и серверное состояние

Дата / ответственный: 01.10.2026, 14:16 МСК, /root/design_resume; B02/B09, private-docs связи B01/B11. Ветка audit/full-technical-20260930, HEAD6fa0595d88bea4fcd59d4a1e2139c2d128cf6821; чужие локальные изменения сохранены. Цель — разобрать все состояния VerifyDriver и сравнить UI с контрактом: pending/verified/rejected/OCRerror не подменяются; новый документ и несохранённый ввод не теряются при гонке; приватность и ошибки проверяются по всей цепочке. R37 — source evidence; новых runtime PASS нет.

### Read-map / SHA256LF

| Файл / длина / SHA256LF | Диапазоны |
| --- | --- |
| android/.../SosVerifyScreens.kt1188 /5171d7484dfa75d2feb2dc39aa290633ffd02144a59cdbfcdde3ac41a5449906 | Полностью760–1188: wrapper/Content/status/reasons/error/UploadTile/StepDot/DocumentRow; не полный файл1–1188 |
| android/.../data/ApiClient.kt7946 /b3d3b34665d5cf339eecabef5a7b27ddd6bab01ea7567fac9b8b1d45bc3e0c70 | 1640–1669, DriverStatus6519–6538 и parse6890–6909 |
| android/.../ProfileScreen.kt4819 /f2963adae108d2b0865ae7a7255a2c8f933a6aaaa324a820cb235dea82e95e4d | decodeToJpeg4760–4819 полностью |
| backend/app/routers/drivers.py648 /e19c1eb3fb82f43daf1f3727f972014993955bd4e335ad53afa48f218cc2d2b9 | Новое1–177/209–451; upload/private-doc178–206 и admin569–648 уже R26, не перечитаны |
| backend/app/services.py2110 /060566080a682cf8f913213f8839de54335ddc710093011d7e2441ac423b7981 | verdict344–359/clear362–385/revoke388–412 |
| backend/app/config.py1158 /5ca09de8521c471cd0e63bf92985ea81bb91b90aca7bdfce4e070d14dfa6411a | 44–55 defaults OCR/autoreject=true, autoapprove=false; секретное окружение не читалось |
| backend/tests/test_drivers_edges.py146 /b7e9235b98f2b17943846b142a01c691b12d1a0803cfbc82f01e06f30b3e91cd | Полностью1–146 |
| android/.../SosVerifyDeepContentTest.kt520 /eca90ba1d9abafd22cc53c97a9f7062dc9a7b7b0207bc1fab67bb8695bcc7d9c | 1–59 header/fixture и308–520 Verify:15 definitions |
| android/.../SosVerifyContentTest.kt198 /7af3b23c114e02c8d680dad1fe6e4377ba396dd114b07db2b085d49812a03598 | Полностью1–198:12 component definitions |
| android/.../SosVerifyDeep2ContentTest.kt243 /fa0053ec243f06adc8854ac8c9b0833211a5d35dbd9a0f331f5730f4ec223ff6 | Полностью1–243:15 component definitions |
| android/.../data/ApiClientAccountTest.kt223 /1d8461df119fd0a200f5eb47095dc44b604e15f40fb935049e03a0c86febdd59 | 160–199 verify wire-only; fixture R35 |
| android/.../data/ApiClientBookingsTest.kt292 /280139376dd107822c6562bc1aceac17be20d0193e9c30575271aa607d8bb86f | 1–45/185–226 status parse/defaults |
| android/.../PhotosAreCompressedBeforeSendingTest.kt75 /25a3fa771a6a1d852fa3e55c921d4e37c9b841e2c976eae2ed4466f66cda81b8 | Полностью1–75 source guards, не decoder execution |
| webapp/src/screens/VerifyDriverScreen.tsx321 /ca4aeaa346d87496c2c1701b7f6a319831d620fc0d5bd6e25bf4edae8796e073 | Полностью1–321 |
| webapp/src/api/driver.ts431 /d94a6025a9a3141a96ff3c795e1595e82a4046c3eaf20020a637b180ef4beebd | 62–82 и299–342; не весь файл |

Окружение reviewer — Windows source review. Android API34/411×2600dp относится к Robolectric fixture, не фактически исполненному устройству. Текущие сервер/БД/OCR/provider/device не запускались. Настоящее время декодирования, EXIF/качество изображений, действия модератора и озвучку TalkBack я не могу подтвердить.

Связка: Cabinet/Profile→Screen.VerifyDriver→GET status→form→GetContent→decode→upload/photo→private owner URL→state→POST profile(validations/moderation/verified revoke)→POST verify(own URL/existence/pair)→pending+OCR verdict→commit→delete replaced docs→optional admin notification→moderation R26→push. Private route/current_user владельца/админа разобраны R26; новых чужих аккаунтов здесь не использовали.

### DESIGN-044 — фактический итог отправки документов потерян

Условия: profile200, verify200 с rejected/reject, либо supported autoapprove verified/pass; также старый verified→машина изменена. ApiClient1653/1661 превращает responses в Unit; wrapper860–862 всегда пишет pending, очищает OCR и не обновляет verified. Server397 запускает _run_autocheck, services354–355 меняет User.verified/docs_status и423 возвращает сохранённый DriverProfile. Config допускает autoreject; autoapprove — отдельная поддерживаемая конфигурация, не утверждение о production. Прочитанный test_drivers_edges61–99 закрепляет оба200 с сохранёнными статусами; тест в этом этапе не запускался.

Влияние: водитель ждёт уже отклонённую заявку без причины либо повторяет подтверждённую; после снятия verified server257 UI980 продолжает показывать «Профиль подтверждён». Web165–179 также игнорирует возвращённый статус и подменяет pending; API331 обещает перечитать статус, actual caller этого не делает. Это однозначный source contract mismatch.

Минимально — authoritative ответ или после accepted submit отдельный GET полного статуса, включая verified/OCR/URLs; сбой refresh отличается от отклонённой отправки, не должен вызвать автоматическую повторную подачу. Критерий: actual wrapper с controlled profile→verify→status, pending/reject/pass/error/OCRoff, changed car, server persisted state, RU/BA причины; GETfailure после accepted submit не выдаёт заведомо неверный pending. Actual RED→fix→GREEN открыт.

### DESIGN-045 — неизвестный начальный статус выглядит установленным none

Начальные docsStatus=none/verified=false774–775, statusLoading=true796; refreshing889 передаётся только при docsStatus!=none. Отдельного initial loading нет. Пока GET удержан, Content983 рисует «Проверка не пройдена» и активную пустую форму, хотя сервер может иметь pending/verified. AGENTS4.5 требует отдельную загрузку; web220/232 разделяет loading/ready.

Влияние — неизвестный статус обозначен отрицательным результатом. Критерий: held GET actual wrapper→видимая загрузка, затем200none/pending/verified либо500→правильное состояние/повтор. Длительность/геометрия flicker не измерены; это source, не device failure.

### DESIGN-046 — поздняя загрузка перезаписывает ввод

806–812 без dirty/snapshot guard подставляет непустые server поля; форма993–1005 остаётся редактируемой. Reproduction: held GET Lada/4→ввести Toyota/2→release200 Lada/4→draft заменён старым значением. PullRefresh→edit имеет ту же связь. Обратный stale вариант: документы удалены с другого устройства→GETempty→условия811–812 не очищают прежние URLs.

Минимально — разделить загрузку статуса и черновик с явным владением/dirty, не допускать редактирование неизвестной начальной формы либо контролировать применение snapshot. Критерий: actual delayed GET на каждый field, сохранение draft и очистка исчезнувших docs, reload/discard по правилу; late after submit/back/account switch отдельно. Source loss-of-input установлен, actual controlled RED ещё открыт.

### DESIGN-047 — при замене фото submit допускает прежнюю пару

Существуют обе URLs; выбрать новое фото и held upload. Uploading826/839=true, прежняя URL остаётся; canSubmit850 зависит лишь отnonnull pair/!submitting. Tap submit до release отправляет старую URL858, позднее831/844 новая выглядит «Загружено», но проверялись старые документы. UploadTile1143 остаётся clickable приloading; второй picker и обратный порядок ответов допускают overwrite более нового выбора. Webslot46 блокирует picker приupload, но canSubmit150 тоже допускает old-pair submit.

Минимально — связать latest выбранную операцию с публикацией URL и защитить submit до завершения нужной замены; старый действующий документ не удалять на pick. Критерий: old validpair→new held upload→submit не отправляет старый выбранный документ;200/500/cancel/reverse order/leave/account, actual payload→server/admin view. Общий immutable-doc/privacy fix не выдумывается; actual RED открыт.

### DESIGN-048 — веб скрывает сбой сохранения автомобиля

Web159–163 await profile(...).catch(()=>undefined), затем165verify/166success. Profile400/422/500 означает, что введённая машина не сохранена, а экран подтверждает подачу. Server234 валидирует/модерирует; сведения об автомобиле видны модератору/пассажирам. Android857–858 прекращает verify приprofilefailure.

Критерий: controlled profile400/500→verify spy не вызван, нет полного успеха, draft/фото сохранены, RU/BA ошибка/повтор;200control→verify→authoritative state044. Нельзя игнорировать security validation ради удобства. Source mismatch, browser/API RED пока нет.

### DESIGN-049 — синхронный decoder вызывается на Main

Scope.launch828/841 без dispatcher сразу выполняет обычную decodeToJpeg829/842. Helper4769 не переключает поток: contentResolver/ImageDecoder/BitmapFactory/EXIF/resize/compress выполняются4777–4817; собственный комментарий4762 требует IO. Подтверждены место/механизм тяжёлой работы на UI/Main, не измеренный лаг/ANR.

Минимально — существующий Dispatchers.IO для decode и корректный cancel/finally, без произвольной смены качества/размера. Критерий: decoder thread oracle !=Main по actual picker path, responsive UI при gated content read, null/failure/cancel освобождаютbusy; device trace больших/облачных фото. 60fps/время/ANR не заявляются по чтению.

### Качество доказательств и остальные открытые варианты

15 VerifyContent+12+15 components=42 прочитанных определения @Test, не42 runtimePASS. Fixture напрямую подставляет canSubmit/verified/docs/urls, не доказывая вычисление850, profile→verify, OCR, delayed GET или actual upload. Highviewport2600 не проверяет обычную прокрутку; RU/BA labels не заменяют large-font/dark/keyboard/TalkBack. ApiClientverify188 возвращает{}, проверяя wire/isSuccess, а не статус. Driverstatustest194 использует artificial "approved" вместо actual "verified": parser control, не E2E.

Compression source guards не проверяют thread/EXIF/corrupt/OOM. Defaults Verify1024/88 и TaxiOnboarding>=1400/90 относятся разным callers; плохая читаемость1024 не объявлена доказанной без изображений. DriverReasonBanner guarded JSON/unknown fallback прочитан; для OCR unavailable footer «перешли снова» требует проверки смысла в pending, не автоматически новый дефект.

Дополнительные probes: read-before-insert _get_or_create_profile218–223/DBunique/concurrent verification/admin, дваPOST неатомарны, replaced-file delete послеcommit/неудача повторной подачи, lostresponse/dedup/уведомления. Не объявляются actual races без PG. UploadError782 устанавливается832/845, но не передаётся Content, failure лишьToast; persistenterror/retry требуется проверить. Seats input фильтрует digits, а toIntOrNull()?:4 молча заменяет empty/overflow; boundaries0/1/8/9/Unicode/long и правило сохранения draft открыты.

Комментарий ApiClient1643 называет upload публичным, но actual protected route R26/web API говорит private: комментарий устарел, утечка не доказана. Отсутствующий preview выбранной фотографии — предложение; Source UploadTile loaded status не является full-image приёмкой. Проверки truecamera/EXIF/providers/physical/FLAG_SECURE capture/TalkBack/viewport/theme/release/performance открыты. Не удаляются StepDot/DocumentRow по одному отсутствию прямого вызова.

Root получает044–049 для единственного реестра. Общие исходники/документы не изменялись. Далее SupportBoost donation/boost по существующей96карте; полный B09 остаётся открыт.


## R38 — поддержка платформы и поднятие объявления: договор ответа и поздние платежи

01.10.2026,14:32 МСК; /root/design_resume, независимый read-only проход B09 по исходной карте96 экранов; денежные связи B07 переданы root. Цель: полностью прочитать собственный SupportBoostScreen и проверить переход выбор→POST→ответ провайдера→экран→подтверждение, включая неуспех/повтор/смену выбора. Приёмка потребует actual wrapper RED→минимальный fix→GREEN для нижеуказанных условий и UI matrix, а не готового succeeded в Content.

Версия: audit/full-technical-20260930; HEAD6fa0595d88bea4fcd59d4a1e2139c2d128cf6821; dirty. SupportBoost не изменён мной, payments содержит чужой QA004 diff. Из исходников и тестов ничего не изменено. Windows; Android API/viewport/шрифт/язык/тема этого этапа не исполнялись. Синтетические данные из определений тестов; настоящий сервер/БД/device/provider не использованы. Это чтение источников, а не результат прогонов. Branch/status проверены локальным git, без GitHub. SHA256 нормализует CRLF/CR→LF, как прежде.

### Карта прочитанного и версия

| Файл /строк /LF-SHA256 | Точно прочитанный диапазон |
|---|---|
| [android/app/src/main/java/com/yuldash/app/SupportBoostScreen.kt](../android/app/src/main/java/com/yuldash/app/SupportBoostScreen.kt) /951 /5723e610ec8a0811c4d5d039a91c461afffb131269f77bc51c09c23577ee255e | Полностью1–951: imports, Support/Content, Boost/Content, carryRU/BA, RideRow/PlanCard, Result wrapper/content, StateMessage и завершающий OAuth-comment; прежнее структурное обнаружение не выдано за семантическое чтение |
| [android/app/src/main/java/com/yuldash/app/MainActivity.kt](../android/app/src/main/java/com/yuldash/app/MainActivity.kt) /1007 /79c7865bb0a17ae2d1d5ac1ef6268aadd8cebedeb422c99e887d7444d70ebabe | 901–953 SbpTransferSheet и955–969 QR/link: сумма в копейках, null-requisites→константы, copy/paid/dismiss; enum456–464. Не полный файл |
| [android/app/src/main/java/com/yuldash/app/YuldashApp.kt](../android/app/src/main/java/com/yuldash/app/YuldashApp.kt) /2618 /92883a6d253da875ddec50a229f2d565e937cb7051556613bf5c1d353a34487f | 696–719,1248–1255,1476–1486: Support/Boost navigation/deep-link; не полный файл |
| [android/app/src/main/java/com/yuldash/app/data/ApiClient.kt](../android/app/src/main/java/com/yuldash/app/data/ApiClient.kt) /7946 /b3d3b34665d5cf339eecabef5a7b27ddd6bab01ea7567fac9b8b1d45bc3e0c70 | 730–746,1570–1594,2268–2350,6985–7002,7184–7221: me/referral/freeboost/ride/plans/donate/status DTO/parser; не весь файл |
| [backend/app/routers/payments.py](../backend/app/routers/payments.py) /825 /bf2315c1cb76da34820c11f6f6061f3d34ce5dd55345604795b269bcc2ad23d8 | 1–196,252–313,470–701: планы/reuse/provider/часть activation/boostfree/create/donate/support/status. Не весь файл, актуальный QA004 diff здесь отдельно не принимается |
| [backend/app/payments.py](../backend/app/payments.py) /155 /d17731f2aded91737cc68b79f9830a2281fdf654c5a7c98a079c5c6fa51a6109 | 1–54: BOOST_PLANS/weight и начало receipt. Не полный файл/не реальная ЮKassa |
| [backend/app/routers/referral.py](../backend/app/routers/referral.py) /263 /62ecd8f38da7abe66e0bcfac47397d1a901f8044f6f44e49a2693824cd86a8e1 | 1–120: зависимости бонусов; не полный файл/не семантическая приёмка всего referral |
| [webapp/src/screens/BoostScreen.tsx](../webapp/src/screens/BoostScreen.tsx) /301 /44fa8036fb261a1bb604059a0bdb452def6c279e340ac32eb34a747c5f2a1d99 | Полностью1–301 |
| [webapp/src/screens/SupportYuldashScreen.tsx](../webapp/src/screens/SupportYuldashScreen.tsx) /193 /e7b01c80556ece5e5710e7267730aa97add6d98b57a455d9aa37f46a8e4f4bb4 | Полностью1–193 |
| [webapp/src/api/boost.ts](../webapp/src/api/boost.ts) /59 /27e2e5d1e00aa0b7e7e15a0c10e54e62bea39287a718e09545db0ac1788e62bc | Полностью1–59 |
| [webapp/src/api/support-donate.ts](../webapp/src/api/support-donate.ts) /29 /c3d41a1e10ad89867de76c84ab7919dc4f0f71639b24dff73e12405ddbca4ffb | Полностью1–29 |
| [webapp/src/utils/pendingPayment.ts](../webapp/src/utils/pendingPayment.ts) /61 /68c3568280c30b5874805fa828665a18e70e32408b26a2fa10ea75662925b883 | Полностью1–61; не все потребители return payment |
| [android/app/src/test/java/com/yuldash/app/SupportBoostContentTest.kt](../android/app/src/test/java/com/yuldash/app/SupportBoostContentTest.kt) /177 /f28445e9d93284bf3370f7ef1eafc338e05d22e2ecea7ad408769c293dd83132 | Полностью1–177 |
| [android/app/src/test/java/com/yuldash/app/SupportBoostDeepContentTest.kt](../android/app/src/test/java/com/yuldash/app/SupportBoostDeepContentTest.kt) /272 /72da1262bfb3c53925326bba910ddf043190cc77589ce8f77c6049252701a701 | Полностью1–272; разрыв230–272 дочитан отдельной командой после output truncation |
| [android/app/src/test/java/com/yuldash/app/SupportBoostDeep2ContentTest.kt](../android/app/src/test/java/com/yuldash/app/SupportBoostDeep2ContentTest.kt) /218 /f96a249768e4251708da3d3dfde627377d51d2ca60babb5da6cef8d4c1e0796a | Полностью1–218; header1–29 дочитан после output truncation |
| [android/app/src/test/java/com/yuldash/app/SupportBoostDeep3ContentTest.kt](../android/app/src/test/java/com/yuldash/app/SupportBoostDeep3ContentTest.kt) /296 /ff37d888a91d71b0345e4257613a8e2aa9520a03721b1e7f4688ea3e29a209f3 | Полностью1–296 |
| [android/app/src/test/java/com/yuldash/app/SupportBoostDeep4ContentTest.kt](../android/app/src/test/java/com/yuldash/app/SupportBoostDeep4ContentTest.kt) /227 /454dc9c40feb778f0c6d95070dcd472061dc4f366dde89cab3c7743d1521b8e4 | Полностью1–227 |
| [android/app/src/test/java/com/yuldash/app/BoostCarryTextTest.kt](../android/app/src/test/java/com/yuldash/app/BoostCarryTextTest.kt) /41 /dfa0396fe6e7dbe1c5e0715481f9c3399a04297c5787da93e52e7d9ba5545c02 | Полностью1–41 |
| [android/app/src/test/java/com/yuldash/app/data/ApiClientBookingsTest.kt](../android/app/src/test/java/com/yuldash/app/data/ApiClientBookingsTest.kt) /292 /280139376dd107822c6562bc1aceac17be20d0193e9c30575271aa607d8bb86f | 250–292; прежняя fixture1–45 уже вR37 |
| [android/app/src/test/java/com/yuldash/app/data/ApiClientCriticalBadPathTest.kt](../android/app/src/test/java/com/yuldash/app/data/ApiClientCriticalBadPathTest.kt) /320 /d0fbc01dbee6877f38fdd2db7f40b6974b9e64912871c232ec3aa0945b4c92ea | 1–63,217–273: fixture и ошибки donate/boost/free; не весь файл |
| [android/app/src/test/java/com/yuldash/app/data/ApiClientEndpointContractTest.kt](../android/app/src/test/java/com/yuldash/app/data/ApiClientEndpointContractTest.kt) /479 /801ab537fdb4d511c31e4049c35c2581f56c78158aef0a723c4f355f6ca3fd23 | 1–60,273–291: контракт method/path; не весь файл |
| [android/app/src/test/java/com/yuldash/app/ApiParsingTest.kt](../android/app/src/test/java/com/yuldash/app/ApiParsingTest.kt) /689 /6792f8e04b959a89340ef138f83ace6969c733e69ab37e4644c76b3a127877ac | 1–63,208–232,591–638: DTO units/nullableparser; не весь файл |
| [backend/tests/test_boost_yookassa.py](../backend/tests/test_boost_yookassa.py) /185 /8087aebe4ef0eadd6b63535c91e253ccc6f8cc17ee6efc50d7a02cf6b0180cd2 | Полностью1–185; окончание169–185 дочитано после output truncation |
| [backend/tests/test_payment_double_tap.py](../backend/tests/test_payment_double_tap.py) /92 /67cf71c1b3924e46b6a96bf8f0d66d998f80c103c2665cf2762e73f349da8c45 | Полностью1–92 |
| [backend/tests/test_payments_edges.py](../backend/tests/test_payments_edges.py) /227 /c65e20b0f3b73b1c82bc631f37414c4d1c0f89b95f12a48dfbce472634c3638b | 1–68,101–227: support/donate/boost/webhook; ad69–100 не прочитан в этом этапе |

### Разобранные пути

| Пользовательское действие → Android → запрос → сервер/данные → участники | Состояния и границы доказательства |
|---|---|
| Profile→Support263→presets20/50/100 либоcustom→supportDonate2322→support_donate639 | Client10..5000₽/digits.take4, server1000..500000коп, purpose support; sbp_manual pending/admin либо yookassa URL/succeeded. Водительский ledger по правилу support не меняется; definitions backend проверяют отсутствие начисления. Смена суммы разрешена во время отправки, запрос захватывает сумму. Реальная сумма/принятие не доказаны исполнением |
| DriverCabinet→Boost465→getBoostPlans/getDriverRides/getReferral/me | Loading/error/retry/empty и перенесённый остаток/bonus/free/планы прочитаны. Initial selectionfirstRide, stale removed ride/tier после reload, hidden optional GET failures, cold process loss remaining open |
| Выбрать свою поездку+plan→createBoost2312→boost_create517→Payment | Own driver403/ride404/active400/prod-mock503, pending reuse30мин и yookassa permanent row key, responsemanual/yookassa/succeeded. Серверные sequential definitions не доказывают PG simultaneous reuse/seat locks |
| yookassa create→ACTION_VIEW→RESUMED repeatOnLifecycle→payment_status681→_sync_provider_status | Server returns own payment_id/status/purpose/boosted_until; чужой→404. Android6 retries/delay2500 — константы кода, не измеренная частота; manual scope check и cancel effect разобраны отдельно. Две стороны manual=driver иadmin, их actual совместный сценарий ещё нужен |
| Adminconfirm/reject ручного счёта→Payment terminal/ride boost→экран водителя | API/server путь определения тестов есть; Androidmanual ответ остаётся статичным, см.DESIGN054. Никаких настоящих денег/провайдеров/Telegram в этом этапе |
| WebSupport/Boost→sameAPI→confirmation_url/rememberPayment→return | PWA support обрабатывает URL и succeeded; Boost реальный pending UI branch manual и yookassa имеют check. Webdrivers GET failure превращён в [], бонус/free/carried отсутствуют в этом BoostScreen, а sourcependingstore не доказывает полный return/login/owner-path |

### Измеримые замечания по коду — runtime остаётся открытым

**DESIGN-050: Android поддержка выдаёт ручной СБП для любого успешного ответа провайдера.**
Условия: выбрать50₽; вернуть предусмотренный payments.py679 JSONstatus=pending,method=yookassa,payment_id,confirmation_url безamount/payee либо678succeeded. Support318–320 без условий ставитshowSbp=true; parser6996 absentamount→Int0, поэтому nullable-elvis330 использует0×100коп. SbpTransferSheet910–912 заменяетnullpayee прежними константами; это уточняет раннее сообщение root «отсутствующие реквизиты»: фактический код показывает fallback-реквизиты, а не пустоту. URL не передан/не открыт. Влияние: вместо разрешённой карточной оплаты показана инструкция ручного перевода0₽, после ужеsucceeded также ждёт перевод; неправильный payment method нарушает договор и вводит человека в заблуждение. Wrong receiver/реальное списание не утверждаются. PWA47–53/66–88 выделяет эти ветки. Правило: фактический ответ/все состояния, деньги/двуязычное действие. Минимальное исправление: отдельно принимать succeeded, pending manual с серверными реквизитами/суммой и pending yookassa с действующей URL/check; повреждённый ответ давать как ошибку без реквизитов другого способа. Проверка после: actual SupportScreen+реальный parser с тремя response forms; URL intent/one request, absence manual UI foryookassa/succeeded; manual правильный serveramount, networkerror/lateowner и отсутствие двойного логического invoice. RED/fix/GREEN открыты.

**DESIGN-051: поздняя ручная проверка платежа A помечает текущий результат B успешным.**
Условия: получитьpending yookassaA; после auto tries нажатьcheck и удержатьGETA вscope.launch593–599; сменитьride/tier561–562; начатьpayB576→получитьpendingB; выпуститьGETA succeeded. pollPaymentOnce497 захватываетpidA, но501 копируетstatus в текущийresultB и502 очищаетcurrentpendingPaymentIdB без проверкиID. Result875 затем пишет «Объявление поднято», хотя сервер подтверждалA; B не проходит дальнейшую проверку. Проверяется именно manual coroutine, отменаLaunchedEffect(A) не отменяетscope.launch manual. Selectors иpay747 не запрещеныcheckingPayment. Это source trace, не выполненный race/не доказательство backendнеправильного начисления. Правило: поздний ответ не должен менять другой платёж/выбор. Минимальный fix: correlates captured pid+intent/owner с currentresult/pending; stale response не очищает/new succeeds B, тихийrefresh отдельно. Проверка после: два независимых paymentID/ride, deferredGETA, реальные клики выбора/POSTB, oracleBpending+pollB сохраняется/Aridesbadge можетrefresh; backend payment/boostB остаются pending/unboosted. Соседние pending/canceled/401/network и Back/cold-restart открыты.

**DESIGN-052: имена действующих тарифов остаются русскими при башкирском языке.**
Backend BOOST_PLANS20–24 содержит только русские названия; boost_plans104–108 возвращает одинtitle безлокализации; ApiClient2275/parseBoostPlans передают его; AndroidPlanCard834 Text(plan.title), webBoost275 тожеrawtitle. Условия: AppLanguage.Ba + реальные триplans «Быстрое поднятие»/«День вверху»/«Срочная поездка»; подзаголовкиBA, названияRU. ПравилоAGENTS§3: двуязычные собственные строки. Исправление: локализовать известныйtier черезappText либо согласованный serverbilingualtitle, цену/часы продолжатьserver-only; неизвестныйtier не потерять. Проверка: same real responseRU/BA/defaultunknown/dynamicprice-hours, layoutlongBA. НовыеBAчерновики→tasks ownerroot. Source подтверждено, actualUI open.

**DESIGN-053: отказ загрузки бонусов/оплаченного остатка скрыт как нулевое значение.**
reload517–520 getReferral/me.onSuccess безFailure, defaultscredits0/carried0; loadError толькоp/r. При p/r200 и referral500 кнопкаfree735 отсутствует; приme500 emptytext680 предлагаетсначалаpublish безинформацииужеуплаченногоостатка. WebBoost54 такжеcatch driverfailure→[] и«нетактивных» вместоошибки. Влияние: неизвестный доступный бесплатный/оплаченный ресурс показан как отсутствующий, человеку доступна платнаяCTA. Подлинное сгорание/новое списание не утверждается. Правило всех состояний/честноеunknown vsempty. Fix: отдельный unavailable+retry дляbonus/carry (сохранятьпригодный staleконтекст) без превращения всегоосновногосписка в отказ. Проверка: each failure separately/currentpositivebalance→failedrefresh, recover→free/carryrealstate, остальныеданныеработают; webdriverfailure отдельноerror/retry. Sourceconfirmed, исполнениянет.

**DESIGN-054: экран не наблюдает итог ручного СБП-платежа за поднятие.**
Условия: boost_create→sbp_manualpending; второйучастникadmin подтверждает илиотклоняет тотжеID при открытомAndroid. pendingPaymentId583 ставитсятолькодляyookassa, pollEffect530 приnullвыходит; checkUI754 тожеyookassaonly. BoostResultContent880 manual показываетrequisites и обещаниеручного включения, результатstatus в этойветке не обновляется. Изинтерфейса нельзя проверить succeeded/canceled; paidCTA остаётсядоступной, serverboostcreate послеsucceeded можетсоздатьещёсчёт. Это отсутствиенаблюденияterminal поsource, не утверждениеобязательного двойногосписания — новаядокупка можетбытьосознанной. Webmanual157–165 имеетrecheck. Fix: statusпоserverpayment_id дляmanual с понятнымиpending/succeeded/canceled и сохранениемнамерения, двуязычный retry; отличатьпроверкуэтойоплаты отдокупки. Проверка: driver+adminподтвердить/отклонить вstand безденег, actualUI увиделименноэтотID/новыйbadge, не предлагаетплатитьзаотменённыйсчёт какзаpending; repeatedcheck no invoice/extraactivation. Runtimeopen.

DESIGN020 сохраняется как прежний механизм: Boost584 runCatching ACTION_VIEW скрываетошибку; Result917–920 говоритоботкрытомокне, retryURL отсутствует. Здесь добавленпотребитель, отдельный конкурирующийIDнесоздан. DESIGN001/002 аналогично: whiteprice838/StateMessage940 иradio/presetцветбезselectedsemantics, sourceconsumerдобавлен; фактическийTalkBack/contrastmatrix неисполнялся. Формы Card14/22dp/Card vsCanon и длинныеназвания/ценабейдж требуютactualadaptiverender, безобщеговизуальногоPASS.

### Качество существующих тестов и непроверенные варианты

В пяти полностью прочитанных SupportBoostContent/Deep1–4 найдено54 @Test =7+12+12+13+10; отдельно4 carrytest =58определений, не58прогонов/PASS. Они запускаютContent/готовыйresultSlot, анеSupportScreen/BoostScreen с настоящим path/ответами; не ловят050/051/053/054, provider/owner/process/races. Native RobolectricAPI34; Deep4viewport411×2600dp не доказывает маленький экран/реальный nonlinearfont. Freeze mainClock вcomponenttests не скрываетсостояниегонки, т.к.wrapperвообще отсутствует. Deep4 validInput121 проверяетотсутствиенеиспользуемой строки«От10до100000», тогдакакактивныйContentпредел5000; этотnegativeoracle слабый. Исправитьтестпоредактируемойpolicyизпользовательскогосценария, неослаблятьprod.

ApiClientBookings254plans проверяетparser; CriticalBadPath235/243/265 — failure/status; EndpointContract283–284 лишьmethod/path с synthetictinyamount1. ApiParsing597 manualpayload c amount1000 иnullpid не ловит отсутствующуюamount в yookassa. Ни один не проверяетUIконтрактprovider. Backendbooster5definitions иdoubletap5 читаются как определения, поэтомунеприсвоенgreen. Их two sequential POST не доказывают simultaneousPG. Edge173support действительно смотритpaymentamount/purpose/noledger, waveform201 повторяетwebhook, однако не входитвAndroidUI. Teststand realprovider отсутствует.

Следующие probes безстатуса«дефект»: PG simultaneous reuseFreshPending/create/free sameRide иразныеUser, ordering canceled ride↔activate/free, coldprocess/restart/paymentrestore, account-switch/latecreate, selectionpending/create→другойride resultlabel, stoppedcheckingfinally, corruptmissingid/url/method, serverplanscachepricechangewithinTTL, readonlydisk/offline; неизвестныйтариф/backguestlogin/scroll+font/IME+bars/theme/TalkBack/CTAfeedback; реальнаяreceiptdelivery/таймингиadmin/provider/MapKit не подтверждены. MapScreen1448–1460/1513–1528 подтверждаетиспользованиеboostedbool дляpin, поэтомуобещаниевыделениякарты не объявлено ложным бездальнейшейпроверки.

### Результат и следующий шаг

Полное семантическое чтение SupportBoost1–951 и связанного websupport/boost закончено. DESIGN050–054/root получатcurrentregistrylinks; правила оплаты обсуждаются как подтверждённыеcodepaths инеисполненныеreproductioncriteria. Ничегонеисправлял/sourceнепереписывал. WholeB07/B09/96screen/realproviders/physicaldevice не приняты. Следующее ещёнепрочитанноеосновноеAndroidполотно по карте — BookingScreen326–769 (полностьюwrapper и егоContent/связи; прежнийPayOnlineR22читатьтолькоеслиhashизменён), затемActiveTrip. Доэтого важные frozenmoney/UIfixevidence отroot имеютприоритет.

## R39 — переход по ответу поддержки ведёт на донат

01.10.2026,14:40 МСК; /root/design_resume; независимое уточнение связи R16/R20/R38, B09 и push-путь B08. Branch/HEAD прежние, рабочая копия dirty. Только чтение; Android/FCM/сервер/БД не запускались. Исходный экран Support уже полностью прочитан в R38, неизменённое чтение не повторялось. Цель: сохранить точную связь admin reply→push→Intent→MainActivity→экран. Критерий: конечный экран того же обращения после входа, а не только установленный Boolean-сигнал.

| Источник /строк /LF-SHA256 | Проверенный диапазон |
|---|---|
| [android/app/src/main/java/com/yuldash/app/MainActivity.kt](../android/app/src/main/java/com/yuldash/app/MainActivity.kt) /1007 /79c7865bb0a17ae2d1d5ac1ef6268aadd8cebedeb422c99e887d7444d70ebabe | 313–433: handleNavIntent/openChatFromPush и DeepLink; не полный MainActivity |
| [android/app/src/main/java/com/yuldash/app/data/FcmService.kt](../android/app/src/main/java/com/yuldash/app/data/FcmService.kt) /167 /0e00a602f5befa6d36b446d7f0e8c8f0098d54ce7ac21558b4feaba8f0d20005 | Полностью1–167, после output truncation дочитано70–151; это source, не доставка FCM |
| [android/app/src/main/java/com/yuldash/app/YuldashApp.kt](../android/app/src/main/java/com/yuldash/app/YuldashApp.kt) /2618 /92883a6d253da875ddec50a229f2d565e937cb7051556613bf5c1d353a34487f | 709–714,442,1252,1375,1497–1505: сигнал→Screen.Support; правильный путь из ленты→SupportTicket, ранее R16/R20 прочитан |
| [android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt](../android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt) /2490 /335684e9e57c5aa625c089f3e708de55bce02c95d5c8d59501ba319ccac63b1f | 357: notify routing support→onOpenSupport; весь Notifications ранее R20, не повтор |
| [backend/app/routers/support.py](../backend/app/routers/support.py) /369 /81b7969fd97ec425a50b06bbd1be47490e1a2a35d08b4de5ad59ea282c6ce586 | 326–353 admin reply и push аргументы; прежние own_ticket/GET проверки R16/R17 не повтор |
| [backend/app/services.py](../backend/app/services.py) /2110 /060566080a682cf8f913213f8839de54335ddc710093011d7e2441ac423b7981 | 684–704,730–767: notification→type/id; не полный services/send_push |
| [android/app/src/test/java/com/yuldash/app/PushRoutingChainTest.kt](../android/app/src/test/java/com/yuldash/app/PushRoutingChainTest.kt) /118 /d13a70e75d13b5f18455f4ccafb140a41fd337b5a2bc4ce0c8ba7fcf69d1e040 | Полностью1–118:9 определений, ни одного для support/конечного Compose destination |

**DESIGN-055 — уведомление поддержки открывает добровольную финансовую поддержку платформы.**
Условия по коду: администратор отвечает в обращении с положительным ticket_id. support.py346–350 вызывает push_notification(ref_kind=support,ref_id=t.id); services747–748 создаёт type=support,id. FcmService76–85 записывает эти поля в PendingIntent; необработанные background extras type/id идут в ту же MainActivity ветку357–369. Извлечённый id369 теряется: сохраняется только DeepLink.pendingSupport:Boolean. YuldashApp709–714 ждёт запуск и логин, затем гасит флаг и открывает Screen.Support; ветка1252 показывает SupportScreen — добровольный донат R38. Ответ обращения человек там не увидит. Лента приложения Secondary357→YuldashApp1375 устанавливает supportTicketId и открывает Screen.SupportTicket, поэтому тот же ответ имеет разные назначения. Это однозначная source цепочка; фактическая доставка FCM/нажатие на устройстве пока не подтверждены.

Правило: уведомление ведёт к исходному действию/разговору, включая восстановление после входа; финансовое действие не должно подменять ответ на обращение. Предлагаемый минимальный fix: сохранить ticket_id до разрешённого authenticated перехода и открыть SupportTicket для этого ID, с предусмотренным owner404/error; legacy флаг без ID — список обращений. Не менять назначение обычной Profile→Support кнопки добровольного доната. Переход не должен раскрывать чужой тикет после смены аккаунта. Root получил цепочку и критерии.

Проверка после исправления: actual MainActivity raw type/id и локально сформированный FcmService PendingIntent→настоящий YuldashApp→SupportTicket→GET своего ID→текст ответа; приложение уже открыто, cold start, выход→вход того же аккаунта, смена аккаунта→отказ/без старого текста, положительный/нулевой/битый ID, повтор consumed Intent и Back. Для настоящей FCM доставки/phone нужен отдельный внешний прогон. Прежний PushRoutingChainTest проверяет signals, не конечный экран; все9 определений source прочитаны, не названы PASS. Здесь не исправлен исходник/не запущены тесты.

Передача: R38 DESIGN050–054 и R39 DESIGN055 source подтверждены; fix/RED/GREEN остаются открыты. Booking326–769 полностью дочитан раздельными диапазонами; ещё нужны BookingDecisionBar/DriverHero/Route/Meeting/PayAgreement/Minor и caller/server/tests. Source связи с оплатой R22 не читать повторно без изменения SHA. Свои процессы/замки отсутствуют; общие ресурсы у root.

## R40 — запрос обратного звонка: независимая проверка исправления и первый сбой устройства

01.10.2026, 14:51:59 МСК; /root/design_resume; QA-B08-001 / DESIGN040, B08/B09. Windows, ветка audit/full-technical-20260930, HEAD6fa0595d88bea4fcd59d4a1e2139c2d128cf6821 по сохранённому checkpoint; локальные изменения есть. Агент читал исходники, raw-before diff, первичные результаты и PNG; собственные Gradle/adb/БД/provider действия отсутствуют. Цель: от SimpleMode через настоящий CallbackHelp/YuldashApp/API получить честное ожидание, отказ без потери текста и подтверждение только после принятого ответа; повторное нажатие при удержанном HTTP создаёт один POST. Backend: False уведомителя не должен объявляться успехом, при этом не возникает фиктивно сохранённой заявки.

Уточнение времени прежних записей: R39 содержит вручную указанное14:40, которое не измерялось. Файл после первоначальной записи имел mtime01.10.2026 14:35:29.8918879+03; это время изменения файла, не доказательство точного начала проверки. R38 также содержит вручную указанное14:32 без самостоятельного измерения. Исторические записи/результаты сохранены; новые отметки этого этапа получены datetime.now().astimezone().

### Прочитанные изменения и версии

| Источник / строки / LF-SHA256 | Семантический разбор |
|---|---|
| android/app/src/main/java/com/yuldash/app/YuldashApp.kt /2630 /01ca3d4cb3a78a9f8171a728dbbcb77eaa4cc0f76cdcfa1ccfc1cfd54ed72a0c | Raw-before/current diff;347–349,1583–1609: single-flight до launch, generation до HTTP, true только onSuccess, finally освобождает ожидание. ScreenTopBar2258–2271 отдельно для наблюдения адаптива; это не полное чтение YuldashApp. |
| android/app/src/main/java/com/yuldash/app/AccessibilityScreens.kt /2120 /b70d3cdf5cace46a66c7e33c584eeb62d2e3890c8f89a9ff3ba5e1cc30fd7f78 | Raw-before/current diff и полностью CallbackHelp2020–2102: draft, телефонная ветка, requested card, AppButton loading58dp. Остальные ранее прочитанные экраны не повторялись. |
| android/app/src/main/java/com/yuldash/app/YuldashViewModel.kt /119 /225f9c59aaa942b1d9bb0586a5c4aa1d077d0d8f7d88360fa1eb634e077cb469 | callbackRequested66,clearUserData94–112; false при очистке аккаунта, не persisted ticket. |
| android/app/src/main/java/com/yuldash/app/data/ApiClient.kt /7946 /b3d3b34665d5cf339eecabef5a7b27ddd6bab01ea7567fac9b8b1d45bc3e0c70 | requestCallback1232–1233/authenticated POST note и existing session-generation helper; не весь файл. |
| android/app/src/test/java/com/yuldash/app/CallbackRequestJourneyTest.kt /307 /8a8dafdefc79bedd83acb3fcfb57b9cc0cb8cdce8bfaaa2c46fadb2fd6d6bbf7 | Полностью1–307, после усечения отдельно238–278. Настоящий UI wrapper/ApiClient + MockWebServer, три сценария. |
| android/app/src/androidTest/java/com/yuldash/app/CallbackHelpJourneyInstrumentedTest.kt /213 /d3a35da3eca14487d22527da3f3adb437022006f40884916c87ba7a4ba2a2a9d | Полностью1–213: настоящий ComponentActivity/SimpleMode→CallbackHelp, pointer taps, реальный Android HTTP к синтетическому loopback, PNG и markers. |
| backend/app/routers/safety.py /1439 /f6c772b7c5d1f4804bd2c1c907f31148e5cdc86188acf7007612e4a69adae891 | Diff и CallbackIn/POST402–431, соседний SOS; весь safety не заявлен прочитанным. False→herr503 до ok. |
| backend/app/services.py /2110 /060566080a682cf8f913213f8839de54335ddc710093011d7e2441ac423b7981 | Неизменённый notifier987–1012: нет конфигурации/сбой→False,2xx→True. |
| backend/app/errors.py /13 /f95b0273ab353e20c7ab0bb7e08be67a9a3147799492dd928e97b723193005ea | Полностью1–13, RU/BA detail. |
| backend/tests/test_callback_delivery_result.py /199 /f1ca65d8f6a9342328ec7c887af8ae3c324f8d0768f31431f1274cadeb4f6d2b | Полностью1–199:18 параметризованных cases/существенные эффекты. |
| backend/tests/test_safety_edges.py /125 /ecda9f0485b4abbc54cd3a929dcb580df4391dcee1264c88b7756ed22406b321 | Полностью1–125 и diff: callback notifier возвращает явный True; соседний SOS persist-policy не ослаблен. |

Это снимок версии14:49:23, полученный собственным пересчётом SHA; последующее изменение снимает актуальность соответствующего доказательства. Existing AppButton149–205/UiKit перечитан только для loading/disabled/semantic label, предыдущие неизменённые тесты не запускались.

### Первичные доказательства backend

[Backend checkpoint](../test-results/audit-callback-backend-checkpoint-20261001.json) LF-SHA d91cfa658a852b81241c9a1367c692a4f26ff3c8ffce7c3393a615d7e4c00feb. Самостоятельно повторно сверены7 current +7 raw snapshot +24 artifact =38 файловых проверок, все совпали; это не38 тестов. Source security/conftest metadata не объявлены новым полным семантическим чтением.

Самостоятельно разобраны XML и18 primary QA_CALLBACK_DELIVERY markers в каждом RED/final/mutation:
- [RED](../test-results/audit-callback-red-20261001.xml):18 cases,3 failures,0errors/skips,2.545с XML; RU/BA False и реальная missing-config False ранее возвращали HTTP200/ok=true без сохранённой заявки.
- [Final](../test-results/audit-callback-final-20261001.xml):24=18 новых+6 соседних,0 failures/errors/skips,3.113с XML; PID7924,5.687с wall по metadata. False→503 с RU/BA detail, принятый повтор→200; before/after профиля/жалоб/SOS/уведомлений/тикетов/сообщений проверены. Авторизация гостя/битого токена, чужие поля тела, omit/empty/Unicode/499/500 и invalid501/null/number покрыты.
- [Mutation](../test-results/audit-callback-mutation-20261001.xml): удалён False guard в изолированной копии;18 cases, те же3 failures и15 controls,0errors/skips,2.834с XML. Это подтверждает чувствительность тестов к исходной ошибке; сохранённый restore manifest не равен новому самостоятельному чтению всех110 файлов копии.

Настоящий FastAPI/test DB здесь SQLite с synthetic accounts; focused notifier boundary заменён, missing-config case использует настоящий notifier и его False. Реальные Telegram, SMS, оператор и рабочий сервер не вызывались. Изменённая ветка не зависит от PostgreSQL locking; PG-proof ей не присвоен. True означает принятую notifier2xx границу, не звонок человека и не независимую проверку Telegram JSON. Backend-часть DESIGN040 принимается только в этих условиях. Durable queue/idempotency при потере ответа по-прежнему отдельный открытый риск.

### Первичные доказательства Android JVM

[Initial RED](../test-results/audit-callback-ui-red-20261001/TEST-com.yuldash.app.CallbackRequestJourneyTest.xml):3 cases,2 failures,21.689с XML, PID31608,93.781с wall. Pending ещё до HTTP назывался requested; двойной настоящий pointer tap создавал2 POST, второй завершался200 пока первый удержан. BA503→retry был отдельным зелёным контролем, не стирающим два сбоя.

[Final metadata](../test-results/audit-callback-ui-final-20261001.json) и пять XML разобраны независимо:13 AccessibilityForms +3 CallbackJourney +14 ApiClientAccount +8 LoadingButton +3 ProtectedActionLogin =41 cases,0 failures/errors/skips;31.646+6.943+6.118+1.589+3.534=49.830с XML. PID24424,92.281с wall. Семь primary markers CallbackJourney показывают requested=false/card0/disabled/1POST пока gate удержан; после503 сохранён тот же BA draft и разрешён повтор; после200 requested=true/card1. Все auth соответствуют синтетическому аккаунту, повтор после503 идёт после завершения предыдущего HTTP, gateTimedOut=false.

Команда точна в metadata: :app:testDebugUnitTest --tests CallbackRequestJourneyTest --tests AccessibilityFormsContentTest --tests LoadingButtonAccessibilityTest --tests ProtectedActionLoginJourneyTest --tests data.ApiClientAccountTest --no-daemon. API34/Robolectric Native/411×1200dp; MockWebServer заменяет backend. Реальная SimpleMode навигация не подменяет проверяемый callback handler; исходный экран seeded, полный guest→login restore не доказан. Эти41 прогона не41 новых уникальных проверки. UI3 initial green и final41 относятся к перечисленному source, а не к будущему исправлению device/Toast.

[Build metadata](../test-results/audit-callback-ui-build-20261001.json): :app:assembleDebug :app:assembleDebugAndroidTest -PstorageAuditRunner=com.yuldash.app.StorageAuditRunner --no-daemon, JBR, PID19936, exit0,43.688с. Сборку выполнял root; release/R8 не доказаны.

### Первое настоящее устройство — результат не принят целиком

[Device log](../test-results/audit-callback-device-20261001.log), [metadata](../test-results/audit-callback-device-20261001.json), [markers](../test-results/audit-callback-device-20261001-markers.txt). Root подтвердил emulator-5580 device/API35/1080×2340/density440/fontScale2/light. Locale getprop пустой — системный язык не подтверждён; RU/BA задаёт приложение. APK installation Success, rawSHA debug e7fc7340ec91b48d4aa8c14248658eeeccb6d47f62dba2d468b15e8b4c2017d7 и androidTest165a412e57be8c89a4cbaa2257ea9f09ee0f32b6db4b93a46007f244e2df4255. Actual instrument2 cases:2 failures,14.505с instrument,27с wall. am instrument process exit0 не означает PASS; tests_passed=false и первичный FAILURES сохранены.

Оба теста после освобождения ответа падают с NPE «Can't toast on a thread that has not called Looper.prepare()», YuldashApp1597 success/1600 failure; стек содержит Compose ApplyingContinuationInterceptor/FrameDeferringContinuationInterceptor→CoroutineScheduler. Pending observation до исключения сохраняется: requested=false,1 POST/returnedAt0,disabled=true,gateTimedOut=false. Accepted/after503 PNG отсутствуют, pull exit1 честно сохранён. Нельзя объявлять product-thread причину без отделения влияния test continuation; классификацию/исправление и повтор выполняет root. Ни итогового accepted-device, ни retry-device PASS эта запись не присваивает.

Самостоятельно просмотрены реальные:
- [RU pending PNG](../test-results/audit-callback-device-20261001-images/qa-callback-Ru-pending.png), rawSHA f4874daf002860bbe7f6deb97493f9a53e8b84bd3eb5a30e3af9a698804741d8: поле и waiting CTA видимы, ложной confirmation нет.
- [BA pending PNG](../test-results/audit-callback-device-20261001-images/qa-callback-Ba-pending.png), rawSHA62a5b2c211b08b6d253e4ee624e372e77b9e4bdfa54d1eec16be9f30f0433670: draft читается, keyboard открыта; CTA кадром не подтверждается.

**DESIGN-056 — длинный BA заголовок при fontScale2 обрезан сверху.**
Экран CallbackHelp,BA,API35,1080×2340,font2/light,поле в фокусе/IME открыта; actual PNG выше. «Шылтыратыу ярҙамы» рисуется под часами/status bar и верхняя часть первой строки вне кадра. ScreenTopBar2258–2271 помещает multiline Text в TopAppBar; callback Scaffold2030 использует этот компонент. Нарушено правило AGENTS§4.5: системные бары/увеличенный шрифт/длинные тексты не перекрывают контент. Влияние: имя текущего экрана не читается целиком. Это наблюдаемое нарушение, не предложение по вкусу; повтор без IME и другие заголовки ещё открыты. Root получил PNG/строки. Предложение: адаптивно разместить заголовок в доступной высоте и safe-area, не уменьшать системный шрифт и не менять чужие экраны без проверки. Критерий после fix: полный BA/RU title bounds вне системного бара, Back доступен, поле/CTA достижимы при font1/2 и IME; скриншоты+измеряемые bounds. Исправления/REDtest/GREEN нет. Spinner на RU disabled серой CTA визуально слабый: кандидат для измерения контраста, не численно подтверждённый новый дефект.

### Ограничения и следующий шаг

Backend False guard принимается bounded; Android source/JVM объясняют устранение старых premature-success/double-tap случаев. Финальный Android-device acceptance открыт из-за сохранённых двух failures. Не принимаются весь CallbackHelp/B08/B09/96 экранов, theme/viewports/TalkBack/реальный телефон/call/FCM/GPS/providers/release/process-death/account-late/guest-login. Дополнительный source probe: после ранее принятого запроса callbackRequested остаётся true при запуске новой просьбы (1588–1590 его не очищает); свежие3 journey не покрывают второй смысловой запрос. Это кандидат, не воспроизведённый runtime дефект/новый ID; clearUserData104 очищает флаг при выходе.

Следующий точный шаг: дождаться root классификации Toast/Looper и frozen повторного instrument log/PNG, самостоятельно сравнить изменённый source и primary results, повторить scoped приёмку. Backend не повторять без изменения. Затем продолжить Booking уже прочитанного326–1140/Minor3500–3539 через server details/pay-agreement/caller/tests и ещё не прочитанный map-preview. Своих процессов/замков нет; все общие ресурсы у root. Root обновляет единственный реестр, этот документ остаётся доказательствами независимого дизайна.

## R41 — бронирование: семантика полей, доверие и обновление статуса

01.10.2026, 15:00:01 МСК; /root/design_resume; PATH02/03, B02/B09, исходная карта96 сохранена. Windows/source-only; ветка/HEAD как R40, dirty. Android API/экран/язык/тема для новой проверки не исполнялись. Ни тесты, ни сервер, ни БД, ни эмулятор этим агентом не запущены. Цель: полностью разобрать BookingScreen и его компоненты, сопоставить server fields/переходы с UI/тестами и web. Критерий достаточности чтения: ветви состояния/действия/связи и оставшиеся runtime варианты названы отдельно. Критерий пользовательского пути требует actual доказательства, чтением не закрывается.

| Источник / строки / LF-SHA256 | Проверенное содержимое |
|---|---|
| android/app/src/main/java/com/yuldash/app/BookingActiveTripScreen.kt /3817 /b161ac19ca66a198f60c8cbcb4abee9f8722e3d9a0d90938d559978ff545d31f | Полностью326–1340: wrapper/нулевой ride, initial details/save/retry/removal, actions/cancel/minor/pay формы, DriverHero/Route/Meeting/Metric, MapPreview/label/camera/unavailable;3500–3540 Minor целиком. ActiveTrip1428–2673 ещё не прочитан. |
| android/app/src/main/java/com/yuldash/app/YuldashApp.kt /2630 /01ca3d4cb3a78a9f8171a728dbbcb77eaa4cc0f76cdcfa1ccfc1cfd54ed72a0c | Selected-status writers415/638/769/869/1221/1276/1320/1367/1636; caller1255–1337 полностью; restoration834–880; не весь файл. |
| android/app/src/main/java/com/yuldash/app/data/ApiClient.kt /7946 /b3d3b34665d5cf339eecabef5a7b27ddd6bab01ea7567fac9b8b1d45bc3e0c70 | Полностью methods965–1020/parser6857–6892/DTO6587–6622; generic call3434–3524 по session-bound response, не весь файл. |
| backend/app/routers/bookings.py /910 /8e36a71eb07d7fed744def2d434702c08a8a78cb0561fb01bfe91333a2f7cca2 | 137–446: create, details, agreement, начало receipt. Прежний QA003 payment locks не запускался заново; не весь910строчный файл. |
| webapp/src/screens/BookingScreen.tsx /218 /2aaace426a1f1e29ec19babe064439660cb03b3ccce77366d8c0f8b7f847efe4 | Полностью1–218: GET/abort/retry, server-authoritative поля, cancel/failure, terminal actions, phone/pickup, ads. |
| webapp/src/api/bookings.ts /311 /dde1c02141b27d62c979a44c043810b1f79eada8679c56955f32c38bf2b5f3d7 | 1–190: типы/создание/details/state/code/mine/cancel/agreement/rating; остаток191–311 не объявлен прочитанным. |
| android/app/src/test/java/com/yuldash/app/BookingConfirmationJourneyTest.kt /312 /179cf2d7f7a5b2def81154cd1b329d934a9dc0777e1ece1f39e100a5c863698c | Полностью1–312; после truncated вывода отдельно189–312:6 определений. |
| android/app/src/test/java/com/yuldash/app/AuditDelta06BookingStatusTest.kt /99 /3031a97d92c0a3c89de7a43b6adaa0a577e505411bf3cf3cd34a44334be3fcf6 | Полностью1–99,3 определения. |
| android/app/src/test/java/com/yuldash/app/BookingHistoryCleanupTest.kt /122 /6a03611090bdda3c9772077df3ee5eccd492c27cce4e0411913a6bf6736bc57e | Полностью1–122,5 определений. |
| android/app/src/test/java/com/yuldash/app/BookingTerminalPassTest.kt /119 /629932452b2487562416378867a3b2b40f6507f8fb662ef681c8e926dabad551 | Полностью1–119,2 определения. |
| android/app/src/test/java/com/yuldash/app/BookingOfflineSaveTest.kt /202 /f1eae7cc118a294ebb88db4b8a0f70ff90f5390ee7608150bfa7e056b4a1c18d | Полностью1–202,4 определения. |
| android/app/src/test/java/com/yuldash/app/data/ApiClientBookingsTest.kt /292 /280139376dd107822c6562bc1aceac17be20d0193e9c30575271aa607d8bb86f | Полностью1–292,14 определений: DTO fields/errors и chat/driver/ads/boost соседи; после truncation дочитано129–292. |
| android/app/src/androidTest/java/com/yuldash/app/BookingActiveTripMapInstrumentedTest.kt /79 /336467b6e995f4d48ba5fc18707ed4b227e4a466cfac76dee1c6aa6666c8a432 | Полностью1–79,2 определения; запуск здесь отсутствует. |


Собственные SHA BookingActiveTrip, BookingConfirmationJourney и web Booking совпали с существующим inventory: b161ac…/179cf2…/2aaace…; обновление состава не нужно. Перечитаны только новые диапазоны/связи, не весь уже разобранный ApiClient/MainActivity/YuldashApp. Эта таблица — source read-map, не новая успешная сборка или число покрытых строк.

### Связи и состояния

Публичная карточка→YuldashApp selectedRide→BookingScreen без ID: цена/публичные центры городов, договорённость cash/sbp/negotiate и optionalamount, minor guardian форма. Primary требует guardian name/phone еслиminor; callerPOST bookWithStatus(rid,1,…), существующий bookingInFlight до запуска защищает двойной submit. Backend137–295: auth/active/flood/ride FORUPDATE/границы времени/ownride/block/trust/women-policy/existingactive; атомарный decrement/seats и commit→push водителю. Это чтение, не новый PostgreSQL concurrency PASS. Переданный price/body не используется как доверенная цена: total_price=ride.price×seats, договорённость нормализуется сервером.

После ID: details с owner-gated phone/pickup/plate; код посадки и офлайн-паспорт только при contactUnlocked и statusconfirmed/onboard; терминальный свежий GET разрешает очистку паспорта, локальные storage failures имеют warning/retry. Phone/Dial/maps/share открываются системными Intent, launcher failure как существующий failure-feedback механизм остаётся отдельным probe; геокарта endpoints public, точная встреча gated. Generic ApiClient3448–3501 отсеивает ответ старой sessionGeneration до возврата Result; здесь не подтверждена новая утечка позднего ответа. UI неизвестный статус не объявляет onboard. Нулевойride с известнымID ждёт загрузки summary, безID уходит назад — прежние guard tests не заменяют actual cold-process.

MapPreview1142–1339: remember(MapView endpoints), onStart/onStop/dispose, route/camera, currentbooking-only peer marker, interpolate bearing, remove markerfinally; labels и privacy notice. Настоящий маршрут MapKit не исполнялся. Source polyline из2 endpoints не доказывает навигационную автомобильную трассу/провайдерный routing; perf/battery/frame interpolation/lifecycle foreground нужно измерить. Map unavailable текст«загружается» не имеет собственного refresh action — существующий общий details retry даёт путь при HTTPfailure; отсутствие неизвестных городских координат после200 отдельно проверить, не объявлять вечныйloader по одному чтению.

### DESIGN-057 — забронированные места названы свободными, общая цена названа ценой за место

Условия: существующая бронь, details вернул seats/price; для отличимого синтетического случая ride.price=700,seats_total=4,seats_left=1,booking.seats=2,booking.price=1400. Сервер details333–334 возвращает booking.seats/booking.price; create231–246 вычисляет общую сумму. Parser6866–6867 сохраняет поля. Booking436–440 переносит их в displayRide, RouteMetric968/975 печатает price с подписью«Цена за место» и seats как«Свободных мест». Фактически бронь занимает2 места/стоит1400 всего, свободное1 и цена за место700. Even seats1: свободных0 после последней брони будет изображено1.

Это однозначное source несоответствие договора полей, UI RED ещё не запущен. Ошибка влияет на приглашение попутчика/оценку стоимости; shareText469 печатает ту же общую цену без объяснения. Web Booking118–124 подписывает d.seats нейтрально«мест», pay row166–170 показывает договорённость/итого. Исправление владельца: различить public ride availability/per-seat и existing booking reserved seats/total; не вычислять свободные места из booking.seats и не делить сумму слепо без договорного поля. Проверка послеfix: actual GETdetails на samebooking/multiseat/lastseat/oldride, RU/BA semantic values/labels и share text; серверная seats mutation тест должен ловить подмену. Два участника/параллельная бронь и actual PG остаются отдельными.

### DESIGN-058 — свежий отказ в бейдже «проверен» подавлен устаревшим true

Условия: старая selectedRide.verified=true, сервер актуально driver_verified=false (отзыв/смена допуска). Backend338 вычисляет bool(driver.verified), parser6871 принимаетfalse; Booking440 делает details.driverVerified || ride.verified. DriverHero662 получаетtrue, badge не исчезает. Coldrestore YuldashApp865 делает b.driverVerified || feed.verified, воспроизводя правило. Web Booking140–146 доверяет текущему d.driver_verified. Это подтверждённый source дефект доверия, не runtime доказательство конкретного отзыва пользователя.

Влияние: человек принимает решение на основании отменённой проверки. Source не доказывает, что водитель допускается к заказу сервером — это отдельные права. Минимальный fix: при полном свежем authoritative details использовать его bool; fallback допустим только при отсутствии свежих деталей, не через OR. Критерий: actual oldtrue→GETfalse/oldfalse→GETtrue/missingoldserverfield и coldrestore; согласованный статус бейджа/профиля обоих участников, не менять admission policy. Root получил приоритет доверия.

### DESIGN-059 — явно согласованная нулевая сумма не показывается

Условия: допустимая договорённость pay_amount=0, исходная цена700. Backend _clean_pay_amount/QA003 сохраняет0 как явную сумму, receipt amount440 выбирает её по is not None; parser6869 сохраняет0. Booking717–724 передаёт details.payAmount; PayAgreement1133 показывает сумму только если>0. Поэтому запись бесплатной договорённости исчезает, рядом RouteCard продолжает700 ₽. Если и booking.price=0, Booking438 заменяет её старой ride.price при>0fallback; coldrestore862 аналогично feedprice. Web166–170 использует pay_amount!=null, включая0, и не заменяет price0 прежней ценой.

Source подтверждает потерю значения отображения, не двойное списание/реальный неверный платёж. Root знает0 vs None денежнуюpolicy по QA002/003. Предлагаемыйfix: сохранить различие explicit0/null, показать0 как бесплатную/0₽ договорённость; authoritative price0 нельзя заменять ненулевой устаревшей. Критерий: UIactual0/null/positive и дальнейший receipt/pay CTA согласованы с effectiveamount; действующую бесплатнуюpolicy/провайдерные ограничения не менять, никакие настоящие деньги не запускать.

### DESIGN-060 — свежий статус деталей не меняет действия текущего экрана

Условия по коду: POST book вернулpending; водитель подтвердил до получения GETdetails. loaded.status=confirmed/contactUnlockedtrue в Booking385–399 открывает приватные детали и сохраняет паспорт. Но normalizedBookingStatus473 использует только входнойbookingStatus, canOpenActiveTrip приходит parent1267 поselectedBookingStatus. Callback из loaded.status в parent отсутствует; grep всех selectedBookingStatus writers указан вtable. Поэтому actions остаются«Ждём ответа водителя»/«Отменить бронь», а «Открыть поездку» не появляется. Freshcancelled/done также не меняют эти actions. Сам wrapper GET только поbookingId/detailsReload, polling/booking WebSocket event handler в этом участке отсутствует.

Это confirmed source break состояния; actual controlled UI RED ещё нужен. Existing BookingConfirmationJourney127–168 после driverconfirm полностью размонтирует экран, меняетtoken, selectedRide=null и восстанавливаетGETmine; этот отличный путь не доказывает обновление уже открытого BookingScreen. PATH02/QA006/009 прежний scoped remount результат не отменён, его границы уточнены. WebBooking использует d.status для actions190 и может открыть active-screen изpending; нужна отдельная проверка его active-screen gate, не automatic parityPASS.

Минимальныйfix владельца: согласовать свежий status/родительскуюactiveTrip/CTA и способ наблюдения подтверждения другого участника, сохраняяgates для pending/cancelled/done/unknown и generation. КритерийRED/GREEN: current mounted passenger UI, controlled pendingPOST→driver confirmed→freshdetailsconfirmed, thenOpen, no secondPOSTbooking; отмена/terminal/ошибка/reorderedresponse/Back/account B; два реальных test participants на настоящем тестовом backend отдельно. Не путать ручной remount со своевременной двусторонней доставкой события.

### Проверка качества тестов и остатки

Полностью прочитаны20 UI определения=6Journey+3status+5history+2terminal+4offline-save; отдельно14API definitions и2Mapinstrument definitions, всего36 source определения, **не36 новых прогона/PASS**. Journey выбирает настоящий public ride/POST/drivercabinet/restore/boarding/finish/rating+GETreceipt, profile API MockWebServer. Два токена устанавливаетfixture, настоящий login/server transaction не доказывается. Status3 теста передают готовый status и не ловят060; API14 проверяетparser, но не labels/OR/zero/UI. History/offline20 структура защищает network/storage побочные действия, соседний паспорт не удаляется; сохранённый confirmcall должен оставаться0, useful oracle. Olddetailsfixture missingprice0 используетfallback и может маскировать059.

Mapinstrument79строк имеет2 определения без meaningful assertion: waitIdle+Thread.sleep3500/3000 и отсутствие exception. Название hidesGeo не доказывает приватность: MapPreviewcontactUnlockedfalse рисует публичные endpoints и notice, тест не проверяет actual coordinate source/phone/точнуювстречу. Для приёмки нужен caller privacy/semantic/actual render и device evidence, одних двух noCrash тестов недостаточно. Они в этом этапе не запускались.

Дополнительные непринятые probes: remember(bookingId=null) payAmount/minor при замене выбраннойride; Unicode digits filter/isDigit→toIntOrNull; guardian bounds/phone consent/errors; terminal status text/телефон expiry/no exactpoint whennotunlocked; cancellation duplicate/late result vsB; single-flight publicbooking load state не передаётся вbutton(guard есть, visual loading нет); IME+font/theme/viewports/TalkBack; realMapKit route/perf/physicalGPS. PayAgreement1098 text«деньги через приложение не проходят» описывает сам recordingrequest; связь с внедрённой online-payment нужна в формулировке продукта, отдельный новый факт списания не выдуман.

Результат: semantic Booking/component source этап закрыт в указанном диапазоне; DESIGN057–060/root получитregistry, actual воспроизведение/исправления открыты. Source/backend/тесты не менял. Приоритетный следующийшаг — callback040 окончательный device freeze; затем ActiveTrip1428–2673 и компоненты2804–3498/связанные server/web/tests. Полный BookingActiveTripfile/96screens/B02/B09 не принят.

## R42 — повтор CallbackHelp на настоящем Android: функциональная приёмка, визуальный остаток

01.10.2026, 15:10:24 МСК; /root/design_resume; QA-B08-001 / DESIGN040, R40 continuation. Source/build/device выполняет root; этот reviewer только читал/пересчитывал хэши/просматривал local PNG. Ветка/HEAD/dirty как R40. Новое доказательство обосновано первым Toast failure и затем fixture marker failure, не повтором неизменённой успешной проверки ради количества.

[Device V3 primary log](../test-results/audit-callback-device-v3-20261001.log) целиком: обе именованные проверки status_code0, OK(2 tests),13.406с instrument. [Receipt](../test-results/audit-callback-device-v3-20261001.json): PID28896,21.094с wall; emulator-5580/API35/1080×2340/density440/font2/light, приложением RU/BA, system locale getprop пустой. [Runner](../test-results/audit-callback-device-rerun-v3-20261001.py) прочитан полностью; exact command am instrument -w -r -e class com.yuldash.app.CallbackHelpJourneyInstrumentedTest com.yuldash.app.test/com.yuldash.app.StorageAuditRunner. install обоих APK Success; loopback заменительHTTP, synthetic login/name/id, no provider/production/SMS/operator.

Main YuldashApp2630 LF01ca3d4cb3a78a9f8171a728dbbcb77eaa4cc0f76cdcfa1ccfc1cfd54ed72a0c и AccessibilityScreens2120 LFb70d3cdf5cace46a66c7e33c584eeb62d2e3890c8f89a9ff3ba5e1cc30fd7f78 пересчитаны, неизменны относительно final41/JVM и первого устройства. DebugAPK178621697bytes/raw e7fc7340ec91b48d4aa8c14248658eeeccb6d47f62dba2d468b15e8b4c2017d7 совпадает с первой failed-device версией — production workaround не добавлен. TestAPK1369750bytes/raw56afdb3526c335fd0abcb43df11816c991d48402be7b0a6a3ce43a095e4c6c96. Все2APK и5PNG rawSHA самостоятельно совпали с V3metadata.

Fixture текущий216строк/LFc67905c9c9a726cfc919f5829b118f10e08bbff6909d63bafc9e22e846904c9f прочитан полностью1–216 (после первого1–213 чтения повторён изменённый import/marker и текущий tail). Factory import мигрировал на junit4.v2.createAndroidComposeRule; run/read/draft/request-count/auth/order/gateTimedOut/confirmation meaningful assertions сохранились. Marker144–150 теперь учитывает offscreen LazyColumn CTA: buttonComposed0 и disabled=null допустимы после scroll к confirmation, не заменены на фиктивный enabled=true. Выделенная стадияV2 завершилась2fail только в marker, потому что требовалаone CTA node после утилизации offscreen item; её primary FAILURES не стёрт. [Thread diagnostic другого независимого агента](../test-results/audit-callback-device-thread-review-20261001.json), rollback_probe, объясняет исходную old test dispatcher/Unconfined continuation/Toast-no-Looper; его javap review не присвоен как самостоятельно выполненный. Наши собственные наблюдения: первый actualfail стек, ровно import migration, productionAPK равен, V3 настоящие Toast ветви больше не падают. Это согласуется с fixture причиной; production-thread crash не объявлен доказанным.

[BuildV3](../test-results/audit-callback-ui-build-v3-20261001.json): debug+androidTest со StorageAuditRunner, JBR, PID12224,exit0,25.484с; выполнялroot. Старые first43.688с build и finalJVM41/41 остаются своим видом доказательства;3device прогона не6 уникальных сценариев.

### Текущий HTTP/state результат

[Markers](../test-results/audit-callback-device-v3-20261001-markers.txt) содержит10 строк из трёх запусков. Самостоятельно отобраны только5 строк с AndroidPID8351/TID8389/12:03:41–47 deviceclock:2RU+3BA. Это отличается от runner process PID28896.
- RU pending: requested=false/buttonComposed1/disabledtrue,1POST с returnedAt0; повторный pointer tap не добавилPOST. RU accepted: requestedtrue,1POST200/auth matches и returnedAt>receivedAt.
- BA pending: false/disabledtrue/1heldPOST; after503: requestedfalse/enabled/тот же draft,1POST503; retry accepted: true/2POST statuses503,200, текст точно одинаков, второйreceivedAt после первогоreturnedAt.
- Во всех текущих observations gateTimedOut=false; fixture не отпускал gate по собственному deadline. Scope authentic productSimpleMode→CallbackHelp/YuldashApp→ApiClient; seeded synthetic auth/начальный экран, настоящего guest-login нет.

**Ограниченная функциональная приёмка DESIGN040 Android:** устранён преждевременный requested и повторный concurrentPOST при first request; подтверждён draft после503/настоящий retry200 на текущемsource и AndroidAPI35. BackendFalse guard принят отдельноR40. Это не приёмка потерянногоresponse/серверногоdedup/durable queue/второй новой просьбы после прошлойsuccess/late-account/logout/Back/process/release/реальногоcallbackоператора.

### Независимый просмотр снимков

| PNG | Raw SHA256 | Самостоятельное наблюдение |
|---|---|---|
| [qa-callback-Ba-accepted.png](../test-results/audit-callback-device-v3-20261001-images/qa-callback-Ba-accepted.png) | 2ed01ba5b2433773c7ef4fe193ca7f1012d5e9d536cbbcd4c7997ed937b1c1e3 | Тот же draft/IME; на кадре ещё прежний failure Toast, confirmation pixels не показаны; header clipping. |
| [qa-callback-Ba-after-503.png](../test-results/audit-callback-device-v3-20261001-images/qa-callback-Ba-after-503.png) | ad56f7be4da478e55709daf602d5021985155bb61440592706f9c415108184aa | Тот же draft, BA failure Toast, IME, confirmation нет; header clipping. |
| [qa-callback-Ba-pending.png](../test-results/audit-callback-device-v3-20261001-images/qa-callback-Ba-pending.png) | 94bfe932cb24af61a273682352fb30c37226f14fef2f4a991f4d62e843eae20f | BA draft, IME; header clipping DESIGN056 сохраняется, CTA не видна. |
| [qa-callback-Ru-accepted.png](../test-results/audit-callback-device-v3-20261001-images/qa-callback-Ru-accepted.png) | b6344f582e36c3f3160b3d7fb2e0fbefbf43d648c2dc8d29e5a8d53d5eff0536 | Синтетический draft+IME; виден success Toast, самой confirmation card кадр не показывает. |
| [qa-callback-Ru-pending.png](../test-results/audit-callback-device-v3-20261001-images/qa-callback-Ru-pending.png) | 6346233e57b897d407c25d3e1cddf4ca987bbf893d7731156b76b3114a7e7cf4 | Draft/верхняя InfoCard; IME открыта, CTA в кадре не видна. Ложной confirmation нет. |


Все5 исходныхPNG1080×2340 просмотрены через view_image; отображение в tool уменьшено до945×2048, raw файлы не редактировались. В named accepted кадрах самой карточки«Звонок запрошен/Шылтыратыу һоралды» нет. assertIsDisplayed и state true доказывают Compose oracle, но эти pixels не подтверждают отсутствие перекрытия системойIME. BAaccepted показываетещёprevious503Toast; это момент кадра/очередь Toast, не самостоятельное доказательство новогоHTTPотказа, который опровергается marker200.

**Визуальную видимость accepted card по этим PNG я не могу подтвердить.** Root получил concrete capture criterion: закрытьIME/снятьfocus, причинно дождаться состояния и фактических bounds, прокрутить доconfirmation, сделать реальныйPNG; не фиксированнаяsleep. WaitingCTA сIME требует отдельного кадра/достижимости. DESIGN056 остаётся непрошедшим: BAheader в pending/503/accepted обрезан и под statusclock. БлокB09/полный CallbackHelp/TalkBack/theme/viewports/phone не принят.

Передача: functional scoped принять по5currentmarkers/actual2PASS, visual оставитьоткрытым до новыхpixels; сохранятьfirst/v2failures как историю. Далее ActiveTrip уже дочитан1428–2803, но read-map/компоненты2804+ и server/testcontracts ещё не завершены; source061–064 озвученыroot, IDs будут подробно записаны следующим этапом после проверки связей. У reviewer своих фоновых процессов/замков/Gradle/adb нет.

## R43 — активная поездка: чат, статусы, офлайн-паспорт и отзыв доступа

Дата независимой записи: 01.10.2026, 15:26:03 МСК; ответственный /root/design_resume; B04/B05/B06/B09, точные PATH/QA-связи и текущая очередь остаются у root в audit-blocks.md. Рабочая ветка audit/full-technical-20260930, ранее самостоятельно измеренный HEAD6fa0595d88bea4fcd59d4a1e2139c2d128cf6821; локальные изменения других владельцев сохранены. Новый git-снимок этой записью не делался. Только read-only исходники/тесты и запись design-doc. Сборка, adb, эмулятор, сервер/БД/провайдеры этим агентом не запускались.

Цель: разобрать настоящий wrapper ActiveTrip и его компоненты, включая условия первого входа, role/polling, terminal cleanup, optimistic chat/edit/media/retry, boarding-code, offline fallback, status CTA, sharing/revoke. Критерий чтения: полная карта собственного файла и связей; критерий поведения остаётся отдельным — controlled request/UI/state/data assertions и актуальные runtime-доказательства. Чтение не закрывает их.

### Источники и карта чтения

SHA256 здесь LF-normalized, команда Python hashlib.sha256(bytes CRLF/CR→LF); строки — len(text.splitlines()). Все 13 отпечатков ниже в 15:21:18 МСК совпали с текущей inventory. Это 13 сравнений файлов, а не 13 пройденных сценариев. Поиск rg -n/rg --files, фактическое чтение Get-Content с номерами строк и ограниченными диапазонами; все места, где вывод обрезался, для новых существенных ветвей перечитывались отдельно.

| Файл | SHA256(LF), фактический объём чтения |
|---|---|
| android/.../BookingActiveTripScreen.kt | b161ac19ca66a198f60c8cbcb4abee9f8722e3d9a0d90938d559978ff545d31f /3817; новый R43:1–325,1403–3499,3554–3817. С прежними R41:326–1340,3500–3540 и R18:1336–1402 весь собственный файл3817 разобран; неизменность подтверждена SHA, повтор не записан как новое уникальное чтение |
| android/.../data/TripPass.kt | 395e84264f83e09dd34942479271c724f6646b2ba6a87aedf6129df2c7064b37 /458;1–130: модель/JSON/начало store. Остальные131–458 здесь не приняты; отдельные storage-проверки других агентов не присвоены |
| android/.../data/ChatSocket.kt | cff92374e7281fdc563f1a91903c858ced0b4150ff9ec1653cba9686594baaf9 /185;1–185 полностью: callbacks/protocol/auth/reconnect/network/close/send. Повторное объединённое чтение сделано для устранения неполноты ранних фрагментов, не новый PASS |
| android/.../data/ApiClient.kt | b3d3b34665d5cf339eecabef5a7b27ddd6bab01ea7567fac9b8b1d45bc3e0c70 /7946;1527–1568,2140–2197; общие call/DTO ранее R41; остальные7946 строки этой записью не приняты |
| android/.../RidesRequestsChatScreens.kt | f44e2d651bcc5927699f98c7e9eee6a0eade34ec8c394544211de1ac3bd6f9ea /3535;ChatComposer3219–3368 полностью; другие3535 строки не приняты |
| backend/app/routers/chat.py | 469ebe95951b393f1c41000185ca7ddccd07d25b1a5b0bccf162e99634085f32 /925;233–352 booking WS,801–834 edit; не весь925-файл |
| backend/app/routers/family.py | 09ef7d1d8ed90ee57edf1e1e5ef62f6815d4545521cda52d18c10aefc5c5eca9 /617;143–198 share/list,349–381 revoke,384–464 status; не весь617-файл |
| webapp/src/screens/ActiveTripScreen.tsx | 2a08f7a460d75425a1914c2a1953f71596393e534f6fecb8533f35470c5a416e /1212;271–309 boarding-code/location,872–999 chat history/send,1012–1036 edit; не весь1212-файл |
| ActiveTripBoardingCodeSaveTest.kt | 7396c482d7cd49e46933dc2ee54810598a5f9841073bbdb177c6b552d0f64923 /183 полностью |
| ActiveTripSessionChainTest.kt | 1de63ebf2bc538f47d1dfea8e7ddf265bf16cc9fed7d5beb9265451c499fe4ea /120 полностью |
| ActiveTripPollingSessionTest.kt | 8c42ad235c16b4e15dd2453c7cac61be0e1044c5869575a91f69599424efd51e /158 полностью |
| ActiveTripLateTerminalTest.kt | 112729d748ab315eeaeb570616e895e4f118a35e7a5d7b0806da6c2568c3bef6 /153 полностью |
| RideshareActiveTripGuardTest.kt | c949833c3625491fa387bd3c99284526614819d232fcebb213a3219feb0885c2 /52 полностью |

Последние пять файлов содержат 12 определений @Test:3+2+3+1+3. Здесь это чтение тестов, не запуск12/12. Первые четыре монтируют настоящий ActiveTripScreen на Robolectric API34/NATIVE, RU, ride=null без внешней карты, HTTP MockWebServer и MemoryDiskPreferences. BoardingSave проверяет commit(false)/старый код/retry/restarted disk; SessionChain два hold/latch-контроля A→B до следующего HTTP; PollingSession три случая позднего A и устойчивых done/cancelled; LateTerminal causally держит код до durable удаления и наблюдает следующий details HTTP, который подтверждает выполнение позднего callback. Mount — прямой ActiveTrip, не весь путь поиска/брони; restart store — не настоящий process death. Clock pumping/Thread.sleep10 в двух тестах обеспечивает bounded наблюдение текущих coroutine/poll, а не доказательство всех будущих ответов. Guard52 проверяет source strings и pure helper nextStatus, без actual layout, accessibility/отказов HTTP/WS. Existing tests не воспроизводят DESIGN061–066.

### Связь действий и состояний

Parent выбранная бронь→ActiveTrip1428→getTripState/poll/role и независимо getMessages→getBoardingCode→getBookingDetails; generation-проверки каждого шага initial chain и polling защищены упомянутыми тестами, их прошлые GREEN здесь не объявлены новым результатом. done/cancelled→terminal removal1472–1488→RideshareCompleted early-return/exit; durable-error→нескрываемый dialog/retry или явное закрытие. boardingCode→updateBoardingCode1590–1608→локальный паспорт/предупреждение о несохранении. Offline определяется сетевым провалом role, а не всеми HTTP failures; если pass есть→2003–2010 OfflineTripBanner/TripPassCard; known newer code не заменяется старым UI-кодом.

Чат: draft/editMode→ChatComposer→sendText/deliver1740→WS либо idempotency-key REST/Outbox→server Message/WS broadcast→optimistic replace/history→оба участника. Фото decodeToJpeg вызывается прямо из picker callback, voice читает файл/загружает/отправляет/удаляет независимо от исхода; замеры main-thread/памяти, сохранение failed media и late-account chains остаются отдельными probes. History имеет загрузку/ошибку/повтор, code fetch отдельного error/retry не имеет. Quick replies проходят тот же sendText. Passenger status→1827–1855 optimistic status→server family389→shares last_status/booking done→progress/CTA, driver status отдельный onSuccess путь1798–1823. Sharing→лист серверных TripShare→создание contact/ссылка→отзыв DELETE→локальное удаление только при успехе; failing list и частичный commit разобраны ниже.

Внешне достижимые pure/legacy компоненты различались: rg нашёл TripPassCard только в ActiveTrip2010, ChatComposer только ActiveTrip2483. DriverApproachingBanner и TripStatusButtons определены здесь, прямых production calls найдено не было; это не доказательство полной неиспользуемости, не основание удаления. Старые done/rating/pay sections внутри LazyColumn требуют проверки достижимости при раннем RideshareCompleted return; их существование не названо отдельным defect. Long-text/font2/dark/insets/IME/TalkBack/touch/MapKit/physical-phone/render/motion измерения ActiveTrip не выполнялись; Я не могу это подтвердить.

### DESIGN061 — входящее сообщение другого участника заменяет моё ожидающее

Экран/условия: ActiveTrip, моё optimistic negative-id сообщение ожидает подтверждения, peer присылает тот же текст (например, «Привет»). Измеримое source-нарушение:1669 ищет myId только у старой строки и сравнивает text, но не проверяет inc.senderId==myId;1672 заменяет её новым peer DTO. ChatSocketIncoming35–39 и backend booking WS broadcast233–352 передают sender_id обоих участников; такой input не является моим эхо. Влияние: моя ещё не подтверждённая строка пропадает/сменяет сторону, peer отображается вместо неё; утверждения о потере серверной записи нет. Корень — корреляция только по text вместо проверки автора/идентичности запроса.

Fix-критерий: источник optimistic echo должен совпасть с моим участником; одинаковые последовательные тексты и последующее настоящее эхо сохраняют по одному результату каждого логического сообщения. Controlled test: реальный synthetic booking WS, hold своего acceptance, peer same-text приходит первым, затем own echo; проверить ids/author/order/сохранённые две Message и отсутствие исчезновения/дубля. Нужны neighbouring InstantChat consumer (rg нашёл подобный source; полный экран здесь не принимался)/chat reconnect/rejected. runtime RED и fix открыты, root уведомлён.

### DESIGN062 — серверный отказ оставляет passenger UI в новом статусе

Экран/условия: passenger нажимает «Я сел/Доехал/Завершить», POST /trip-status возвращает ApiException403/409/5xx. Source1828 ставит status=st до HTTP;1847 откат есть только для несохранённой offline queue,1850–1854 ApiException лишь показывает Toast. activeTripNextStatus2778 и progress/route читают уже новый status, следующий CTA меняется несмотря на отказ. Backend389–406 содержит реальные403/409 отказы, поэтому failure ветвь не недостижима. bookingStatus/done меняется отдельно на успехе; реальное server completion этим дефектом не утверждается.

Fix-критерий: подтверждённый переход и явная queued operation отличаются, при запрете/409 новый статус не показан как принятый; old authoritative status и действие восстановлены, concurrent/late replies не откатывают более новое состояние. Actual test должен нажать настоящую CTA, получить controlled403/409/503, проверить POST payload, unchanged booking/share last_status и старые progress/CTA; offline successful/failed enqueue, doubletap и poll-order соседние случаи. Прямой nextAction test нынешних Guard52 отказ не ловит. runtime RED/fix открыты.

### DESIGN063 — отказ правки стирает пользовательский черновик

ActiveTrip context menu→редактировать→ChatComposer→send. Source2491–2494 запускает edit HTTP, но2496 сразу сбрасывает editingId,2500 стирает draft; при failure только Toast. backend chat801–834 имеет действительные отказы по автору/удалению/пустому тексту. Влияние: новый пользовательский текст и режим правки потеряны до подтверждения; старое серверное сообщение сохранено. Web saveEdit1015–1033 при refusal сохраняет editText/editId и показывает attachNote, после success закрывает edit. Это конкретное поведенческое различие Android/PWA, не целая приёмка веба.

Fix-критерий: хранить текст/target до confirmed success либо явно дать retry/cancel, отдельное inFlight исключает повтор; ошибочный запрос не превращается в новую отправку. Controlled edit→503/403→draft/target видимы→retry200→ровно одно изменение того же id, обоим участникам итог; новое send/quick replies/delete/media соседние. Нет screenshots/actual RED/fix здесь.

### DESIGN064 — неизвестный список доступа выдаётся за отсутствие доступа

ShareModal2570–2586: initial activeShares=[] и showContacts=true, GET list имеет только onSuccess. При 503/401/повреждении ответа существующий серверный share не показывается, нет error/retry/list loading, revoke невозможно из этого состояния. backend family179–198 возвращает владельцу его сохранённые shares,349–381 удаляет token; это не отсутствие действующей функции. Влияние: человек не видит, кому уже открыл поездку, и не может сразу отозвать по списку; отдельное доказательство передачи координат постороннему не получено.

Сосед частичного результата: share_trip158–162 коммитит новый token, затем166–168 может вернуть429 от SMS-потолка. Android onFailure2634–2636 закрывает sheet и не добавляет существующий share. Поэтому отказ сообщения не доказывает отсутствия сохранённого доступа. Было ли SMS доставлено — не подтверждено; конкретный429 case надо воспроизвести на изолированной БД без SMS.

Fix-критерии: неизвестный список→понятная RU/BA ошибка/повтор и сохранённые известные права, successful empty→пустое состояние; create response-loss/429→проверяемое серверное состояние/повтор без нового share, revoke реально удаляет token/доступ. Actual saved share→reopen GET503→retry200→revoke→server row absent/link404, также контролируемый429 после write. Новые запреты legitimate sharing без понимания политики не внедрять. runtime RED/fix/PG race/delivery открыты.

### DESIGN065 — офлайн-паспорт не сохраняет действующую договорённость оплаты

Booking confirmed→saveTripPass292–321 сохраняет d.price314, paymentNote316 всегда пусто, несмотря на d.payAmount/d.payMethod модели и действующие money rules QA-B07-002/003. TripPass model43–45 не хранит отдельные поля метода/согласованной суммы; TripPassCard3434–3436 при price>0 безусловно пишет «перевод по СБП». Влияние: cash или negotiate показаны как СБП; согласованные400/0 при исходной700 остаются700 в офлайн-паспорте. Явный0 не равен отсутствующей договорённости. Это source-доказательство неверной информации, не фактическое списание/ошибка нового cashless invoice.

Fix-критерий: локально сохранённый снимок отражает effective agreement amount и method, миграция старых паспортов не выдумывает способ, RU/BA/zero/null отличаются; sensitive store owner/generation/terminal cleanup не нарушены. Controlled server details (original700, agreed400/cash; agreed0; None/negotiate)→настоящий подтверждённый путь→durable snapshot→network refusal/offline screen→правильные сумма/метод; secure write failure и старый snapshot отдельно. runtime/мутация/actual device открыты, root уведомлён; варианты переводов согласовать по tasks правилам.

### DESIGN066 — ошибка кода посадки остаётся бесконечной загрузкой

ActiveTrip initial history успешна, GET boarding-code1641 возвращает503/403/empty response. Source1643–1646 имеет только onSuccess, blank code оставляет default1586. ActiveTripBoardingCodeCard2992–2999 любое blank трактует как спиннер «Код загружается» без failed state/retry. Эффект зависит лишь от bookingId/historyTick; повтор history появляется только при historyError, который для данного case false. role poll не повторяет boarding-code. Имеющиеся BoardingCodeSave183 tests всегда возвращают valid code5678; они ловят локальную запись, а не отказ этого HTTP. В PWA274–296 catch также игнорируется, отсутствие кода не названо проверенным приемлемым состоянием.

Fix-критерий: после конечного HTTP refusal дать честную RU/BA ошибку и retry, различать initial/pending/empty/not-allowed, valid code и известный saved snapshot не противоречат terminal/privacy. Actual GET history200+code503+role/details200→нет бесконечного loading→retry valid→код показан/сохранён; 403/empty/timeout/смена аккаунта/done/bad disk соседние. runtime RED/fix/TalkBack/device matrix открыты. Root получит этот source finding отдельно; новый список текущих задач здесь не создаётся.

### Оставшиеся probes и точная передача

Не превращены в подтверждённый runtime дефект: WS send=true означает лишь локальный enqueue без server ACK, deliver1746 ждёт echo; reconnect1701 заменяет весь messages серверной историей. Нужно controlled disconnect между enqueue и commit/echo, чтобы отличить исчезновение неподтверждённого draft от реального потерянного server message. ChatSocket102 читает currentToken при каждом reconnect, callbacks поколения не проверяют; actual old mounted screen/account-switch/reconnect должен подтвердить или опровергнуть позднее использование B до утверждения утечки. MediaPicker decodeToJpeg вызван на UI callback, но ANR/latency не измерены. Progress restored-onboard/local status-null и terminal legacy sections — reachability probes, не вкусовые предложения и не разрешение удаления.

В этой записи нет нового тестового прогона, новой сборки, screenshot или actual серверной операции. Полный семантический Android-файл3817 прочитан, 13 hashes актуальны, зависимые файлы лишь в обозначенном объёме. B04/B05/B06/B09, 96-screen audit, перечисленные runtime критерии и DESIGN056 остаются открыты. Исторические R42 Callback2/2/пять PNG и first/v2 failures сохранены; они не показывают confirmation-card pixels.

Следующий конкретный независимый шаг: Get-Content android/app/src/test/java/com/yuldash/app/ActiveTripDeletionTest.kt диапазонами1–130/131–253, затем BookingActiveTripContent/Deep* и остаток webapp ActiveTrip1212 в тех же source-hash версиях. При новых Callback checkpoint/PNG сначала сверить реальные изменённые hashes/primary evidence и отображение подтверждения с убранной IME; не повторять неизменённые backend24/JVM41. Сборка/эмулятор/сервер исключительно root, source/shared-doc readonly; собственных процессов и замков нет.

## R44 — качество существующих тестов активной поездки

Дата независимой записи: 01.10.2026, 15:32:42 МСК; /root/design_resume; B04/B05/B06/B09. Только чтение, ни одного теста/Gradle/adb/серверной операции не выполнено. Source/build/device ownership root, docs/audit-design-review.md sole writer этот агент; branch/HEAD ограничение R43 сохраняется. Цель: сопоставить реальные assertions/фикстуры с текущим wrapper, отличить meaningful durable cleanup/контракт от pure-content/legacy rendering. Принят только критерий чтения перечисленных файлов, runtime PASS не добавлен.

| Полностью прочитанный файл | SHA256(LF), строки, определения @Test |
|---|---|
| ActiveTripDeletionTest.kt | 24e3bc064eb5af8b9d73eddbe2537333dd7513b3b4470ebe6fe4a2d03920430d /253 /6 |
| BookingActiveTripContentTest.kt | 8890e469724cfc24fc987cd1b49a1fd3e3cdfba9e80d810c5ff0c8715f6f607b /70 /4 |
| BookingActiveTripContentDeepTest.kt | 4d28c8cdff41b40021115534491f37bdd6f7e554e16a42835b322afece5294cb /308 /22 |
| BookingActiveTripDeep2ContentTest.kt | d4d1cb05c00879d35e9aaa091c6447115604c6ac2f202a99b97c5a1a64f5def1 /364 /23 |
| BookingActiveTripDeep3ContentTest.kt | a5d4673463a106958fbc103f4c1b7fd48ec0f8b59d7904288467e0878ed2d5ed /182 /8 |
| ChatContentTest.kt | 546978170f0d7207b2b5a76986ea048cefdd225e64f2b1d25c575be0e0273c42 /185 /9 |
| data/ChatRetryLiveBackendTest.kt | 25889702e36199dda9cc1d74d8028f0307d77d8ff062e91276458f2b3067b476 /71 /2 |
| production SecureImageRequest.kt | cfc24b74ba5168067aa581c4fbaff4ef5c91d7b4443f9d137919a5c9520a02c7 /68 полностью; не тест |

Хэши самостоятельно измерены15:30:27 МСК и 8/8 совпали текущей inventory. Семь test files содержат74 определения:6+4+22+23+8+9+2. Это 74 прочитанных тела, не74 выполненных проверок, не процент качества. Ранее R43 другие пять файлов/12 определений сохраняются отдельно; повторные definitions не сложены с прошлыми прогонами. Методы Get-Content по диапазонам/rg references/прочитанные assertions; обрезанный фрагмент Deep3:129–166 и ChatContent source1978–1997 перечитан, чтобы не принять пропущенное.

### Существенные свойства и ограничения тестов

Deletion253/6 монтирует настоящий ActiveTripScreen: remote done не шлёт POST; failed durable deletion сохраняет retry/диск при commit(false); cancelled выходит ровно один раз лишь после accepted cleanup; DEFERRED имеет durable tombstone до выхода и очищает недоступный secure disk при следующем init; настоящий cancel CTA/диалог/причина plans_changed→один server POST, последующий retry лишь локального удаления. Assertions independent disk,restarted stores, unrelated pass99, callbacks и payload существенны. Ограничения: API34 Robolectric NATIVE/RU, MockWebServer/MemoryDiskPreferences, ride=null/noMapKit/noauthenticatedWS; mounted callback немедленно unmount fixture, это не весь production navigation/process-death. Blank boarding-code здесь сочетался с terminal/тестом cancel, отдельный пользовательский error/retry кода не проверяется.

Content70/4 проверяет готовую map label и fallback title/body RU/BA; не actual route/network/retry/error. Deep308/22:2 TripRouteHeader +7 DriverApproachingBanner +5 BoardingCodeCard +5 TripStatusButtons +3 ShareTripRow. В rg production обнаружены лишь определения первых четырёх компонентов, то есть19/22 definitions используют legacy cards. Нельзя на этом основании их удалять: indirect calls/ресурсы/будущие approved варианты отдельно исключаются; но результат этих19 не доказывает текущий ActiveTripOptionBHero. В действующем code2840 вызывается другой private ActiveTripBoardingCodeCard2956, с blank-code loading2992–2999; legacy BoardingCodeCard3297 получал готовый7421/0042. Особенно DESIGN066 не проверен этими valid-code assertions. Click callbacks TripStatusButtons и ShareTripRow доказывают только lambda wiring, не server transitions, SMS, token/revoke или rollback.

Deep2 364/23 проверяет настоящий MessageBubble renderer, RU/BA deleted/edited/failed labels, menu по longpress и scope me/all, foreign media deny UI, existing own media image semantics/play affordance. Permissions canEdit/canDeleteAll передаются готовыми flags; backend IDOR/actual edit refusal/authoritative role не проверяются. Воздействие failed retry проверяется булевой callback, не реальным повтором HTTP/WS; одинаковые peer/own optimistic texts DESIGN061 отсутствуют. Audio-play button не нажимается, загрузка/декодирование изображения не наблюдается; наличие contentDescription Фото не подтверждает успешный pixel render или сетевые права. Source AsyncImage3741–3748 имеет model=ownPhoto, ownPhoto в тесте `${ApiClient.apiBase()}/media/chat/p.jpg`162–163; ни этот test file, ни найденный source не внедряет FakeImageLoader. Проверка обращения к production/loopback/отсутствия выхода в сеть при прогоне остаётся probe: фактический сетевой запрос этим агентом не измерялся, Я не могу это подтвердить. Перед новым прогоном нужен явный изолированный endpoint/контролируемый loader и audit сетевого журнала, без обращения к настоящим документам.

Deep3 182/8 — готовые SettingsNavRow/SettingSwitchRow/SettingsGroup/CompactProfileBanner наAPI34,qualifiers w411dp-h2600dp. Assertions labels/callbacks полезны, но нет достижения Settings через реальную навигацию, сохранения theme/language/logout/restart. CompactProfileBanner берёт cachedName/role из ApiClient1404–1420; fixture не устанавливает/не очищает это состояние явно, утверждение комментария «без залогиненного пользователя» должно иметь фактическую подготовку/изоляцию. Не объявлено подтверждённым order-dependent failure без запуска в переставленном порядке.

ChatContent185/9 монтирует ChatContent1915–2011 из RidesRequestsChatScreens.kt: states sending=true/false заданы при старте, проверяется disabled/один callback, а также пусто/40 сообщений/blank-input/loading. Это другой компонент, current ActiveTrip2483 использует ChatComposer3219–3368 без sending-параметра. Поэтому комментарий теста «гард двойного нажатия» означает готовый disabled state в Content, не цепочку реальный tap→HTTP→inFlight guard на ActiveTrip. loading test лишь assert absence empty text, не assert самого spinner; эта ограниченность не повод ослаблять текущие asserts. Quality criterion для текущего send/edit/status — actual wrapper/pointer two taps/hold/requests/data итог и meaningful causal witnesses, как R42 Callback, без искусственного включения ready state.

ChatRetryLiveBackend71/2 защищает REST/outbox response-loss: первый keyed request fail после записи, enqueue/init/flush→2requests/1message/1receipt/1live/1push/no pending; новое логически отдельное same-text сообщение→2message. Unkeyed control подтверждает2message. Но @Before28 требует ровно YULDASH_CHAT_QA_URL=http://127.0.0.1:5191 через assumeTrue, иначе cases skipped; обычный unit task не означает эту интеграцию. Adapter `/qa/session`, `/qa/stats` и реальные счётчики должны быть сверены перед приёмкой; их implementation/environment в R44 не запускались и не приняты. Эта проверка вызывается через ApiClient/Outbox, не текущий WS+Composer путь, не обе UI-роли. Факт доставки настоящего push/FCM из synthetic counter не следует.

SecureImageRequest68 прочитан как сосед renderer: URI scheme/host/port ownBases, relative path кроме protocol-relative, ownImageModel trim и authedImageRequest только собственный адрес→header; новых изменений нет. Это source validation, не runtime certificate/redirect/actual request proof, не приёмка всех медиа. Source RidesRequestsChatScreens1911–2035 дополнительно прочитан для сопоставления ChatContent, не весь3535-файл.

### Передача

Root получил конкретное расхождение между legacy/ready-content тестами и текущим ActiveTrip. DESIGN061–066 не закрыты этими74 определениями; новых runtime результатов/мутаций/билда нет. Существующие tests не отключались/не ослаблялись. Следующее новое чтение — настоящий Chats/ChatScreen wrapper и связи в RidesRequestsChatScreens.kt; остаток PWA ActiveTrip1212 остаётся открытым. Свежие Callback checkpoint/визуальные PNG имеют приоритет перед расширением. Full96/B09, font/dark/IME/physical/TalkBack остаются открыты; design-doc sole writer, общих ресурсов не занимал, фоновых процессов нет.

## R45 — список диалогов и путь к переписке после завершения

Дата независимой записи: 01.10.2026, 15:44:23 МСК; /root/design_resume; B01/B04/B09, реестр/текущие статусы ведёт root. Read-only исходники/tests, runtime/UI/сервер/БД/эмулятор не запускались; ветка/HEAD/dirty ownership как R43. Цель: проверить вкладки активные/заявки/система, список реальных conversations, переход к броне и возврат к переписке после done/lost-item. Критерий чтения выполнен в обозначенной области; пользовательский путь не объявлен пройденным.

### Источники / актуальность

В15:38:26 МСК самостоятельно измерены9 SHA/line metadata, совпали inventory; затем два дополнительных SHA RatingReopen/DeltaRestoration также сверены. Это11 сравнений, не11 тестов. BookingCompletionDestination только metadata/отдельные fragments, полной семантической приёмки225 строк нет. Ранее SHA тех же RidesRequestsChat/ApiClient/backend chat/bookings неизменны R43–R44; остальные файлы:

| Файл | SHA256(LF) / чтение |
|---|---|
| AppNavHome.kt | 9e07c79e432cd83a1a304772f3b05073c5f048edce044629e495b0c33d6e128f /218;134–183, особенно actual chat navigation154–161; не весь218 |
| RidesRequestsChatScreens.kt | f44e2d651bcc5927699f98c7e9eee6a0eade34ec8c394544211de1ac3bd6f9ea /3535;новый ChatScreen1692–1898/комментарий1900–1913 полностью; callbacks ChatCard ранее частично R43, весь3535 не принят |
| YuldashApp.kt | HomeTab.Chat2407–2413 callback wiring; полная новаяSHA здесь не измерена, root может менять callback-owned source. Этот fragment не объявляет нового замороженного whole-file proof |
| ApiClient.kt | b3d3b34665d5cf339eecabef5a7b27ddd6bab01ea7567fac9b8b1d45bc3e0c70 /7946;1074–1081,1736–1743,1775–1789,6748–6750,7083–7091,4915–4926. Остальное7946 не принято |
| backend/app/routers/chat.py | 469ebe95951b393f1c41000185ca7ddccd07d25b1a5b0bccf162e99634085f32 /925;новые105–132 booking-chat window,185–218 сосед taxi guard,861–925 inbox полностью; остальное не принято |
| backend/app/routers/bookings.py | 8e36a71eb07d7fed744def2d434702c08a8a78cb0561fb01bfe91333a2f7cca2 /910;862–910 lost-item полностью,63–64 константы; предыдущая R41 receipt/create/agreement связь не повторялась |
| webapp/src/screens/ChatInboxScreen.tsx | a67847a661584412e250ce5a1744a80f7d03dc0acc1fc5b9d2df2076f8079573 /288 полностью;обрезанные247–264 перечитаны отдельно |
| RideshareCompletedScreen.kt | f9dfb99316abcfd3671fbcc280af627bf99b2684842d477639d8635a7b4002f9 /938;90–277 wrapper полностью,280–319 interface,798–864 lost-row/payment;1–89,320–797,865–938 ещё не приняты. Ранее R28 source-money receipt fragments остаются историческими |
| BookingCompletionDestinationTest.kt | 2d1ec5c8315d39736eecbbafe3ae8362e003deaadc26d9a824692da975a17e3c /225;metadata и отдельные fragments134–144/190–217, не весь225 |
| backend/tests/test_lost_item_everywhere.py | b76242952648fc395d17183dfbcbd08cd0232d144106e6755135b5a718bc0f5c /109 полностью,6 test definitions |
| RideshareRatingReopenTest.kt | ae0613d534c477e8469e172be998bc47970b5fcc1bf6e446605865c1eda421a7 /105 полностью,4 definitions;truncated73–88 перечитаны |
| AuditDelta02RestorationTest.kt | f26beb8afc4508d5ad17d45c9886696f7a42cfb39c38659e771c425fbdd0e050 /208;98–134, lost-item107–129 actual taxi restoration, не весь208 |

Source команды rg references/Get-Content numbered ranges; hashes Python LF. Nine initial rows metadata включает источники/API/backend/test; YuldashApp wiring указан отдельно без нового whole hash, Rating/Delta добавлены отдельно. Число11 относится к сверенным файлам без YuldashApp. Константа chat_after_trip_hours найдена config945=48, но весьconfig/version здесь не принимался; API doc/comment про «сутки» исторически не доказывает текущую величину. Backend bookingLostItem890 ограничивает30 днями/3 открытиями (constants63–64), новый пока доступный48h grant не отменяет эти ограничения.

### Карта сценария и тестов

HomeTab.Chat→ChatScreen1692. selected active/requests; system CTA→Notifications. Initial HTTP chain1713–1728 последовательно conversations→myrequests→notifications; имеются conv/req skeleton,error,retry,empty и реальный ID-key; 401 трактуется как отсутствие сессии/пусто. Existing data имеет приоритет перед error/loading (если список уже непустой); failed reload не помечает сохранённые карточки устаревшими. Поскольку тут нет pull-to-refresh/poll, поздние списки при уходе/повторе/смене аккаунта и информационное устаревание — отдельные probes, а не выполненный defect. Initial chain не имеет отдельной captured-generation проверки перед следующим HTTP; текущий generic call защищает лишь свой captured запрос, возможная chained A→B требует actual mounted/logout/context наблюдения. Предыдущий ActiveTripSessionChain не доказывает эту другую цепочку.

Conversation API→server user_bookings→один запрос всех Message/hidden ids/last_by_booking, batch Ride/Peer→generated previews→Android ChatCard/PWA row→onOpenChat/AppNavHome selected dummyRide/bookingId→Screen.ActiveTrip→role/details/history. DTO не имеет booking status/message kind, активные и закрытые с историей объединены сервером. PWA288 похожие три вкладки и запросы, isAuthed guest отдельно/AbortController на initial load; requests/conversations/notif запускаются параллельно, отличаясь от Android sequential. Subsequent manual load без signal/late account/reorder требует своего теста. Notification badge является unread уведомлений, не unread конкретного чата; Android c.unread=0 не объявлен потерей сообщения без согласованного server unread contract.

Backend lost-item109/6 synthetic driver/passenger trip three days ago: closed409 control, passenger/driver reopen→messages200/201, чужой403/404, confirmed409, обоих endpoints наличие через OpenAPI. Assertions response+последующий send имеют смысл, но двухстороннее UI/read/push/48h expiry/PG/concurrency не доказаны. RideshareRatingReopen105/4 ready receipt/injected lambdas защищают saved-rating no duplicate, text update before closing, error/retry and disabled while pending. Эти4 не создают real HTTP и не входят через Inbox; StateRestorationTester там отсутствует. RideshareCompletedGuard52 ранее R28 прочитан; нынешний поиск связи подтвердил только ApiClient.bookingLostItem строку guard, не successful chat navigation.

### DESIGN067 — серверные подписи диалога не переключаются на башкирский

BA Inbox/Chat. Backend chat871–878 генерирует «Сообщение удалено», «Голосовое», «Сообщение»,915 fallback «Собеседник»,919 «Чат открыт» как plain RU strings. parseConversationDto6748–6749 сохраняет их как lastMessage/peerName, Android1857–1859 и PWA239–241 рисуют без appText/typed preview. UI переключение языка не изменяет эти server literals. Это source-confirmed нарушение bilingual requirement, не качество пользовательского текста: реальный text пользователя875 должен остаться без перевода.

Fix-критерий: DTO/server различает generated preview kind/fallback name и user-written text, RU/BA отображают типизированные system/voice/deleted/no-message состояния; не переводить совпадение строки реального текста «Голосовое/Сообщение удалено». Controlled requests и UI обеих языков: deleted/voice/no messages/empty peer/user exact-same words, toggle без нового login→корректная локализация и сохранённые IDs/order/actual text. Нужно fixture/schema совместимости старого сервера; новые BA черновики→tasks. Runtime RED/fix/visual всё открыто, root уведомлён.

### DESIGN068 — открытая после done переписка недостижима из Inbox/«Забыл вещь»

Backend inbox907–909 включает completed/cancelled booking с историей. AppNavHome154–159 всегда переводит row в Screen.ActiveTrip. Если server role вернул done, ActiveTrip1864–1875 ранним return рендерит RideshareCompleted, до history/chat editor ниже дело не доходит. Cancelled терминальный cleanup1472–1488 выводит из ActiveTrip после удаления; права читать историю сервером105–132 не отменены. Таким образом наличие закрытого thread в inbox не доказывает достижимость просмотра истории.

Конкретный lost-item путь: Completed openLostItem105→ApiClient POST4915–4926→bookings862–910 устанавливает lost_item_until, done status сохраняется. onSuccess261–262 только lostOpened=true, row798–828 после opened disabled и пишет «Можно написать второй стороне в течение48 часов»; onOpenChat callback отсутствует в wrapper90–106/content280–318, editor/network handler чата в этом экране по source/rgrep не найден. Повторное открытие из inbox снова упирается в done early-return. Это source-confirmed разрыв server grant→UI action; фактическое двухстороннее end-to-end воспроизведение не запускалось.

Fix-критерий: доступный экран/режим истории после завершения и действующий editor по valid reopen grant, server booking остаётся done и privacy/time limits действуют. Controlled actual Inbox done→история→«Забыл вещь»200→сообщение→другой участник получает/читает, после revoke/expiry писать нельзя, историю читать по исходным правам; обе роли, notification/deeplink/Back/restart/cancelled. Не возвращать booking status в active ради обхода UI. Backend109/6 и строковой Guard этого client пути не ловят. Root получил критерий; actual RED/fix/physical/current matrix открыты.

### DESIGN059 — сосед явного0 на экране завершения

Current Completed173–175 выбирает receipt.amount лишь >0, затем fallbackPayAmount лишь >0, затем ненулевой ride.price. При authoritative receipt.amount=0 и исходной700 итог может показывать700; при inbox dummyRide price0 — «По договорённости» вместо явного0. RideshareCompletedPayment848 умеет показать0 если amount=0, проблема выше в отборе. Это тот же класс null-vs-zero ошибки R41 DESIGN059; новый конкурирующий defect ID не создан. Invoice server QA-B07-002/003 bounded acceptance этим source-наблюдением не опровергается, фактическое списание иной суммы не утверждается. Criteria добавить receipt0/fallback0/None/positive, paid/cash/negotiate, incoming authoritative state vs local old price и online amount label.

### DESIGN069 — кандидат зависшего pending после восстановления

Completed ratingBusy119/thanksBusy123/lostBusy125 использует rememberSaveable, suspend handlers231–239/247–251/260–265 устанавливают false после ожидания без finally/recovery effect. Сохранённое true может восстановиться после отмены coroutine без живого запроса, UI loading/disabled325/371/729/801 не снимается. Пока source hazard, actual StateRestorationTester/Activity/process proof не получен; именно это должно подтвердить или опровергнуть дефект. AuditDelta02Restoration107–129 защищает TaxiPassengerCompletedScreen, а не rideshare; сходство имени/«Забыл вещь» не переносит тест сюда.

Controlled критерий: три отдельных реальных wrapper actions→held awaitCancellation/HTTP→saveAndRestore→старый coroutine cancelled→новые CTA доступны/соответствуют authoritative side effects, без lost draft/rating и duplicate money/messages. Restore Activity и настоящий process death различать; зафиксировать первоначальный сбой, safe retry после successful server write/lost response отдельно. Owner должен выбрать корректную политику disposable pending/reconciliation, не просто растянуть timeout. Root уведомлён как о кандидате, не выполненном runtime баге.

### Передача и границы

Full Android ChatScreen/полный PWA ChatInbox288 прочитаны со связями; обе rendering/runtime matrices ещё открыты. Metrics smoothness, GPS/FCM/provider/device/TalkBack не измерялись, Я не могу это подтвердить. Server conversations `.all()`892–893 читает все сообщения owned bookings, хотя нужен лишь последний; это источник для bounded performance/data-volume/index probe, не результат нагрузки или доказанный ANR. Список private phone не проверялся чужим HTTP этим агентом; UI InfoCard обещание не является security proof.

Следующее новое чтение: остатки RideshareCompleted1–89,320–797,865–938 и полный BookingCompletionDestination225 с текущими SHA, затем InstantChat/ParcelChat/SupportChat actual consumers (rg212/242/426) для общего ChatContent и WS. Не повторять неизменённое Reading/Guard/74 tests ради числа. Новая Callback freeze/PNG по-прежнему имеет приоритет; R42 bounded2/2 сохраняется, confirmation pixels/BA DESIGN056 не закрыты. Writer design-doc единственный; source/shareddocs/resources read-only, build/device/root; своих процессов/замков нет.

## Заморозка R45 для передачи следующему агенту

Дата: 01.10.2026, 15:45:58 МСК. Root передал явную просьбу пользователя остановить текущий этап и подготовить передачу. Эта запись завершает текущий независимый этап; аудит B09/96 экранов не завершён. Нового чтения, новых запусков или изменений исходников при заморозке нет.

| Замечание | Точный статус доказательства на версиях R43/R45 | Незакрытый критерий |
|---|---|---|
| DESIGN061 | Source-confirmed неверная корреляция peer/own WS echo; actual воспроизведение отсутствует | Real synthetic WS hold own→peer same-text→own echo; author/id/order/durable two messages, затем минимальный fix/мутация/GREEN и соседние chat consumers |
| DESIGN062 | Source-confirmed optimistic passenger status остаётся после ApiException; runtime не выполнен | Actual CTA→403/409/503→старый progress/action/server state; offline queue/двойной tap/late poll; fix/recheck |
| DESIGN063 | Source-confirmed edit draft/id сбрасывается до результата HTTP | Actual context edit→503/403→сохранённые draft/target→retry200 того же id у обеих сторон; fix/recheck |
| DESIGN064 | Source-confirmed неизвестный shares-list скрыт как пустой; source partial commit перед SMS429 | Saved share→GET503→честная ошибка/retry→revoke token404; isolated429 после записи/access reconciliation/dedup/PG race; no realSMS |
| DESIGN065 | Source-confirmed сохранённый passport использует исходную price и всегда SBP wording | Details original700/agreed400 cash/agreed0/None→durable snapshot→offline UI RU/BA; migration/owner/generation/disk failures; fix/recheck |
| DESIGN066 | Source-confirmed failed/blank boarding-code остаётся loading без error/retry | History200+code503/403/empty+role200→error/retry validcode/durable save; terminal/account switch/disk; fix/recheck |
| DESIGN067 | Source-confirmed server-generated RU-only inbox previews/fallback names | Typed system kinds/user same-string text compatibility→RU/BA toggle Android/PWA, no translation of user content; fix/recheck |
| DESIGN068 | Source-confirmed done/cancelled inbox и lost-item grant не дают достижимый chat editor/history | Actual Inbox done→история→lost-item200→message у обоих участников; expired/forbidden/cancelled/notification/Back; preserve done, fix/recheck |
| DESIGN069 | Кандидат source hazard saveable busy; actual RED отсутствует | Три held actions→StateRestorationTester/actual Activity отдельно→не stuck pending/no repeated side effect; настоящий process death отдельно; подтвердить или опровергнуть до дефекта |

DESIGN059 расширение R45 сохраняет source-proof current Completed173–175 zero-vs-null; actual receipt0/fallback0/None и UI/payment label ещё не проверены. DESIGN056 actual BA callback header clipping и отсутствие confirmation-card pixels в пяти R42 PNG остаются открыты; bounded Callback2/2 не закрывает этот visual критерий. Source/зависимость, изменённая после указанных SHA, потребует нового evidence review; старые результаты остаются историческими.

Последнее завершённое действие: R45 source/тестовая карта Inbox/completion и эта заморозка. Изменён этим агентом только docs/audit-design-review.md. Свои фоновые процессы, locks, resource ownership отсутствуют; Gradle/adb/emulator/PG/БД/providers/production/GitHub/коммиты этим агентом не запускались. Остальные процессы root/других сессий этой записью не инвентаризировались, Я не могу это подтвердить. После выдачи итогового SHA никаких файлов этим агентом больше не редактировать.

Следующему владельцу: сначала frozen handoff/реестр/root интеграционные evidence, затем незакрытые criteria выше в принятой очереди. Чтение оставшихся Completed1–89,320–797,865–938 и Destination225 было следующим планом, но по остановке не начато; не присваивать его как выполненное. Новая независимая приёмка UI/privacy/money после изменений требует конкретного frozen diff/hash и первичных доказательств; физический телефон/реальные GPS/FCM/провайдеры/full96 обязательные результаты здесь отсутствуют.
