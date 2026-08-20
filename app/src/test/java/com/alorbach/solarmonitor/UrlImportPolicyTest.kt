package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.data.importing.UrlImportPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlImportPolicyTest {
    @Test
    fun https_isAlwaysAllowed() {
        assertTrue(UrlImportPolicy.isAllowed("https://example.com/day.csv"))
        assertTrue(UrlImportPolicy.isAllowed("https://192.0.2.1/day.csv"))
    }

    @Test
    fun http_allowsPrivateAndLoopbackOnly() {
        assertTrue(UrlImportPolicy.isAllowed("http://192.168.1.20/smadata/day.csv"))
        assertTrue(UrlImportPolicy.isAllowed("http://10.0.0.5/file.zip"))
        assertTrue(UrlImportPolicy.isAllowed("http://172.16.4.1/SBFspot.db"))
        assertTrue(UrlImportPolicy.isAllowed("http://127.0.0.1/local.csv"))
        assertTrue(UrlImportPolicy.isAllowed("http://localhost/local.csv"))
        assertTrue(UrlImportPolicy.isAllowed("http://169.254.1.1/linklocal.csv"))
        assertTrue(UrlImportPolicy.isAllowed("http://[::1]/loop.csv"))
        assertTrue(UrlImportPolicy.isAllowed("http://[fd12:3456:789a::1]/ula.csv"))
        assertTrue(UrlImportPolicy.isAllowed("http://[fe80::1]/link.csv"))

        assertFalse(UrlImportPolicy.isAllowed("http://example.com/day.csv"))
        assertFalse(UrlImportPolicy.isAllowed("http://8.8.8.8/day.csv"))
        assertFalse(UrlImportPolicy.isAllowed("http://172.32.0.1/day.csv"))
        assertFalse(UrlImportPolicy.isAllowed("ftp://192.168.1.1/day.csv"))
    }
}
