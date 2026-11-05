package net.hawyn.ablefisp.patch

import net.hawyn.ablefisp.api.AblEfisp
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

class VmDtboCompatTest {
    private val root=generateSequence(File(System.getProperty("user.dir")).absoluteFile){it.parentFile}
        .firstOrNull { File(it,"analysis/downgrade-fastboot-20261104/qtvm-selection-dataflow.json").isFile }
    private fun assertFinalReport(result:PatchResult) {
        for(patch in result.patches) {
            val after=patch.after.replace(" ","").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            assertTrue(patch.name,result.output.matches(patch.fileOffset,after))
        }
    }
    @Test fun firmwareCompatibilityAndTextRoundtrip() {
        assumeNotNull(root);val fixtureRoot=requireNotNull(root)
        val inventory=File(fixtureRoot,"repairs/hnubl-3.0.2-kbl-clearstart-20261104/input-inventory.json").readText()
        val sources=Regex("\"path\": \"([^\"]+)\"").findAll(inventory).map { File(it.groupValues[1].replace("\\\\","\\")) }.toList()
        val seen=hashSetOf<String>();var count=0;val report=arrayListOf<String>()
        val expansionOnly=System.getProperty("vm.textExpansionOnly")=="true"
        val output=File(System.getProperty("user.dir"),if(expansionOnly)"build/vm-dtbo-text-expansion-fixtures" else "build/vm-dtbo-fixtures").apply{mkdirs()}
        for(path in sources) {
            val source=path.readBytes()
            if(expansionOnly&&source.sha256().take(8) !in setOf("509e8f96","8caf3c88"))continue
            if(!seen.add(source.sha256()))continue
            count++
            val pe=FirmwareExtractor.extract(source).output
            File(output,source.sha256().take(8)+"-native.pe").writeBytes(pe)
            try {
                val facts=VmDtboCompatFacts.analyze(PeImage.parse(pe))
                val direct=VmDtboCompatPatcher.patch(pe)
                assertNotNull(direct.facts);assertFalse(pe.contentEquals(direct.bytes))
                assertArrayEquals(pe,VmDtboCompatPatcher.remove(direct.bytes))
                assertArrayEquals(direct.bytes,VmDtboCompatPatcher.patch(direct.bytes).bytes)
                val name=source.sha256().take(8)
                File(output,"$name.img").writeBytes(direct.bytes)
                File(output,"$name-facts.txt").writeText(listOf(facts.toString(),"rx="+PeImage.parse(direct.bytes).sections.last().rva).joinToString("\n"))
                if(System.getProperty("vm.factsOnly")=="true") {
                    report+="PASS ${source.sha256()} $path $facts";continue
                }
                val first=AblEfisp.patchWithKbl(source,PatchMode.FREEDOM_FAKE_LOCK)
                assertFinalReport(first)
                val scan=AblEfisp.scanText(first.output)
                assertEquals(1,scan.entries.count{it.id=="fastboot-unlocked"})
                // Exact application label forces .texttxt expansion on the
                // built-in family, exhausting the original final NT-table slot.
                val label="device freedom (github hawyn2018)"
                val edited=AblEfisp.editText(first.output,mapOf("fastboot-unlocked" to label),false)
                assertEquals(label,AblEfisp.scanText(edited.output).entries.single{it.id=="fastboot-unlocked"}.text)
                val editedVm=KblPatcher.removeAdapter(FastbootRecoveryPatcher.remove(edited.output))
                val editedNative=VmDtboCompatPatcher.remove(editedVm)
                assertFalse("$path VM layer must survive final text customization",editedVm.contentEquals(editedNative))
                assertNotNull(VmDtboCompatFacts.analyze(PeImage.parse(editedNative)))
                assertArrayEquals("$path final VM layer must be canonical",editedVm,VmDtboCompatPatcher.patch(editedNative).bytes)
                val finalVmPe=PeImage.parse(editedVm);val finalNativePe=PeImage.parse(editedNative)
                assertEquals(finalNativePe.sections,finalVmPe.sections.take(finalNativePe.sections.size))
                assertEquals(finalNativePe.headersSize,finalVmPe.headersSize)
                File(output,"$name-text-vm.img").writeBytes(editedVm)
                val finalFacts=VmDtboCompatFacts.analyze(finalNativePe)
                File(output,"$name-text-vm-facts.txt").writeText(listOf(finalFacts.toString(),"rx="+finalVmPe.sections.last().rva).joinToString("\n"))
                val second=AblEfisp.patchWithKbl(edited.output,PatchMode.FREEDOM_FAKE_LOCK)
                assertFinalReport(second)
                assertArrayEquals("$path reimport",edited.output,second.output)
                assertTrue(second.alreadyPatched)
                report+="PASS ${source.sha256()} $path $facts"
            }catch(e:Exception) { report+="FAIL ${source.sha256()} $path ${e.message}";e.printStackTrace() }
        }
        File(output,"compatibility.txt").writeText(report.joinToString("\n"));report.forEach(::println)
        assertEquals(if(expansionOnly)2 else 8,count);assertTrue(report.filter{it.startsWith("FAIL")}.joinToString("\n"),report.none{it.startsWith("FAIL")})
    }
    @Test fun missingOptionalInterfaceDoesNotGuess(){
        assumeNotNull(root)
        val path=File(requireNotNull(root),"analysis/downgrade-fastboot-20261104/efisp-identity/deviceEfisp.pe")
        val original=path.readBytes();val image=PeImage.parse(original)
        val reference=A64.references(image).single { image.ascii(it.address)?.startsWith("Find TVM - %d & OEMVM - %d")==true }
        val at=requireNotNull(image.rvaToOffset(reference.address));val bad=original.copyOf();bad[at]='X'.code.toByte()
        val skipped=VmDtboCompatPatcher.patch(bad)
        assertArrayEquals(bad,skipped.bytes);assertTrue(skipped.notes.single().contains("未完整匹配"))
    }
    @Test fun textCustomizationRejectsLossOfAnExistingVmLayer() {
        assumeNotNull(root)
        val original=AblEfisp.extract(File(requireNotNull(root),"abl.elf").readBytes()).output
        val pe=original.u32(0x3c).toInt()
        // Keep the native DOS/NT headers and its last free section slot. Occupy
        // only the otherwise unused DOS padding, so relocating a later NT table
        // has no proved zero region. Initial VM fits; text then consumes its slot.
        val occupied=original.copyOf().also { it.fill(0xa5.toByte(),64,pe) }
        val first=AblEfisp.patch(occupied,PatchMode.FREEDOM_FAKE_LOCK)
        assertFalse(first.output.contentEquals(VmDtboCompatPatcher.remove(first.output)))
        val saved=first.output.copyOf()
        val error=assertThrows(PatchException::class.java) {
            AblEfisp.editText(first.output,mapOf("fastboot-unlocked" to "device freedom (github hawyn2018)"),false)
        }
        assertTrue(error.message.orEmpty(),error.message.orEmpty().contains("文字定制后VM启动前选择接口未完整重建"))
        assertArrayEquals(saved,first.output)
    }
}
