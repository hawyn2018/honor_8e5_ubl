package dev.honor.bootforge.core

import dev.honor.bootforge.MagicRootAdapter
import org.junit.Assert.assertEquals
import org.junit.Test

/** run.log 结束标志解析：只认整行 DONE 与行首 FAIL=，避免中间标志误判。 */
class MagicRootAdapterTest {
    @Test fun plainDoneLineFinishes() {
        assertEquals(MagicRootAdapter.FINISH_DONE, MagicRootAdapter.finishKind("MODULE_LOADED\nDONE\n"))
        assertEquals(MagicRootAdapter.FINISH_DONE, MagicRootAdapter.finishKind("DONE"))
        assertEquals(MagicRootAdapter.FINISH_DONE, MagicRootAdapter.finishKind("  DONE  \n"))
    }

    @Test fun intermediateMarkersNeverCountAsDone() {
        assertEquals(MagicRootAdapter.FINISH_NONE, MagicRootAdapter.finishKind("PHASE2_DONE"))
        assertEquals(MagicRootAdapter.FINISH_NONE, MagicRootAdapter.finishKind("LAYOUT_DONE\nCAPBND_OK"))
        assertEquals(MagicRootAdapter.FINISH_NONE, MagicRootAdapter.finishKind("echo DONE"))
        assertEquals(MagicRootAdapter.FINISH_NONE, MagicRootAdapter.finishKind("DONE_ALL"))
        assertEquals(MagicRootAdapter.FINISH_NONE, MagicRootAdapter.finishKind(""))
    }

    @Test fun failPrefixWins() {
        assertEquals(MagicRootAdapter.FINISH_FAIL, MagicRootAdapter.finishKind("== 1. KASLR ==\nFAIL=kaslr"))
        assertEquals(MagicRootAdapter.FINISH_FAIL, MagicRootAdapter.finishKind("FAIL=module\n"))
        // 同一批日志里 FAIL 与 DONE 同时出现时，先出现的 DONE 行优先（与行序一致）。
        assertEquals(MagicRootAdapter.FINISH_DONE, MagicRootAdapter.finishKind("DONE\nFAIL=late"))
        assertEquals(MagicRootAdapter.FINISH_FAIL, MagicRootAdapter.finishKind("FAIL=grant\nDONE"))
    }
}
