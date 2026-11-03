#ifndef KBL_EROFS_CONFIG_H
#define KBL_EROFS_CONFIG_H
#define NDEBUG 1
#define HAVE_CONFIG_H 1
#define LZ4_ENABLED 1
#define HAVE_FFS 1
#define __EROFS_PRINT_H
#define erofs_err(...) ((void)0)
#define erofs_warn(...) ((void)0)
#define erofs_info(...) ((void)0)
#define erofs_dbg(...) ((void)0)
#endif
