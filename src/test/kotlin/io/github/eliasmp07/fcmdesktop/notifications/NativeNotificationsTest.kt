package io.github.eliasmp07.fcmdesktop.notifications

import io.github.eliasmp07.fcmdesktop.notifications.internal.DBusConnection
import io.github.eliasmp07.fcmdesktop.notifications.internal.LinuxNotifications
import io.github.eliasmp07.fcmdesktop.notifications.internal.WindowsToasts
import org.junit.Assume
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeNotificationsTest {

    // ---- Windows: the parts that do not need Windows ----

    private fun parse(xml: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream()).documentElement

    @Test
    fun buildsWellFormedToastXml() {
        val notification = RichNotification(
            title = "Ana <b> & \"co\"",
            body = "línea 1\nlínea 2 \u0001 😀 'x'",
            actions = listOf(NotificationAction("Abrir <chat>") {}, NotificationAction("Silenciar") {}),
            reply = NotificationReply(placeholder = "Responder & enviar", sendLabel = "Enviar") {},
            timeoutMillis = 0,
        )
        val xml = WindowsToasts.toastXml(notification, "file:///C:/a%20b/avatar.png", "file:///C:/img.png?x=1&y=2")
        val toast = parse(xml)
        assertEquals("click", toast.getAttribute("launch"))
        assertEquals("reminder", toast.getAttribute("scenario"))

        val texts = toast.getElementsByTagName("text")
        assertEquals("Ana <b> & \"co\"", texts.item(0).textContent)
        assertEquals("línea 1\nlínea 2  😀 'x'", texts.item(1).textContent) // the control character is dropped

        val images = toast.getElementsByTagName("image")
        assertEquals(2, images.length)
        assertEquals("appLogoOverride", images.item(0).attributes.getNamedItem("placement").nodeValue)
        assertEquals("circle", images.item(0).attributes.getNamedItem("hint-crop").nodeValue)
        assertEquals("file:///C:/img.png?x=1&y=2", images.item(1).attributes.getNamedItem("src").nodeValue)

        val input = toast.getElementsByTagName("input").item(0).attributes
        assertEquals("Responder & enviar", input.getNamedItem("placeHolderContent").nodeValue)
        val actions = toast.getElementsByTagName("action")
        assertEquals(
            listOf("Enviar" to "reply", "Abrir <chat>" to "action-0", "Silenciar" to "action-1"),
            (0 until actions.length).map { actions.item(it).attributes.run { getNamedItem("content").nodeValue to getNamedItem("arguments").nodeValue } },
        )
        assertEquals(input.getNamedItem("id").nodeValue, actions.item(0).attributes.getNamedItem("hint-inputId").nodeValue)
    }

    @Test
    fun plainToastHasNoActionsBlock() {
        val toast = parse(WindowsToasts.toastXml(RichNotification("Solo título"), null, null))
        assertEquals(0, toast.getElementsByTagName("actions").length)
        assertEquals(1, toast.getElementsByTagName("text").length)
        assertEquals("", toast.getAttribute("scenario"))
        assertEquals("long", parse(WindowsToasts.toastXml(RichNotification("t", timeoutMillis = 0), null, null)).getAttribute("duration"))
        assertEquals("long", parse(WindowsToasts.toastXml(RichNotification("t", timeoutMillis = 20_000), null, null)).getAttribute("duration"))
        assertEquals("", parse(WindowsToasts.toastXml(RichNotification("t", timeoutMillis = 3_000), null, null)).getAttribute("duration"))
    }

    @Test
    fun derivesStableWindowsIdentifiers() {
        assertEquals(WindowsToasts.tagFor("chat-42"), WindowsToasts.tagFor("chat-42"))
        assertTrue(WindowsToasts.tagFor("chat-42") != WindowsToasts.tagFor("chat-43"))
        assertEquals(16, WindowsToasts.tagFor("x".repeat(500)).length)
        assertEquals("FcmDesktop.MiApp20", WindowsToasts.defaultAppId("Mi App 2.0"))
        assertEquals("FcmDesktop.App", WindowsToasts.defaultAppId("¡¿?!"))
    }

    @Test
    fun helperScriptOnlyDecodesWhatItReceives() {
        val script = WindowsToasts.HELPER_SCRIPT
        assertFalse(script.contains("Invoke-Expression", ignoreCase = true) || Regex("\\biex\\b", RegexOption.IGNORE_CASE).containsMatchIn(script))
        assertTrue(script.lines().first().startsWith("\$ErrorActionPreference"), "the script must not be indented")
    }

    @Test
    fun escapesBodyMarkup() {
        assertEquals("a &lt;b&gt; &amp; c", LinuxNotifications.escapeMarkup("a <b> & c"))
    }

    // ---- Linux: against a real session bus and tools/fake_notification_server.py ----

    private val events = CopyOnWriteArrayList<String>()
    private var center: DesktopNotificationCenter? = null
    private var desktop: DBusConnection? = null

    @AfterTest
    fun tearDown() {
        center?.close()
        desktop?.close()
    }

    private fun fake(member: String, vararg args: Any): List<Any> = desktop!!.call(
        "org.freedesktop.Notifications", "/org/freedesktop/Notifications", "io.github.eliasmp07.FakeDesktop", member, *args,
    )

    @Suppress("UNCHECKED_CAST")
    private fun ids(): List<Int> = (fake("Ids").first() as List<String>).map { it.toInt() }

    @Suppress("UNCHECKED_CAST")
    private fun describe(id: Int): Map<String, String> =
        (fake("Describe", DBusConnection.U32(id)).first() as List<String>).associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what; events=$events" }
            Thread.sleep(20)
        }
    }

    private fun startLinux(limitedDesktop: Boolean = false) {
        Assume.assumeTrue("set FCM_DESKTOP_DBUS_TEST=1 with the fake service running", System.getenv("FCM_DESKTOP_DBUS_TEST") == "1")
        // FAKE_CAPABILITIES=body makes the fake service a desktop without actions or replies.
        Assume.assumeTrue(limitedDesktop == (System.getenv("FAKE_CAPABILITIES") == "body"))
        desktop = DBusConnection.connectToSessionBus()
        fake("Reset")
        center = DesktopNotificationCenter(NotificationCenterConfig(appName = "Mi App", backend = NotificationBackend.Native))
    }

    private fun png(): ByteArray {
        val image = BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { color = Color.ORANGE; fillRect(0, 0, 64, 32); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun notification(name: String) = RichNotification(
        title = "Título $name",
        body = "Hola <b>mundo</b> & más 😀",
        id = name,
        image = NotificationImage.Bytes(png()),
        actions = listOf(NotificationAction("Abrir") { events += "$name:open" }, NotificationAction("Quedarse", dismissOnClick = false) { events += "$name:stay" }),
        reply = NotificationReply(placeholder = "Responder", sendLabel = "Enviar") { events += "$name:reply:$it" },
        onClick = { events += "$name:click" },
        onDismiss = { events += "$name:dismissed:$it" },
    )

    @Test
    fun sendsNotificationsToTheDesktopService() {
        startLinux()
        center!!.show(notification("a"))
        await("notification") { ids().size == 1 }
        val shown = describe(ids().single())
        assertEquals("Mi App", shown["app_name"])
        assertEquals("Título a", shown["summary"])
        assertEquals("Hola &lt;b&gt;mundo&lt;/b&gt; &amp; más 😀", shown["body"])
        assertEquals("default||action-0|Abrir|action-1|Quedarse|inline-reply|Enviar", shown["actions"])
        assertEquals("-1", shown["expire_timeout"])
        assertEquals("Responder", shown["hint:x-kde-reply-placeholder-text"])
        assertEquals("True", shown["hint:resident"])
        val picture = File(assertNotNull(shown["hint:image-path"]))
        assertEquals(64, ImageIO.read(picture).width)

        // Showing the same id again replaces it instead of adding a second one.
        center!!.show(notification("a").copy(title = "Actualizada", timeoutMillis = 0))
        await("replacement") { describe(ids().single())["summary"] == "Actualizada" }
        assertEquals(1, ids().size)
        assertEquals("0", describe(ids().single())["expire_timeout"])
        assertEquals(emptyList(), events.toList())
    }

    @Test
    fun reportsWhatTheUserDoes() {
        startLinux()
        center!!.show(notification("a"))
        await("a") { ids().size == 1 }
        val first = ids().single()

        fake("Invoke", DBusConnection.U32(first), "action-1") // dismissOnClick = false
        await("stay") { events.contains("a:stay") }
        Thread.sleep(200)
        assertEquals(listOf(first), ids())

        fake("Invoke", DBusConnection.U32(first), "action-0")
        await("open") { events.contains("a:dismissed:Action") }
        assertEquals(listOf("a:stay", "a:open", "a:dismissed:Action"), events.toList())
        assertEquals(emptyList(), ids()) // the library closed it

        events.clear()
        center!!.show(notification("b"))
        await("b") { ids().size == 1 }
        fake("Reply", DBusConnection.U32(ids().single()), "  voy en camino  ")
        await("reply") { events.contains("b:dismissed:Replied") }
        assertEquals(listOf("b:reply:voy en camino", "b:dismissed:Replied"), events.toList())

        events.clear()
        center!!.show(notification("c"))
        await("c") { ids().size == 1 }
        fake("Invoke", DBusConnection.U32(ids().single()), "default")
        await("click") { events.contains("c:dismissed:Clicked") }
        assertEquals(listOf("c:click", "c:dismissed:Clicked"), events.toList())

        events.clear()
        center!!.show(notification("d"))
        center!!.show(notification("e"))
        await("d and e") { ids().size == 2 }
        val (d, e) = ids()
        fake("Close", DBusConnection.U32(d), DBusConnection.U32(2)) // the user dismissed it
        fake("Close", DBusConnection.U32(e), DBusConnection.U32(1)) // it expired
        await("closed") { events.size == 2 }
        assertEquals(setOf("d:dismissed:Closed", "e:dismissed:Timeout"), events.toSet())

        // Signals about notifications of other apps are ignored.
        fake("Invoke", DBusConnection.U32(9999), "default")
        Thread.sleep(200)
        assertEquals(2, events.size)
    }

    @Test
    fun dismissesThroughTheService() {
        startLinux()
        center!!.show(notification("a"))
        center!!.show(notification("b"))
        center!!.show(notification("c"))
        await("three") { ids().size == 3 }
        center!!.dismiss("b")
        await("b gone") { events.contains("b:dismissed:Cancelled") }
        assertEquals(2, ids().size)
        center!!.dismissAll()
        await("all gone") { ids().isEmpty() && events.size == 3 }
        assertEquals(setOf("a:dismissed:Cancelled", "b:dismissed:Cancelled", "c:dismissed:Cancelled"), events.toSet())
    }

    @Test
    fun fallsBackToInAppWhenTheDesktopLacksAFeature() {
        Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless())
        startLinux(limitedDesktop = true)
        center!!.show(notification("rich")) // needs actions and inline reply
        center!!.show(RichNotification("Plain", id = "plain", onDismiss = { events += "plain:dismissed:$it" }))
        await("plain shown natively") { ids().size == 1 }
        assertEquals("Plain", describe(ids().single())["summary"])
        await("rich shown in-app") {
            var showing = 0
            SwingUtilities.invokeAndWait { showing = java.awt.Window.getWindows().count { it.isShowing } }
            showing == 1
        }
        center!!.dismissAll()
        await("both dismissed") { events.toSet() == setOf("rich:dismissed:Cancelled", "plain:dismissed:Cancelled") }
    }
}
