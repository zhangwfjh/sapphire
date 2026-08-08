package com.sapphire.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectiveAssemblerTest {

    @Test
    fun `all fields empty returns empty string`() {
        val result = DirectiveAssembler.assemble("", "", "", "", 1)
        assertEquals("", result)
    }

    @Test
    fun `goal only collapses to goal plus base instruction`() {
        val result = DirectiveAssembler.assemble("My goal", "", "", "", 1)
        assertTrue(result.contains("GOAL: My goal"))
        assertTrue(result.contains("OUTPUT REQUIREMENTS"))
        assertTrue(!result.contains("TASK:"))
        assertTrue(!result.contains("FORMAT:"))
        assertTrue(!result.contains("RULES:"))
    }

    @Test
    fun `all four fields produces full structured prompt`() {
        val result = DirectiveAssembler.assemble("G", "T", "F", "R", 3)
        assertTrue(result.contains("GOAL: G"))
        assertTrue(result.contains("TASK: T"))
        assertTrue(result.contains("FORMAT: F"))
        assertTrue(result.contains("RULES: R"))
        assertTrue(result.contains("OUTPUT REQUIREMENTS"))
    }

    @Test
    fun `blank fields are omitted`() {
        val result = DirectiveAssembler.assemble("G", "", "F", "", 1)
        assertTrue(result.contains("GOAL: G"))
        assertTrue(!result.contains("TASK:"))
        assertTrue(result.contains("FORMAT: F"))
        assertTrue(!result.contains("RULES:"))
    }

    @Test
    fun `whitespace-only fields are treated as blank`() {
        val result = DirectiveAssembler.assemble("G", "  ", "  ", "  ", 1)
        assertTrue(result.contains("GOAL: G"))
        assertTrue(!result.contains("TASK:"))
    }

    @Test
    fun `base instruction includes max items`() {
        val result = DirectiveAssembler.assemble("G", "", "", "", 5)
        assertTrue(result.contains("5 item"))
    }

    @Test
    fun `base instruction mentions HTML and paragraphs`() {
        val result = DirectiveAssembler.assemble("G", "", "", "", 1)
        assertTrue(result.contains("80 chars"))
        assertTrue(result.contains("HTML"))
        assertTrue(result.contains("paragraphs"))
    }
}
