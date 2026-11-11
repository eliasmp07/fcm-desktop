package io.github.eliasmp07.fcmdesktop.notifications.internal

import io.github.eliasmp07.fcmdesktop.notifications.NotificationImage
import java.awt.AlphaComposite
import java.awt.Image
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/** Loads notification pictures off the UI thread, with limits suited to untrusted push payloads. */
internal object ImageLoader {
    const val MAX_BYTES = 5 * 1024 * 1024
    private const val MAX_PIXELS = 40_000_000L
    private const val TARGET_SIZE = 1024
    private const val CACHE_ENTRIES = 16

    private val threadNumber = AtomicInteger()
    private val executor: ExecutorService = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "fcm-desktop-images-${threadNumber.incrementAndGet()}").apply { isDaemon = true }
    }
    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }
    private val cache = object : LinkedHashMap<String, BufferedImage>(CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BufferedImage>?): Boolean = size > CACHE_ENTRIES
    }

    /** Delivers the image, or null when it cannot be loaded, on the AWT event thread. */
    fun load(source: NotificationImage, callback: (BufferedImage?) -> Unit) {
        val key = (source as? NotificationImage.Url)?.url
        val cached = key?.let { synchronized(cache) { cache[it] } }
        if (cached != null) {
            SwingUtilities.invokeLater { callback(cached) }
            return
        }
        executor.execute {
            val image = try {
                loadBlocking(source)
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
            if (image != null && key != null) synchronized(cache) { cache[key] = image }
            SwingUtilities.invokeLater { callback(image) }
        }
    }

    fun loadBlocking(source: NotificationImage): BufferedImage? = when (source) {
        is NotificationImage.Url -> decode(download(source.url))
        is NotificationImage.File -> {
            if (source.file.length() > MAX_BYTES) throw IOException("image file is too large")
            decode(source.file.readBytes())
        }
        is NotificationImage.Resource -> {
            val path = source.path.removePrefix("/")
            val stream = (Thread.currentThread().contextClassLoader ?: ImageLoader::class.java.classLoader)
                .getResourceAsStream(path) ?: throw IOException("resource $path not found")
            decode(stream.use { readCapped(it) })
        }
        is NotificationImage.Bytes -> decode(source.bytes)
        is NotificationImage.Awt -> toBuffered(source.image)
    }

    private fun download(url: String): ByteArray {
        val uri = URI.create(url.trim())
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") throw IOException("only http(s) images are supported")
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "image/*")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { body ->
            if (response.statusCode() !in 200..299) throw IOException("HTTP ${response.statusCode()}")
            return readCapped(body)
        }
    }

    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > MAX_BYTES) throw IOException("image is larger than $MAX_BYTES bytes")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Decodes with a pixel-count check first, and subsamples big pictures while reading. */
    fun decode(bytes: ByteArray): BufferedImage? {
        if (bytes.size > MAX_BYTES) throw IOException("image is larger than $MAX_BYTES bytes")
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.setInput(stream, true, true)
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) {
                    throw IOException("image dimensions ${width}x$height are not supported")
                }
                val param = reader.defaultReadParam
                val step = maxOf(1, minOf(width, height) / TARGET_SIZE)
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0)
                return toBuffered(reader.read(0, param))
            } finally {
                reader.dispose()
            }
        }
        return null
    }

    private fun toBuffered(image: Image): BufferedImage? {
        if (image is BufferedImage && image.type == BufferedImage.TYPE_INT_ARGB) return image
        val width = image.getWidth(null)
        val height = image.getHeight(null)
        if (width <= 0 || height <= 0) return null
        val copy = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = copy.createGraphics()
        g.drawImage(image, 0, 0, null)
        g.dispose()
        return copy
    }

    /**
     * Scales [source] to cover a [width] x [height] box (center crop) and rounds its corners with
     * antialiasing. The result is rendered at twice the size so it stays sharp on HiDPI screens.
     */
    fun cover(source: BufferedImage, width: Int, height: Int, arc: Int): BufferedImage {
        val scale = 2
        val w = width * scale
        val h = height * scale
        val result = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = result.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.fill(RoundRectangle2D.Float(0f, 0f, w.toFloat(), h.toFloat(), (arc * scale).toFloat(), (arc * scale).toFloat()))
        g.composite = AlphaComposite.SrcIn
        val ratio = maxOf(w.toDouble() / source.width, h.toDouble() / source.height)
        val drawWidth = Math.ceil(source.width * ratio).toInt()
        val drawHeight = Math.ceil(source.height * ratio).toInt()
        g.drawImage(downscale(source, drawWidth, drawHeight), (w - drawWidth) / 2, (h - drawHeight) / 2, drawWidth, drawHeight, null)
        g.dispose()
        return result
    }

    /** Halves the image step by step: a single big reduction looks jagged even with bicubic. */
    private fun downscale(source: BufferedImage, targetWidth: Int, targetHeight: Int): BufferedImage {
        var current = source
        while (current.width / 2 >= targetWidth && current.height / 2 >= targetHeight) {
            val half = BufferedImage(current.width / 2, current.height / 2, BufferedImage.TYPE_INT_ARGB)
            val g = half.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(current, 0, 0, half.width, half.height, null)
            g.dispose()
            current = half
        }
        return current
    }
}
