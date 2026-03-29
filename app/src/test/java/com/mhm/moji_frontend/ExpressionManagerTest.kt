package com.mhm.moji_frontend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionManagerTest {
    @Test
    fun resolveHexCode_returnsBackendIndicatorForBackendDisconnectExpression() {
        assertEquals("1F5A5-FE0F", ExpressionManager.resolveHexCode("backend_disconnected"))
    }

    @Test
    fun resolveHexCode_returnsBleIndicatorForBleDisconnectExpression() {
        assertEquals("1F4F6", ExpressionManager.resolveHexCode("ble_disconnected"))
    }

    @Test
    fun getEmojiUrl_buildsSvgUrlForConnectionIssueExpressions() {
        val url = ExpressionManager.getEmojiUrl("backend_disconnected")

        assertTrue(url.endsWith("1F5A5-FE0F.svg"))
    }
}

