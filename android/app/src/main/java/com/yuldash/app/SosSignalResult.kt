package com.yuldash.app

// Итог сигнала «своим» на экране SOS (SosVerifyScreens.kt): отправлен, слишком часто, вход сменился,
// не подтверждён. Вынесено, чтобы файл экрана SOS не рос (храповик размера файлов).

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sos

/** Вход в аккаунт сменился, пока экран SOS открыт: до запроса сигнал точно не ушёл, после — мог дойти. */
internal enum class SosSessionChange { None, BeforeSend, AfterSend }

/** Карточки итога под выбором типа сигнала. Обещаем только то, что знаем наверняка. */
internal fun LazyListScope.sosSignalResult(sent: Boolean, failed: Boolean, rateLimited: Boolean, sessionChange: SosSessionChange) {
    if (sent) item {
        InfoCard(
            title = appText("Сигнал отправлен", "Сигнал ебәрелде"),
            // Успех API подтверждает сохранение сигнала, а не получение внешним дежурным.
            text = appText("Сигнал сохранён в Юлдаш. Не жди ответа — если можешь, позвони 112 и близким сам.",
                "Сигнал Юлдашта һаҡланды. Яуап көтмә — мөмкин булһа, 112-гә һәм яҡындарыңа үҙең шылтырат."),
            icon = Icons.Default.Sos,
        )
    }
    if (failed) item {
        InfoCard(
            title = when {
                rateLimited -> appText("Слишком часто", "Артыҡ йыш")
                sessionChange == SosSessionChange.BeforeSend -> appText("Вход в аккаунт сменился", "Аккаунтҡа инеү алмашынды")
                else -> appText("Не удалось подтвердить отправку", "Ебәрелеүҙе раҫлап булманы")
            },
            text = when {
                rateLimited -> appText("Сигнал уже отправлялся недавно. Подожди немного и нажми ещё раз.",
                    "Сигнал күптән түгел ебәрелгән. Бер аҙ көт тә тағы баҫ.")
                // До запроса сигнал точно не ушёл — так и говорим.
                sessionChange == SosSessionChange.BeforeSend -> appText(
                    "Сигнал не отправлен. Закрой экран и открой SOS снова. Позвонить 112 можно и без входа.",
                    "Сигнал ебәрелмәне. Экранды яп та SOS-ты яңынан ас. 112-гә инеүһеҙ ҙә шылтыратырға була.")
                // Запрос ушёл: сервер мог сигнал уже сохранить, «не отправлен» было бы неправдой.
                sessionChange == SosSessionChange.AfterSend -> appText(
                    "Вход в аккаунт сменился, пока сигнал отправлялся, — он мог дойти. Если можешь, позвони 112 и открой SOS снова.",
                    "Сигнал ебәрелеп ятҡанда аккаунтҡа инеү алмашынды — ул барып етеүе мөмкин. Мөмкин булһа, 112-гә шылтырат һәм SOS-ты яңынан ас.")
                else -> appText("Проверь связь, повтори сигнал или позвони 112 и близким, если можешь.",
                    "Бәйләнеште тикшер, сигналды ҡабатла йәки, мөмкин булһа, 112-гә һәм яҡындарыңа шылтырат.")
            },
            icon = Icons.Default.Sos,
        )
    }
}
