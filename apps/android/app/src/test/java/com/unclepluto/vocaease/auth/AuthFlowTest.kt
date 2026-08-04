package com.unclepluto.vocaease.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthFlowTest {
    @Test
    fun `initial password routes participant to forced password change`() {
        assertEquals(
            AuthDestination.CHANGE_PASSWORD,
            destinationAfterLogin(LoginResult("token", mustChangePassword = true)),
        )
    }

    @Test
    fun `activated participant routes to home`() {
        assertEquals(
            AuthDestination.HOME,
            destinationAfterLogin(LoginResult("token", mustChangePassword = false)),
        )
    }
}
