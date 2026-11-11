package io.github.eliasmp07.fcmdesktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Persists the registration (device ids, encryption keys and the FCM token) between runs, so the
 * app keeps the same token. The blob is an opaque JSON string that contains secrets: store it
 * somewhere only the current user can read.
 */
public interface CredentialStore {
    public fun load(): String?
    public fun save(value: String)
    public fun clear()
}

/** Keeps the registration in memory only: a new token is created on every launch. Useful for tests. */
public class InMemoryCredentialStore : CredentialStore {
    @Volatile
    private var value: String? = null

    override fun load(): String? = value
    override fun save(value: String) {
        this.value = value
    }

    override fun clear() {
        value = null
    }
}

/** Stores the registration in a file readable only by the current user (where the OS supports it). */
public class FileCredentialStore(private val file: File) : CredentialStore {

    override fun load(): String? = try {
        if (file.isFile) file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() } else null
    } catch (_: Exception) {
        null
    }

    @Synchronized
    override fun save(value: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(value, Charsets.UTF_8)
        restrictToOwner(temp)
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        restrictToOwner(file)
    }

    @Synchronized
    override fun clear() {
        file.delete()
    }

    private fun restrictToOwner(target: File) {
        try {
            Files.setPosixFilePermissions(
                target.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: Exception) {
            // Windows: the per-user profile folder already limits access to the current user.
        }
    }

    public companion object {
        /**
         * `%APPDATA%\<appName>` on Windows, `~/Library/Application Support/<appName>` on macOS and
         * `$XDG_CONFIG_HOME/<appName>` (or `~/.config/<appName>`) elsewhere.
         */
        public fun default(appName: String): FileCredentialStore {
            // A single folder name: no separators, and never "." or "..".
            val safeName = appName.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim(' ', '.').ifEmpty { "fcm-desktop" }
            val os = System.getProperty("os.name", "").lowercase()
            val home = System.getProperty("user.home", ".")
            val base = when {
                os.contains("win") -> System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let(::File)
                    ?: File(home, "AppData/Roaming")
                os.contains("mac") -> File(home, "Library/Application Support")
                else -> System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
                    ?: File(home, ".config")
            }
            return FileCredentialStore(File(File(base, safeName), "fcm-registration.json"))
        }
    }
}
