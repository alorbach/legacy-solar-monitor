package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.data.security.CredentialStore
import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialStorePinDisplayTest {
    @Test
    fun pinForDisplay_usesResolvedSecret() {
        assertEquals("1234", CredentialStore.pinForDisplay("cred_abc", "1234"))
    }

    @Test
    fun pinForDisplay_hidesMissingCredentialId() {
        assertEquals("", CredentialStore.pinForDisplay("cred_550e8400-e29b-41d4-a716-446655440000", null))
    }

    @Test
    fun pinForDisplay_keepsLegacyPlaintext() {
        assertEquals("0000", CredentialStore.pinForDisplay("0000", null))
        assertEquals("1111", CredentialStore.pinForDisplay("1111", "1111"))
    }

    @Test
    fun pinForDisplay_blankDefaultsToSeed() {
        assertEquals("0000", CredentialStore.pinForDisplay(null, null))
        assertEquals("0000", CredentialStore.pinForDisplay("", null))
    }
}
