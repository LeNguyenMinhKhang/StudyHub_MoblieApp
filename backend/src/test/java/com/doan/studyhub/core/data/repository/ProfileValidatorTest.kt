package com.doan.studyhub.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileValidatorTest {
    private val validator = ProfileValidator()

    @Test fun `registration rejects invalid identity fields`() {
        val errors = validator.validate("a b", "   ", null)
        assertTrue("username" in errors)
        assertTrue("displayName" in errors)
    }

    @Test fun `an unchanged legacy username can be retained during unrelated edits`() {
        val errors = validator.validate("legacy-name", "New display name", "bio", "legacy-name")
        assertTrue(errors.isEmpty())
    }

    @Test fun `a changed legacy username must follow the new format`() {
        val errors = validator.validate("different-name", "Name", null, "legacy-name")
        assertTrue("username" in errors)
    }

    @Test fun `name is trimmed before length validation and bio is capped`() {
        assertFalse("displayName" in validator.validate("valid_1", "  Name  ", "bio"))
        val errors = validator.validate("valid_1", "Name", "x".repeat(501))
        assertEquals(setOf("bio"), errors.keys)
    }
}
