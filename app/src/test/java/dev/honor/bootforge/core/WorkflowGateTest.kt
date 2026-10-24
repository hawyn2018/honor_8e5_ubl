package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Test

class WorkflowGateTest {
    private val proofs = listOf(
        WorkflowStage.BackupVerified,
        WorkflowStage.FilesystemAdjusted,
        WorkflowStage.GptAndEfispVerified,
        WorkflowStage.CurrentAblVerified,
        WorkflowStage.PeVerified,
    )

    private fun allVerified(): WorkflowGate =
        proofs.fold(WorkflowGate.startCreate()) { gate, proof -> gate.recordProof(proof) }

    @Test fun everyProofIsRequiredBeforeAnExplicitRebootChoice() {
        var gate = WorkflowGate.startCreate()
        proofs.forEach { proof ->
            assertEquals(proof, gate.expectedProof)
            assertFalse(gate.canChooseReboot)
            gate = gate.recordProof(proof)
            assertEquals(proof, gate.stage)
        }
        assertFalse(gate.canChooseReboot)
        val ready = gate.awaitRebootChoice()
        assertEquals(WorkflowStage.AwaitingRebootChoice, ready.stage)
        assertTrue(ready.canChooseReboot)
        ready.requireRebootChoiceAllowed()
        assertNull(ready.expectedProof)
    }

    @Test fun skippedProofsAndNonProofStatesAreRejected() {
        val initial = WorkflowGate.startCreate()
        assertThrows(IllegalStateException::class.java) {
            initial.recordProof(WorkflowStage.FilesystemAdjusted)
        }
        val backedUp = initial.recordProof(WorkflowStage.BackupVerified)
        assertThrows(IllegalStateException::class.java) {
            backedUp.recordProof(WorkflowStage.GptAndEfispVerified)
        }
        val partitioned = backedUp
            .recordProof(WorkflowStage.FilesystemAdjusted)
            .recordProof(WorkflowStage.GptAndEfispVerified)
        assertThrows(IllegalStateException::class.java) {
            partitioned.recordProof(WorkflowStage.PeVerified)
        }
        assertThrows(IllegalStateException::class.java) {
            allVerified().recordProof(WorkflowStage.AwaitingRebootChoice)
        }
    }

    @Test fun duplicateProofsAndRepeatedCompletionAreRejected() {
        val backedUp = WorkflowGate.startCreate().recordProof(WorkflowStage.BackupVerified)
        assertThrows(IllegalStateException::class.java) {
            backedUp.recordProof(WorkflowStage.BackupVerified)
        }
        val ready = allVerified().awaitRebootChoice()
        assertThrows(IllegalStateException::class.java) { ready.awaitRebootChoice() }
        assertThrows(IllegalStateException::class.java) {
            ready.recordProof(WorkflowStage.PeVerified)
        }
    }

    @Test fun failureBlocksEveryLaterProofAndRebootChoice() {
        val failed = WorkflowGate.startCreate()
            .recordProof(WorkflowStage.BackupVerified)
            .fail("filesystem readback failed")
        proofs.forEach { proof ->
            assertThrows(IllegalStateException::class.java) { failed.recordProof(proof) }
        }
        assertFalse(failed.canChooseReboot)
        assertThrows(IllegalStateException::class.java) { failed.awaitRebootChoice() }
        assertThrows(IllegalStateException::class.java) { failed.requireRebootChoiceAllowed() }
        assertSame(failed, failed.fail("later cleanup failed"))
        assertEquals("filesystem readback failed", failed.failureReason)
    }

    @Test fun ablVerificationDoesNotPermitRebootBeforeTheFinalPeProof() {
        var gate = WorkflowGate.startCreate()
        (listOf<WorkflowStage?>(null) + proofs.dropLast(1)).forEach { proof ->
            if (proof != null) gate = gate.recordProof(proof)
            assertThrows(IllegalStateException::class.java) { gate.awaitRebootChoice() }
            assertThrows(IllegalStateException::class.java) { gate.requireRebootChoiceAllowed() }
        }
        assertEquals(WorkflowStage.CurrentAblVerified, gate.stage)
        assertEquals(WorkflowStage.PeVerified, gate.expectedProof)
        val peFailed = gate.fail("PE readback hash mismatch after ABL write")
        assertFalse(peFailed.canChooseReboot)
        assertThrows(IllegalStateException::class.java) { peFailed.awaitRebootChoice() }
        assertThrows(IllegalStateException::class.java) { peFailed.requireRebootChoiceAllowed() }
        assertThrows(IllegalStateException::class.java) {
            peFailed.recordProof(WorkflowStage.PeVerified)
        }
        gate = gate.recordProof(WorkflowStage.PeVerified)
        assertThrows(IllegalStateException::class.java) { gate.requireRebootChoiceAllowed() }
    }

    @Test fun transitionsPreserveEarlierValuesAndLateFailureRevokesPermission() {
        val initial = WorkflowGate.startCreate()
        val backedUp = initial.recordProof(WorkflowStage.BackupVerified)
        assertEquals(WorkflowStage.AwaitingBackup, initial.stage)
        assertEquals(WorkflowStage.BackupVerified, backedUp.stage)
        val ready = allVerified().awaitRebootChoice()
        val failed = ready.fail("final device identity changed")
        assertTrue(ready.canChooseReboot)
        assertFalse(failed.canChooseReboot)
        assertEquals(WorkflowStage.Failed, failed.stage)
        assertThrows(IllegalStateException::class.java) { failed.requireRebootChoiceAllowed() }
        assertThrows(IllegalArgumentException::class.java) { initial.fail(" ") }
    }
}
