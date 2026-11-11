package io.github.eliasmp07.fcmdesktop.notifications.internal

import io.github.eliasmp07.fcmdesktop.notifications.NotificationImage
import io.github.eliasmp07.fcmdesktop.notifications.RichNotification
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities

/** What the card asks its owner to do. All calls happen on the AWT event thread. */
internal interface CardListener {
    fun onBodyClicked()
    fun onCloseClicked()
    fun onAction(index: Int)
    fun onReply(text: String)

    /** The preferred height changed (a picture failed to load and its space was released). */
    fun onHeightChanged()
}

/**
 * The visual of one notification. Text and pictures are painted directly; only the interactive
 * parts (close, actions, reply) are child components.
 *
 * @param shadow Transparent margin around the card where the drop shadow is painted; 0 when the
 *   window cannot be translucent (the owner then clips the window to the rounded card).
 */
internal class NotificationCard(
    private val notification: RichNotification,
    private val appName: String?,
    icon: NotificationImage?,
    private val palette: Palette,
    private val cardWidth: Int,
    private val shadow: Int,
    private val listener: CardListener,
) : JPanel(null) {

    private val headerFont = Fonts.get(Font.PLAIN, 12)
    private val titleFont = Fonts.get(Font.BOLD, 14)
    private val bodyFont = Fonts.get(Font.PLAIN, 13)
    private val buttonFont = Fonts.get(Font.BOLD, 12)

    private val closeButton = CloseButton()
    private val actionButtons = notification.actions.mapIndexed { index, action ->
        FlatButton(action.label, filled = false) { listener.onAction(index) }
    }
    private val replyField: ReplyField? = notification.reply?.let { ReplyField(it.placeholder) }
    private val sendButton: FlatButton? = notification.reply?.let { FlatButton(it.sendLabel, filled = true) { submitReply() } }

    // Picture slots: whether space is reserved, and the decoded picture once it arrives.
    private var hasIcon = icon != null
    private var hasLargeIcon = notification.largeIcon != null
    private var hasImage = notification.image != null
    private var iconImage: BufferedImage? = null
    private var largeIconImage: BufferedImage? = null
    private var bigImage: BufferedImage? = null

    private val iconRect = Rectangle()
    private val largeIconRect = Rectangle()
    private val imageRect = Rectangle()
    private val replyRect = Rectangle()
    private var headerBaseline = 0
    private var headerTextX = 0
    private var textX = 0
    private var titleLines: List<String> = emptyList()
    private var bodyLines: List<String> = emptyList()
    private var titleTop = 0
    private var bodyTop = 0
    private var cardHeight = 0

    val isReplyFocused: Boolean get() = replyField?.isFocusOwner == true
    val hasReply: Boolean get() = replyField != null

    init {
        isOpaque = false
        add(closeButton)
        actionButtons.forEach(::add)
        replyField?.let(::add)
        sendButton?.let(::add)
        if (notification.onClick != null) cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) listener.onBodyClicked()
            }
        })
        replyField?.addActionListener { submitReply() }
        relayout()

        icon?.let { source -> ImageLoader.load(source) { loaded(it, Slot.Icon) } }
        notification.largeIcon?.let { source -> ImageLoader.load(source) { loaded(it, Slot.LargeIcon) } }
        notification.image?.let { source -> ImageLoader.load(source) { loaded(it, Slot.Image) } }
    }

    private enum class Slot { Icon, LargeIcon, Image }

    private fun loaded(image: BufferedImage?, slot: Slot) {
        when (slot) {
            Slot.Icon -> {
                hasIcon = image != null
                iconImage = image?.let { ImageLoader.cover(it, ICON, ICON, 4) }
            }
            Slot.LargeIcon -> {
                hasLargeIcon = image != null
                largeIconImage = image?.let { ImageLoader.cover(it, LARGE_ICON, LARGE_ICON, LARGE_ICON) }
            }
            Slot.Image -> {
                hasImage = image != null
                bigImage = image?.let { ImageLoader.cover(it, imageRect.width, imageRect.height, 10) }
            }
        }
        if (image == null) {
            // Release the reserved space.
            val before = cardHeight
            relayout()
            if (cardHeight != before) listener.onHeightChanged()
        }
        repaint()
    }

    private fun submitReply() {
        val text = replyField?.text?.trim().orEmpty()
        if (text.isNotEmpty()) listener.onReply(text)
    }

    override fun getPreferredSize(): Dimension = Dimension(cardWidth + 2 * shadow, cardHeight + 2 * shadow)

    /** Computes every rectangle from the content; called again when a picture slot collapses. */
    private fun relayout() {
        val left = shadow + PADDING
        val right = shadow + cardWidth - PADDING
        val contentWidth = right - left
        var y = shadow + PADDING

        closeButton.setBounds(right - CLOSE + 4, shadow + PADDING - 4, CLOSE, CLOSE)

        val hasHeader = hasIcon || !appName.isNullOrBlank()
        if (hasHeader) {
            val metrics = getFontMetrics(headerFont)
            iconRect.setBounds(left, y, ICON, ICON)
            headerTextX = if (hasIcon) left + ICON + 7 else left
            headerBaseline = y + (ICON - metrics.height) / 2 + metrics.ascent
            y += ICON + 10
        }

        textX = left
        var textWidth = contentWidth
        if (hasLargeIcon) {
            largeIconRect.setBounds(left, y, LARGE_ICON, LARGE_ICON)
            textX = left + LARGE_ICON + 12
            textWidth -= LARGE_ICON + 12
        }
        if (!hasHeader) textWidth -= CLOSE // keep the first lines clear of the close button

        val titleMetrics = getFontMetrics(titleFont)
        val bodyMetrics = getFontMetrics(bodyFont)
        titleLines = TextLayouts.wrap(notification.title, titleMetrics, textWidth, 2)
        bodyLines = TextLayouts.wrap(notification.body, bodyMetrics, textWidth, if (hasImage) 3 else 5)
        titleTop = y
        var textHeight = titleLines.size * titleMetrics.height
        if (bodyLines.isNotEmpty()) {
            if (titleLines.isNotEmpty()) textHeight += 3
            bodyTop = y + textHeight
            textHeight += bodyLines.size * bodyMetrics.height
        }
        val blockHeight = maxOf(textHeight, if (hasLargeIcon) LARGE_ICON else 0)
        if (hasLargeIcon && textHeight < LARGE_ICON) {
            // Center a short text against the avatar.
            val shift = (LARGE_ICON - textHeight) / 2
            titleTop += shift
            bodyTop += shift
        }
        y += blockHeight

        if (hasImage) {
            y += 12
            imageRect.setBounds(left, y, contentWidth, contentWidth * 9 / 19)
            y += imageRect.height
        }

        if (replyField != null && sendButton != null) {
            y += 12
            val sendWidth = sendButton.preferredWidth()
            replyRect.setBounds(left, y, contentWidth - sendWidth - 8, FIELD_HEIGHT)
            replyField.setBounds(replyRect.x + 10, replyRect.y, replyRect.width - 20, replyRect.height)
            sendButton.setBounds(right - sendWidth, y, sendWidth, FIELD_HEIGHT)
            y += FIELD_HEIGHT
        }

        if (actionButtons.isNotEmpty()) {
            y += 10
            val gap = 8
            val width = (contentWidth - gap * (actionButtons.size - 1)) / actionButtons.size
            actionButtons.forEachIndexed { index, button ->
                button.setBounds(left + index * (width + gap), y, width, BUTTON_HEIGHT)
            }
            y += BUTTON_HEIGHT
        }

        cardHeight = y + PADDING - shadow
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            applyTextHints(g)
            paintBackground(g)

            if (hasIcon) paintSlot(g, iconImage, iconRect, 4)
            if (!appName.isNullOrBlank()) {
                g.font = headerFont
                g.color = palette.muted
                val available = closeButton.x - 6 - headerTextX
                g.drawString(TextLayouts.ellipsize(appName, g.fontMetrics, available), headerTextX, headerBaseline)
            }
            if (hasLargeIcon) paintSlot(g, largeIconImage, largeIconRect, LARGE_ICON)

            g.font = titleFont
            g.color = palette.title
            var metrics = g.fontMetrics
            titleLines.forEachIndexed { index, line -> g.drawString(line, textX, titleTop + index * metrics.height + metrics.ascent) }
            g.font = bodyFont
            g.color = palette.body
            metrics = g.fontMetrics
            bodyLines.forEachIndexed { index, line -> g.drawString(line, textX, bodyTop + index * metrics.height + metrics.ascent) }

            if (hasImage) paintSlot(g, bigImage, imageRect, 10)
            if (replyField != null) {
                g.color = palette.field
                g.fill(RoundRectangle2D.Float(replyRect.x.toFloat(), replyRect.y.toFloat(), replyRect.width.toFloat(), replyRect.height.toFloat(), 10f, 10f))
            }
        } finally {
            g.dispose()
        }
    }

    private fun paintBackground(g: Graphics2D) {
        val x = shadow.toFloat()
        val y = shadow.toFloat()
        val w = cardWidth.toFloat()
        val h = cardHeight.toFloat()
        if (shadow > 0) {
            // Soft drop shadow: stacked translucent outlines, slightly offset downwards.
            for (i in shadow downTo 1) {
                // Each layer is nearly transparent; they add up to about 15% black next to the card.
                g.color = Color(0, 0, 0, 1 + (3f * (1f - i.toFloat() / shadow)).toInt())
                g.fill(RoundRectangle2D.Float(x - i, y - i + 3, w + 2 * i, h + 2 * i, RADIUS + 2f * i, RADIUS + 2f * i))
            }
        }
        val arc = RADIUS.toFloat()
        g.color = palette.background
        g.fill(RoundRectangle2D.Float(x, y, w, h, arc, arc))
        g.color = palette.border
        g.stroke = BasicStroke(1f)
        g.draw(RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1f, h - 1f, arc, arc))
    }

    private fun paintSlot(g: Graphics2D, image: BufferedImage?, rect: Rectangle, arc: Int) {
        if (image != null) {
            g.drawImage(image, rect.x, rect.y, rect.width, rect.height, null)
        } else {
            g.color = palette.placeholder
            g.fill(RoundRectangle2D.Float(rect.x.toFloat(), rect.y.toFloat(), rect.width.toFloat(), rect.height.toFloat(), arc.toFloat(), arc.toFloat()))
        }
    }

    private fun applyTextHints(g: Graphics2D) {
        val hints = try {
            Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints") as? Map<*, *>
        } catch (_: Exception) {
            null
        }
        if (hints != null) g.addRenderingHints(hints) else g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }

    /** Test hook: types and sends a reply as the user would. */
    internal fun sendReplyForTest(text: String) {
        replyField?.text = text
        submitReply()
    }

    /** Test hook: presses the action button at [index]. */
    internal fun pressActionForTest(index: Int) = actionButtons[index].press()

    private inner class CloseButton : JComponent() {
        private var hovered = false

        init {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    repaint()
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) listener.onCloseClicked()
                }
            })
        }

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            if (hovered) {
                g.color = palette.buttonHover
                g.fillOval(0, 0, width, height)
            }
            g.color = if (hovered) palette.title else palette.muted
            g.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val inset = 7.5
            g.draw(Line2D.Double(inset, inset, width - inset, height - inset))
            g.draw(Line2D.Double(width - inset, inset, inset, height - inset))
            g.dispose()
        }
    }

    private inner class FlatButton(private val label: String, private val filled: Boolean, private val onPress: () -> Unit) : JComponent() {
        private var hovered = false

        init {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    repaint()
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) press()
                }
            })
        }

        fun press() = onPress()

        fun preferredWidth(): Int = getFontMetrics(buttonFont).stringWidth(label).coerceAtMost(120) + 28

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            applyTextHints(g)
            g.color = when {
                filled -> if (hovered) palette.accent.darker() else palette.accent
                hovered -> palette.buttonHover
                else -> palette.button
            }
            g.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 10f, 10f))
            g.font = buttonFont
            g.color = if (filled) palette.background else palette.accent
            val metrics = g.fontMetrics
            val text = TextLayouts.ellipsize(label, metrics, width - 16)
            g.drawString(text, (width - metrics.stringWidth(text)) / 2, (height - metrics.height) / 2 + metrics.ascent)
            g.dispose()
        }
    }

    private inner class ReplyField(private val placeholder: String) : JTextField() {
        init {
            isOpaque = false
            border = BorderFactory.createEmptyBorder()
            font = bodyFont
            foreground = palette.title
            caretColor = palette.title
            selectionColor = palette.accent
            selectedTextColor = palette.background
            // Smooth typed text even where the desktop publishes no font hints.
            putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) = repaint()
                override fun focusLost(e: FocusEvent) = repaint()
            })
        }

        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            if (text.isEmpty()) {
                val g = graphics.create() as Graphics2D
                applyTextHints(g)
                g.font = font
                g.color = palette.muted
                val metrics = g.fontMetrics
                g.drawString(TextLayouts.ellipsize(placeholder, metrics, width), 0, (height - metrics.height) / 2 + metrics.ascent)
                g.dispose()
            }
        }
    }

    private companion object {
        const val PADDING = 16
        const val RADIUS = 14
        const val ICON = 16
        const val LARGE_ICON = 44
        const val CLOSE = 22
        const val FIELD_HEIGHT = 34
        const val BUTTON_HEIGHT = 32
    }
}
