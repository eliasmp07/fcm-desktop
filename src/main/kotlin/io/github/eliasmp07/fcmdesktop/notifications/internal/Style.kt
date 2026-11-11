package io.github.eliasmp07.fcmdesktop.notifications.internal

import io.github.eliasmp07.fcmdesktop.notifications.NotificationTheme
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.GraphicsEnvironment
import java.util.concurrent.TimeUnit
import javax.swing.text.StyleContext

internal class Palette(
    val background: Color,
    val border: Color,
    val title: Color,
    val body: Color,
    val muted: Color,
    val field: Color,
    val button: Color,
    val buttonHover: Color,
    val accent: Color,
    val placeholder: Color,
) {
    companion object {
        fun of(dark: Boolean, accent: Int?): Palette = if (dark) {
            Palette(
                background = Color(0x1F2023),
                border = Color(0x3A3C42),
                title = Color(0xF3F4F6),
                body = Color(0xC2C5CC),
                muted = Color(0x8F939C),
                field = Color(0x2B2D32),
                button = Color(0x2E3036),
                buttonHover = Color(0x3A3D44),
                accent = Color(accent ?: 0x8AB4F8),
                placeholder = Color(0x34363C),
            )
        } else {
            Palette(
                background = Color(0xFFFFFF),
                border = Color(0xDFE2E7),
                title = Color(0x1B1C1F),
                body = Color(0x4A4E57),
                muted = Color(0x7B808A),
                field = Color(0xF1F3F6),
                button = Color(0xF1F3F6),
                buttonHover = Color(0xE4E7EC),
                accent = Color(accent ?: 0x1A6FE0),
                placeholder = Color(0xECEEF2),
            )
        }
    }
}

internal object Fonts {
    private val family: String by lazy {
        val os = System.getProperty("os.name", "").lowercase()
        val candidates = when {
            os.contains("win") -> listOf("Segoe UI")
            os.contains("mac") -> listOf("Helvetica Neue", "Lucida Grande")
            else -> listOf("Inter", "Ubuntu", "Cantarell", "Noto Sans", "DejaVu Sans")
        }
        val available = try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toHashSet()
        } catch (_: Exception) {
            emptySet()
        }
        candidates.firstOrNull { it in available } ?: Font.SANS_SERIF
    }

    /** StyleContext returns fonts with glyph fallback, so emoji and CJK text still render. */
    fun get(style: Int, size: Int): Font = StyleContext.getDefaultStyleContext().getFont(family, style, size)
}

internal object SystemTheme {
    /** Asks the OS whether dark mode is on. Null when it cannot be told. */
    fun isDark(): Boolean? {
        val os = System.getProperty("os.name", "").lowercase()
        return try {
            when {
                os.contains("win") -> run(
                    "reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme",
                )?.let { output -> Regex("0x([0-9a-fA-F]+)").find(output)?.groupValues?.get(1)?.toIntOrNull(16)?.let { it == 0 } }
                os.contains("mac") -> run("defaults", "read", "-g", "AppleInterfaceStyle").let { it?.contains("Dark", true) ?: false }
                else -> run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")?.contains("dark", true)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun resolve(theme: NotificationTheme): Boolean = when (theme) {
        NotificationTheme.Dark -> true
        NotificationTheme.Light -> false
        NotificationTheme.System -> isDark() ?: false
    }

    /** Output of the command, or null when it fails or takes longer than a second. */
    private fun run(vararg command: String): String? {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.outputStream.close()
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        return if (process.exitValue() == 0) output else null
    }
}

internal object TextLayouts {
    private const val ELLIPSIS = "…"

    /** Greedy word wrap with hard breaks for long words; the last allowed line ends with an ellipsis. */
    fun wrap(text: String, metrics: FontMetrics, width: Int, maxLines: Int): List<String> {
        val lines = ArrayList<String>()
        val paragraphs = text.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ').split('\n')
        for (paragraph in paragraphs) {
            if (lines.size > maxLines) break
            var current = ""
            for (word in paragraph.split(' ')) {
                if (word.isEmpty()) continue
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (metrics.stringWidth(candidate) <= width) {
                    current = candidate
                    continue
                }
                if (current.isNotEmpty()) lines += current
                // A word wider than the line is broken by characters.
                var rest = word
                while (metrics.stringWidth(rest) > width && lines.size <= maxLines) {
                    val cut = fitting(rest, metrics, width).takeIf { it > 0 } ?: rest.offsetByCodePoints(0, 1)
                    lines += rest.substring(0, cut)
                    rest = rest.substring(cut)
                }
                current = rest
                if (lines.size > maxLines) break
            }
            if (current.isNotEmpty() || lines.isNotEmpty()) lines += current // keeps blank lines between paragraphs
        }
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        if (lines.size <= maxLines) return lines
        val kept = ArrayList(lines.subList(0, maxLines))
        kept[maxLines - 1] = ellipsize(kept[maxLines - 1], metrics, width, force = true)
        return kept
    }

    /** Shortens [text] with an ellipsis so it fits; [force] adds one even when the text already fits. */
    fun ellipsize(text: String, metrics: FontMetrics, width: Int, force: Boolean = false): String {
        if (!force && metrics.stringWidth(text) <= width) return text
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.substring(0, end).trimEnd() + ELLIPSIS) > width) {
            end = text.offsetByCodePoints(end, -1)
        }
        return text.substring(0, end).trimEnd() + ELLIPSIS
    }

    /** Index of the longest prefix (on a code point boundary) that fits in [width]. */
    private fun fitting(text: String, metrics: FontMetrics, width: Int): Int {
        var end = 0
        while (end < text.length) {
            val next = text.offsetByCodePoints(end, 1)
            if (metrics.stringWidth(text.substring(0, next)) > width) break
            end = next
        }
        return end
    }
}
