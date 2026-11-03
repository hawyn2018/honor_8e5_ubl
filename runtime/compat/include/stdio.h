/* SPDX-License-Identifier: MIT */
#ifndef KBL_COMPAT_STDIO_H
#define KBL_COMPAT_STDIO_H
#include <stddef.h>
#include <stdarg.h>
#include <sys/types.h>
#define EOF (-1)
#define SEEK_SET 0
#define SEEK_CUR 1
#define SEEK_END 2
typedef struct kbl_compat_file FILE;
extern FILE *stdin,*stdout,*stderr;
int printf(const char *,...);int fprintf(FILE *,const char *,...);
int vprintf(const char *,va_list);int vfprintf(FILE *,const char *,va_list);
int snprintf(char *,size_t,const char *,...);int vsnprintf(char *,size_t,const char *,va_list);
int puts(const char *);int putchar(int);int fputs(const char *,FILE *);int fputc(int,FILE *);int fgetc(FILE *);
FILE *fopen(const char *,const char *);int fclose(FILE *);int fflush(FILE *);
size_t fread(void *,size_t,size_t,FILE *);size_t fwrite(const void *,size_t,size_t,FILE *);
int fseek(FILE *,long,int);long ftell(FILE *);int fseeko(FILE *,off_t,int);off_t ftello(FILE *);
void rewind(FILE *);int feof(FILE *);int ferror(FILE *);void clearerr(FILE *);
char *fgets(char *,int,FILE *);int fileno(FILE *);void perror(const char *);
int remove(const char *);
#endif
