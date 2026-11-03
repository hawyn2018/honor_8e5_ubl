/* Explicit page-separated ELF profile, no interpreter or external linker. */
PHDRS {
    headers PT_LOAD FLAGS(4) FILEHDR PHDRS;
    note PT_NOTE FLAGS(4);
    text PT_LOAD FLAGS(5);
    rodata PT_LOAD FLAGS(4);
    data PT_LOAD FLAGS(6);
    dynamic PT_DYNAMIC FLAGS(6);
    stack PT_GNU_STACK FLAGS(6);
}
SECTIONS {
    . = SIZEOF_HEADERS;
    .note.kbl : { KEEP(*(.note.kbl)) } :headers :note
    . = ALIGN(4096);
    .text : { *(.text.kbl_main) *(.text .text.*) } :text
    . = ALIGN(4096);
    .rodata : { *(.rodata .rodata.*) } :rodata
    .dynsym : { *(.dynsym) } :rodata
    .dynstr : { *(.dynstr) } :rodata
    .hash : { *(.hash) } :rodata
    .gnu.hash : { *(.gnu.hash) } :rodata
    .rela.dyn : { *(.rela.dyn) } :rodata
    . = ALIGN(4096);
    .data : { *(.data .data.*) *(.got .got.*) } :data
    .dynamic : { *(.dynamic) } :data :dynamic
    .bss (NOLOAD) : { *(.bss .bss.*) *(COMMON) } :data
    /DISCARD/ : { *(.comment) *(.eh_frame*) *(.note.GNU-stack) }
}
