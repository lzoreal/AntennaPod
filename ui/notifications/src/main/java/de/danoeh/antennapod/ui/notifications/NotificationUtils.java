package de.danoeh.antennapod.ui.notifications;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationChannelGroupCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.Arrays;
import java.util.List;

import de.danoeh.antennapod.storage.preferences.UserPreferences;

public class NotificationUtils {
    public static final String CHANNEL_ID_USER_ACTION = "user_action";
    public static final String CHANNEL_ID_DOWNLOADING = "downloading";
    public static final String CHANNEL_ID_REFRESHING = "refreshing";
    public static final String CHANNEL_ID_PLAYING = "playing";
    public static final String CHANNEL_ID_DOWNLOAD_ERROR = "error";
    public static final String CHANNEL_ID_SYNC_ERROR = "sync_error";
    public static final String CHANNEL_ID_EPISODE_NOTIFICATIONS = "episode_notifications";
    public static final String CHANNEL_ID_AD_SCAN = "ad_scan";
    public static final String CHANNEL_ID_AD_SKIP = "ad_skip";
    private static final int NOTIFICATION_ID_AD_SCAN_RESULT = 0xAD01;
    private static final int NOTIFICATION_ID_AD_SKIP = 0xAD02;

    public static final String GROUP_ID_ERRORS = "group_errors";
    public static final String GROUP_ID_NEWS = "group_news";

    public static void createChannels(final Context context) {
        final NotificationManagerCompat mNotificationManager = NotificationManagerCompat.from(context);

        final List<NotificationChannelGroupCompat> channelGroups = Arrays.asList(
                createGroupErrors(context),
                createGroupNews(context));
        mNotificationManager.createNotificationChannelGroupsCompat(channelGroups);

        final List<NotificationChannelCompat> channels = Arrays.asList(
                createChannelUserAction(context),
                createChannelDownloading(context),
                createChannelRefreshing(context),
                createChannelPlaying(context),
                createChannelError(context),
                createChannelSyncError(context),
                createChannelEpisodeNotification(context),
                createChannelAdScan(context),
                createChannelAdSkip(context));
        mNotificationManager.createNotificationChannelsCompat(channels);
    }

