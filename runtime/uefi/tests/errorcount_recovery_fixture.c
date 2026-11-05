/* SPDX-License-Identifier: GPL-3.0-only
 * Host fixture links the real counter-reset and recovery state transitions.
 */
#include "../boot_recovery.h"
__declspec(dllexport)
void errorcount_test_note_failure(BRState *state, const BRConfig *config) {
    br_note_failure(state, config);
}
