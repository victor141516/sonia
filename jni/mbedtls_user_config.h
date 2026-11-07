/*
 * Overrides on top of mbedTLS 3.6's default mbedtls_config.h for the a6000 (Android 2.3 bionic).
 */

/* The camera's Android clock reads 1970; certificate validity uses the time supplied from Java
 * (Sony TimeUtil) through mbedtls_platform_set_time(). */
#define MBEDTLS_PLATFORM_TIME_ALT

/* mbedTLS 3.6 reads /dev/random by default, which blocks indefinitely on the camera's 3.0 kernel
 * (and the emulator) when the pool estimate is low. The app runs long after kernel boot and
 * Android restores its saved entropy at start-up, so /dev/urandom is the right source here. */
#define MBEDTLS_PLATFORM_DEV_RANDOM "/dev/urandom"

/* Hardware AES paths: none exist on the camera's Cortex-A5 (ARMv7, no crypto extensions), and
 * AES-NI does not build for the x86 emulator ABI with this NDK. Portable C tables are used. */
#undef MBEDTLS_AESNI_C
#undef MBEDTLS_AESCE_C
#undef MBEDTLS_PADLOCK_C

/* Unused features (smaller library, less attack surface). */
#undef MBEDTLS_SSL_SRV_C
#undef MBEDTLS_SSL_CACHE_C
#undef MBEDTLS_SSL_TICKET_C
#undef MBEDTLS_SSL_COOKIE_C
#undef MBEDTLS_SSL_DTLS_HELLO_VERIFY
#undef MBEDTLS_SSL_DTLS_ANTI_REPLAY
#undef MBEDTLS_SSL_DTLS_CLIENT_PORT_REUSE
#undef MBEDTLS_SSL_DTLS_CONNECTION_ID
#undef MBEDTLS_SSL_PROTO_DTLS
#undef MBEDTLS_PKCS7_C
#undef MBEDTLS_X509_CRL_PARSE_C
#undef MBEDTLS_X509_CSR_PARSE_C
#undef MBEDTLS_X509_CSR_WRITE_C
#undef MBEDTLS_X509_CRT_WRITE_C
#undef MBEDTLS_X509_CREATE_C
#undef MBEDTLS_DEBUG_C
#undef MBEDTLS_SELF_TEST
