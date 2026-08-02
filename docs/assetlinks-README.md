# Диплинки Юлдаша (`yulbash.ru/r/...`) — assetlinks.json

Чтобы ссылки вида `https://yulbash.ru/r/123` открывались сразу в приложении (а не в браузере),
в манифесте у нас стоит `android:autoVerify="true"` на диплинк. Для проверки Android требует
файл `assetlinks.json` на домене. Шаблон — `assetlinks-template.json` рядом.

## Что сделать (за тобой — нужен keystore)

1. **Получи SHA256-отпечаток** своего релизного ключа подписи:
   ```
   keytool -list -v -keystore путь/к/твоему.jks -alias твой_алиас
   ```
   В выводе строка `SHA256: AB:CD:...` — это и есть отпечаток.

2. **Вставь его** в `assetlinks-template.json` вместо `ЗАМЕНИ_НА_SHA256_ОТПЕЧАТОК_ТВОЕГО_KEYSTORE`.
   (Формат — как в выводе keytool, с двоеточиями, в верхнем регистре.)

3. **Размести файл** на домене строго по пути:
   ```
   https://yulbash.ru/.well-known/assetlinks.json
   ```
   Отдаваться должен как `application/json`, по HTTPS, без редиректов.

4. **Проверь**: открой `https://yulbash.ru/.well-known/assetlinks.json` в браузере — виден JSON;
   после установки подписанного релиза ссылки `yulbash.ru/r/...` откроются в приложении.

> Если используешь Google Play App Signing — отпечаток бери из Play Console
> (Setup → App integrity → App signing key certificate → SHA-256), а не из своего keystore.
