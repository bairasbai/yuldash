package com.yuldash.app.data

/** Текстовый отзыв о поездке, ждущий модерации. Пока не одобрен — в профиле его нет. */
data class PendingRatingDto(
    val id: Int,
    val author: String,       // кто оставил (админу; в публичном профиле — тоже без телефона)
    val rateeId: Int,         // кому адресован
    // Имя того, О КОМ отзыв. Без него модератор читал текст вслепую: видно «вёз молча»,
    // а чей это профиль и кому прилетит публикация — нет. Телефон не отдаём, имени хватает.
    val ratee: String = "",
    val stars: Int,
    val text: String,
    val createdAt: String,
)
