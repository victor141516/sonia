package com.sonia.uploader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Sony signals the end of an app session; kill the process like Sony's own apps do. */
public class ExitReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
