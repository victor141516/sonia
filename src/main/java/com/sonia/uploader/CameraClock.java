package com.sonia.uploader;

import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import com.sonia.uploader.core.Clock;
import com.sonia.uploader.core.Log;

/**
 * Real time from Sony's com.sony.scalar.sysutil.TimeUtil (the Android system clock on the camera
 * stays at 1970). getCurrentCalendar() returns the camera's local wall time (measured on the a6000:
 * one hour ahead of UTC in CET), so the zone offset from getCurrentTimeZone() is subtracted.
 * Accessed by reflection so the APK needs no Sony stubs and still runs (with the system clock)
 * on a normal Android device.
 */
final class CameraClock implements Clock {
    private final Method getCurrentCalendar;
    private final Method getCurrentTimeZone;

    private CameraClock(Method getCurrentCalendar, Method getCurrentTimeZone) {
        this.getCurrentCalendar = getCurrentCalendar;
        this.getCurrentTimeZone = getCurrentTimeZone;
    }

    static Clock create(Log log) {
        try {
            Class<?> timeUtil = Class.forName("com.sony.scalar.sysutil.TimeUtil");
            CameraClock clock = new CameraClock(timeUtil.getMethod("getCurrentCalendar"),
                    timeUtil.getMethod("getCurrentTimeZone"));
            log.log("clock: Sony TimeUtil, zone " + clock.offsetMinutes() + " min, now UTC "
                    + new java.util.Date(clock.currentTimeMillis()));
            return clock;
        } catch (Exception e) {
            log.log("clock: Sony TimeUtil unavailable (" + e + "), using the Android clock");
            return Clock.SYSTEM;
        }
    }

    @Override
    public long currentTimeMillis() {
        try {
            Object plain = getCurrentCalendar.invoke(null);
            Class<?> type = plain.getClass();
            Calendar wall = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            wall.clear();
            wall.set(type.getField("year").getInt(plain), type.getField("month").getInt(plain) - 1,
                    type.getField("day").getInt(plain), type.getField("hour").getInt(plain),
                    type.getField("minute").getInt(plain), type.getField("second").getInt(plain));
            return wall.getTimeInMillis() - offsetMinutes() * 60000L;
        } catch (Exception e) {
            throw new IllegalStateException("Sony TimeUtil failed", e);
        }
    }

    private int offsetMinutes() throws Exception {
        Object zone = getCurrentTimeZone.invoke(null);
        Class<?> type = zone.getClass();
        return type.getField("gmtDiff").getInt(zone) + type.getField("summerTimeDiff").getInt(zone);
    }
}
