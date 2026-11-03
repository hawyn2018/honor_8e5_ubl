ENTRY(kbl_parent_dispatch)
SECTIONS {
 . = 0;
 .module : { KEEP(*(.text.kbl_entry)) *(.text .text.*) *(.rodata .rodata.*) }
 /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) }
}
