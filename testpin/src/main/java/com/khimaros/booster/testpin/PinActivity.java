package com.khimaros.booster.testpin;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;

/** asks the launcher to pin a shortcut with a bitmap icon, the way a browser's "add to home" does. */
public class PinActivity extends Activity {
    private static final int ICON_SIZE = 96;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!getIntent().getBooleanExtra("pin", false)) return;
        Bitmap bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.MAGENTA);
        ShortcutInfo shortcut = new ShortcutInfo.Builder(this, "page")
                .setShortLabel("Pinned Page")
                .setIcon(Icon.createWithBitmap(bitmap))
                .setIntent(new Intent(Intent.ACTION_VIEW, Uri.parse("booster://page"), this, PinActivity.class))
                .build();
        getSystemService(ShortcutManager.class).requestPinShortcut(shortcut, null);
    }
}
