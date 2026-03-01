package com.mhm.moji_frontend

/**
 * ExpressionManager: Maps emotion tags and robot states to OpenMoji emoji hex codes.
 *
 * Supports:
 * - Fixed state emojis (IDLE, LISTENING, etc.)
 * - Randomized emotion variants (happy → one of several happy emojis)
 * - Direct hex code passthrough (for response_meta emoji sequences)
 */
object ExpressionManager {

    private const val CDN_BASE_URL = "https://openmoji.org/data/color/svg/"

    // Fixed state emojis (always the same emoji for each state)
    private val stateEmojiMap = mapOf(
        "loading" to "231B",        // ⌛
        "idle" to "1F916",          // 🤖
        "listening" to "1F442",     // 👂
        "searching" to "1F50D",     // 🔍
        "thinking" to "1F914",      // 🤔
        "error" to "1F615",         // 😕
        "disconnected" to "1F50C",  // 🔌
        "greeting" to "1F44B",      // 👋
        "registering" to "2753"     // ❓
    )

    // Emotion tag → list of possible emoji variants (random selection)
    private val emotionVariantsMap = mapOf(
        "happy" to listOf("1F600", "1F603", "1F604", "1F60A"),
        "excited" to listOf("1F929", "1F389", "1F38A", "2728"),
        "sad" to listOf("1F622", "1F625", "1F62D"),
        "empathy" to listOf("1F97A", "1F615", "2764-FE0F"),
        "confused" to listOf("1F615", "1F914", "2753"),
        "surprised" to listOf("1F632", "1F62E", "1F92F"),
        "love" to listOf("2764-FE0F", "1F60D", "1F970", "1F498"),
        "cool" to listOf("1F60E", "1F44D", "1F525"),
        "greeting" to listOf("1F44B", "1F917"),
        "neutral" to listOf("1F642", "1F916"),
        "curious" to listOf("1F9D0", "1F50D"),
        "worried" to listOf("1F61F", "1F628"),
        "playful" to listOf("1F61C", "1F609", "1F638")
    )

    /**
     * Get the OpenMoji CDN URL for an expression.
     *
     * @param expression Can be:
     *   - A robot state name (e.g., "idle", "listening")
     *   - An emotion tag from the LLM (e.g., "happy", "curious")
     *   - A direct hex code (e.g., "1F600", "2764-FE0F")
     */
    fun getEmojiUrl(expression: String): String {
        val hexCode = resolveHexCode(expression)
        return "$CDN_BASE_URL$hexCode.svg"
    }

    /**
     * Resolve an expression string to a hex code.
     * Priority: direct hex code > emotion variant > state emoji > default idle.
     */
    fun resolveHexCode(expression: String): String {
        val key = expression.lowercase()

        // 1. Check if it's already a valid hex code (e.g., "1F600" or "2764-FE0F")
        if (isHexCode(expression)) {
            return expression.uppercase()
        }

        // 2. Check emotion variants (random selection)
        emotionVariantsMap[key]?.let { variants ->
            return variants.random()
        }

        // 3. Check fixed state emojis
        stateEmojiMap[key]?.let { return it }

        // 4. Default to idle
        return stateEmojiMap["idle"]!!
    }

    /**
     * Get a specific emotion emoji hex code (random variant).
     */
    fun getEmotionHexCode(emotionTag: String): String {
        return emotionVariantsMap[emotionTag.lowercase()]?.random()
            ?: stateEmojiMap["idle"]!!
    }

    /**
     * Check if a string looks like a hex code (e.g., "1F600" or "2764-FE0F").
     */
    private fun isHexCode(s: String): Boolean {
        return s.matches(Regex("^[0-9A-Fa-f]+(-[0-9A-Fa-f]+)*$")) && s.length >= 4
    }
}
