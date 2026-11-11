package io.github.eliasmp07.fcmdesktop.notifications

import com.sun.net.httpserver.HttpServer
import io.github.eliasmp07.fcmdesktop.DesktopNotification
import io.github.eliasmp07.fcmdesktop.RemoteMessage
import io.github.eliasmp07.fcmdesktop.RemoteNotification
import io.github.eliasmp07.fcmdesktop.notifications.internal.CardListener
import io.github.eliasmp07.fcmdesktop.notifications.internal.ImageLoader
import io.github.eliasmp07.fcmdesktop.notifications.internal.NotificationCard
import io.github.eliasmp07.fcmdesktop.notifications.internal.Palette
import io.github.eliasmp07.fcmdesktop.notifications.internal.TextLayouts
import kotlinx.serialization.json.JsonObject
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Everything here runs without a display. */
class NotificationPartsTest {

    private val metrics: FontMetrics =
        BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().getFontMetrics(Font(Font.SANS_SERIF, Font.PLAIN, 13))

    private fun png(width: Int, height: Int, color: Color = Color.RED): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            this.color = color
            fillRect(0, 0, width, height)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    // ---- text ----

    @Test
    fun wrapsWordsWithinTheWidth() {
        val lines = TextLayouts.wrap("uno dos tres cuatro cinco seis siete ocho nueve diez", metrics, 80, 10)
        assertTrue(lines.size > 1)
        assertTrue(lines.all { metrics.stringWidth(it) <= 80 }, lines.toString())
        assertEquals("uno dos tres cuatro cinco seis siete ocho nueve diez", lines.joinToString(" "))
    }

    @Test
    fun truncatesWithAnEllipsis() {
        val lines = TextLayouts.wrap("uno dos tres cuatro cinco seis siete ocho nueve diez", metrics, 80, 2)
        assertEquals(2, lines.size)
        assertTrue(lines.last().endsWith("…"), lines.toString())
        assertTrue(lines.all { metrics.stringWidth(it) <= 80 }, lines.toString())
    }

    @Test
    fun breaksWordsLongerThanALine() {
        val word = "x".repeat(200)
        val lines = TextLayouts.wrap(word, metrics, 80, 50)
        assertTrue(lines.size > 2)
        assertTrue(lines.all { metrics.stringWidth(it) <= 80 })
        assertEquals(word, lines.joinToString(""))
    }

    @Test
    fun keepsLineBreaksAndIgnoresBlankEdges() {
        assertEquals(listOf("a", "", "b"), TextLayouts.wrap("\n\na\n\nb\n\n", metrics, 200, 10))
        assertEquals(emptyList(), TextLayouts.wrap("", metrics, 200, 3))
        assertEquals(emptyList(), TextLayouts.wrap("  \n ", metrics, 200, 3))
        assertEquals(listOf("a", "b…"), TextLayouts.wrap("a\nb\nc", metrics, 200, 2))
    }

    @Test
    fun doesNotSplitSurrogatePairs() {
        val emoji = "😀".repeat(40)
        for (line in TextLayouts.wrap(emoji, metrics, 60, 100)) {
            assertEquals(0, line.length % 2, "a surrogate pair was split")
            assertTrue(!line.first().isLowSurrogate() && !line.last().isHighSurrogate())
        }
        val short = TextLayouts.ellipsize(emoji, metrics, 60)
        assertTrue(short.endsWith("…") && !short.dropLast(1).last().isHighSurrogate())
    }

    // ---- images ----

    @Test
    fun decodesAndSubsamplesImages() {
        val small = assertNotNull(ImageLoader.decode(png(40, 30)))
        assertEquals(40 to 30, small.width to small.height)
        val big = assertNotNull(ImageLoader.decode(png(4100, 2050)))
        assertTrue(big.width in 1024..2100, "subsampled width was ${big.width}")
        assertNull(ImageLoader.decode("definitely not an image".toByteArray()))
        assertFailsWith<IOException> { ImageLoader.decode(ByteArray(ImageLoader.MAX_BYTES + 1)) }
    }

    @Test
    fun coverCropsToTheRequestedBox() {
        val source = assertNotNull(ImageLoader.decode(png(300, 100, Color.BLUE)))
        val result = ImageLoader.cover(source, 44, 44, 44)
        assertEquals(88 to 88, result.width to result.height) // rendered at 2x
        assertEquals(0, result.getRGB(0, 0) ushr 24, "the corner must be transparent")
        assertEquals(0xFF0000FF.toInt(), result.getRGB(44, 44))
    }

