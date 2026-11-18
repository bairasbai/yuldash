# Не подключённая pure claim policy

Independent pre-code PASS journal, только synthetic/noIO leaf component. Не account/worker integration, не lease/reservation/authorization, не generation cutover и не409.

`evaluate_resume_claim(captured,current,now,retry_policy)` принимает frozen value snapshots: id,status,revision,lifecycle,searching_at,round,driver,recipient,token,offered_at,deadline и три pending поля. Возвращает frozen decision, никогда не меняет input/БД/часы. AllNULL pending = absent no-op; partial = invalid. Exact complete current/captured equality требуется, включая cooldown/attempts. Валидное изменение любогоowner/pendingfield = obsolete, не permission clear. Malformed values = invalid.

Due только searching с полным matching pending и next_at<=явныйnaiveUTC now; future=not_due. Offered с retained obligation не выполняется и не очищается, accepted/terminal тоже не выдают clearpermission. Caller будущего протокола обязан получить actualfreshlock, clock и выполнить exactCAS полногоowner/pending, атомарно persist proposal; результат функции сам неclaim.

Policy base>0,cap>=base, exactint/no bool; attempts0..INTMAX. Первый attempts0 даёт delaybase/новыйattempts1; capped doubling без огромной степени, насыщениеattemptsINTMAX. Datetime overflow invalid безclamp, aware/mixed time invalid. Timestampполитика по полям: offer deadline можетбытьfuture, next_atfuture законенnotdue. LifecycleNone — legacyvalid, nonNULL только32hex. Отрицательнаяrevisioninvalid здесь отражает pre-cutoverlab, не signedadapter contract. Никакого implicitclock/IO/SQL/ORM.

Tests: frozen/deterministic/immutability; absent/partial/invalidtypes/bool/negative/overflow; сменакаждогополя; exactdueboundary; offeredretained/terminalnoop; capthreshold/MAX; datetime.max/timezones; hugeexactpositivegenerationбезfloat; staleoldclaimпослеproposedcooldownobsolete. Zero productioncallers/imports проверить статически. Независимая scoped приёмка и полный regression передпубликациейlabмодулей; не называть syntheticPASS реализованным recovery.

Freeze e2021b7: app module+tests, productioncallers0. Author87 PASS0.34с, independent87 PASS0.20с, actualexit0; Ruff/diff PASS. Предыдущийauthor82PASS сохранён отдельно, отказов не было. Lab seconds budget явно1..INTMAX ДОарифметики, enormous inputs rejected; это не выбранная productionretryfrequency. Temporal policy historicalsearching/offered times<=now; deadline/next_at могутбытьfuture. Offered legacyNULLtoken/deadline допустимы только какno-opdata, не execution. Логи `/tmp/taxi-resume-policy-author2.log` и `/tmp/taxi-resume-policy-independent-scoped.log`. НовыйFULL общегоlabпакета ещёвпереди.

Общий FULL lab-пакета на 9e817d3: 7677 PASS / 424 SKIP, exit 0, 735.38 с. Это подтверждает совместимость тестового набора, но не превращает pure proposal в персистентный claim или работающий recovery consumer. Модуль остаётся неподключённым.
