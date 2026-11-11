package io.github.eliasmp07.fcmdesktop.notifications

import io.github.eliasmp07.fcmdesktop.notifications.internal.NotificationCard
import org.junit.Assume
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JDialog
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opens real windows, so it only runs when a display is available (e.g. under `xvfb-run`). */
class NotificationCenterTest {
    private val events = CopyOnWriteArrayList<String>()
    private lateinit var center: DesktopNotificationCenter

    @BeforeTest
    fun setUp() {
        Assume.assumeFalse("needs a display", GraphicsEnvironment.isHeadless())
        center = DesktopNotificationCenter(
            NotificationCenterConfig(appName = "Test", theme = NotificationTheme.Light, maxVisible = 2, defaultTimeoutMillis = 0),
        )
    }

    @AfterTest
    fun tearDown() {
        if (::center.isInitialized) {
            center.close()
            flush()
        }
    }

    private fun flush() = SwingUtilities.invokeAndWait { }

    private fun <T> onEdt(block: () -> T): T {
        var result: T? = null
        SwingUtilities.invokeAndWait { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun cards(): List<NotificationCard> = onEdt {
        Window.getWindows().filter { it.isShowing }.mapNotNull { (it as? JDialog)?.contentPane as? NotificationCard }
    }

    private fun windows(): List<Window> = onEdt { Window.getWindows().filter { it.isShowing && (it as? JDialog)?.contentPane is NotificationCard } }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what; events=$events" }
            Thread.sleep(20)
        }
    }

    private fun notification(name: String, timeout: Long? = null, reply: Boolean = false) = RichNotification(
        title = name,
        id = name,
        timeoutMillis = timeout,
        actions = listOf(
            NotificationAction("Keep", dismissOnClick = false) { events += "$name:keep" },
            NotificationAction("Go") { events += "$name:go" },
        ),
        reply = if (reply) NotificationReply { events += "$name:reply:$it" } else null,
        onClick = { events += "$name:click" },
        onDismiss = { events += "$name:dismissed:$it" },
    )

    @Test
    fun showsStacksAndQueues() {
        center.show(notification("a"))
        center.show(notification("b"))
        center.show(notification("c"))
        await("two windows") { windows().size == 2 }
        Thread.sleep(600) // let the slide-in settle

        val (first, second) = windows().sortedBy { it.y }
        assertEquals(first.x, second.x)
        assertTrue(first.y + first.height <= second.y + 30, "the windows overlap: $first / $second")
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
        assertTrue(windows().all { screen.contains(it.bounds) }, "a window is off screen")
        assertTrue(windows().none { it.isFocused }, "a notification stole the focus")

        // Dismissing one makes room for the queued one.
        center.dismiss("a")
        await("c to appear") { events.contains("a:dismissed:Cancelled") && windows().size == 2 }
        assertEquals(listOf("a:dismissed:Cancelled"), events.toList())

        center.dismissAll()
        await("all gone") { windows().isEmpty() }
        assertEquals(setOf("a:dismissed:Cancelled", "b:dismissed:Cancelled", "c:dismissed:Cancelled"), events.toSet())
    }

    @Test
    fun replacesByIdAndDismissesFromTheQueue() {
        center.show(notification("a"))
        await("a") { windows().size == 1 }
        val before = windows().single()

        center.show(notification("a").copy(body = "updated\nwith\nmore\nlines"))
        flush()
        assertEquals(1, windows().size)
        assertTrue(windows().single() === before, "the window must be reused")
        assertTrue(windows().single().height > 0)
        assertEquals(emptyList(), events.toList())

        center.show(notification("b"))
        center.show(notification("c")) // queued: maxVisible is 2
        center.dismiss("c")
        await("c dismissed") { events.contains("c:dismissed:Cancelled") }
        assertEquals(2, windows().size)
    }

    @Test
    fun timesOut() {
        center.show(notification("quick", timeout = 300))
        center.show(notification("sticky")) // defaultTimeoutMillis = 0
        await("timeout") { events.contains("quick:dismissed:Timeout") }
        Thread.sleep(500)
        assertEquals(listOf("quick:dismissed:Timeout"), events.toList())
        assertEquals(1, windows().size)
    }

    @Test
    fun actionsRepliesAndClicks() {
        center.show(notification("a", reply = true))
        await("a") { cards().size == 1 }
        val card = cards().single()

        onEdt { card.pressActionForTest(0) } // dismissOnClick = false
        assertEquals(listOf("a:keep"), events.toList())
        assertEquals(1, windows().size)

        onEdt { card.sendReplyForTest("hola") }
        await("reply") { windows().isEmpty() }
        assertEquals(listOf("a:keep", "a:reply:hola", "a:dismissed:Replied"), events.toList())

        events.clear()
        center.show(notification("b"))
        await("b") { cards().size == 1 }
        val second = cards().single()
        onEdt { second.pressActionForTest(1) }
        await("action") { windows().isEmpty() }
        assertEquals(listOf("b:go", "b:dismissed:Action"), events.toList())
    }

    @Test
    fun aFailingCallbackDoesNotBreakTheCenter() {
        center.show(RichNotification("boom", id = "boom", actions = listOf(NotificationAction("x") { error("callback failure") })))
        await("boom") { cards().size == 1 }
        val boom = cards().single()
        onEdt { boom.pressActionForTest(0) }
        await("dismissed") { windows().isEmpty() }
        center.show(notification("after"))
        await("after") { windows().size == 1 }
    }

    @Test
    fun ignoresShowAfterClose() {
        center.show(notification("a"))
        await("a") { windows().size == 1 }
        center.close()
        await("closed") { windows().isEmpty() }
        center.show(notification("b"))
        flush()
        Thread.sleep(200)
        assertTrue(windows().isEmpty())
    }
}
