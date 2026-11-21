# How it works

The α6000 runs Android 2.3.7 with an old OpenSSL that only speaks TLS 1.0, so TLS is done natively with [Mbed TLS](https://github.com/Mbed-TLS/mbedtls) (`jni/`). Uploads use the [Photos Library API](https://developers.google.com/photos/library/guides/upload-media): simple uploads for small files and [resumable uploads](https://developers.google.com/photos/library/guides/resumable-uploads) for large ones. The real time comes from Sony's `TimeUtil`, because the Android clock on the camera reads 1970.
