package com.mhm.moji_frontend

data class EmojiSequencePlan(
    val leadInEmojis: List<String>,
    val finalEmoji: String?
)

object EmojiSequencePlanner {
    fun createPlan(emojis: List<String>): EmojiSequencePlan {
        val sanitizedEmojis = emojis
            .map(String::trim)
            .filter(String::isNotEmpty)

        return when (sanitizedEmojis.size) {
            0 -> EmojiSequencePlan(
                leadInEmojis = emptyList(),
                finalEmoji = null
            )
            1 -> EmojiSequencePlan(
                leadInEmojis = emptyList(),
                finalEmoji = sanitizedEmojis.first()
            )
            else -> EmojiSequencePlan(
                leadInEmojis = sanitizedEmojis.dropLast(1),
                finalEmoji = sanitizedEmojis.last()
            )
        }
    }
}

