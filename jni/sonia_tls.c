/*
 * JNI bridge: TLS client (mbedTLS) for com.sonia.uploader.NativeTls.
 *
 * Why native: on the a6000, Java crypto runs at ~0.14 MB/s with the JIT disabled, and the JIT
 * itself crashes the camera while running BouncyCastle's TLS code. The camera's own OpenSSL is
 * TLS 1.0 only and also crashed. Certificate time comes from Java (Sony TimeUtil) because the
 * Android system clock on the camera reads 1970.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <time.h>
#include <sys/socket.h>
#include <sys/time.h>

#include "mbedtls/build_info.h"
#include "mbedtls/ctr_drbg.h"
#include "mbedtls/entropy.h"
#include "mbedtls/error.h"
#include "mbedtls/net_sockets.h"
#include "mbedtls/platform_time.h"
#include "mbedtls/ssl.h"
#include "mbedtls/version.h"
#include "mbedtls/x509_crt.h"
#include "psa/crypto.h"

/* ------------------------------------------------------------------ time */

static volatile int64_t g_epoch_base; /* UTC seconds supplied by Java at g_mono_base */
static volatile int64_t g_mono_base;

static int64_t monotonic_seconds(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec;
}

static int64_t monotonic_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static mbedtls_time_t camera_time(mbedtls_time_t *out) {
    mbedtls_time_t now = (mbedtls_time_t) (g_epoch_base + (monotonic_seconds() - g_mono_base));
    if (out != NULL) {
        *out = now;
    }
    return now;
}

/* ------------------------------------------------------------------ helpers */

static void throw_io(JNIEnv *env, const char *what, int ret) {
    char text[256];
    char err[160];
    mbedtls_strerror(ret, err, sizeof(err));
    snprintf(text, sizeof(text), "%s: -0x%04x %s", what, (unsigned) -ret, err);
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls != NULL) {
        (*env)->ThrowNew(env, cls, text);
    }
}

typedef struct {
    mbedtls_net_context net;
    mbedtls_ssl_context ssl;
    mbedtls_ssl_config conf;
    mbedtls_entropy_context entropy;
    mbedtls_ctr_drbg_context drbg;
    int suites[8];
    int64_t handshake_ms;
} conn_t;

static void conn_free(conn_t *c) {
    mbedtls_ssl_free(&c->ssl);
    mbedtls_ssl_config_free(&c->conf);
    mbedtls_ctr_drbg_free(&c->drbg);
    mbedtls_entropy_free(&c->entropy);
    mbedtls_net_free(&c->net);
    free(c);
}

/* ------------------------------------------------------------------ init / CA */

JNIEXPORT jstring JNICALL
Java_com_sonia_uploader_NativeTls_nativeInit(JNIEnv *env, jclass cls) {
    psa_status_t status = psa_crypto_init();
    char text[96];
    snprintf(text, sizeof(text), "mbedTLS %s, psa_crypto_init=%d", MBEDTLS_VERSION_STRING_FULL, (int) status);
    mbedtls_platform_set_time(camera_time);
    return (*env)->NewStringUTF(env, text);
}

JNIEXPORT void JNICALL
Java_com_sonia_uploader_NativeTls_setTime(JNIEnv *env, jclass cls, jlong epoch_seconds) {
    g_mono_base = monotonic_seconds();
    g_epoch_base = epoch_seconds;
}

/** Parses a NUL-terminated PEM bundle once; returns a handle for open(). */
JNIEXPORT jlong JNICALL
Java_com_sonia_uploader_NativeTls_loadCa(JNIEnv *env, jclass cls, jbyteArray pem) {
    jsize len = (*env)->GetArrayLength(env, pem);
    jbyte *bytes = (*env)->GetByteArrayElements(env, pem, NULL);
    mbedtls_x509_crt *ca = malloc(sizeof(*ca));
    mbedtls_x509_crt_init(ca);
    int ret = mbedtls_x509_crt_parse(ca, (const unsigned char *) bytes, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, pem, bytes, JNI_ABORT);
    if (ret != 0) {
        mbedtls_x509_crt_free(ca);
        free(ca);
        throw_io(env, "CA parse", ret);
        return 0;
    }
    return (jlong) (intptr_t) ca;
}

