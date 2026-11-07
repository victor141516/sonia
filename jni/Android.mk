LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := soniatls
LOCAL_SRC_FILES := sonia_tls.c $(patsubst $(LOCAL_PATH)/%,%,$(wildcard $(LOCAL_PATH)/mbedtls/library/*.c))
LOCAL_C_INCLUDES := $(LOCAL_PATH) $(LOCAL_PATH)/mbedtls/include $(LOCAL_PATH)/mbedtls/library
LOCAL_CFLAGS := -O2 -std=c99 -D_POSIX_C_SOURCE=200112L -DMBEDTLS_USER_CONFIG_FILE='"mbedtls_user_config.h"'
include $(BUILD_SHARED_LIBRARY)
