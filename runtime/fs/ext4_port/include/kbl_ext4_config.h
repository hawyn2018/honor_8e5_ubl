/* SPDX-License-Identifier: MIT */
#ifndef KBL_EXT4_CONFIG_H
#define KBL_EXT4_CONFIG_H
#include <stddef.h>
#include <stdint.h>
#define CONFIG_USE_DEFAULT_CFG 1
#define CONFIG_USE_USER_MALLOC 1
#define CONFIG_HAVE_OWN_ERRNO 1
#define CONFIG_HAVE_OWN_OFLAGS 1
#define CONFIG_HAVE_OWN_ASSERT 1
#define CONFIG_DEBUG_PRINTF 0
#define CONFIG_DEBUG_ASSERT 0
#define CONFIG_XATTR_ENABLE 1
#define CONFIG_JOURNALING_ENABLE 1
#define CONFIG_BLOCK_DEV_CACHE_SIZE 16
void *ext4_user_malloc(size_t);
void *ext4_user_calloc(size_t, size_t);
void *ext4_user_realloc(void *, size_t);
void ext4_user_free(void *);
#endif
