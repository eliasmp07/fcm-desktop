package io.github.eliasmp07.fcmdesktop.notifications.internal

import io.github.eliasmp07.fcmdesktop.notifications.DismissReason
import io.github.eliasmp07.fcmdesktop.notifications.NotificationImage
import io.github.eliasmp07.fcmdesktop.notifications.RichNotification
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Shows notifications through the operating system, so they land in its notification history and
 * follow its "do not disturb" setting. All methods are called from one background thread.
 */
internal interface NativeBackend : AutoCloseable {
    /**
     * Returns false when the OS cannot express this notification (or is unreachable); the caller
     * then shows it with the in-app windows instead.
     */
    fun show(notification: RichNotification): Boolean

    fun dismiss(id: String)

    fun dismissAll()
}

/** What a backend needs from the center. */
internal class NativeContext(
    val appName: String,
    val appId: String?,
    val appIcon: NotificationImage?,
    /** Runs a user callback on the AWT event thread, guarded against exceptions. */
    val dispatch: (() -> Unit) -> Unit,
) {
    fun dismissed(notification: RichNotification, reason: DismissReason) {
        val callback = notification.onDismiss ?: return
        dispatch { callback(reason) }
    }
}

internal object NativeBackends {
    /** The backend for this OS, or null when there is none (macOS, old Windows, no session bus...). */
    fun create(context: NativeContext): NativeBackend? {
        val os = System.getProperty("os.name", "").lowercase()
        return try {
            when {
                os.contains("win") -> WindowsToasts.create(context)
                os.contains("mac") -> null
                else -> LinuxNotifications.create(context)
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Operating systems take pictures as local files: this turns any [NotificationImage] into a PNG on disk. */
internal class ImageFiles(private val directory: File) {
    private var counter = 0
    private val known = LinkedHashMap<NotificationImage, File>()

    /** The PNG for [source], or null when it cannot be loaded. [round] crops it to a circle. */
    @Synchronized
    fun file(source: NotificationImage?, round: Boolean = false, maxSize: Int = 1024): File? {
        if (source == null) return null
        val cacheable = !round && (source is NotificationImage.Url || source is NotificationImage.File || source is NotificationImage.Resource)
        if (cacheable) known[source]?.takeIf { it.isFile }?.let { return it }
        return try {
            val image = ImageLoader.loadBlocking(source) ?: return null
            val prepared = when {
                round -> ImageLoader.cover(image, 96, 96, 96)
                image.width > maxSize || image.height > maxSize -> {
                    val scale = maxSize.toDouble() / maxOf(image.width, image.height)
                    ImageLoader.cover(image, (image.width * scale / 2).toInt().coerceAtLeast(1), (image.height * scale / 2).toInt().coerceAtLeast(1), 0)
                }
                else -> image
            }
            directory.mkdirs()
            val file = File(directory, "image-${++counter}.png")
            if (!ImageIO.write(prepared, "png", file)) return null
            if (cacheable) known[source] = file
            prune()
            file
        } catch (_: Exception) {
            null
        }
    }

    private fun prune() {
        val files = directory.listFiles { candidate -> candidate.name.startsWith("image-") }?.sortedBy { it.lastModified() } ?: return
        files.dropLast(MAX_FILES).forEach { stale ->
            stale.delete()
            known.values.remove(stale)
        }
    }

    fun deleteAll() {
        directory.listFiles()?.forEach { it.delete() }
        directory.delete()
    }

    companion object {
        private const val MAX_FILES = 40

        fun temporary(): ImageFiles = ImageFiles(Files.createTempDirectory("fcm-desktop-notifications").toFile())
    }
}
