package com.ajctrl.sumiresync.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ClipboardChangedReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!SumireContract.isChangedAction(intent.getAction())) return;
        SyncScheduler.requestCatchUp(context);
    }
}
