package com.opendash.opendash_dash_engine.dash

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DashConfigPasswordTest {
    @Test
    fun `invalid passphrases throw without including the secret in the error`() {
        for (password in listOf("пароль123", "1234567", "a".repeat(64))) {
            val error = assertFailsWith<IllegalArgumentException> {
                DashConfig.validatePassword(password)
            }
            assertFalse(error.message!!.contains(password))
        }
    }
}
