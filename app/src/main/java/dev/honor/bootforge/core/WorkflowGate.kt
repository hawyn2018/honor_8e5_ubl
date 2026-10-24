package dev.honor.bootforge.core

/** Verified milestones for creating efisp. Existing-partition reuse has a separate workflow. */
enum class WorkflowStage {
    AwaitingBackup,
    BackupVerified,
    FilesystemAdjusted,
    GptAndEfispVerified,
    CurrentAblVerified,
    PeVerified,
    AwaitingRebootChoice,
    Failed,
}

/**
 * An immutable ordering gate, not a verifier or an executor. The worker must
 * obtain each proof from the completed operation before recording it here.
 * This type never issues a reboot; the final state only permits a user choice.
 * A private constructor deliberately avoids a public data-class copy method
 * that could manufacture a later milestone without recording earlier proofs.
 */
class WorkflowGate private constructor(
    val stage: WorkflowStage,
    val failureReason: String?,
) {
    val expectedProof: WorkflowStage?
        get() = when (stage) {
            WorkflowStage.AwaitingBackup -> WorkflowStage.BackupVerified
            WorkflowStage.BackupVerified -> WorkflowStage.FilesystemAdjusted
            WorkflowStage.FilesystemAdjusted -> WorkflowStage.GptAndEfispVerified
            WorkflowStage.GptAndEfispVerified -> WorkflowStage.CurrentAblVerified
            WorkflowStage.CurrentAblVerified -> WorkflowStage.PeVerified
            WorkflowStage.PeVerified,
            WorkflowStage.AwaitingRebootChoice,
            WorkflowStage.Failed -> null
        }

    val canChooseReboot: Boolean
        get() = stage == WorkflowStage.AwaitingRebootChoice

    /** Rejects skipped, repeated, terminal, and non-proof milestones. */
    fun recordProof(proof: WorkflowStage): WorkflowGate {
        check(stage != WorkflowStage.Failed) { "Workflow failed: $failureReason" }
        val expected = expectedProof
        check(expected != null && proof == expected) {
            "Expected proof $expected at $stage; received $proof"
        }
        return WorkflowGate(proof, null)
    }

    /** Explicitly closes verification before the UI can present reboot choices. */
    fun awaitRebootChoice(): WorkflowGate {
        check(stage == WorkflowStage.PeVerified) {
            "All creation proofs are required before choosing a reboot; current stage is $stage"
        }
        return WorkflowGate(WorkflowStage.AwaitingRebootChoice, null)
    }

    /** A failure is terminal; retaining the first reason preserves the root cause. */
    fun fail(reason: String): WorkflowGate {
        require(reason.isNotBlank()) { "A failure reason is required" }
        return if (stage == WorkflowStage.Failed) this
        else WorkflowGate(WorkflowStage.Failed, reason)
    }

    /** The caller must still require an explicit user action before any reboot. */
    fun requireRebootChoiceAllowed() {
        check(canChooseReboot) { "Reboot choice is blocked at $stage" }
    }

    companion object {
        fun startCreate(): WorkflowGate = WorkflowGate(WorkflowStage.AwaitingBackup, null)
    }
}
