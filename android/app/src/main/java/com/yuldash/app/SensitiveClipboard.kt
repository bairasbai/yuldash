package com.yuldash.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Копирование того, что не должно светиться: телефон, код вручения посылки, ссылка слежения.
 *
 * Зачем (аудит 2026-08-12, волна 32). Android 13+ на каждое копирование показывает всплывашку
 * с ПРЕДПРОСМОТРОМ скопированного — прямо поверх экрана, где её видит любой, кто смотрит
 * на телефон через плечо. Плюс буфер обмена читают клавиатуры и менеджеры паролей, а на старых
 * версиях — вообще любое приложение.
 *
 * Что копируют у нас:
 *   • номер телефона для перевода по СБП — чужой номер;
 *   • код вручения посылки — кто знает код, тот и получит посылку;
 *   • ссылка «следить за поездкой» — по ней видно, где человек едет прямо сейчас;
 *   • текст для диктовки при SOS — марка машины, номер, место.
 *
 * Системный флаг `EXTRA_IS_SENSITIVE` прячет предпросмотр и просит систему не запоминать
 * значение. Копирование при этом работает как раньше — человек вставит куда хотел.
 *
 * Коды скидок копируются обычным способом намеренно: их для того и показывают, чтобы человек
 * их видел и передавал.
 */
internal fun copySensitive(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText("", text)     // label пустой: он тоже виден в системном UI
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    manager.setPrimaryClip(clip)
}
