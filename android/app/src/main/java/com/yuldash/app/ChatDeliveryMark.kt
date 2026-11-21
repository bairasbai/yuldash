package com.yuldash.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.yuldash.app.data.MessageDto

internal enum class ChatDeliveryMark { None, Queued, Failed, Sending, Sent, Delivered, Read }

internal fun deliveryMark(
    message: MessageDto, owner: Int, failed: Boolean, queued: Boolean, view: TripChatReceiptView,
): ChatDeliveryMark {
    if (message.senderId != owner || message.deleted || message.hiddenForMe) return ChatDeliveryMark.None
    if (message.id < 0) return when {
        failed -> ChatDeliveryMark.Failed
        queued -> ChatDeliveryMark.Queued
        else -> ChatDeliveryMark.Sending
    }
    val item = view.items[message.id] ?: return ChatDeliveryMark.None
    return when {
        item.peerRead -> ChatDeliveryMark.Read
        item.peerDelivered -> ChatDeliveryMark.Delivered
        else -> ChatDeliveryMark.Sent
    }
}

@Composable
internal fun MessageDeliveryMark(mark: ChatDeliveryMark, showLabel: Boolean, modifier: Modifier = Modifier) {
    AnimatedContent(mark, modifier = modifier, transitionSpec = {
        fadeIn(tween(CanonMotion.QUICK)) togetherWith fadeOut(tween(CanonMotion.QUICK))
    }, label = "chat-delivery") { state ->
        val (label, description) = when (state) {
            ChatDeliveryMark.Sent -> appText("Отправлено", "Ебәрелгән") to appText("Сообщение отправлено", "Хәбәр ебәрелгән")
            ChatDeliveryMark.Delivered -> appText("Доставлено", "Еткерелгән") to appText("Сообщение доставлено", "Хәбәр еткерелгән")
            ChatDeliveryMark.Read -> appText("Прочитано", "Уҡылған") to appText("Сообщение прочитано", "Хәбәр уҡылған")
            else -> return@AnimatedContent
        }
        val tint = if (state == ChatDeliveryMark.Read) CanonGreen2 else CanonMuted
        Row(
            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CanonSpace.xs),
        ) {
            Icon(
                if (state == ChatDeliveryMark.Sent) Icons.Default.Done else Icons.Default.DoneAll,
                contentDescription = null, tint = tint, modifier = Modifier.size(CanonSpace.lg),
            )
            if (showLabel) Text(label, color = tint, style = CanonMicro)
        }
    }
}
