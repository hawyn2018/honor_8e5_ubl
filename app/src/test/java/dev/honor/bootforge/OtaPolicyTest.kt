package dev.honor.bootforge

import dev.honor.bootforge.core.AblRollbackInspector
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class OtaPolicyTest {
    @Test fun clickTimeUsesDeviceTimeZoneAndRequiredFolder() {
        assertEquals("hnubl_ota_backup_19700101_080000", OtaPolicy.folder(1, TimeZone.getTimeZone("Asia/Shanghai")))
        assertEquals("hnubl_ota_backup_19700101_000000", OtaPolicy.folder(1, TimeZone.getTimeZone("UTC")))
    }
    @Test fun absentAndUnparsedArbAreRecordedAsZero() {
        val invalid = OtaPolicy.arb(AblRollbackInspector.Result.Invalid("metadata v99"))
        assertEquals(0L, invalid.value); assertTrue(invalid.detail.contains("metadata v99"))
        val absent = OtaPolicy.arb(AblRollbackInspector.Result.NoMetadata(AblRollbackInspector.Format.EFI_PE, listOf("raw PE")))
        assertEquals(0L, absent.value); assertFalse(OtaPolicy.requiresBootChainCopy(listOf(invalid, absent)))
    }
    @Test fun everyNonzeroCounterIncludingGreaterThanOneTriggersCopy() {
        for (v in listOf(1L, 2L, 7L, 0xffffffffL)) assertTrue(OtaPolicy.requiresBootChainCopy(listOf(OtaPolicy.Arb(0, ""), OtaPolicy.Arb(v, ""))))
    }
    @Test fun bootRolesDoNotTreatAllAbPartitionsAsBootChain() {
        assertTrue("xbl" in OtaPolicy.bootRoles && "tz" in OtaPolicy.bootRoles)
        listOf("uefi", "shrm", "hyp_ac_config", "dcp_dtb", "dcp", "tz_ac_config", "soccp", "soccp_dtb",
            "soccp_debug", "soccp_dcd", "pdp", "pdp_cdb", "cpucp", "cpucpfw", "cpucp_dtb", "tz_qti_config").forEach {
            assertTrue(it, it in OtaPolicy.bootRoles)
        }
        listOf("abl", "boot", "init_boot", "dtbo", "vbmeta", "super", "modem", "vendor_boot", "userdata").forEach { assertFalse(it, it in OtaPolicy.bootRoles) }
    }
    private fun p32(b: ByteArray, at: Int, v: Long) { repeat(4) { b[at + it] = (v ushr (8 * it)).toByte() } }
    private fun elf(): ByteArray = ByteArray(1024).also {
        byteArrayOf(127, 69, 76, 70, 2, 1, 1).copyInto(it)
        p32(it, 32, 64); it[56] = 1
        p32(it, 68, 0x02000000); p32(it, 72, 256); p32(it, 96, 128)
    }
    @Test fun requiresRealQualcommHashSegmentNotFilenameOrElfMagic() {
        assertTrue(OtaPolicy.signedContainer(elf()))
        assertFalse(OtaPolicy.signedContainer(elf().also { p32(it, 68, 0) }))
        assertFalse(OtaPolicy.signedContainer(elf().also { p32(it, 72, 1000) }))
        assertFalse(OtaPolicy.signedContainer("xbl_a efisp".toByteArray()))
    }
    @Test fun modernBareMbnSignedContainersWorkWithoutReadableArbMetadata() {
        for (version in listOf(7L, 8L)) {
            val header = if (version == 8L) 56 else 40
            val image = ByteArray(header + 16 + 32 + 16)
            p32(image, 4, version); p32(image, 20, 16); p32(image, 32, 32); p32(image, 36, 16)
            image[header + 16 + 32] = 0x30
            assertTrue("MBNv$version", OtaPolicy.signedContainer(image))
            p32(image, 36, 0)
            assertFalse(OtaPolicy.signedContainer(image))
        }
    }
    @Test fun supplementalReferencesRequireKnownWholeNames() {
        assertEquals(listOf(0), OtaPolicy.referenceOffsets("imagefv\u0000".toByteArray(), "imagefv"))
        assertTrue(OtaPolicy.referenceOffsets("notimagefv\u0000".toByteArray(), "imagefv").isEmpty())
        assertTrue(OtaPolicy.referenceOffsets("notimagefv\u0000".toByteArray(Charsets.UTF_16LE), "imagefv").isEmpty())
        assertTrue(OtaPolicy.referenceOffsets("imagefv_experimental\u0000".toByteArray(), "imagefv").isEmpty())
        assertEquals(listOf(0), OtaPolicy.referenceOffsets("imagefv_a\u0000".toByteArray(Charsets.UTF_16LE), "imagefv"))
    }
    @Test fun multipleSigningAuthoritiesUseMaximumDeclaredCounter() {
        val result = AblRollbackInspector.Result.Parsed(AblRollbackInspector.Format.SIGNED_ELF,
            listOf(AblRollbackInspector.Counter(AblRollbackInspector.Authority.OEM, 0, "OEM metadata", 4),
                AblRollbackInspector.Counter(AblRollbackInspector.Authority.QTI, 2, "QTI metadata", 8)), emptyList())
        assertEquals(2L, OtaPolicy.arb(result).value)
    }
    @Test fun requestRoundTripIncludesEverySourceAndMode() {
        for (source in OtaEfispSource.entries) for (abl in OtaAblSource.entries) {
            val request = OtaRequest(ablSource = abl, efispSource = source, preset = OtaPreset.CUSTOM)
            assertEquals(request, OtaRequest.decode(request.encode()))
        }
        assertEquals(OtaEfispSource.OTHER_SLOT_ABL, OtaRequest().efispSource)
        assertEquals(OtaAblSource.BUILTIN, OtaRequest().ablSource)
        assertEquals(OtaPreset.EXPERIMENTAL, OtaRequest().preset)
        assertEquals(dev.honor.bootforge.core.EfispPatchMode.FAKE_LOCK, OtaRequest().patchMode)
    }
}
