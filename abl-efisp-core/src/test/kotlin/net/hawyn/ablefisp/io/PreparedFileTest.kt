package net.hawyn.ablefisp.io

import net.hawyn.ablefisp.api.AblEfisp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

class PreparedFileTest {
    @get:Rule val directory = TemporaryFolder()

    private fun pe(certificate: Boolean = false): ByteArray {
        val bytes = ByteArray(if (certificate) 0x680 else 0x600)
        fun word(at: Int, value: Int) { repeat(4) { bytes[at + it] = (value ushr (it * 8)).toByte() } }
        bytes[0] = 0x4d; bytes[1] = 0x5a
        word(0x3c, 0x80); word(0x80, 0x4550); word(0x84, 0x0001aa64)
        word(0x94, 0x202200f0); word(0x98, 0x20b); word(0xa8, 0x1000)
        word(0xb8, 0x1000); word(0xbc, 0x200); word(0xd0, 0x2000); word(0xd4, 0x200)
        word(0xdc, 10); word(0x104, 16)
        ".text".toByteArray().copyInto(bytes, 0x188)
        word(0x190, 0x400); word(0x194, 0x1000); word(0x198, 0x400)
        word(0x19c, 0x200); word(0x1ac, 0x60000020); word(0x200, 0xd65f03c0.toInt())
        if (certificate) {
            word(0x128, 0x600); word(0x12c, 0x80)
            for (i in 0x600 until 0x680) bytes[i] = (i and 0xff).toByte()
        }
        return bytes
    }

    private fun file(name: String, bytes: ByteArray, length: Long = bytes.size.toLong()): File =
        directory.newFile(name).apply { RandomAccessFile(this, "rw").use { it.write(bytes); it.setLength(length) } }

    @Test fun actualNinetySevenSectionTableRetainsStreamingPeExtent() {
        val original=pe();val image=ByteArray(0x2400)
        original.copyInto(image,0,0,0x200);original.copyInto(image,0x2000,0x200,0x600)
        fun word(at:Int,value:Int){repeat(4){image[at+it]=(value ushr (it*8)).toByte()}}
        word(0x84,(97 shl 16) or 0xaa64);word(0xd4,0x2000);word(0xa8,0x3000);word(0xd0,0x4000)
        word(0x194,0x3000);word(0x19c,0x2000)
        for(i in 1 until 97)".empty".toByteArray().copyInto(image,0x188+i*40)
        val source=file("many-sections.pe",image,1024L*1024)
        val prepared=ImageIo.prepareFile(source)
        assertArrayEquals(image,prepared.bytes)
        assertEquals(1024L*1024-image.size,prepared.trimmedPaddingBytes)
        assertArrayEquals(image,AblEfisp.extract(prepared.bytes).output)
    }

    @Test fun fullPartitionInputRetainsOnlyValidatedPeAndKeepsCompleteSourceDigest() {
        val image = pe()
        val source = file("efisp.img", image, 68L * 1024 * 1024)
        val fullHash = source.inputStream().use(ImageIo::sha256)
        val prepared = ImageIo.prepareFile(source)
        assertEquals(68L * 1024 * 1024, prepared.sourceBytes)
        assertEquals(fullHash, prepared.sourceSha256)
        assertEquals(ImageIo.sha256(image), prepared.analysisSha256)
        assertEquals(source.length() - image.size, prepared.trimmedPaddingBytes)
        assertArrayEquals(image, prepared.bytes)
        assertArrayEquals(AblEfisp.extract(image).output, AblEfisp.extract(prepared.bytes).output)
        assertEquals(fullHash, source.inputStream().use(ImageIo::sha256))
    }

    @Test fun declaredCertificateAndErasedFlashPaddingArePreservedSeparately() {
        val image = pe(certificate = true)
        val source = file("certificate.pe", image, 8192)
        RandomAccessFile(source, "rw").use { it.seek(image.size.toLong()); it.write(ByteArray(8192 - image.size) { 0xff.toByte() }) }
        val prepared = ImageIo.prepareFile(source)
        assertArrayEquals(image, prepared.bytes)
        assertEquals(8192L - image.size, prepared.trimmedPaddingBytes)
        assertEquals(source.inputStream().use(ImageIo::sha256), prepared.sourceSha256)
    }

    @Test fun opaqueOverlayKeepsOriginalExtractorInputInsteadOfBeingTreatedAsPadding() {
        val image = pe()
        val source = file("overlay.pe", image + "opaque manufacturer metadata".toByteArray() + ByteArray(2048))
        val saved = source.readBytes()
        val prepared = ImageIo.prepareFile(source)
        assertArrayEquals(saved, prepared.bytes)
        assertEquals(0L, prepared.trimmedPaddingBytes)
        assertEquals(prepared.sourceSha256, prepared.analysisSha256)
        assertArrayEquals(AblEfisp.extract(saved).output, AblEfisp.extract(prepared.bytes).output)
    }

    @Test fun unknownContainerKeepsBytesAndSourceHashForOriginalExtractor() {
        val bytes = byteArrayOf(0x7f, 0x45, 0x4c, 0x46) + ByteArray(4096) { (it and 0xff).toByte() }
        val prepared = ImageIo.prepareFile(file("abl.elf", bytes))
        assertArrayEquals(bytes, prepared.bytes)
        assertEquals(0L, prepared.trimmedPaddingBytes)
        assertEquals(ImageIo.sha256(bytes), prepared.sourceSha256)
    }

    @Test fun invalidPeExtentDoesNotSilentlyTruncateInputBeforeExistingValidation() {
        val bytes = pe()
        repeat(4) { bytes[0x19c + it] = (0x20000 ushr (it * 8)).toByte() }
        val prepared = ImageIo.prepareFile(file("bad-range.pe", bytes))
        assertArrayEquals(bytes, prepared.bytes)
        assertEquals(0L, prepared.trimmedPaddingBytes)
        assertThrows(Exception::class.java) { AblEfisp.extract(prepared.bytes) }
    }

    @Test fun streamDigestHandlesShortAndZeroReadsWithoutWholeContentAllocation() {
        val bytes = ByteArray(4097) { (it * 37).toByte() }
        val input = object : InputStream() {
            var at = 0; var zero = true
            override fun read(): Int = if (at == bytes.size) -1 else bytes[at++].toInt() and 0xff
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                zero = !zero
                if (!zero) return 0
                if (at == bytes.size) return -1
                val count = minOf(13, bytes.size - at, len)
                bytes.copyInto(b, off, at, at + count); at += count
                return count
            }
        }
        assertEquals(ImageIo.sha256(bytes), ImageIo.sha256(input))
    }
}
