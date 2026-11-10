package com.yosikou.hagemashi

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

internal enum class NoticeState { IDLE, SENDING, POSTED, PERMISSION_DENIED, DISABLED, FAILED }
internal class AnswerNotifications(private val context: Context) {
    private val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
    private val channelId = "encouragement.test.v1"
    internal fun build(answer: SavedEncouragement): Notification {
        val navigation = Intent(context, MainActivity::class.java)
            .setAction("${context.packageName}.OPEN_ANSWER")
            .setData(Uri.parse(answerNavigation(answer.id, context.packageName)))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Intent identity includes the answer ID, so later answers cannot replace this destination.
        val pending = PendingIntent.getActivity(context, 0, navigation, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val short = notificationShort(answer.short, context.getString(R.string.notification_fallback))
        return Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_encouragement)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(short).setStyle(Notification.BigTextStyle().bigText(short))
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).build()
    }
    fun allowed(): Boolean = (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        manager.areNotificationsEnabled() && manager.getNotificationChannel(channelId)?.importance != NotificationManager.IMPORTANCE_NONE
    fun post(answer: SavedEncouragement, tag: String = answer.id): NoticeState {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return NoticeState.PERMISSION_DENIED
        manager.createNotificationChannel(NotificationChannel(channelId, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = context.getString(R.string.notification_channel_description) })
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE)
            return NoticeState.DISABLED
        manager.notify(tag, 0, build(answer))
        return NoticeState.POSTED
    }
}
