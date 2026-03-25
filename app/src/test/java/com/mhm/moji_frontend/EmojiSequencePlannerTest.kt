package com.mhm.moji_frontend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmojiSequencePlannerTest {
    @Test
    fun createPlan_returnsEmptyPlan_whenEmojiListIsEmptyOrBlank() {
        val plan = EmojiSequencePlanner.createPlan(listOf("", "  "))

        assertEquals(emptyList<String>(), plan.leadInEmojis)
        assertNull(plan.finalEmoji)
    }

    @Test
    fun createPlan_usesSingleEmojiAsFinalEmoji() {
        val plan = EmojiSequencePlanner.createPlan(listOf("1F600"))

        assertEquals(emptyList<String>(), plan.leadInEmojis)
        assertEquals("1F600", plan.finalEmoji)
    }

    @Test
    fun createPlan_splitsLeadInAndFinalEmoji_whenMultipleEmojisArrive() {
        val plan = EmojiSequencePlanner.createPlan(listOf("1F600", "1F389", "2764-FE0F"))

        assertEquals(listOf("1F600", "1F389"), plan.leadInEmojis)
        assertEquals("2764-FE0F", plan.finalEmoji)
    }
}

