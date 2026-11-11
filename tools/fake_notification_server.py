"""A stand-in for the desktop's notification service, used by LinuxNotificationsTest.

It owns org.freedesktop.Notifications on the session bus, records what it is asked to show and
lets the test play the user through the io.github.eliasmp07.FakeDesktop interface.

    pip install dbus-next
    dbus-run-session -- sh -c 'python tools/fake_notification_server.py & FCM_DESKTOP_DBUS_TEST=1 ./gradlew test'

Set FAKE_CAPABILITIES (comma separated) to change what the service claims to support.
"""

import asyncio
import os

from dbus_next import Variant
from dbus_next.aio import MessageBus
from dbus_next.service import ServiceInterface, method, signal

CAPABILITIES = os.environ.get("FAKE_CAPABILITIES", "actions,body,body-markup,icon-static,inline-reply").split(",")


class Notifications(ServiceInterface):
    def __init__(self):
        super().__init__("org.freedesktop.Notifications")
        self.next_id = 0
        self.shown = {}

    @method()
    def GetCapabilities(self) -> "as":
        return CAPABILITIES

    @method()
    def Notify(self, app_name: "s", replaces_id: "u", app_icon: "s", summary: "s", body: "s",
               actions: "as", hints: "a{sv}", expire_timeout: "i") -> "u":
        if replaces_id:
            nid = replaces_id
        else:
            self.next_id += 1
            nid = self.next_id
        self.shown[nid] = {
            "app_name": app_name, "replaces_id": str(replaces_id), "app_icon": app_icon, "summary": summary,
            "body": body, "actions": "|".join(actions), "expire_timeout": str(expire_timeout),
            **{"hint:" + key: str(value.value) for key, value in hints.items()},
        }
        return nid

    @method()
    def CloseNotification(self, nid: "u"):
        if self.shown.pop(nid, None) is not None:
            self.NotificationClosed(nid, 3)

    @signal()
    def NotificationClosed(self, nid: "u", reason: "u") -> "uu":
        return [nid, reason]

    @signal()
    def ActionInvoked(self, nid: "u", key: "s") -> "us":
        return [nid, key]

    @signal()
    def NotificationReplied(self, nid: "u", text: "s") -> "us":
        return [nid, text]


class FakeDesktop(ServiceInterface):
    """What a user (or the desktop) could do to a notification."""

    def __init__(self, notifications: Notifications):
        super().__init__("io.github.eliasmp07.FakeDesktop")
        self.notifications = notifications

    @method()
    def Ids(self) -> "as":
        return [str(nid) for nid in sorted(self.notifications.shown)]

    @method()
    def Describe(self, nid: "u") -> "as":
        return [f"{key}={value}" for key, value in sorted(self.notifications.shown.get(nid, {}).items())]

    @method()
    def Invoke(self, nid: "u", key: "s"):
        self.notifications.ActionInvoked(nid, key)

    @method()
    def Reply(self, nid: "u", text: "s"):
        self.notifications.NotificationReplied(nid, text)

    @method()
    def Close(self, nid: "u", reason: "u"):
        self.notifications.shown.pop(nid, None)
        self.notifications.NotificationClosed(nid, reason)

    @method()
    def Reset(self):
        self.notifications.shown.clear()


async def main():
    bus = await MessageBus().connect()
    notifications = Notifications()
    bus.export("/org/freedesktop/Notifications", notifications)
    bus.export("/org/freedesktop/Notifications", FakeDesktop(notifications))
    await bus.request_name("org.freedesktop.Notifications")
    print("fake notification service ready", flush=True)
    await asyncio.get_event_loop().create_future()


if __name__ == "__main__":
    asyncio.run(main())
