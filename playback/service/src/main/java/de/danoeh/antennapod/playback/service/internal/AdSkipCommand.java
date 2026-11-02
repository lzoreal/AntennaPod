package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.content.Intent;

public final class AdSkipCommand {
    public static final String ACTION_UNDO = "de.danoeh.antennapod.action.UNDO_AD_SKIP";
    public static final String ACTION_DISABLE_ALL = "de.danoeh.antennapod.action.DISABLE_ALL_AD_SKIPS";

    private AdSkipCommand() {
    }

    public static void undo(Context context) {
        send(context, ACTION_UNDO);
    }

    public static void disableAll(Context context) {
        send(context, ACTION_DISABLE_ALL);
    }

    private static void send(Context context, String action) {
        context.sendBroadcast(new Intent(action).setPackage(context.getPackageName()));
    }
}
