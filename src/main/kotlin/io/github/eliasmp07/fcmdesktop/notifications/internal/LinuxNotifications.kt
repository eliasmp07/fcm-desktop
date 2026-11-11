package io.github.eliasmp07.fcmdesktop.notifications.internal

import io.github.eliasmp07.fcmdesktop.notifications.DismissReason
import io.github.eliasmp07.fcmdesktop.notifications.RichNotification

/**
 * Notifications through the freedesktop.org notification service (GNOME, KDE Plasma, dunst, ...),
 * spoken directly over D-Bus.
 */
internal class LinuxNotifications private constructor(
    private val context: NativeContext,
    private val bus: DBusConnection,
    private val capabilities: Set<String>,
) : NativeBackend {

    private class Entry(val notification: RichNotification, val nativeId: Int) {
        /** Why we expect the server to close it; null when the user or the server decides. */
        @Volatile
        var pendingReason: DismissReason? = null
    }

    private val images = ImageFiles.temporary()
    private val lock = Any()
    private val byId = HashMap<String, Entry>()
    private val byNativeId = HashMap<Int, Entry>()

    init {
        bus.onSignal(::onSignal)
    }

    override fun show(notification: RichNotification): Boolean {
        if (!bus.isOpen) return false
        // Fall back to the in-app window rather than silently dropping what the server cannot show.
        if (notification.reply != null && "inline-reply" !in capabilities) return false
        if ((notification.actions.isNotEmpty() || notification.onClick != null) && "actions" !in capabilities) return false

        val actions = ArrayList<String>()
        if (notification.onClick != null) actions += listOf("default", "")
        notification.actions.forEachIndexed { index, action -> actions += listOf("$ACTION_PREFIX$index", action.label) }
        val hints = LinkedHashMap<String, Any>()
        notification.reply?.let { reply ->
            actions += listOf("inline-reply", reply.sendLabel)
            hints["x-kde-reply-placeholder-text"] = reply.placeholder
            hints["x-kde-reply-submit-button-text"] = reply.sendLabel
        }
        // The service shows one picture: prefer the big one, then the avatar.
        (images.file(notification.image) ?: images.file(notification.largeIcon, round = true))?.let { hints["image-path"] = it.absolutePath }
        if (notification.actions.any { !it.dismissOnClick }) hints["resident"] = true
        context.appId?.let { hints["desktop-entry"] = it }

        val icon = images.file(notification.icon ?: context.appIcon, maxSize = 256)?.absolutePath.orEmpty()
        val markup = "body-markup" in capabilities
        val body = if (markup) escapeMarkup(notification.body) else notification.body
        val timeout = when (val millis = notification.timeoutMillis) {
            null -> -1 // the server's default
            0L -> 0 // never expires
            else -> millis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val previous = synchronized(lock) { byId[notification.id]?.nativeId } ?: 0
        val reply = try {
            bus.call(
                SERVICE, PATH, SERVICE, "Notify",
                notification.appName ?: context.appName, DBusConnection.U32(previous), icon,
                notification.title, body, actions, hints, timeout,
            )
        } catch (_: Exception) {
            return false
        }
        val nativeId = reply.firstOrNull() as? Int ?: return false
        synchronized(lock) {
            byId.remove(notification.id)?.let { byNativeId.remove(it.nativeId) }
            val entry = Entry(notification, nativeId)
            byId[notification.id] = entry
            byNativeId[nativeId] = entry
        }
        return true
    }

    override fun dismiss(id: String) {
        val entry = synchronized(lock) { byId[id] } ?: return
        close(entry, DismissReason.Cancelled)
    }

    override fun dismissAll() {
        synchronized(lock) { byId.values.toList() }.forEach { close(it, DismissReason.Cancelled) }
    }

    private fun close(entry: Entry, reason: DismissReason) {
        if (entry.pendingReason == null) entry.pendingReason = reason
        try {
            bus.call(SERVICE, PATH, SERVICE, "CloseNotification", DBusConnection.U32(entry.nativeId))
        } catch (_: Exception) {
            // The server is gone or already closed it: report the dismissal ourselves.
            forget(entry)?.let { context.dismissed(it.notification, reason) }
        }
    }

    private fun forget(entry: Entry): Entry? = synchronized(lock) {
        if (byNativeId.remove(entry.nativeId) == null) return null
        if (byId[entry.notification.id] === entry) byId.remove(entry.notification.id)
        entry
    }

    /** Runs on the D-Bus reader thread. */
    private fun onSignal(message: DBusConnection.Message) {
        if (message.interfaceName != SERVICE) return
        val nativeId = message.body.getOrNull(0) as? Int ?: return
        val entry = synchronized(lock) { byNativeId[nativeId] } ?: return // someone else's notification
        val notification = entry.notification
        when (message.member) {
            "ActionInvoked" -> {
                val key = message.body.getOrNull(1) as? String ?: return
                if (key == "default") {
                    val onClick = notification.onClick ?: return
                    context.dispatch(onClick)
                    closeLater(entry, DismissReason.Clicked)
                } else if (key.startsWith(ACTION_PREFIX)) {
                    val action = notification.actions.getOrNull(key.removePrefix(ACTION_PREFIX).toIntOrNull() ?: -1) ?: return
                    context.dispatch(action.onClick)
                    if (action.dismissOnClick) closeLater(entry, DismissReason.Action)
                }
            }
            "NotificationReplied" -> {
                val text = (message.body.getOrNull(1) as? String)?.trim().orEmpty()
                val reply = notification.reply ?: return
                if (text.isEmpty()) return
                context.dispatch { reply.onReply(text) }
                closeLater(entry, DismissReason.Replied)
            }
            "NotificationClosed" -> {
                val code = message.body.getOrNull(1) as? Int ?: 0
                forget(entry) ?: return
                val reason = entry.pendingReason ?: when (code) {
                    1 -> DismissReason.Timeout
                    3 -> DismissReason.Cancelled
                    else -> DismissReason.Closed
                }
                context.dismissed(notification, reason)
            }
        }
    }

    /** Calling the bus from its own reader thread would deadlock waiting for the reply. */
    private fun closeLater(entry: Entry, reason: DismissReason) {
        entry.pendingReason = reason
        Thread({ close(entry, reason) }, "fcm-desktop-dbus-close").apply { isDaemon = true }.start()
    }

    override fun close() {
        // Notifications already delivered stay in the desktop's history; only the connection goes away.
        bus.close()
        images.deleteAll()
    }

    companion object {
        private const val SERVICE = "org.freedesktop.Notifications"
        private const val PATH = "/org/freedesktop/Notifications"
        private const val ACTION_PREFIX = "action-"

        fun create(context: NativeContext): LinuxNotifications {
            val bus = DBusConnection.connectToSessionBus()
            try {
                @Suppress("UNCHECKED_CAST")
                val capabilities = (bus.call(SERVICE, PATH, SERVICE, "GetCapabilities").firstOrNull() as? List<String>).orEmpty()
                bus.addMatch("type='signal',interface='$SERVICE',path='$PATH'")
                return LinuxNotifications(context, bus, capabilities.toSet())
            } catch (e: Exception) {
                bus.close()
                throw e
            }
        }

        /** Servers with body markup treat the body as a small HTML subset. */
        fun escapeMarkup(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
