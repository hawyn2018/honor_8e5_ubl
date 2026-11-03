This module is the native Qualcomm USB Device ABI binding for KBL binary DATA
and FETCH. It calls the existing protocol's Send/HandleEvent/DMA allocation and
free functions. It neither starts nor stops USB and contains no image RVA,
model table, firmware command registration, hardware probe or kernel call.
Firmware semantic facts and parent-dispatch integration remain separate.

The caller must provide a zeroed, persistent, non-moving binding and environment
that outlive the installed clone. `init` validates and copies the callback ABI
without provider calls. `observe` is installed only by explicit KBL start,
before that command's original native final response. It initially has no TX
idle witness and cannot send private DATA. It redirects the proven Fbd protocol
slot to a clone whose Send and HandleEvent monitor, then forward the original
native operations. The clone's other protocol functions are unchanged.

The original start ACK Send and its exact successful EP81 terminal event
(pointer, byte count and status) establish a live idle witness. A subsequent
normal-command RX completion creates a new epoch ticket. RX classification must
use the proven original command buffer and command/download state, not a guessed
device identity. Only the exact FETCH wrapper may call `begin`, which requires
the idle witness, no outstanding TX, a fresh ticket, and the caller's explicit
exact-handler/exclusive-owner check. It consumes that ticket. Timer-delivered
native command dispatch is therefore not assumed to prove TX idle on its own.

Private intermediate DATA and body spans are copied into protocol-owned DMA
buffers and synchronously completed by the existing independent `kbl_usb.c`
sender. The driver limits each body submission to 64 KiB; partial write results
let the fetch loop send the complete announced DATA length without a single
large controller submission. Native Send errors are conservatively treated as possibly submitted:
only an exact terminal notification ends ownership. Failed FreeTransferBuffer
retains its pointer for retry and prevents further DMA allocation or unload.
Timeout/abort does not free pending DMA. A later HandleEvent may reap the exact
terminal event without running or initializing KBL.

Private intermediate terminal events are consumed and never enter the original
native final-TX branch. Other state, RX and OEM events are queued in original
order. The queue holds 32 decoded events; on exhaustion the sender stops before
polling another event and retains the transfer until native replay has drained
space. OEM Context pointers retain their original recipient ownership and are
delivered once, not freed or silently discarded by this module.

The final response uses the existing native Okay/Fail and its persistent ACK
buffer. It queues once and returns. The original event loop receives that final
terminal event and executes its original RX-rearm branch exactly once. The
observer remains installed for later ordinary traffic; these paths do not run
the kernel or private sender. An ambiguous overlapping native TX or foreign TX
completion disables future private sends and retains ownership conservatively,
while ordinary native Send/events still pass through.

`stop_observing` requests restoration of the original Fbd slot. It returns
RETAINED while DMA, native TX, replay events or uncertain ownership remain.
Later native event polling retries cleanup and completes restoration only when
all resources are quiescent. `can_release` must succeed before the caller frees
the binding or unloads its containing code. A driver/runtime OFF is not proof
that its parent USB observer is already releasable.

Required semantic/runtime inputs are the proven shared Fbd protocol slot,
native ACK-buffer slot value, original Okay/Fail wrappers, normal RX
classification, and passive monotonic clock. All relevant native Send and
HandleEvent paths must reload the shared slot. A bypass alias or reentrant
endpoint owner is outside that proof; the observer must not be enabled from a
GUID match alone. An exact handler callback must be implemented from the
resolved parent entry, not used as a made-up TX-idle assertion. No binding with
missing facts is represented as hardware-ready.

`tests/test_native_usb_runner.py` builds actual C logic against an explicit
synthetic provider and compiles/links the freestanding AArch64 modules with no
undefined symbols. The fixture verifies ordinary passthrough, initial lack of
witness, command-vs-download tickets, exact intermediate/final boundaries,
OEM replay ownership, queue exhaustion, pending completion reap, ambiguous Send
errors, deferred free failures, overlapping/foreign TX and deferred detach.
These tests do not exercise a physical USB controller, DMA, actual parent image,
or phone boot. A separate `tests/test_native_usb_tcp.py` builds the actual
observer/sender C binding into a host DLL with a synthetic USB provider. The
stock Fastboot client reads 65,793 bytes through that fixture in 65,536 + 257
byte body spans, with matching SHA256, then issues an ordinary getvar response.
DMA allocation/free pairs and safe observer removal are checked. This is actual
client interoperability against a localhost model, not physical USB/DMA or a
substitute for the binding's real firmware validation.
