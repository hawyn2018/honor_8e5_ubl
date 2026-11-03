/* SPDX-License-Identifier: MIT
 * Minimal AArch64 UEFI ABI declarations. Offsets are standard ABI, not model
 * addresses. Roles match MdePkg UefiSpec.h/LoadedImage.h/MemoryAttribute.h.
 */
#ifndef HNU_KBL_EFI_TYPES_H
#define HNU_KBL_EFI_TYPES_H
#include <stddef.h>
#include <stdint.h>
typedef uint64_t kefi_status;
typedef void *kefi_handle;
struct kefi_guid { uint32_t a;uint16_t b,c;uint8_t d[8]; };
struct kefi_header { uint64_t signature;uint32_t revision,bytes,crc32,reserved; };
struct kefi_system_table;
struct kefi_loaded_image;
#define KEFI_ERROR(n) (UINT64_C(0x8000000000000000)|(n))
#define KEFI_SUCCESS UINT64_C(0)
#define KEFI_INVALID KEFI_ERROR(2)
#define KEFI_UNSUPPORTED KEFI_ERROR(3)
#define KEFI_OUT_OF_RESOURCES KEFI_ERROR(9)
#define KEFI_ACCESS_DENIED KEFI_ERROR(15)
#define KEFI_ALREADY_STARTED KEFI_ERROR(20)
#define KEFI_SYSTEM_SIGNATURE UINT64_C(0x5453595320494249)
#define KEFI_BOOT_SIGNATURE UINT64_C(0x56524553544f4f42)
#define KEFI_RP UINT64_C(0x2000)
#define KEFI_XP UINT64_C(0x4000)
#define KEFI_RO UINT64_C(0x20000)
#define KEFI_PAGE 4096u

struct kefi_boot_services {
    struct kefi_header header;
    void *raise_tpl,*restore_tpl;
    kefi_status (*allocate_pages)(unsigned,unsigned,size_t,uint64_t *);
    kefi_status (*free_pages)(uint64_t,size_t);
    void *get_memory_map;
    kefi_status (*allocate_pool)(unsigned,size_t,void **);
    kefi_status (*free_pool)(void *);
    void *create_event,*set_timer,*wait_event,*signal_event,*close_event,*check_event;
    kefi_status (*install_protocol)(kefi_handle *,const struct kefi_guid *,unsigned,void *);
    void *reinstall_protocol;
    kefi_status (*uninstall_protocol)(kefi_handle,const struct kefi_guid *,void *);
    kefi_status (*handle_protocol)(kefi_handle,const struct kefi_guid *,void **);
    void *reserved,*register_notify,*locate_handle,*locate_device_path,*install_configuration_table;
    void *load_image,*start_image,*exit_image,*unload_image,*exit_boot_services;
    void *monotonic,*stall,*watchdog,*connect,*disconnect,*open_protocol,*close_protocol,*protocol_info;
    void *protocols_per_handle,*locate_handle_buffer;
    kefi_status (*locate_protocol)(const struct kefi_guid *,void *,void **);
    void *install_multiple,*uninstall_multiple,*crc,*copy_mem,*set_mem,*create_event_ex;
};
struct kefi_system_table {
    struct kefi_header header;void *vendor;uint32_t firmware_revision;
    kefi_handle console_in_handle;void *console_in;kefi_handle console_out_handle;void *console_out;
    kefi_handle stderr_handle;void *stderr_interface;void *runtime_services;
    struct kefi_boot_services *boot_services;size_t configuration_count;void *configuration_table;
};
struct kefi_loaded_image {
    uint32_t revision;kefi_handle parent;struct kefi_system_table *system_table;
    kefi_handle device;void *file_path,*reserved;uint32_t options_bytes;void *options;
    void *image_base;uint64_t image_bytes;unsigned code_type,data_type;
    kefi_status (*unload)(kefi_handle);
};
struct kefi_memory_attributes {
    kefi_status (*get)(struct kefi_memory_attributes *,uint64_t,uint64_t,uint64_t *);
    kefi_status (*set)(struct kefi_memory_attributes *,uint64_t,uint64_t,uint64_t);
    kefi_status (*clear)(struct kefi_memory_attributes *,uint64_t,uint64_t,uint64_t);
};
#define KEFI_LOADED_GUID {0x5b1b31a1,0x9562,0x11d2,{0x8e,0x3f,0x00,0xa0,0xc9,0x69,0x72,0x3b}}
#define KEFI_ATTRIBUTES_GUID {0xf4560cf6,0x40ec,0x4b4a,{0xa1,0x92,0xbf,0x1d,0x57,0xd0,0xb1,0x89}}
_Static_assert(sizeof(void *)==8,"AArch64 UEFI ABI only");
_Static_assert(offsetof(struct kefi_system_table,runtime_services)==0x58,"runtime table ABI");
_Static_assert(offsetof(struct kefi_system_table,boot_services)==0x60,"boot table ABI");
_Static_assert(offsetof(struct kefi_boot_services,allocate_pool)==0x40,"pool ABI");
_Static_assert(offsetof(struct kefi_boot_services,free_pool)==0x48,"free pool ABI");
_Static_assert(offsetof(struct kefi_boot_services,install_protocol)==0x80,"install ABI");
_Static_assert(offsetof(struct kefi_boot_services,handle_protocol)==0x98,"handle ABI");
_Static_assert(offsetof(struct kefi_boot_services,locate_protocol)==0x140,"locate ABI");
_Static_assert(offsetof(struct kefi_loaded_image,unload)==0x58,"unload ABI");
#endif
