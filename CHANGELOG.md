# Changelog

All notable changes to this project are listed here. Versions follow [Semantic Versioning](https://semver.org); while the version is 0.x, minor releases may change the API.

## [0.3.1] - 2026-11-11

### Fixed
- Windows native notifications no longer fail silently. A toast is confirmed by Windows before it counts as shown; a rejected or unconfirmed one falls back to the in-app window.
- A failure to subscribe to toast events no longer prevents the toast from being displayed.

### Added
- The reason is logged when Windows rejects a toast, when notifications are turned off for the app, or when the helper cannot start.
- `Push received` log line for every push that reaches the library.
- `FcmLogger.Verbose`, which traces every frame exchanged with FCM.

## [0.3.0] - 2026-11-11

### Added
- `NotificationBackend.Native`: send `RichNotification`s through the operating system, so they appear in its notification history and follow Do Not Disturb.
  - Windows 10/11 toast notifications with big picture, avatar, buttons and inline reply.
  - Linux desktop notifications (freedesktop.org service) through a built-in D-Bus client, with no extra dependencies.
  - Automatic fallback to in-app windows for anything the OS cannot show, including everything on macOS.
- `NotificationCenterConfig.appId` to set the AppUserModelID (Windows) or desktop file id (Linux).

### Known limitations
- The Windows backend was released without being run on Windows.

## [0.2.0] - 2026-11-11

### Added
- `DesktopNotificationCenter`: notifications drawn by the library, identical on Windows, macOS and Linux, with big picture, avatar, app icon, up to three action buttons and inline reply.
- Light, dark and system themes, accent color, screen position, stacking with a waiting queue, and timeouts that pause while hovered or while typing.
- `RichDesktopNotifier` to show FCM pushes as rich notifications; pushes with the same collapse key replace each other.
- Images from URLs, files, classpath resources, bytes or AWT images, loaded in the background with size limits.

## [0.1.0] - 2026-11-10

First release.

### Added
- `FcmDesktop`: a regular FCM registration token for desktop (JVM) apps and real-time delivery of `notification` and `data` messages.
- Web Push payload decryption (`aesgcm` and `aes128gcm`) using only the JDK.
- Token persistence across restarts, weekly refresh, automatic re-registration, reconnection with backoff and de-duplicated deliveries.
- `SystemDesktopNotifier` for basic notifications on Windows, macOS and Linux.
- Installation through JitPack.

[0.3.1]: https://github.com/eliasmp07/fcm-desktop/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/eliasmp07/fcm-desktop/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/eliasmp07/fcm-desktop/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/eliasmp07/fcm-desktop/releases/tag/v0.1.0
