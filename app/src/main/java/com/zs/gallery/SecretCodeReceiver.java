package com.zs.gallery;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class SecretCodeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String host = intent.getData() == null ? "" : intent.getData().getHost();
        if (!"73836245".equals(host)) return;

        Intent launch = new Intent(context, AdminActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(launch);
    }
}
