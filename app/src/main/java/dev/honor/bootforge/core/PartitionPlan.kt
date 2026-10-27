package dev.honor.bootforge.core

import java.util.Locale
import java.util.UUID

/** GPT values are logical LBAs, not the 512-byte sectors reported by sysfs. */
data class GptPartition(
    val number: Int,
    val startLba: Long,
    val endLba: Long,
    val name: String,
    val typeGuid: String,
    val uniqueGuid: String,
    val attributes: String,
)

data class GptSnapshot(
    val diskGuid: String,
    val logicalSectorBytes: Int,
    val firstUsable: Long,
    val lastUsable: Long,
    val entryCapacity: Int,
    val partitions: List<GptPartition>,
)

/** A geometry proposal only. It does not inspect or resize a filesystem. */
sealed interface EfispPlan {
    val originalSnapshot: GptSnapshot
    val resultSnapshot: GptSnapshot
    val userdata: GptPartition
    val efisp: GptPartition
}

data class CreateEfispPlan(
    override val originalSnapshot: GptSnapshot,
    override val resultSnapshot: GptSnapshot,
    val originalUserdata: GptPartition,
    override val userdata: GptPartition,
    override val efisp: GptPartition,
    /** Signed GPT capacity reduction: negative when userdata grows. This does not resize a filesystem. */
    val shrinkBytes: Long,
) : EfispPlan {
    val userdataSizeChangeBytes: Long get() = Math.negateExact(shrinkBytes)
    val efispBytes: Long get() = Math.multiplyExact(
        Math.addExact(Math.subtractExact(efisp.endLba, efisp.startLba), 1L),
        resultSnapshot.logicalSectorBytes.toLong(),
    )
}

data class ReusePlan(
    override val originalSnapshot: GptSnapshot,
    override val resultSnapshot: GptSnapshot,
    override val userdata: GptPartition,
    override val efisp: GptPartition,
) : EfispPlan

class PartitionPlanningException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** Shared GPT metadata observed on the three devices; identity and geometry remain per-device. */
object EfispProfile {
    const val ID = "efisp-common-v1"
    const val LABEL = "efisp"
    const val TYPE_CODE = "EF00"
    const val TYPE_GUID = "C12A7328-F81F-11D2-BA4B-00A0C93EC93B"
    // HONOR has no flags; the other two set bit 60. Their shared flag intersection is zero.
    const val NEW_ATTRIBUTES = "0000000000000000"
}

object PartitionPlanner {
    const val EFISP_BYTES: Long = 67_108_864L
    const val EFI_SYSTEM_TYPE_GUID = EfispProfile.TYPE_GUID
    private const val ZERO_GUID = "00000000-0000-0000-0000-000000000000"
    private val guidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val attributesPattern = Regex("[0-9a-fA-F]{16}")

    /** Geometry model; execution supplies the directly measured filesystem size.
     * Without it, preserve userdata when its following free extent holds 64 MiB;
     * otherwise propose only the reduction needed to reach that capacity.
     * Explicit filesystem capacity may also grow or preserve the GPT userdata
     * extent. Every other entry and userdata's starting address stay unchanged.
     * This model does not inspect, shrink or grow a filesystem. */
    fun plan(
        snapshot: GptSnapshot,
        efispUniqueGuid: String = UUID.randomUUID().toString().uppercase(Locale.ROOT),
        remainingUserdataBytes: Long? = null,
        minimumImageBytes: Long = 1L,
    ): EfispPlan = checkedGeometry {
        val original = snapshot.copy(partitions = snapshot.partitions.toList())
        validate(original)
        val userdata = original.partitions.singleOrNull { it.name == "userdata" }
            ?: fail("exactly one partition named userdata is required")
        if (minimumImageBytes <= 0) fail("image must have positive capacity")
        val existing = original.partitions.singleOrNull { it.name == EfispProfile.LABEL }
        if (existing != null) {
            if (partitionBytes(existing, original.logicalSectorBytes) < minimumImageBytes)
                fail("existing efisp does not fit the image")
            if (remainingUserdataBytes != null && (remainingUserdataBytes <= 0 ||
                    remainingUserdataBytes % original.logicalSectorBytes != 0L ||
                    remainingUserdataBytes > partitionBytes(userdata, original.logicalSectorBytes)))
                fail("filesystem extends beyond userdata")
            if (!existing.typeGuid.equals(EFI_SYSTEM_TYPE_GUID, ignoreCase = true)) {
                fail("existing efisp must have the EFI System type GUID")
            }
            return@checkedGeometry ReusePlan(original, original, userdata, existing)
        }

        validateGuid(efispUniqueGuid, "new efisp unique GUID")
        if (original.partitions.any { it.uniqueGuid.equals(efispUniqueGuid, ignoreCase = true) }) {
            fail("new efisp unique GUID duplicates an existing partition")
        }
        val usedNumbers = original.partitions.mapTo(HashSet()) { it.number }
        // At most N + 1 candidates are needed for N occupied entries. Do not
        // enumerate an attacker-controlled entryCapacity or overflow an Int.
        val candidates = Math.addExact(original.partitions.size.toLong(), 1L)
            .coerceAtMost(original.entryCapacity.toLong())
        var newNumber = 1L
        while (newNumber <= candidates && newNumber.toInt() in usedNumbers) newNumber++
        if (newNumber > candidates) fail("GPT has no unused partition entry")

        val oldBytes = partitionBytes(userdata, original.logicalSectorBytes)
        // Only the physically adjacent free range belongs to this plan. A hole
        // beyond another partition cannot be borrowed, regardless of entry order.
        val nextStart = original.partitions.asSequence()
            .filter { it.number != userdata.number && it.startLba > userdata.endLba }
            .minOfOrNull { it.startLba }
        val contiguousEnd = nextStart?.let { Math.subtractExact(it, 1L) } ?: original.lastUsable
        val contiguousBytes = Math.multiplyExact(
            Math.addExact(Math.subtractExact(contiguousEnd, userdata.startLba), 1L),
            original.logicalSectorBytes.toLong(),
        )
        val newBytes = remainingUserdataBytes ?: minOf(oldBytes,
            Math.subtractExact(contiguousBytes, EFISP_BYTES))
        if (newBytes <= 0 || newBytes >= contiguousBytes || newBytes % original.logicalSectorBytes != 0L)
            fail("filesystem boundary must be aligned, positive and leave a contiguous efisp extent")
        val shrinkBytes = Math.subtractExact(oldBytes, newBytes)
        val efispBytes = Math.subtractExact(contiguousBytes, newBytes)
        if (efispBytes < minimumImageBytes) fail("contiguous userdata tail does not fit the image")
        val newEnd = Math.subtractExact(Math.addExact(userdata.startLba,
            newBytes / original.logicalSectorBytes), 1L)
        val resizedUserdata = userdata.copy(endLba = newEnd)
        val efisp = GptPartition(
            number = newNumber.toInt(),
            startLba = Math.addExact(newEnd, 1L),
            endLba = contiguousEnd,
            name = EfispProfile.LABEL,
            typeGuid = EfispProfile.TYPE_GUID,
            uniqueGuid = efispUniqueGuid,
            attributes = EfispProfile.NEW_ATTRIBUTES,
        )
        val result = original.copy(partitions = original.partitions.map {
            if (it.number == userdata.number) resizedUserdata else it
        } + efisp)
        validate(result)
        if (Math.subtractExact(
                partitionBytes(userdata, original.logicalSectorBytes),
                partitionBytes(resizedUserdata, original.logicalSectorBytes),
            ) != shrinkBytes || partitionBytes(efisp, original.logicalSectorBytes) != efispBytes
        ) {
            fail("partition size calculation differs from the measured filesystem boundary")
        }
        CreateEfispPlan(original, result, userdata, resizedUserdata, efisp, shrinkBytes)
    }

