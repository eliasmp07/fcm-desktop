package io.github.eliasmp07.fcmdesktop

import java.awt.Image
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.util.concurrent.TimeUnit

/** What gets shown to the user when a push with a `notification` payload arrives. */
public data class DesktopNotification(
    val title: String,
    val body: String,
    /** The full push, so a click handler can read its `data`. */
    val message: RemoteMessage,
)

/**
 * Shows a notification on the desktop. Implement it to plug in your own UI, for example the
 * `TrayState.sendNotification` of Compose Desktop, or an in-app toast.
 */
public fun interface DesktopNotifier {
    public fun show(notification: DesktopNotification)
}

/**
 * Native notifications without extra dependencies:
 * - **Windows**: a tray balloon/toast through `java.awt.SystemTray` (this adds a tray icon).
 * - **macOS**: Notification Center through `osascript`.
 * - **Linux**: `notify-send` when installed, otherwise the system tray.
 *
 * @param trayIcon Reuse a tray icon the app already owns instead of adding a new one.
 * @param icon Image for the tray icon this class creates when [trayIcon] is null.
 * @param onClick Called when the user clicks the notification. Only the tray based path reports clicks.
 */
public class SystemDesktopNotifier(
    private val appName: String,
    private val trayIcon: TrayIcon? = null,
    private val icon: Image? = null,
    private val onClick: ((DesktopNotification) -> Unit)? = null,
) : DesktopNotifier {

    private val os = System.getProperty("os.name", "").lowercase()
    private var ownTrayIcon: TrayIcon? = null

    @Volatile
    private var lastShown: DesktopNotification? = null

    override fun show(notification: DesktopNotification) {
        val shown = when {
            os.contains("mac") -> showWithOsascript(notification) || showWithTray(notification)
            os.contains("win") -> showWithTray(notification)
            else -> showWithNotifySend(notification) || showWithTray(notification)
        }
        if (!shown) System.err.println("[fcm-desktop] No notification backend available on this system")
    }

    private fun showWithTray(notification: DesktopNotification): Boolean = try {
        val target = trayIcon ?: obtainOwnTrayIcon()
        if (target == null) {
            false
        } else {
            lastShown = notification
            target.displayMessage(notification.title, notification.body, TrayIcon.MessageType.NONE)
            true
        }
    } catch (_: Exception) {
        false
    }

    @Synchronized
    private fun obtainOwnTrayIcon(): TrayIcon? {
        ownTrayIcon?.let { return it }
        if (!SystemTray.isSupported()) return null
        val image = icon ?: BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val created = TrayIcon(image, appName).apply { isImageAutoSize = true }
        created.addActionListener { lastShown?.let { shown -> onClick?.invoke(shown) } }
        SystemTray.getSystemTray().add(created)
        ownTrayIcon = created
        return created
    }

    // Title and body travel as process arguments, never through a shell or inside the script text.
    private fun showWithOsascript(notification: DesktopNotification): Boolean = run(
        "osascript",
        "-e", "on run argv",
        "-e", "display notification (item 2 of argv) with title (item 1 of argv)",
        "-e", "end run",
        notification.title, notification.body,
    )

    private fun showWithNotifySend(notification: DesktopNotification): Boolean =
        run("notify-send", "--app-name=$appName", "--", notification.title, notification.body)

    private fun run(vararg command: String): Boolean = try {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.outputStream.close()
        if (process.waitFor(5, TimeUnit.SECONDS)) {
            process.exitValue() == 0
        } else {
            process.destroyForcibly()
            false
        }
    } catch (_: Exception) {
        false
    }

    /** Removes the tray icon this class added, if any. */
    @Synchronized
    public fun dispose() {
        ownTrayIcon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        ownTrayIcon = null
    }
}
