package dev.honor.bootforge.wireless

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Date

class AdbCertificateTest {
    @Test fun certificateParsesAndAuthenticatesTheGeneratedIdentity() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certificate = AdbIdentity.createCertificate(key.private, key.public as RSAPublicKey)
        certificate.verify(key.public)
        certificate.checkValidity(Date(1_800_000_000_000L))
        assertEquals(3, certificate.version)
        assertEquals("SHA256withRSA", certificate.sigAlgName)
        assertArrayEquals(key.public.encoded, certificate.publicKey.encoded)
    }
}
