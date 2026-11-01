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

/** asks the launcher to pin a shortcut, the way a browser's "add to home" does. */
public class PinActivity extends Activity {
    // browsers send site icons at this size or larger
    private static final int ICON_SIZE = 512;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String icon = getIntent().getStringExtra("pin");
        if (icon == null) return;
        ShortcutInfo.Builder shortcut = new ShortcutInfo.Builder(this, "page")
                .setShortLabel("Pinned Page")
                .setIntent(new Intent(Intent.ACTION_VIEW, Uri.parse("booster://page"), this, PinActivity.class));
        Bitmap bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.MAGENTA);
        if (icon.equals("bitmap")) shortcut.setIcon(Icon.createWithBitmap(bitmap));
        if (icon.equals("adaptive")) shortcut.setIcon(Icon.createWithAdaptiveBitmap(bitmap));
        getSystemService(ShortcutManager.class).requestPinShortcut(shortcut.build(), null);
    }
}
