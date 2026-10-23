package com.rud.freemomo.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignDataSanitizerTest {

    private val replacement = 99666
    private val original = 671

    @Test
    fun `rewrites leaked max_voc_count in the debt report`() {
        val body = """
            {"last_sign_time_new":"2026-08-24T14:33:00.884+0800","mmx_version":1000064,
            "debt_report_data":{"time":"2026-10-17T02:01:48.329+0800",
            "learned_voc_count":50,"max_voc_count":$replacement,"day_new_limit":0}}
        """.trimIndent().replace("\n", "")
        val sanitized = SignDataSanitizer.sanitize(body, replacement, original)!!
        assertEquals(body.replace("\"max_voc_count\":$replacement", "\"max_voc_count\":$original"), sanitized)
        assertEquals(original, Regex("\"max_voc_count\":(\\d+)").find(sanitized)!!.groupValues[1].toInt())
    }

    @Test
    fun `returns null when body carries no replacement value`() {
        val body = """{"debt_report_data":{"learned_voc_count":50,"max_voc_count":50,"day_new_limit":0}}"""
        assertNull(SignDataSanitizer.sanitize(body, replacement, original))
    }

    @Test
    fun `returns null when body is unrelated`() {
        assertNull(SignDataSanitizer.sanitize("""{"events":{"type":"PageView"}}""", replacement, original))
    }

    @Test
    fun `rewrites every guarded key that carries the replacement`() {
        val body = """{"day_new_limit":$replacement,"today_plan_voc_count":$replacement,
            "day_limit":$replacement,"studied_voc_count":$replacement}""".replace("\n", "")
        val sanitized = SignDataSanitizer.sanitize(body, replacement, original)!!
        assertEquals(
            original,
            Regex("\"day_new_limit\":(\\d+)").find(sanitized)!!.groupValues[1].toInt()
        )
        assertEquals(
            original,
            Regex("\"today_plan_voc_count\":(\\d+)").find(sanitized)!!.groupValues[1].toInt()
        )
        assertEquals(
            original,
            Regex("\"day_limit\":(\\d+)").find(sanitized)!!.groupValues[1].toInt()
        )
        // studied_voc_count is real study data, not a guarded limit key: must stay untouched.
        assertEquals(replacement, Regex("\"studied_voc_count\":(\\d+)").find(sanitized)!!.groupValues[1].toInt())
    }

    @Test
    fun `leaves similar but non-exact numbers untouched`() {
        val body = """{"max_voc_count":996660,"day_limit":${replacement}0,"max_voc_count2":$replacement}"""
        assertNull(SignDataSanitizer.sanitize(body, replacement, original))
    }

    @Test
    fun `does not rewrite when value appears inside a string literal`() {
        val body = """{"note":"\"max_voc_count\":$replacement"}"""
        assertNull(SignDataSanitizer.sanitize(body, replacement, original))
    }

    @Test
    fun `replacement equal to original leaves body unchanged`() {
        val body = """{"max_voc_count":$replacement}"""
        val sanitized = SignDataSanitizer.sanitize(body, replacement, replacement)!!
        assertEquals(body, sanitized)
    }

    @Test
    fun `prebuilt needles invalidate when the parameter pair changes`() {
        val body = """{"max_voc_count":$replacement}"""
        assertEquals("""{"max_voc_count":671}""", SignDataSanitizer.sanitize(body, replacement, 671))
        assertEquals("""{"max_voc_count":100}""", SignDataSanitizer.sanitize(body, replacement, 100))
        assertEquals("""{"max_voc_count":671}""", SignDataSanitizer.sanitize(body, replacement, 671))
        // A different replacement value must not run against the stale needle set.
        assertEquals("""{"max_voc_count":671}""", SignDataSanitizer.sanitize("""{"max_voc_count":100}""", 100, 671))
    }
}