    @Test
    fun downloadsOverHttpWithLimits() {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        fun serve(path: String, status: Int, body: ByteArray) = server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        serve("/ok.png", 200, png(20, 10))
        serve("/missing.png", 404, png(20, 10))
        serve("/huge.png", 200, ByteArray(ImageLoader.MAX_BYTES + 1024))
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals(20, ImageLoader.loadBlocking(NotificationImage.Url("$base/ok.png"))?.width)
            assertFailsWith<IOException> { ImageLoader.loadBlocking(NotificationImage.Url("$base/missing.png")) }
            assertFailsWith<IOException> { ImageLoader.loadBlocking(NotificationImage.Url("$base/huge.png")) }
            assertFailsWith<IOException> { ImageLoader.loadBlocking(NotificationImage.Url("file:///etc/passwd")) }
            assertFailsWith<IOException> { ImageLoader.loadBlocking(NotificationImage.Url("jar:file:/x.jar!/a.png")) }
        } finally {
            server.stop(0)
        }
    }

    // ---- card ----

    private class Recorder : CardListener {
        val events = ArrayList<String>()
        override fun onBodyClicked() { events += "body" }
        override fun onCloseClicked() { events += "close" }
        override fun onAction(index: Int) { events += "action:$index" }
        override fun onReply(text: String) { events += "reply:$text" }
        override fun onHeightChanged() { events += "height" }
    }

    private fun card(notification: RichNotification, recorder: Recorder = Recorder()): NotificationCard {
        lateinit var card: NotificationCard
        SwingUtilities.invokeAndWait { card = NotificationCard(notification, "App", null, Palette.of(false, null), 380, 0, recorder) }
        return card
    }

    private fun settle() {
        Thread.sleep(400) // image loads finish on a background thread, then post to the event thread
        SwingUtilities.invokeAndWait { }
    }

    @Test
    fun cardGrowsWithItsContent() {
        val plain = card(RichNotification("Title", "Body")).preferredSize.height
        val withActions = card(RichNotification("Title", "Body", actions = listOf(NotificationAction("Ok") {}))).preferredSize.height
        val withReply = card(RichNotification("Title", "Body", reply = NotificationReply { })).preferredSize.height
        val withImage = card(RichNotification("Title", "Body", image = NotificationImage.Bytes(png(400, 200)))).preferredSize.height
        assertTrue(withActions > plain && withReply > plain && withImage > withActions)
        assertEquals(380, card(RichNotification("Title")).preferredSize.width)
    }

    @Test
    fun cardReleasesTheSpaceOfAPictureThatFailsToLoad() {
        val recorder = Recorder()
        val broken = card(RichNotification("Title", "Body", image = NotificationImage.Bytes("nope".toByteArray())), recorder)
        settle()
        val withPicture = card(RichNotification("Title", "Body", image = NotificationImage.Bytes(png(400, 200))))
        settle()
        assertEquals(card(RichNotification("Title", "Body")).preferredSize.height, broken.preferredSize.height)
        assertTrue(broken.preferredSize.height < withPicture.preferredSize.height)
        assertEquals(listOf("height"), recorder.events)
    }

    @Test
    fun cardForwardsInteractions() {
        val recorder = Recorder()
        val card = card(
            RichNotification("Title", actions = listOf(NotificationAction("A") {}, NotificationAction("B") {}), reply = NotificationReply { }),
            recorder,
        )
        SwingUtilities.invokeAndWait {
            card.pressActionForTest(1)
            card.sendReplyForTest("   ") // blank replies are ignored
            card.sendReplyForTest("  hola  ")
        }
        assertEquals(listOf("action:1", "reply:hola"), recorder.events)
    }

    @Test
    fun cardPaintsWithoutADisplay() {
        val card = card(
            RichNotification(
                "Title", "Body 😀",
                largeIcon = NotificationImage.Bytes(png(64, 64)),
                image = NotificationImage.Bytes(png(400, 200, Color.GREEN)),
                actions = listOf(NotificationAction("Ok") {}),
                reply = NotificationReply { },
            ),
        )
        settle()
        val image = BufferedImage(card.preferredSize.width, card.preferredSize.height, BufferedImage.TYPE_INT_ARGB)
        SwingUtilities.invokeAndWait {
            card.size = card.preferredSize
            val g = image.createGraphics()
            card.paint(g)
            g.dispose()
        }
        val pixels = (0 until image.height).flatMap { y -> (0 until image.width step 7).map { x -> image.getRGB(x, y) } }.toSet()
        assertTrue(0xFF00FF00.toInt() in pixels, "the picture was not painted")
        assertTrue(0xFFFFFFFF.toInt() in pixels, "the background was not painted")
    }

    // ---- model and FCM mapping ----

    @Test
    fun validatesNotifications() {
        val four = List(4) { NotificationAction("a$it") {} }
        assertFailsWith<IllegalArgumentException> { RichNotification("t", actions = four) }
        assertFailsWith<IllegalArgumentException> { RichNotification("t", timeoutMillis = -1) }
        assertFailsWith<IllegalArgumentException> { NotificationCenterConfig(maxVisible = 0) }
        assertTrue(RichNotification("a").id != RichNotification("a").id)
    }

    private fun push(imageUrl: String?, collapseKey: String?): DesktopNotification {
        val message = RemoteMessage(
            messageId = "m", from = null,
            notification = RemoteNotification("T", "B", imageUrl, null, null),
            data = mapOf("k" to "v"), collapseKey = collapseKey, link = null, sentTime = 0, ttl = 0, persistentId = null,
            raw = JsonObject(emptyMap()),
        )
        return DesktopNotification("T", "B", message)
    }

    @Test
    fun mapsPushesToRichNotifications() {
        var clicked: RemoteMessage? = null
        val rich = RichDesktopNotifier.defaultNotification(push("https://example.com/a.png", "chat-7")) { clicked = it }
        assertEquals("T" to "B", rich.title to rich.body)
        assertEquals(NotificationImage.Url("https://example.com/a.png"), rich.image)
        assertEquals("fcm:chat-7", rich.id)
        rich.onClick?.invoke()
        assertEquals(mapOf("k" to "v"), clicked?.data)

        val plain = RichDesktopNotifier.defaultNotification(push(" ", "do_not_collapse"))
        assertNull(plain.image)
        assertNull(plain.onClick)
        assertTrue(!plain.id.startsWith("fcm:"))
    }
}
