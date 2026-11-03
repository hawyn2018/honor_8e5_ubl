/* SPDX-License-Identifier: MIT */
#ifndef HNU_KBL_SERVICE_H
#define HNU_KBL_SERVICE_H
#include <stddef.h>
#include <stdint.h>

#define KBL_SERVICE_ABI 1u
#define KBL_SERVICE_MAGIC UINT64_C(0x31454649534c424b)
#define KBL_SERVICE_GUID_VALUES {0x7f9316a8,0x5c31,0x4b52,{0xad,0xc3,0x28,0xd2,0x66,0x04,0x9d,0xc1}}
#define KBL_LINK_DEFER_INFO UINT64_C(1)

/* These are private ABI identifiers, not source-firmware addresses. */
enum kbl_service_op {
    KBL_S_START=1, KBL_S_IN, KBL_S_OFF, KBL_S_STATUS, KBL_S_COMMAND,
    KBL_S_PUT, KBL_S_POLL, KBL_S_STORE, KBL_S_READ_OPEN, KBL_S_READ_CLOSE,
    KBL_S_FETCH, KBL_S_MAX_FETCH, KBL_S_BLOB_SIZE, KBL_S_BLOB_HAS_SLOT,
    KBL_S_REPLY_HEADER, KBL_S_REPLY_PAGE,
    KBL_S_ADB, /* Explicit ADB opt-in; old operation numbers remain stable. */
    KBL_S_SCREEN_OFF, KBL_S_SCREEN_ON, KBL_S_SCREEN_STATUS
};
enum kbl_service_result { KBL_S_OK=0, KBL_S_ERROR=-1, KBL_S_IO=-2, KBL_S_UNAVAILABLE=-3 };

struct kbl_firmware_link {
    uint32_t abi, bytes;
    void *opaque;
    /* info must wait for its intermediate TX completion. final must preserve
     * native final-TX/RX-rearm semantics; it may queue the original native
     * persistent ACK buffer and return to the original event loop. Callers
     * must not replace this with blind queued Send loops.
     */
    int (*info)(void *, const char *ascii);
    int (*final)(void *, int okay, const char *ascii);
    int (*data)(void *, uint32_t bytes);
    int (*write)(void *, const void *, size_t bytes, size_t *written);
    void (*abort)(void *);
    /* Optional tail: 1 only if no pending transfer, retained DMA buffer or
     * callback can still reference controller-owned memory. If absent after
     * an I/O error, keep the controller loaded rather than guess it is safe.
     */
    int (*quiescent)(void *);
    /* Control-only transport may defer INFO into driver-owned reply pages.
     * Each OEM transaction then sends only one original native final packet;
     * the PC retrieves immutable pages with dedicated getvar requests. This
     * does not replace the binary DATA transport required for real fetch.
     */
    uint64_t flags;
};

struct kbl_service_reply {
    uint32_t bytes;
    uint32_t stopped;       /* Only a fully cleaned, quiescent driver may be unloaded. */
    uint32_t response_sent;
    uint32_t transport_error;
};
/* Immutable, semantically resolved parent facts. All source addresses are RVAs
 * in the actual parent mapping; no model/version address list is accepted.
 * Passing this descriptor does not establish TX-idle. The driver must observe
 * a completed native ACK and a fresh normal command RX before private DATA. */
struct kbl_usb_parent_facts {
    uint32_t abi, bytes;
    uintptr_t parent_base;
    uint64_t protocol_slot_rva, ack_buffer_slot_rva, command_rx_slot_rva;
    uint64_t download_state_byte_rva;
    /* Optional original StartEx descriptor-set RVA. Zero retains ordinary
     * KBL/Fastboot without enabling composite ADB; no prestart hook needed. */
    uint32_t command_state_value, descriptor_set_rva;
    uint64_t native_okay_rva, native_fail_rva;
    uint64_t counter_frequency_rva, counter_ticks_rva;
};
struct kbl_service_protocol {
    uint64_t magic;
    uint32_t abi, bytes;
    int (*request)(struct kbl_service_protocol *, unsigned operation,
                   const unsigned char *argument, size_t argument_bytes,
                   const void *download, size_t download_bytes,
                   const struct kbl_firmware_link *, struct kbl_service_reply *);
    int (*can_unload)(struct kbl_service_protocol *);
    /* Optional tail, owned by this EFI driver's RW memory. Only exact START
     * installs the lightweight observer; EfiMain and normal boot do not. */
    int (*bind_usb)(struct kbl_service_protocol *, const struct kbl_usb_parent_facts *);
};

/* A controller image must be an EFI_BOOT_SERVICE_DRIVER, not an application
 * that is automatically unloaded when StartImage returns. EfiMain records its
 * actual environment and installs this protocol on its own image handle.
 * No runtime, timer, USB, filesystem or hardware initialization occurs there.
 * The parent obtains it with HandleProtocol(returned_image_handle,...), never
 * by a global LocateProtocol that can select an unrelated/stale instance.
 * Full UEFI provider + source-firmware shim binding remains an integration
 * task; this header alone does not register any command or start a driver.
 */
#endif
