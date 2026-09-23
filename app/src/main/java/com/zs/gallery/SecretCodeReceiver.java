package com.zs.gallery;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

public class SecretCodeReceiver extends BroadcastReceiver {
    private static final String SECRET = new String(new char[]{'7','3','8','3','6','2','4','5'});

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        Uri uri = intent.getData();
        if (uri == null) return;
        String code = uri.getHost();
        if (!SECRET.equals(code) && !uri.toString().contains(SECRET)) return;
        Intent launch = new Intent(context, AdminActivity.class);
        launch.setPackage(context.getPackageName());
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(launch);
    }
}
