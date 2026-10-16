package com.yuldash.app

import androidx.compose.runtime.Composable
import com.yuldash.app.data.ConversationDto

@Composable
internal fun ConversationCard(c: ConversationDto, onOpenChat: (Int, String, String) -> Unit) {
    ChatCard(
        initial = c.peerName.take(1).uppercase(),
        name = c.peerName,
        subtitle = c.route,
        message = if (c.hasMessages == false) appText("Чат открыт", "Чат асылды") else c.lastMessage,
        time = c.departAt?.takeIf { it.isNotBlank() }?.let(::formatDepart) ?: "",   // время выезда — различать треды
        unread = 0,
        verified = c.peerVerified,   // реальный статус проверки собеседника (не фейк «проверен» у всех)
        onClick = { onOpenChat(c.bookingId, c.peerName, c.route) },
        avatarUrl = c.peerAvatar
    )
}
