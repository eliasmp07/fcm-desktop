package io.github.eliasmp07.fcmdesktop.notifications.internal

import io.github.eliasmp07.fcmdesktop.notifications.DismissReason
import io.github.eliasmp07.fcmdesktop.notifications.RichNotification
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Windows 10/11 toast notifications. The WinRT notification API is reached through one long-lived
 * Windows PowerShell process that shows the toasts and reports what the user does with them, which
 * avoids shipping native code.
 *
 * The helper connects back over a loopback socket and proves itself with a one-time token; the
 * console streams of PowerShell are not used because it consumes its own standard input.
 *
 * Protocol (tab separated, payloads in base64): the helper sends the token, then receives the app
 * registration followed by `S tag xml`, `D tag`, `C` and `Q`. It answers `ready`,
 * `activated tag arguments input`, `dismissed tag reason` and `error tag message`.
 */
internal class WindowsToasts private constructor(
    private val context: NativeContext,
    private val process: Process,
    private val socket: Socket,
    private val images: ImageFiles,
) : NativeBackend {

    private val reader: BufferedReader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
    private val input: BufferedWriter = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
    private val ready = CountDownLatch(1)
    private val lock = Any()
    private val byTag = HashMap<String, RichNotification>()

    @Volatile
    private var failed = false

    private fun start(appId: String, iconFile: File?) {
        Thread({ readEvents() }, "fcm-desktop-toasts").apply { isDaemon = true }.start()
        send(listOf(encode(appId), encode(context.appName), encode(iconFile?.absolutePath.orEmpty())).joinToString("\t"))
        if (!ready.await(STARTUP_SECONDS, TimeUnit.SECONDS) || failed) {
            close()
            throw IllegalStateException("the Windows notification helper did not start")
        }
    }

    override fun show(notification: RichNotification): Boolean {
        if (failed || !process.isAlive) return false
        val xml = toastXml(
            notification,
            largeIconUri = images.file(notification.largeIcon, round = true)?.toURI()?.toString(),
            imageUri = images.file(notification.image)?.toURI()?.toString(),
        )
        val tag = tagFor(notification.id)
        synchronized(lock) { byTag[tag] = notification }
        return send("S\t$tag\t${encode(xml)}")
    }

    override fun dismiss(id: String) {
        val tag = tagFor(id)
        val notification = synchronized(lock) { byTag.remove(tag) } ?: return
        send("D\t$tag")
        context.dismissed(notification, DismissReason.Cancelled)
    }

    override fun dismissAll() {
        val all = synchronized(lock) { byTag.values.toList().also { byTag.clear() } }
        send("C")
        all.forEach { context.dismissed(it, DismissReason.Cancelled) }
    }

    private fun send(line: String): Boolean = try {
        synchronized(input) {
            input.write(line)
            input.write("\n")
            input.flush()
        }
        true
    } catch (_: Exception) {
        failed = true
        false
    }

    private fun readEvents() {
        try {
            reader.forEachLine { line ->
                val parts = line.trim().split('\t')
                when (parts[0]) {
                    "ready" -> ready.countDown()
                    "activated" -> onActivated(parts.getOrNull(1).orEmpty(), decode(parts.getOrNull(2)), decode(parts.getOrNull(3)))
                    "dismissed" -> onDismissed(parts.getOrNull(1).orEmpty(), parts.getOrNull(2).orEmpty())
                }
            }
        } catch (_: Exception) {
            // fall through: the helper is gone
        }
        failed = true
        ready.countDown()
    }

    private fun onActivated(tag: String, arguments: String, text: String) {
        val notification = synchronized(lock) { byTag[tag] } ?: return
        // Pressing anything on a toast closes it, so the notification is over in every case.
        val reason = when {
            arguments == ARG_REPLY -> {
                val reply = notification.reply ?: return
                val trimmed = text.trim()
                if (trimmed.isNotEmpty()) context.dispatch { reply.onReply(trimmed) }
                DismissReason.Replied
            }
            arguments.startsWith(ARG_ACTION) -> {
                val action = notification.actions.getOrNull(arguments.removePrefix(ARG_ACTION).toIntOrNull() ?: -1) ?: return
                context.dispatch(action.onClick)
                DismissReason.Action
            }
            else -> {
                notification.onClick?.let { context.dispatch(it) }
                DismissReason.Clicked
            }
        }
        synchronized(lock) { byTag.remove(tag) }
        send("D\t$tag") // also clear it from the notification history
        context.dismissed(notification, reason)
    }

    private fun onDismissed(tag: String, reason: String) {
        // "TimedOut" only means the banner left the screen: the toast lives on in the notification
        // history and can still be clicked. "ApplicationHidden" is our own removal.
        if (reason != "UserCanceled") return
        val notification = synchronized(lock) { byTag.remove(tag) } ?: return
        context.dismissed(notification, DismissReason.Closed)
    }

    override fun close() {
        // Toasts already delivered stay in the notification history; only the helper goes away.
        send("Q")
        runCatching { socket.close() }
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        images.deleteAll()
    }

    companion object {
        private const val STARTUP_SECONDS = 20L
        private const val ARG_CLICK = "click"
        private const val ARG_REPLY = "reply"
        private const val ARG_ACTION = "action-"
        private const val INPUT_ID = "reply"

        fun create(context: NativeContext): WindowsToasts? {
            val major = System.getProperty("os.version", "0").substringBefore('.').toIntOrNull() ?: 0
            if (major < 10) return null
            val systemRoot = System.getenv("SystemRoot")?.takeIf { it.isNotBlank() } ?: "C:\\Windows"
            val powershell = File(systemRoot, "System32\\WindowsPowerShell\\v1.0\\powershell.exe")
            if (!powershell.isFile) return null

            val appId = context.appId ?: defaultAppId(context.appName)
            // The app icon is referenced from the registry, so it must outlive this process.
            val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(::File)
                ?: File(System.getProperty("user.home"), "AppData\\Local")
            val permanent = ImageFiles(File(localAppData, "fcm-desktop\\${appId.replace(Regex("[^A-Za-z0-9._-]"), "_")}"))
            val iconFile = permanent.file(context.appIcon, maxSize = 256)

            val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = (STARTUP_SECONDS * 1000).toInt()
                val script = "\$port = ${server.localPort}\n\$token = '$token'\n$HELPER_SCRIPT"
                val process = ProcessBuilder(
                    powershell.absolutePath, "-NoLogo", "-NoProfile", "-NonInteractive", "-InputFormat", "None",
                    "-WindowStyle", "Hidden", "-EncodedCommand", Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)),
                ).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                process.outputStream.close()
                try {
                    val socket = server.accept()
                    socket.soTimeout = (STARTUP_SECONDS * 1000).toInt()
                    val reader = socket.getInputStream()
                    // Only the process we just started knows the token.
                    val presented = ByteArray(token.length)
                    var read = 0
                    while (read < presented.size) {
                        val count = reader.read(presented, read, presented.size - read)
                        if (count < 0) break
                        read += count
                    }
                    if (!MessageDigest.isEqual(presented, token.toByteArray(Charsets.US_ASCII)) || reader.read() != '\n'.code) {
                        socket.close()
                        throw IllegalStateException("unexpected connection to the notification helper port")
                    }
                    socket.soTimeout = 0
                    return WindowsToasts(context, process, socket, ImageFiles.temporary()).also { it.start(appId, iconFile) }
                } catch (e: Exception) {
                    process.destroyForcibly()
                    throw e
                }
            }
        }

        /** An AppUserModelID: Windows files notifications under it. */
        fun defaultAppId(appName: String): String {
            val name = appName.replace(Regex("[^A-Za-z0-9]"), "").ifEmpty { "App" }.take(60)
            return "FcmDesktop.$name"
        }

        /** Toast tags are limited in length, so the notification id is hashed. */
        fun tagFor(id: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }

        fun toastXml(notification: RichNotification, largeIconUri: String?, imageUri: String?): String {
            val hasButtons = notification.actions.isNotEmpty() || notification.reply != null
            val xml = StringBuilder()
            xml.append("<toast launch=\"").append(ARG_CLICK).append('"')
            when (val timeout = notification.timeoutMillis) {
                // Windows decides how long a banner stays; these are the two knobs it offers.
                0L -> if (hasButtons) xml.append(" scenario=\"reminder\"") else xml.append(" duration=\"long\"")
                null -> Unit
                else -> if (timeout > 10_000) xml.append(" duration=\"long\"")
            }
            xml.append("><visual><binding template=\"ToastGeneric\">")
            xml.append("<text hint-maxLines=\"2\">").append(escapeXml(notification.title)).append("</text>")
            if (notification.body.isNotBlank()) xml.append("<text>").append(escapeXml(notification.body)).append("</text>")
            if (largeIconUri != null) {
                xml.append("<image placement=\"appLogoOverride\" hint-crop=\"circle\" src=\"").append(escapeXml(largeIconUri)).append("\"/>")
            }
            if (imageUri != null) xml.append("<image src=\"").append(escapeXml(imageUri)).append("\"/>")
            xml.append("</binding></visual>")
            if (hasButtons) {
                xml.append("<actions>")
                notification.reply?.let { reply ->
                    xml.append("<input id=\"").append(INPUT_ID).append("\" type=\"text\" placeHolderContent=\"")
                        .append(escapeXml(reply.placeholder)).append("\"/>")
                    xml.append("<action content=\"").append(escapeXml(reply.sendLabel)).append("\" arguments=\"").append(ARG_REPLY)
                        .append("\" hint-inputId=\"").append(INPUT_ID).append("\"/>")
                }
                notification.actions.forEachIndexed { index, action ->
                    xml.append("<action content=\"").append(escapeXml(action.label)).append("\" arguments=\"")
                        .append(ARG_ACTION).append(index).append("\"/>")
                }
                xml.append("</actions>")
            }
            xml.append("</toast>")
            return xml.toString()
        }

        /** Escapes markup and drops the control characters XML 1.0 does not allow. */
        fun escapeXml(text: String): String {
            val out = StringBuilder(text.length + 16)
            for (c in text) {
                when {
                    c == '&' -> out.append("&amp;")
                    c == '<' -> out.append("&lt;")
                    c == '>' -> out.append("&gt;")
                    c == '"' -> out.append("&quot;")
                    c == '\'' -> out.append("&apos;")
                    c == '\n' || c == '\t' -> out.append(c)
                    c < ' ' || c == '\uFFFE' || c == '\uFFFF' -> Unit
                    else -> out.append(c)
                }
            }
            return out.toString()
        }

        private fun encode(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

        private fun decode(text: String?): String = try {
            String(Base64.getDecoder().decode(text.orEmpty()), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }

        /**
         * Registers the app (a per-user registry key, so Windows knows its name and icon), shows
         * toasts, and reports what happens to them. It never evaluates anything it receives: the
         * lines are split and base64-decoded into plain strings. `port` and `token` are defined by
         * two lines prepended at launch.
         */
        internal val HELPER_SCRIPT = """
            ${'$'}ErrorActionPreference = 'Stop'
            function Dec(${'$'}s) { if (${'$'}s) { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(${'$'}s)) } else { '' } }
            function Enc(${'$'}s) { [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]${'$'}s)) }
            function Forget(${'$'}tag) {
                Get-EventSubscriber | Where-Object { ${'$'}_.SourceIdentifier -like "*|${'$'}tag|*" } | Unregister-Event
                ${'$'}toasts.Remove(${'$'}tag)
            }
            ${'$'}client = New-Object Net.Sockets.TcpClient
            ${'$'}client.Connect([Net.IPAddress]::Loopback, ${'$'}port)
            ${'$'}stream = ${'$'}client.GetStream()
            ${'$'}writer = New-Object IO.StreamWriter(${'$'}stream, (New-Object Text.UTF8Encoding ${'$'}false))
            ${'$'}writer.NewLine = "`n"
            ${'$'}writer.AutoFlush = ${'$'}true
            ${'$'}reader = New-Object IO.StreamReader(${'$'}stream, [Text.Encoding]::ASCII)
            ${'$'}writer.WriteLine(${'$'}token)
            try {
                ${'$'}null = [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime]
                ${'$'}null = [Windows.UI.Notifications.ToastNotification, Windows.UI.Notifications, ContentType = WindowsRuntime]
                ${'$'}null = [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime]
                ${'$'}init = ${'$'}reader.ReadLine().Split("`t")
                ${'$'}appId = Dec ${'$'}init[0]
                ${'$'}key = "HKCU:\Software\Classes\AppUserModelId\${'$'}appId"
                if (-not (Test-Path -LiteralPath ${'$'}key)) {
                    New-Item -Path ${'$'}key -Force | Out-Null
                    Set-ItemProperty -LiteralPath ${'$'}key -Name DisplayName -Value (Dec ${'$'}init[1])
                }
                ${'$'}icon = Dec ${'$'}init[2]
                if (${'$'}icon) { Set-ItemProperty -LiteralPath ${'$'}key -Name IconUri -Value ${'$'}icon }
                ${'$'}manager = [Windows.UI.Notifications.ToastNotificationManager]
                ${'$'}notifier = ${'$'}manager::CreateToastNotifier(${'$'}appId)
            } catch {
                ${'$'}writer.WriteLine("error`t`t" + (Enc ${'$'}_))
                exit 1
            }
            ${'$'}toasts = @{}
            ${'$'}sequence = 0
            ${'$'}writer.WriteLine('ready')
            ${'$'}pending = ${'$'}reader.ReadLineAsync()
            while (${'$'}true) {
                ${'$'}arrived = ${'$'}false
                try { ${'$'}arrived = ${'$'}pending.Wait(150) } catch { break }
                if (${'$'}arrived) {
                    ${'$'}line = ${'$'}pending.Result
                    if (${'$'}null -eq ${'$'}line) { break }
                    ${'$'}pending = ${'$'}reader.ReadLineAsync()
                    ${'$'}parts = ${'$'}line.Split("`t")
                    ${'$'}tag = if (${'$'}parts.Length -gt 1) { ${'$'}parts[1] } else { '' }
                    try {
                        switch (${'$'}parts[0]) {
                            'S' {
                                ${'$'}xml = [Windows.Data.Xml.Dom.XmlDocument]::new()
                                ${'$'}xml.LoadXml((Dec ${'$'}parts[2]))
                                ${'$'}toast = [Windows.UI.Notifications.ToastNotification]::new(${'$'}xml)
                                ${'$'}toast.Tag = ${'$'}tag
                                ${'$'}toast.Group = 'fcm'
                                Forget ${'$'}tag
                                ${'$'}sequence++
                                foreach (${'$'}name in 'Activated', 'Dismissed') {
                                    Register-ObjectEvent -InputObject ${'$'}toast -EventName ${'$'}name -SourceIdentifier "${'$'}name|${'$'}tag|${'$'}sequence" | Out-Null
                                }
                                ${'$'}notifier.Show(${'$'}toast)
                                ${'$'}toasts[${'$'}tag] = @{ Toast = ${'$'}toast; Sequence = ${'$'}sequence }
                                if (${'$'}toasts.Count -gt 200) {
                                    ${'$'}oldest = ${'$'}toasts.GetEnumerator() | Sort-Object { ${'$'}_.Value.Sequence } | Select-Object -First 50
                                    foreach (${'$'}item in @(${'$'}oldest)) { Forget ${'$'}item.Key }
                                }
                            }
                            'D' { ${'$'}manager::History.Remove(${'$'}tag, 'fcm', ${'$'}appId); Forget ${'$'}tag }
                            'C' { ${'$'}manager::History.Clear(${'$'}appId); ${'$'}toasts.Clear() }
                            'Q' { exit 0 }
                        }
                    } catch {
                        ${'$'}writer.WriteLine("error`t${'$'}tag`t" + (Enc ${'$'}_))
                    }
                }
                foreach (${'$'}queued in @(Get-Event)) {
                    try {
                        ${'$'}id = ${'$'}queued.SourceIdentifier.Split('|')
                        ${'$'}current = ${'$'}toasts[${'$'}id[1]]
                        # Events of a toast that was replaced by a newer one with the same tag are stale.
                        if (${'$'}current -and [string]${'$'}current.Sequence -eq ${'$'}id[2]) {
                            if (${'$'}id[0] -eq 'Activated') {
                                ${'$'}data = [Windows.UI.Notifications.ToastActivatedEventArgs]${'$'}queued.SourceArgs[1]
                                ${'$'}text = ''
                                try { if (${'$'}data.UserInput.ContainsKey('reply')) { ${'$'}text = [string]${'$'}data.UserInput['reply'] } } catch { }
                                ${'$'}writer.WriteLine("activated`t" + ${'$'}id[1] + "`t" + (Enc ${'$'}data.Arguments) + "`t" + (Enc ${'$'}text))
                            } else {
                                ${'$'}data = [Windows.UI.Notifications.ToastDismissedEventArgs]${'$'}queued.SourceArgs[1]
                                ${'$'}writer.WriteLine("dismissed`t" + ${'$'}id[1] + "`t" + ${'$'}data.Reason)
                            }
                        }
                    } catch {
                        ${'$'}writer.WriteLine("error`t`t" + (Enc ${'$'}_))
                    } finally {
                        Remove-Event -EventIdentifier ${'$'}queued.EventIdentifier
                    }
                }
            }
        """.trimIndent()
    }
}