    private static NotificationChannelCompat createChannelUserAction(final Context c) {
        return new NotificationChannelCompat.Builder(
                        CHANNEL_ID_USER_ACTION, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(c.getString(R.string.notification_channel_user_action))
                .setDescription(c.getString(R.string.notification_channel_user_action_description))
                .setGroup(GROUP_ID_ERRORS)
                .build();
    }

    private static NotificationChannelCompat createChannelDownloading(final Context c) {
        return new NotificationChannelCompat.Builder(
                        CHANNEL_ID_DOWNLOADING, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(c.getString(R.string.notification_channel_downloading))
                .setDescription(c.getString(R.string.notification_channel_downloading_description))
                .setShowBadge(false)
                .build();
    }

    private static NotificationChannelCompat createChannelRefreshing(final Context c) {
        return new NotificationChannelCompat.Builder(
                        CHANNEL_ID_REFRESHING, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(c.getString(R.string.notification_channel_refreshing))
                .setDescription(c.getString(R.string.notification_channel_refreshing_description))
                .setShowBadge(false)
                .build();
    }

    private static NotificationChannelCompat createChannelPlaying(final Context c) {
        return new NotificationChannelCompat.Builder(
                        CHANNEL_ID_PLAYING, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(c.getString(R.string.notification_channel_playing))
                .setDescription(c.getString(R.string.notification_channel_playing_description))
                .setShowBadge(false)
                .build();
    }

    private static NotificationChannelCompat createChannelError(final Context c) {
        final NotificationChannelCompat.Builder notificationChannel = new NotificationChannelCompat.Builder(
                        CHANNEL_ID_DOWNLOAD_ERROR, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(c.getString(R.string.notification_channel_download_error))
                .setDescription(c.getString(R.string.notification_channel_download_error_description))
                .setGroup(GROUP_ID_ERRORS);

        if (!UserPreferences.getShowDownloadReportRaw()) {
            // Migration from app managed setting: disable notification
            notificationChannel.setImportance(NotificationManagerCompat.IMPORTANCE_NONE);
        }
        return notificationChannel.build();
    }

    private static NotificationChannelCompat createChannelSyncError(final Context c) {
        final NotificationChannelCompat.Builder notificationChannel = new NotificationChannelCompat.Builder(
                        CHANNEL_ID_SYNC_ERROR, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(c.getString(R.string.notification_channel_sync_error))
                .setDescription(c.getString(R.string.notification_channel_sync_error_description))
                .setGroup(GROUP_ID_ERRORS);

        if (!UserPreferences.getGpodnetNotificationsEnabledRaw()) {
            // Migration from app managed setting: disable notification
            notificationChannel.setImportance(NotificationManagerCompat.IMPORTANCE_NONE);
        }
        return notificationChannel.build();
    }

    private static NotificationChannelCompat createChannelEpisodeNotification(final Context c) {
        return new NotificationChannelCompat.Builder(
                        CHANNEL_ID_EPISODE_NOTIFICATIONS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(c.getString(R.string.notification_channel_new_episode))
                .setDescription(c.getString(R.string.notification_channel_new_episode_description))
                .setGroup(GROUP_ID_NEWS)
                .build();
    }

    private static NotificationChannelCompat createChannelAdScan(final Context c) {
        return new NotificationChannelCompat.Builder(CHANNEL_ID_AD_SCAN, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(c.getString(R.string.ad_scan_notification_title))
                .setShowBadge(false)
                .build();
    }

    private static NotificationChannelCompat createChannelAdSkip(final Context c) {
        return new NotificationChannelCompat.Builder(CHANNEL_ID_AD_SKIP, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(c.getString(R.string.ad_skipped_notification_title))
                .setShowBadge(false)
                .build();
    }

    public static void showAdSkipUndo(Context context, String text, PendingIntent contentIntent,
                                      PendingIntent undoIntent) {
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID_AD_SKIP)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.ad_skipped_notification_title))
                .setContentText(text)
                .setContentIntent(contentIntent)
                .addAction(R.drawable.ic_notification, context.getString(R.string.undo), undoIntent)
                .setAutoCancel(true)
                .setTimeoutAfter(10_000)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_AD_SKIP, notification);
    }

    public static void cancelAdSkipUndo(Context context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_AD_SKIP);
    }

    public static Notification createAdScanNotification(Context context, int episodeIndex, int total) {
        return createAdScanNotification(context,
                context.getString(R.string.ad_scan_notification_text, episodeIndex, total));
    }

    public static Notification createAdScanPreparingNotification(Context context, int total) {
        return createAdScanNotification(context,
                context.getString(R.string.ad_scan_status_preparing, total));
    }

    private static Notification createAdScanNotification(Context context, String text) {
        return new NotificationCompat.Builder(context, CHANNEL_ID_AD_SCAN)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.ad_scan_notification_title))
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    public static void showAdScanFinished(Context context, int episodes, int segments) {
        showAdScanResult(context, R.string.ad_scan_done_title,
                context.getString(R.string.ad_scan_done_text, episodes, segments));
    }

    public static void showAdScanFailed(Context context) {
        showAdScanResult(context, R.string.ad_scan_failed_title,
                context.getString(R.string.ad_scan_failed_text));
    }

    private static void showAdScanResult(Context context, int title, String text) {
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID_AD_SCAN)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_AD_SCAN_RESULT, notification);
    }

    private static NotificationChannelGroupCompat createGroupErrors(final Context c) {
        return new NotificationChannelGroupCompat.Builder(GROUP_ID_ERRORS)
                .setName(c.getString(R.string.notification_group_errors))
                .build();
    }

    private static NotificationChannelGroupCompat createGroupNews(final Context c) {
        return new NotificationChannelGroupCompat.Builder(GROUP_ID_NEWS)
                .setName(c.getString(R.string.notification_group_news))
                .build();
    }
}
