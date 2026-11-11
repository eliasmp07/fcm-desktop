package io.github.eliasmp07.fcmdesktop.notifications

import io.github.eliasmp07.fcmdesktop.notifications.internal.CardListener
import io.github.eliasmp07.fcmdesktop.notifications.internal.NotificationCard
import io.github.eliasmp07.fcmdesktop.notifications.internal.Palette
import io.github.eliasmp07.fcmdesktop.notifications.internal.SystemTheme
import java.awt.Color
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.geom.RoundRectangle2D
import javax.swing.JDialog
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants

/**
 * Shows [RichNotification]s as small always-on-top windows stacked in a corner of the screen.
 *
 * One instance per app is enough. Every method can be called from any thread.
 *
 * ```
 * val center = DesktopNotificationCenter(NotificationCenterConfig(appName = "MyApp"))
 * center.show(
 *     RichNotification(
 *         title = "Ana",
 *         body = "¿Llegas a la junta?",
 *         largeIcon = NotificationImage.Url("https://example.com/ana.png"),
 *         reply = NotificationReply { text -> chat.send(text) },
 *     )
 * )
 * ```
 */
public class DesktopNotificationCenter(
    private val config: NotificationCenterConfig = NotificationCenterConfig(),
) : AutoCloseable {

    private class Popup(var notification: RichNotification, val window: JDialog) {
        lateinit var card: NotificationCard
        var shownMillis = 0L
        var y = 0f
        var targetY = 0
        var opacity = 0f
        var placed = false
    }

    // Everything below is touched only on the AWT event thread.
    private val popups = ArrayList<Popup>() // newest first
    private val waiting = ArrayDeque<RichNotification>()
    private var closed = false
    private var lastTick = 0L
    private val timer = Timer(FRAME_MILLIS) { tick() }

    private val supported = !GraphicsEnvironment.isHeadless()
    private val palette: Palette by lazy { Palette.of(SystemTheme.resolve(config.theme), config.accentColor) }
    private val fades: Boolean by lazy { supports(GraphicsDevice.WindowTranslucency.TRANSLUCENT) }

    // On X11 without a compositor per-pixel translucency is reported but paints black, while uniform
    // translucency is correctly reported as missing: require both before drawing a soft shadow.
    private val translucent: Boolean by lazy { fades && supports(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT) }
    private val shaped: Boolean by lazy { !translucent && supports(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT) }

    /**
     * Shows the notification, or replaces the one with the same id. Returns its id.
     * Does nothing on a headless JVM (see [isSupported]).
     */
    public fun show(notification: RichNotification): String {
        if (supported) {
            // Detecting the OS theme runs a short command: do it here rather than on the event thread when possible.
            if (!SwingUtilities.isEventDispatchThread()) palette
            onEdt { showNow(notification) }
        }
        return notification.id
    }

    /** Removes a notification from the screen or from the waiting queue. */
    public fun dismiss(id: String) {
        if (!supported) return
        onEdt {
            waiting.firstOrNull { it.id == id }?.let {
                waiting.remove(it)
                notifyDismissed(it, DismissReason.Cancelled)
            }
            popups.firstOrNull { it.notification.id == id }?.let { remove(it, DismissReason.Cancelled) }
        }
    }

    public fun dismissAll() {
        if (supported) onEdt(::clear)
    }

    /** Dismisses everything and releases the windows. Later calls to [show] are ignored. */
    override fun close() {
        if (!supported) return
        onEdt {
            clear()
            closed = true
        }
    }

    private fun clear() {
        val pending = waiting.toList()
        waiting.clear()
        pending.forEach { notifyDismissed(it, DismissReason.Cancelled) }
        popups.toList().forEach { remove(it, DismissReason.Cancelled, promote = false) }
    }

    private fun showNow(notification: RichNotification) {
        if (closed) return
        val existing = popups.firstOrNull { it.notification.id == notification.id }
        if (existing != null) {
            existing.notification = notification
            existing.card = createCard(existing, notification)
            existing.window.contentPane = existing.card
            existing.window.focusableWindowState = existing.card.hasReply
            existing.shownMillis = 0
            resize(existing)
            layoutPopups()
            return
        }
        val queued = waiting.indexOfFirst { it.id == notification.id }
        when {
            queued >= 0 -> waiting[queued] = notification
            popups.size >= config.maxVisible -> waiting.addLast(notification)
            else -> open(notification)
        }
    }

    private fun open(notification: RichNotification) {
        val window = JDialog()
        window.isUndecorated = true
        window.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        window.isAlwaysOnTop = true
        window.isResizable = false
        // Never take the focus away from what the user is doing when it appears.
        window.isAutoRequestFocus = false
        window.type = Window.Type.UTILITY
        if (translucent) window.background = Color(0, 0, 0, 0)

        val popup = Popup(notification, window)
        popup.card = createCard(popup, notification)
        window.contentPane = popup.card
        // A notification without a reply field has nothing to type into: keep it out of the focus cycle.
        window.focusableWindowState = popup.card.hasReply
        resize(popup)
        popups.add(0, popup)
        layoutPopups()

        popup.y = popup.targetY.toFloat() + if (isTop()) -SLIDE else SLIDE
        popup.placed = true
        if (fades) {
            try {
                window.opacity = 0f
            } catch (_: Exception) {
                popup.opacity = 1f
            }
        } else {
            popup.opacity = 1f
        }
        window.setLocation(xFor(window.width), popup.y.toInt())
        window.isVisible = true
        if (!timer.isRunning) {
            lastTick = System.nanoTime()
            timer.start()
        }
    }

    private fun createCard(popup: Popup, notification: RichNotification): NotificationCard = NotificationCard(
        notification = notification,
        appName = notification.appName ?: config.appName,
        icon = notification.icon ?: config.appIcon,
        palette = palette,
        cardWidth = config.width,
        shadow = if (translucent) SHADOW else 0,
        listener = object : CardListener {
            override fun onBodyClicked() {
                // Without a click handler the body is inert, so a stray click never discards a half-typed reply.
                val onClick = popup.notification.onClick ?: return
                safely { onClick() }
                remove(popup, DismissReason.Clicked)
            }

            override fun onCloseClicked() = remove(popup, DismissReason.Closed)

            override fun onAction(index: Int) {
                val action = popup.notification.actions.getOrNull(index) ?: return
                safely { action.onClick() }
                if (action.dismissOnClick) remove(popup, DismissReason.Action)
            }

            override fun onReply(text: String) {
                safely { popup.notification.reply?.onReply?.invoke(text) }
                remove(popup, DismissReason.Replied)
            }

            override fun onHeightChanged() {
                resize(popup)
                layoutPopups()
            }
        },
    )

    private fun resize(popup: Popup) {
        popup.window.size = popup.card.preferredSize
        if (shaped) {
            // No translucency: clip the window itself to the rounded card.
            val size = popup.window.size
            popup.window.shape = RoundRectangle2D.Float(0f, 0f, size.width.toFloat(), size.height.toFloat(), CORNER, CORNER)
        }
        popup.window.validate()
        popup.window.repaint()
    }

    private fun remove(popup: Popup, reason: DismissReason, promote: Boolean = true) {
        if (!popups.remove(popup)) return
        popup.window.isVisible = false
        popup.window.dispose()
        notifyDismissed(popup.notification, reason)
        if (promote && !closed) {
            while (popups.size < config.maxVisible && waiting.isNotEmpty()) open(waiting.removeFirst())
        }
        layoutPopups()
        if (popups.isEmpty()) timer.stop()
    }

    private fun notifyDismissed(notification: RichNotification, reason: DismissReason) {
        safely { notification.onDismiss?.invoke(reason) }
    }

    /** Assigns each popup its resting place, the newest one closest to the corner. */
    private fun layoutPopups() {
        val area = usableArea()
        val inset = if (translucent) SHADOW else 0
        var edge = if (isTop()) area.y + config.screenMargin - inset else area.y + area.height - config.screenMargin + inset
        for (popup in popups) {
            val height = popup.window.height
            if (isTop()) {
                popup.targetY = edge
                edge += height - 2 * inset + GAP
            } else {
                popup.targetY = edge - height
                edge -= height - 2 * inset + GAP
            }
        }
    }

    private fun tick() {
        val now = System.nanoTime()
        val elapsed = ((now - lastTick) / 1_000_000).coerceIn(0, 250)
        lastTick = now
        val pointer = try {
            MouseInfo.getPointerInfo()?.location
        } catch (_: Exception) {
            null
        }
        for (popup in popups.toList()) {
            // Ease towards the resting place and fade in.
            val distance = popup.targetY - popup.y
            popup.y = if (Math.abs(distance) < 0.5f) popup.targetY.toFloat() else popup.y + distance * 0.28f
            val location = popup.window.location
            val x = xFor(popup.window.width)
            if (location.x != x || location.y != popup.y.toInt()) popup.window.setLocation(x, popup.y.toInt())
            if (popup.opacity < 1f) {
                popup.opacity = (popup.opacity + 0.14f).coerceAtMost(1f)
                try {
                    popup.window.opacity = popup.opacity
                } catch (_: Exception) {
                    popup.opacity = 1f
                }
            }

            // The countdown pauses while the pointer is over the notification or a reply is being typed.
            val timeout = popup.notification.timeoutMillis ?: config.defaultTimeoutMillis
            val hovered = pointer != null && popup.window.bounds.contains(pointer)
            if (timeout > 0 && !hovered && !popup.card.isReplyFocused) {
                popup.shownMillis += elapsed
                if (popup.shownMillis >= timeout) remove(popup, DismissReason.Timeout)
            }
        }
    }

    private fun isTop(): Boolean =
        config.position == NotificationPosition.TopRight || config.position == NotificationPosition.TopLeft

    private fun xFor(windowWidth: Int): Int {
        val area = usableArea()
        val inset = if (translucent) SHADOW else 0
        return when (config.position) {
            NotificationPosition.TopLeft, NotificationPosition.BottomLeft -> area.x + config.screenMargin - inset
            else -> area.x + area.width - config.screenMargin - windowWidth + inset
        }
    }

    /** The primary screen without the taskbar, dock or menu bar. */
    private fun usableArea(): Rectangle {
        val configuration = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val bounds = configuration.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        return Rectangle(
            bounds.x + insets.left,
            bounds.y + insets.top,
            bounds.width - insets.left - insets.right,
            bounds.height - insets.top - insets.bottom,
        )
    }

    private fun supports(kind: GraphicsDevice.WindowTranslucency): Boolean = try {
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.isWindowTranslucencySupported(kind)
    } catch (_: Exception) {
        false
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            System.err.println("[fcm-desktop] A notification callback failed: $e")
        }
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }

    public companion object {
        private const val FRAME_MILLIS = 16
        private const val SHADOW = 14
        private const val GAP = 10
        private const val SLIDE = 24
        private const val CORNER = 14f

        /** False on a headless JVM, where no window can be shown. */
        public val isSupported: Boolean get() = !GraphicsEnvironment.isHeadless()
    }
}
