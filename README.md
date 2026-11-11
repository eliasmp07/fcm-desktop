# fcm-desktop

Notificaciones push de **Firebase Cloud Messaging en desktop (JVM)** para apps Kotlin Multiplatform / Compose Desktop.

Firebase no tiene SDK de mensajería para desktop. Esta librería hace lo mismo que hace Chrome cuando una web se suscribe a push: registra el equipo en FCM, obtiene un **token FCM normal** y mantiene un socket abierto con `mtalk.google.com` para recibir los mensajes. Tu backend le manda al token de desktop igual que a los de Android e iOS.

- Token FCM real (`messages:send` de la API HTTP v1, Admin SDK, consola de Firebase).
- Recibe `notification` y `data`, descifra el payload (Web Push `aesgcm` y `aes128gcm`).
- Muestra la notificación nativa (Windows, macOS, Linux) o te deja pintarla tú.
- El token se conserva entre ejecuciones; reconexión automática con backoff.
- Dependencias: solo `kotlinx-coroutines-core` y `kotlinx-serialization-json`. Requiere JDK 11+.

## 1. Configurar Firebase

1. Consola de Firebase → Configuración del proyecto → **Tus apps** → agrega una app **Web** (`</>`). Copia `projectId`, `appId` y `apiKey`.
2. En Google Cloud → APIs y servicios → Credenciales, revisa esa API key: debe permitir **Firebase Installations API** y **FCM Registration API**, y no tener restricción de referrer HTTP, Android o iOS (desde desktop no se puede cumplir ninguna).

No necesitas clave VAPID: si no pasas `vapidKey` se usa la de FCM por defecto.

## 2. Instalar

Copia esta carpeta dentro de tu proyecto como módulo:

```kotlin
// settings.gradle.kts
include(":fcm-desktop")
```

```kotlin
// build.gradle.kts de tu módulo compartido
kotlin {
    sourceSets {
        val desktopMain by getting {          // o jvmMain, según el nombre de tu target
            dependencies {
                implementation(project(":fcm-desktop"))
            }
        }
    }
}
```

Las versiones de coroutines y serialization del `build.gradle.kts` del módulo se pueden igualar a las de tu proyecto.

## 3. Usar

```kotlin
val fcm = FcmDesktop(
    options = FirebaseDesktopOptions(
        projectId = "tu-proyecto",
        appId = "1:1234567890:web:abc123",
        apiKey = "AIza...",
    ),
    config = FcmDesktopConfig(appName = "MiApp"),
)

// El token: mándalo a tu backend cada vez que emita (puede cambiar).
scope.launch { fcm.token.filterNotNull().collect { token -> api.registerPushToken(token, platform = "desktop") } }

// Los mensajes (suscríbete antes de start(); no hay replay).
scope.launch {
    fcm.messages.collect { message ->
        println("${message.notification?.title} -> ${message.data}")
    }
}

fcm.start()      // registra (la primera vez) y se queda escuchando
// fcm.stop()    // corta la conexión
// fcm.deleteToken()  // al cerrar sesión: borra el token en FCM y en disco
// fcm.close()   // al salir de la app
```

`fcm.connectionState` expone `Stopped / Registering / Connecting / Connected / WaitingToReconnect` por si quieres mostrarlo.

### Con Compose Desktop

```kotlin
fun main() = application {
    val trayState = rememberTrayState()
    val fcm = remember {
        FcmDesktop(
            options = FirebaseDesktopOptions(projectId = "...", appId = "...", apiKey = "..."),
            config = FcmDesktopConfig(appName = "MiApp"),
            // Usa el tray de Compose en lugar del notificador por defecto.
            notifier = DesktopNotifier { n -> trayState.sendNotification(Notification(n.title, n.body)) },
        )
    }
    DisposableEffect(Unit) {
        fcm.start()
        onDispose { fcm.close() }
    }
    Tray(state = trayState, icon = painterResource("icon.png"))
    Window(onCloseRequest = ::exitApplication) { App() }
}
```

### Encajarlo con Android/iOS

Define en `commonMain` la interfaz que ya uses para el token y los mensajes, e impleméntala en `desktopMain` sobre `FcmDesktop`:

```kotlin
// commonMain
interface PushService {
    val token: Flow<String>
    fun start()
}

// desktopMain
class DesktopPushService(private val fcm: FcmDesktop) : PushService {
    override val token: Flow<String> = fcm.token.filterNotNull()
    override fun start() = fcm.start()
}
```

### Notificaciones

Por defecto (`SystemDesktopNotifier`) un push con bloque `notification` se muestra solo:

| SO | Cómo |
|---|---|
| Windows | `java.awt.SystemTray` (agrega un icono al tray; pásale el tuyo con `trayIcon` o `icon`) |
| macOS | Centro de notificaciones vía `osascript` (aparece a nombre de "Script Editor") |
| Linux | `notify-send`; si no está, el tray |

Para controlar la UI pasa tu propio `DesktopNotifier`, o `notifier = null` y pinta desde `fcm.messages`. Los mensajes solo-`data` nunca muestran nada.

### Enviar desde el backend

Igual que a cualquier token (API HTTP v1):

```json
{
  "message": {
    "token": "<token de desktop>",
    "notification": { "title": "Pedido listo", "body": "Tu pedido #42 ya salió" },
    "data": { "orderId": "42" },
    "webpush": { "headers": { "TTL": "86400", "Urgency": "high" } }
  }
}
```

Topics: el token de desktop no se puede suscribir desde el cliente; suscríbelo desde el servidor con el Admin SDK (`subscribeToTopic`).

## Límites que conviene saber

- **No es una API oficial.** Usa el protocolo de Chrome (check-in, `register3`, Firebase Installations, FCM Registration y MCS). Google puede cambiarlo; ya retiró la API de registro anterior en junio de 2024.
- **Solo recibe con la app abierta.** Lo enviado mientras está cerrada llega al volver a conectar, si no venció su TTL.
- **Red:** conexión TLS directa a `mtalk.google.com` por el puerto 5228, con 443 como alternativa. No pasa por proxies HTTP corporativos.
- **Clave VAPID propia:** el camino con `vapidKey` no está probado; usa la de por defecto salvo que la necesites.
- El archivo de registro (`fcm-registration.json` en la carpeta de configuración del usuario) contiene las claves privadas del dispositivo. Para guardarlo en otro lado implementa `CredentialStore`.

## Estado de las pruebas

`./gradlew test` corre 23 pruebas:

- El codec protobuf escrito a mano produce los mismos bytes que `protobuf` con los `.proto` de referencia.
- El descifrado Web Push abre mensajes cifrados por `http_ece` (Python), en ambos formatos y con varios registros.
- Un servidor falso local (HTTP + MCS) ejercita el flujo completo: registro, login, entrega, acks, duplicados, reinicio con el mismo token, reconexión, heartbeat, re-registro al rechazar el login, refresco y borrado de token.

Lo que **no** cubren: los servidores reales de Google. Los fixtures se regeneran con `python tools/generate_fixtures.py`.
