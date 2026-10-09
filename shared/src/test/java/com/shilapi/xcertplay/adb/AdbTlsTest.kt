package com.shilapi.xcertplay.adb

import java.security.KeyPairGenerator
import org.junit.Assert.assertEquals
import org.junit.Test

class AdbTlsTest {
    @Test
    fun certificateCarriesDiPlaysAdbKeyAndVerifiesWithIt() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certificate = AdbTls.selfSignedCertificate(key)
        certificate.checkValidity()
        certificate.verify(key.public)
        assertEquals(key.public, certificate.publicKey)
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
    }
}
