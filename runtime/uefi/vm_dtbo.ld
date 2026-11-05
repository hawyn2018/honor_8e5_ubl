ENTRY(vm_dtbo_select)
SECTIONS {
 . = 0;
 .module : { KEEP(*(.text.vm_dtbo_select)) *(.text .text.*) *(.rodata .rodata.*) }
 /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) }
}
