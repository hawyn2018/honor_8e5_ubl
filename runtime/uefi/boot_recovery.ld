ENTRY(br_entry)
SECTIONS {
 . = 0;
 .module : {
   KEEP(*(.text.br_entry))
   *(.text .text.*)
   *(.rodata .rodata.*)
   KEEP(*(.br.config))
 }
 /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) }
}
