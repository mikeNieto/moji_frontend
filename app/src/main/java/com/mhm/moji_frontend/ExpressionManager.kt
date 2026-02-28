package com.mhm.moji_frontend

object ExpressionManager {

    private const val CDN_BASE_URL = "https://openmoji.org/data/color/svg/"

    // Mapeo de estados base y emociones a HEXCODE de OpenMoji
    private val emojiMap = mapOf(
        // Estados fijos
        "idle" to "1F916",          // 🤖
        "listening" to "1F442",     // 👂
        "searching" to "1F50D",     // 🔍
        "thinking" to "1F914",      // 🤔
        "error" to "1F615",         // 😕
        "disconnected" to "1F50C",  // 🔌
        "greeting" to "1F44B",      // 👋
        "registering" to "2753",    // ❓

        // Emociones (Ejemplos según arquitectura)
        "happy" to "1F60A",         // 😊
        "excited" to "1F929",       // 🤩
        "sad" to "1F622",           // 😢
        "empathy" to "1F97A",       // 🥺
        "confused" to "1F615",      // 😕
        "surprised" to "1F632",     // 😲
        "love" to "2764-FE0F",      // ❤️
        "cool" to "1F60E",          // 😎
        "neutral" to "1F642",       // 🙂
        "curious" to "1F9D0",       // 🧐
        "worried" to "1F61F",       // 😟
        "playful" to "1F61C"        // 😜
    )

    fun getEmojiUrl(expression: String): String {
        val hexCode = emojiMap[expression.lowercase()] ?: emojiMap["idle"]!!
        return "$CDN_BASE_URL$hexCode.svg"
    }
}
