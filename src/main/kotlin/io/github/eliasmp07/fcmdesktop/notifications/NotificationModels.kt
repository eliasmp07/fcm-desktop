package io.github.eliasmp07.fcmdesktop.notifications

import java.util.UUID

/**
 * A notification drawn by the library itself (not by the OS), so it looks and behaves the same on
 * Windows, macOS and Linux and supports what `java.awt.TrayIcon` cannot: pictures, action buttons
 * and inline replies.
 *
 * Callbacks run on the AWT event thread: keep them short and hand long work to a coroutine.
 *
 * @param id Showing a notification whose id is already on screen replaces it in place (like
 *   `NotificationManager.notify(id, ...)` on Android).
 * @param appName Small label in the header. Defaults to [NotificationCenterConfig.appName].
 * @param icon Small app icon in the header. Defaults to [NotificationCenterConfig.appIcon].
 * @param largeIcon Round picture next to the text, e.g. the sender's avatar.
 * @param image Big picture shown under the text.
 * @param actions Up to three buttons.
 * @param reply Adds a text field to answer without opening the app.
 * @param timeoutMillis How long it stays on screen. `null` uses the center's default; `0` keeps it
 *   until the user or the app dismisses it.
 * @param onClick Called when the body of the notification is clicked (it is dismissed afterwards).
 *   Without it, clicking the body does nothing.
 */
public data class RichNotification(
    val title: String,
    val body: String = "",
    val id: String = UUID.randomUUID().toString(),
    val appName: String? = null,
    val icon: NotificationImage? = null,
    val largeIcon: NotificationImage? = null,
    val image: NotificationImage? = null,
    val actions: List<NotificationAction> = emptyList(),
    val reply: NotificationReply? = null,
    val timeoutMillis: Long? = null,
    val onClick: (() -> Unit)? = null,
    val onDismiss: ((DismissReason) -> Unit)? = null,
) {
    init {
        require(actions.size <= MAX_ACTIONS) { "a notification can have at most $MAX_ACTIONS actions" }
        require(timeoutMillis == null || timeoutMillis >= 0) { "timeoutMillis must not be negative" }
    }

    public companion object {
        public const val MAX_ACTIONS: Int = 3
    }
}

/**
 * A button of a notification.
 * @param dismissOnClick Close the notification after [onClick] runs.
 */
public data class NotificationAction(
    val label: String,
    val dismissOnClick: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * Inline reply, like `RemoteInput` on Android. [onReply] receives the trimmed, non-empty text and
 * the notification is dismissed afterwards.
 */
public data class NotificationReply(
    val placeholder: String = "Reply",
    val sendLabel: String = "Send",
    val onReply: (String) -> Unit,
)

/** Where a picture comes from. Remote and file images are loaded off the UI thread. */
public sealed class NotificationImage {
    /** An `http(s)` URL. Downloads are capped at 5 MB and time out after 10 seconds. */
    public data class Url(val url: String) : NotificationImage()

    public data class File(val file: java.io.File) : NotificationImage()

    /** A classpath resource, e.g. `"icon.png"` from `src/main/resources`. */
    public data class Resource(val path: String) : NotificationImage()

    /** Encoded image bytes (PNG, JPEG, GIF, BMP). */
    public class Bytes(public val bytes: ByteArray) : NotificationImage()

    public class Awt(public val image: java.awt.Image) : NotificationImage()
}

public enum class DismissReason {
    /** The timeout elapsed. */
    Timeout,

    /** The user pressed the close button. */
    Closed,

    /** The user clicked the notification body. */
    Clicked,

    /** The user pressed an action button. */
    Action,

    /** The user sent an inline reply. */
    Replied,

    /** The app called `dismiss`, `dismissAll` or `close`. */
    Cancelled,
}

public enum class NotificationBackend {
    /**
     * Windows drawn by the library. Identical on every OS and nothing to register, but they are not
     * part of the system: no notification history, no "do not disturb".
     */
    InApp,

    /**
     * Real OS notifications: Windows 10/11 toasts and the freedesktop.org service on Linux (GNOME,
     * KDE Plasma, ...). They appear in the system notification history and follow "do not disturb".
     * The OS draws them, so the theme, position and width settings do not apply.
     *
     * A notification the OS cannot show falls back to [InApp]: everything on macOS, and on Linux
     * the ones with features the desktop lacks (e.g. inline reply outside KDE Plasma).
     */
    Native,
}

public enum class NotificationPosition { TopRight, TopLeft, BottomRight, BottomLeft }

public enum class NotificationTheme {
    Light,
    Dark,

    /** Follows the OS setting when it can be detected, otherwise light. */
    System,
}

/**
 * @param position Corner of the primary screen. Defaults to the convention of each OS: top right on
 *   macOS and Linux, bottom right on Windows.
 * @param defaultTimeoutMillis Used when a notification sets no timeout; `0` means sticky.
 * @param maxVisible Notifications on screen at once; the rest wait in a queue.
 * @param accentColor `0xRRGGBB` used for buttons. `null` picks one that fits the theme.
 * @param backend Who draws the notifications. See [NotificationBackend].
 * @param appId Identifies the app to the OS when [backend] is [NotificationBackend.Native]: the
 *   AppUserModelID on Windows (pass the one of your installer's shortcut if it sets one) and the
 *   desktop file id on Linux (e.g. `"com.example.myapp"`). Derived from [appName] when omitted.
 */
public data class NotificationCenterConfig(
    val appName: String? = null,
    val appIcon: NotificationImage? = null,
    val position: NotificationPosition = defaultPosition(),
    val theme: NotificationTheme = NotificationTheme.System,
    val defaultTimeoutMillis: Long = 6_000,
    val maxVisible: Int = 3,
    val width: Int = 380,
    val screenMargin: Int = 16,
    val accentColor: Int? = null,
    val backend: NotificationBackend = NotificationBackend.InApp,
    val appId: String? = null,
) {
    init {
        require(defaultTimeoutMillis >= 0) { "defaultTimeoutMillis must not be negative" }
        require(maxVisible >= 1) { "maxVisible must be at least 1" }
        require(width in 280..640) { "width must be between 280 and 640" }
        require(screenMargin >= 0) { "screenMargin must not be negative" }
    }

    private companion object {
        fun defaultPosition(): NotificationPosition =
            if (System.getProperty("os.name", "").lowercase().contains("win")) {
                NotificationPosition.BottomRight
            } else {
                NotificationPosition.TopRight
            }
    }
}
