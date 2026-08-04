package com.unclepluto.vocaease

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LoginLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun loginControlsAreVerticallySeparated() {
        compose.setContent {
            MaterialTheme {
                LoginScreen(error = "", busy = false) { _, _ -> }
            }
        }

        val phone = compose.onNodeWithTag("login-phone").fetchSemanticsNode().boundsInRoot
        val password = compose.onNodeWithTag("login-password").fetchSemanticsNode().boundsInRoot
        val submit = compose.onNodeWithTag("login-submit").fetchSemanticsNode().boundsInRoot

        assertTrue(phone.bottom < password.top)
        assertTrue(password.bottom < submit.top)
    }
}