/* ------------------------------------------------------------------ connection */

/**
 * Opens a TLS connection to address:port (host name or IP). host is the SNI name and the name
 * the certificate must match; the app passes the same name for both. Must stay in sync with the
 * Java declaration NativeTls.open(String, String, int, long, int).
 * Offers only ChaCha20-Poly1305 (6.3 MB/s on the camera's CPU, AES-GCM 2.4 MB/s).
 */
JNIEXPORT jlong JNICALL
Java_com_sonia_uploader_NativeTls_open(JNIEnv *env, jclass cls, jstring jaddress, jstring jhost, jint port,
                                       jlong ca_handle, jint timeout_ms) {
    const char *address = (*env)->GetStringUTFChars(env, jaddress, NULL);
    const char *host = (*env)->GetStringUTFChars(env, jhost, NULL);
    char port_text[8];
    snprintf(port_text, sizeof(port_text), "%d", (int) port);
    conn_t *c = calloc(1, sizeof(*c));
    mbedtls_net_init(&c->net);
    mbedtls_ssl_init(&c->ssl);
    mbedtls_ssl_config_init(&c->conf);
    mbedtls_entropy_init(&c->entropy);
    mbedtls_ctr_drbg_init(&c->drbg);

    const char *step = "drbg seed";
    int ret = mbedtls_ctr_drbg_seed(&c->drbg, mbedtls_entropy_func, &c->entropy,
                                    (const unsigned char *) "sonia-a6000", 11);
    if (ret == 0) {
        step = "connect";
        ret = mbedtls_net_connect(&c->net, address, port_text, MBEDTLS_NET_PROTO_TCP);
    }
    if (ret == 0) {
        struct timeval tv;
        tv.tv_sec = timeout_ms / 1000;
        tv.tv_usec = (timeout_ms % 1000) * 1000;
        setsockopt(c->net.fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
        step = "config";
        ret = mbedtls_ssl_config_defaults(&c->conf, MBEDTLS_SSL_IS_CLIENT, MBEDTLS_SSL_TRANSPORT_STREAM,
                                          MBEDTLS_SSL_PRESET_DEFAULT);
    }
    if (ret == 0) {
        mbedtls_ssl_conf_authmode(&c->conf, MBEDTLS_SSL_VERIFY_REQUIRED);
        mbedtls_ssl_conf_ca_chain(&c->conf, (mbedtls_x509_crt *) (intptr_t) ca_handle, NULL);
        mbedtls_ssl_conf_rng(&c->conf, mbedtls_ctr_drbg_random, &c->drbg);
        mbedtls_ssl_conf_read_timeout(&c->conf, (uint32_t) timeout_ms);
        /* ChaCha20 only: Google picks AES-GCM whenever it is offered, even listed second, and on the
         * camera AES uploads at ~1 MB/s versus ~1.75 MB/s (Wi-Fi bound) with ChaCha20. */
        c->suites[0] = MBEDTLS_TLS1_3_CHACHA20_POLY1305_SHA256;
        c->suites[1] = MBEDTLS_TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256;
        c->suites[2] = MBEDTLS_TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256;
        c->suites[3] = 0;
        mbedtls_ssl_conf_ciphersuites(&c->conf, c->suites);
        step = "setup";
        ret = mbedtls_ssl_setup(&c->ssl, &c->conf);
    }
    if (ret == 0) {
        step = "hostname";
        ret = mbedtls_ssl_set_hostname(&c->ssl, host); /* SNI + certificate name check */
    }
    if (ret == 0) {
        mbedtls_ssl_set_bio(&c->ssl, &c->net, mbedtls_net_send, NULL, mbedtls_net_recv_timeout);
        step = "handshake";
        int64_t start = monotonic_ms();
        do {
            ret = mbedtls_ssl_handshake(&c->ssl);
        } while (ret == MBEDTLS_ERR_SSL_WANT_READ || ret == MBEDTLS_ERR_SSL_WANT_WRITE);
        c->handshake_ms = monotonic_ms() - start;
    }
    (*env)->ReleaseStringUTFChars(env, jaddress, address);
    (*env)->ReleaseStringUTFChars(env, jhost, host);

    if (ret != 0) {
        uint32_t flags = mbedtls_ssl_get_verify_result(&c->ssl);
        if (ret == MBEDTLS_ERR_X509_CERT_VERIFY_FAILED && flags != 0) {
            char info[256];
            char text[320];
            int n = mbedtls_x509_crt_verify_info(info, sizeof(info), "", flags);
            while (n > 0 && info[n - 1] == '\n') {
                info[--n] = '\0';
            }
            snprintf(text, sizeof(text), "certificate rejected: %s", info);
            jclass io = (*env)->FindClass(env, "java/io/IOException");
            if (io != NULL) {
                (*env)->ThrowNew(env, io, text);
            }
        } else {
            throw_io(env, step, ret);
        }
        conn_free(c);
        return 0;
    }
    return (jlong) (intptr_t) c;
}

JNIEXPORT jint JNICALL
Java_com_sonia_uploader_NativeTls_read(JNIEnv *env, jclass cls, jlong handle, jbyteArray buffer, jint off, jint len) {
    conn_t *c = (conn_t *) (intptr_t) handle;
    unsigned char tmp[16384];
    if (len > (jint) sizeof(tmp)) {
        len = sizeof(tmp);
    }
    int ret;
    do {
        ret = mbedtls_ssl_read(&c->ssl, tmp, (size_t) len);
    } while (ret == MBEDTLS_ERR_SSL_WANT_READ || ret == MBEDTLS_ERR_SSL_WANT_WRITE
             || ret == MBEDTLS_ERR_SSL_RECEIVED_NEW_SESSION_TICKET);
    if (ret == 0 || ret == MBEDTLS_ERR_SSL_PEER_CLOSE_NOTIFY) {
        return -1;
    }
    if (ret < 0) {
        throw_io(env, "read", ret);
        return -1;
    }
    (*env)->SetByteArrayRegion(env, buffer, off, ret, (const jbyte *) tmp);
    return ret;
}

JNIEXPORT void JNICALL
Java_com_sonia_uploader_NativeTls_write(JNIEnv *env, jclass cls, jlong handle, jbyteArray buffer, jint off, jint len) {
    conn_t *c = (conn_t *) (intptr_t) handle;
    unsigned char tmp[16384];
    while (len > 0) {
        jint chunk = len > (jint) sizeof(tmp) ? (jint) sizeof(tmp) : len;
        (*env)->GetByteArrayRegion(env, buffer, off, chunk, (jbyte *) tmp);
        int written = 0;
        while (written < chunk) {
            int ret = mbedtls_ssl_write(&c->ssl, tmp + written, (size_t) (chunk - written));
            if (ret == MBEDTLS_ERR_SSL_WANT_READ || ret == MBEDTLS_ERR_SSL_WANT_WRITE) {
                continue;
            }
            if (ret < 0) {
                throw_io(env, "write", ret);
                return;
            }
            written += ret;
        }
        off += chunk;
        len -= chunk;
    }
}

JNIEXPORT jstring JNICALL
Java_com_sonia_uploader_NativeTls_describe(JNIEnv *env, jclass cls, jlong handle) {
    conn_t *c = (conn_t *) (intptr_t) handle;
    char text[128];
    snprintf(text, sizeof(text), "mbedTLS %s %s", mbedtls_ssl_get_version(&c->ssl),
             mbedtls_ssl_get_ciphersuite(&c->ssl));
    return (*env)->NewStringUTF(env, text);
}

JNIEXPORT jlong JNICALL
Java_com_sonia_uploader_NativeTls_handshakeMs(JNIEnv *env, jclass cls, jlong handle) {
    return (jlong) ((conn_t *) (intptr_t) handle)->handshake_ms;
}

JNIEXPORT void JNICALL
Java_com_sonia_uploader_NativeTls_close(JNIEnv *env, jclass cls, jlong handle) {
    conn_t *c = (conn_t *) (intptr_t) handle;
    mbedtls_ssl_close_notify(&c->ssl);
    conn_free(c);
}
