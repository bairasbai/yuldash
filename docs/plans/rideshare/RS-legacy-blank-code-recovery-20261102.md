# Восстановление после пустого кода посадки

02.11.2026, Root. Ветка codex/rideshare-safety-reconcile-20261102. Статусы только docs/audit-blocks.md; основной код до нового actual RED не менять. Parent26 и permission30 приняты ограниченно, это не готовность всего режима.

## Подтверждённая граница

В source34 полный119 дал114PASS5FAIL: четыре Chat fixture ожидания разбираются отдельно; BookingOfflineSave.emptyBoardingCodeRefreshPreservesSavedCodeAndCanRetry падает на ожидании серверного имени до проверки старого кода/диска. Пустой CODE200 отзывает LIVE/details; прежние личные данные после этого закрыты правильно. 503 разрешает запасной читающий режим и не равнозначен пустому успешному ответу.

По исходникам два восстановления недостижимы: Booking Save Retry ставит busy, но save-effect выходит при details=null до finally; GuardedActiveTripDestination после отзыва показывает Unverified с loading=false/failure=null, который превращается в вечную загрузку без рабочего Retry. Это source-находки, не готовое runtime-воспроизведение.

## Порядок

1. Новый test-only пакет BlankBoardingCodeRecoveryTest: настоящий YuldashApp/Booking и GuardedActiveTripDestination, синтетический владелец и настоящие местные ROLE/DETAILS/CODE. Два blank200 отрицательных случая и обычный nonempty положительный контроль. До продуктовых правок получить собственный RED; source-контроль и независимая проверка точного плана/диффа.
2. После blank приватные телефон/код/точка скрыты, загрузка заканчивается, доступен работающий повтор. Повтор — только от нажатия, свежие DETAILS прежде нового CODE (ActiveTrip при необходимости сначала ROLE); до удержанного нового ответа приватные поля закрыты. Старые сохранённые байты кода не стирать: проверять существующим независимым durable-reader. Никакого POST, восстановления доступа старым кодом, искусственного install/revoke/reset после закрытия или ослабления 503/blank/дисковых ожиданий.
3. Исправить только реальную причину: гарантировать завершение busy и переозначивание свежих данных на явном повторе; для active-parent задавать явное состояние ошибки/повтора после утраты подтверждения. Одной замены forced-spinner недостаточно, если retry игнорирует failure=null. Не открывать старую private-проекцию, не менять TTL/owner/epoch/CAS и не запускать автоматический опрос на каждый version.
4. Те же RED→GREEN, прежние blank/503/retry/диск, parent26/permission/GPS и затронутый широкий набор. Текущий APK/compileAndroidTest, guards, unsignedRelease, реальный интерфейс RU/BA×темы и свежая независимая приёмка остаются обязательными. Потолки не повышать; Booking почти у предела, при необходимости один точный связанный вынос, без искусственного уплотнения. Обновить мозг.

Root единственный применяет исходники и запускает Gradle/эмулятор/сервер. Остальные авторы передают только ограниченные patches. Main/push/prod/деньги/платные провайдеры не разрешены. Новых надписей избегать, использовать существующие два языка; новые черновики BA — в tasks.

## Отдельная подтверждённая ошибка восстановления завершения

Source38 network-navigation-preflight01 сохранил actual RED Completed.supportBackRetainsExactDraftWithoutAutomaticPost и Legacy.currentReceiptCallbackSurvivesLostGlobalSnapshotNotification: ROLE=done и DETAILS=done согласованы, terminal=true, но screen=Booking42 и HISTORY не запрашивается. Причина: restoreBookingSnapshotNavigation вызывает обычный applyBookingLink(...completed=false), затем исключение для уже запрошенного ActiveTrip проверяет только bookingStatusAllowsBoarding, который исключает done.

В том же связанном Nav-файле разрешена ровно замена проверки на существующий bookingStatusAllowsActiveTrip (confirmed/onboard/done) только в исключении восстановления уже запрошенного ActiveTrip пассажира. Обычные ссылки на Booking, driver/cancelled/inTransit, session/owner/revision/current guards и отдельный HISTORY gate не менять. Завершение не получает LIVE-разрешение. Показать точный diff отдельной находкой в metadata; fresh peer проверяет до применения, затем те же старые NAV RED, все51cases после исправления здоровой подготовки, parent26/guards и настоящая текущая сборка/UI обязательны. Номера44/8 в History этой правкой не угадываются.
