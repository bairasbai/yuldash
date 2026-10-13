package com.yuldash.app.walk.l2_2

import com.yuldash.app.data.ApiClient
import com.yuldash.app.isOwnMediaHost
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.URI

/**
 * leaf-2.2 (SecureImageRequest.kt): схема адреса обязана совпадать, не только хост и порт.
 *
 * Правило названо в собственном комментарии файла («Схема должна совпадать: https → http это
 * понижение, при котором токен ушёл бы открытым текстом»), но до этого листа было без теста:
 * `SecureImageRequestTest` (не в зоне этого листа) проверяет чужой хост, протокол-относительные
 * адреса и похожие домены, но не смену схемы на ТОМ ЖЕ хосте и порту. Сценарий: перехватчик на
 * Wi-Fi или скомпрометированный прокси отдаёт ту же ссылку на приватный документ ДРУГОЙ схемой;
 * без проверки схемы токен администратора ушёл бы этим каналом открытым текстом рядом с чужими
 * правами/селфи. Схему не подставляем жёстко (https или http) — берём ПРОТИВОПОЛОЖНУЮ той, что
 * реально отдаёт `ApiClient.apiBase()` в этой сборке, чтобы тест не зависел от local.properties.
 */
class SecureImageRequestSchemeTest {

    @Test
    fun `тот же хост и порт, но другая схема — не свой`() {
        val own = ApiClient.apiBase()
        val ownScheme = URI(own).scheme
        val flippedScheme = if (ownScheme == "https") "http" else "https"
        val flipped = own.replaceFirst("$ownScheme://", "$flippedScheme://") + "/secure/docs/12_abc.jpg"
        assertFalse(
            "адрес с тем же хостом и портом, но другой схемой ($flippedScheme вместо $ownScheme), " +
                "принят как свой — токен мог бы уйти по незашифрованному каналу",
            isOwnMediaHost(flipped),
        )
    }
}
