package com.sonia.uploader.core;

/**
 * Wall-clock source for certificate validity checks. On the camera the Android system clock
 * is not set (it reads 1970); the real time comes from Sony's TimeUtil.
 */
public interface Clock {
    long currentTimeMillis();

    Clock SYSTEM = new Clock() {
        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }
    };
}