    private fun validate(snapshot: GptSnapshot) {
        validateGuid(snapshot.diskGuid, "disk GUID")
        if (snapshot.logicalSectorBytes != 512 && snapshot.logicalSectorBytes != 4096) {
            fail("logical sector size must be explicitly 512 or 4096 bytes")
        }
        if (snapshot.firstUsable <= 0 || snapshot.lastUsable < snapshot.firstUsable) {
            fail("invalid usable LBA range")
        }
        // Byte addresses and exclusive endpoints must fit signed 64-bit values.
        Math.multiplyExact(Math.addExact(snapshot.lastUsable, 1L), snapshot.logicalSectorBytes.toLong())
        if (snapshot.entryCapacity <= 0 || snapshot.partitions.size > snapshot.entryCapacity) {
            fail("invalid GPT entry capacity")
        }
        val numbers = HashSet<Int>()
        val criticalNames = HashSet<String>()
        val identities = HashSet<String>()
        snapshot.partitions.forEach { partition ->
            if (partition.number !in 1..snapshot.entryCapacity || !numbers.add(partition.number)) {
                fail("partition numbers must be unique and within GPT entry capacity")
            }
            // GPT labels hold at most 36 UTF-16 code units. Unrelated entries
            // may have empty or repeated labels and are preserved by number.
            if (partition.name.length > 36 || '\u0000' in partition.name) {
                fail("partition names must fit 36 UTF-16 code units and contain no NUL")
            }
            if (partition.name in setOf("userdata", EfispProfile.LABEL) && !criticalNames.add(partition.name)) {
                fail("userdata and efisp partition names must be unambiguous")
            }
            validateGuid(partition.typeGuid, "partition type GUID")
            validateGuid(partition.uniqueGuid, "partition unique GUID")
            if (!identities.add(partition.uniqueGuid.uppercase(Locale.ROOT))) {
                fail("partition unique GUIDs must be unique")
            }
            if (!attributesPattern.matches(partition.attributes)) {
                fail("partition attributes must contain exactly 16 hexadecimal digits")
            }
            if (partition.startLba < snapshot.firstUsable || partition.endLba < partition.startLba ||
                partition.endLba > snapshot.lastUsable
            ) {
                fail("partition lies outside the usable range or has invalid endpoints")
            }
            partitionBytes(partition, snapshot.logicalSectorBytes)
        }
        val sorted = snapshot.partitions.sortedBy { it.startLba }
        for (i in 1 until sorted.size) {
            if (sorted[i].startLba <= sorted[i - 1].endLba) fail("GPT partitions overlap")
        }
    }

    private fun validateGuid(value: String, label: String) {
        if (!guidPattern.matches(value) || value.equals(ZERO_GUID, ignoreCase = true)) {
            fail("$label must be a nonzero canonical-format GUID")
        }
    }

    private fun partitionBytes(partition: GptPartition, sectorBytes: Int): Long =
        Math.multiplyExact(
            Math.addExact(Math.subtractExact(partition.endLba, partition.startLba), 1L),
            sectorBytes.toLong(),
        )

    private inline fun <T> checkedGeometry(operation: () -> T): T = try {
        operation()
    } catch (error: ArithmeticException) {
        throw PartitionPlanningException("GPT geometry exceeds checked signed 64-bit arithmetic", error)
    }

    private fun fail(message: String): Nothing = throw PartitionPlanningException(message)
}
