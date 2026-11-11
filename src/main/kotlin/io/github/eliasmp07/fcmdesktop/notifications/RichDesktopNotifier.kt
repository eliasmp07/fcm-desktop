package io.github.eliasmp07.fcmdesktop.notifications

import io.github.eliasmp07.fcmdesktop.DesktopNotification
import io.github.eliasmp07.fcmdesktop.DesktopNotifier
import io.github.eliasmp07.fcmdesktop.RemoteMessage

/**
 * Plugs [DesktopNotificationCenter] into `FcmDesktop`, so pushes are shown as rich notifications:
 *
 * ```
 * val center = DesktopNotificationCenter(NotificationCenterConfig(appName = "MyApp"))
 * val fcm = FcmDesktop(options, notifier = RichDesktopNotifier(center))
 * ```
 *
 * By default the push title, body and `notification.image` are used, and pushes that share a
 * collapse key replace each other. Use [build] to add actions or a reply based on the push `data`:
 *
 * ```
 * RichDesktopNotifier(center) { push ->
 *     RichDesktopNotifier.defaultNotification(push).copy(
 *         reply = NotificationReply { text -> chat.send(push.message.data["chatId"], text) },
 *     )
 * }
 * ```
 *
 * @param onClick Called with the push when the user clicks a notification built by the default mapping.
 * @param build Turns a push into the notification to show; return `null` to show nothing.
 */
public class RichDesktopNotifier(
    private val center: DesktopNotificationCenter,
    private val onClick: ((RemoteMessage) -> Unit)? = null,
    private val build: ((DesktopNotification) -> RichNotification?)? = null,
) : DesktopNotifier {

    override fun show(notification: DesktopNotification) {
        val rich = if (build != null) build.invoke(notification) else defaultNotification(notification, onClick)
        if (rich != null) center.show(rich)
    }

    public companion object {
        /** The notification the default mapping produces for a push. */
        public fun defaultNotification(
            notification: DesktopNotification,
            onClick: ((RemoteMessage) -> Unit)? = null,
        ): RichNotification {
            val message = notification.message
            val base = RichNotification(
                title = notification.title,
                body = notification.body,
                image = message.notification?.imageUrl?.takeIf { it.isNotBlank() }?.let { NotificationImage.Url(it) },
                onClick = onClick?.let { handler -> { handler(message) } },
            )
            // Like a notification tag on Android: a newer push with the same collapse key replaces the old one.
            val collapseKey = message.collapseKey?.takeIf { it.isNotBlank() && it != "do_not_collapse" }
            return if (collapseKey != null) base.copy(id = "fcm:$collapseKey") else base
        }
    }
}
