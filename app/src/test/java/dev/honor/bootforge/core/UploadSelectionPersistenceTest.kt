package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Test

class UploadSelectionPersistenceTest {
    private val previous=mapOf<String,Any?>("efisp-abl_file" to "old.img","efisp-abl_selected" to true)
    private val next=mapOf<String,Any?>("efisp-abl_file" to "new.img","efisp-abl_selected" to true)

    @Test fun failedCommitThatAlreadyChangedMemoryRestoresOnlyTheSameRole() {
        val memory=(previous+mapOf("abl_file" to "other-role.img")).toMutableMap();var calls=0
        val error=assertThrows(IllegalStateException::class.java) {
            UploadSelectionPersistence.publish(previous,next) { values -> memory.putAll(values);++calls!=1 }
        }
        assertEquals(2,calls);assertEquals("old.img",memory["efisp-abl_file"]);assertEquals("other-role.img",memory["abl_file"])
        assertTrue(error.message!!.contains("已恢复此前选择"))
    }
    @Test fun thrownCommitAlsoRestoresTheOldPointersBeforeRethrowing() {
        val memory=previous.toMutableMap();var calls=0
        assertThrows(IllegalStateException::class.java) {
            UploadSelectionPersistence.publish(previous,next) { values ->
                memory.putAll(values);if (++calls==1) throw LinkageError("provider failed");true
            }
        }
        assertEquals(previous,memory);assertEquals(2,calls)
    }
    @Test fun failedPersistentRollbackIsReportedWithoutClaimingSuccess() {
        val memory=previous.toMutableMap()
        val error=assertThrows(IllegalStateException::class.java) {
            UploadSelectionPersistence.publish(previous,next) { values -> memory.putAll(values);false }
        }
        assertEquals(previous,memory);assertFalse(error.message!!.contains("已恢复"));assertTrue(error.message!!.contains("持久化恢复未成功"))
    }
    @Test fun rollbackExceptionIsKeptAndReported() {
        var calls=0
        val error=assertThrows(IllegalStateException::class.java) {
            UploadSelectionPersistence.publish(previous,next) { if (++calls==1) false else throw IllegalStateException("disk unavailable") }
        }
        assertTrue(error.message!!.contains("disk unavailable"));assertEquals(1,error.suppressed.size)
    }
    @Test fun successfulPublishIsNotRolledBack() {
        val memory=previous.toMutableMap();var calls=0
        assertTrue(UploadSelectionPersistence.publish(previous,next) { values -> memory.putAll(values);calls++;true })
        assertEquals(next,memory);assertEquals(1,calls)
    }
    @Test fun firstUploadRollbackRemovesOnlyItsPreviouslyAbsentFields() {
        val old=next.mapValues { null };val memory=mutableMapOf<String,Any?>("abl_file" to "keep.img");var calls=0
        assertThrows(IllegalStateException::class.java) {
            UploadSelectionPersistence.publish(old,next) { values ->
                values.forEach { (key,value) -> if (value==null) memory.remove(key) else memory[key]=value };++calls!=1
            }
        }
        assertEquals(mapOf("abl_file" to "keep.img"),memory)
    }
}
