"""Generates src/test/resources/fixtures.json from independent reference implementations.

The Kotlin tests check the hand-written protobuf codec and the Web Push decryption against
bytes produced by protobuf + the firebase-messaging protos and by http_ece.

    pip install firebase-messaging http_ece cryptography protobuf
    python tools/generate_fixtures.py
"""

import base64
import json
import os
import pathlib

import http_ece
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from firebase_messaging.proto.android_checkin_pb2 import (
    DEVICE_CHROME_BROWSER,
    AndroidCheckinProto,
    ChromeBuildProto,
)
from firebase_messaging.proto.checkin_pb2 import AndroidCheckinRequest, AndroidCheckinResponse
from firebase_messaging.proto.mcs_pb2 import (
    DataMessageStanza,
    HeartbeatPing,
    IqStanza,
    LoginRequest,
    LoginResponse,
    SelectiveAck,
)

ANDROID_ID = 5234567890123456789
SECURITY_TOKEN = 17654321098765432109  # larger than a signed 64-bit value on purpose
CHROME_VERSION = "94.0.4606.51"


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def checkin_request(with_ids: bool) -> bytes:
    chrome = ChromeBuildProto()
    chrome.platform = ChromeBuildProto.Platform.PLATFORM_LINUX
    chrome.chrome_version = CHROME_VERSION
    chrome.channel = ChromeBuildProto.Channel.CHANNEL_STABLE
    checkin = AndroidCheckinProto()
    checkin.type = DEVICE_CHROME_BROWSER
    checkin.chrome_build.CopyFrom(chrome)
    payload = AndroidCheckinRequest()
    payload.user_serial_number = 0
    payload.checkin.CopyFrom(checkin)
    payload.version = 3
    if with_ids:
        payload.id = ANDROID_ID
        payload.security_token = SECURITY_TOKEN
    return payload.SerializeToString()


def login_request() -> bytes:
    req = LoginRequest()
    req.adaptive_heartbeat = False
    req.auth_service = LoginRequest.ANDROID_ID
    req.auth_token = str(SECURITY_TOKEN)
    req.id = "chrome-" + CHROME_VERSION
    req.domain = "mcs.android.com"
    req.device_id = f"android-{ANDROID_ID:x}"
    req.network_type = 1
    req.resource = str(ANDROID_ID)
    req.user = str(ANDROID_ID)
    req.use_rmq2 = True
    req.setting.add(name="new_vc", value="1")
    req.received_persistent_id.extend(["0:111%abc", "0:222%def"])
    return req.SerializeToString()


def selective_ack() -> bytes:
    iq = IqStanza()
    iq.type = IqStanza.IqType.SET
    iq.id = ""
    iq.extension.id = 12
    ack = SelectiveAck()
    ack.id.extend(["0:111%abc"])
    iq.extension.data = ack.SerializeToString()
    return iq.SerializeToString()


def heartbeat(last) -> bytes:
    ping = HeartbeatPing()
    if last is not None:
        ping.last_stream_id_received = last
    return ping.SerializeToString()


def checkin_response() -> bytes:
    resp = AndroidCheckinResponse()
    resp.stats_ok = True
    resp.time_msec = 1700000000000
    resp.android_id = ANDROID_ID
    resp.security_token = SECURITY_TOKEN
    resp.version_info = "x"
    return resp.SerializeToString()


def login_response(error: bool) -> bytes:
    resp = LoginResponse()
    resp.id = "chrome-" + CHROME_VERSION
    resp.jid = "user@mcs.android.com"
    if error:
        resp.error.code = 401
        resp.error.message = "Authentication failed"
    resp.server_timestamp = 1700000000000
    return resp.SerializeToString()


def data_message(raw: bytes) -> bytes:
    msg = DataMessageStanza()
    msg.id = "A1B2"
    setattr(msg, "from", "1234567890")
    msg.category = "org.chromium.linux"
    msg.app_data.add(key="subtype", value="wp:app#uuid")
    msg.app_data.add(key="crypto-key", value="dh=AAAA")
    msg.app_data.add(key="encryption", value="salt=BBBB")
    msg.persistent_id = "0:1700000000000000%abcdef"
    msg.ttl = 2419200
    msg.sent = 1700000000123
    msg.raw_data = raw
    msg.immediate_ack = True
    return msg.SerializeToString()


def crypto_case(version: str, content: bytes, rs: int) -> dict:
    receiver = ec.generate_private_key(ec.SECP256R1())
    sender = ec.generate_private_key(ec.SECP256R1())
    point = serialization.PublicFormat.UncompressedPoint
    receiver_pub = receiver.public_key().public_bytes(serialization.Encoding.X962, point)
    sender_pub = sender.public_key().public_bytes(serialization.Encoding.X962, point)
    auth = os.urandom(16)
    salt = os.urandom(16)
    encrypted = http_ece.encrypt(
        content, salt=salt, private_key=sender, dh=receiver_pub, auth_secret=auth, version=version, rs=rs
    )
    return {
        "version": version,
        "recordSize": rs,
        "privateKey": b64(
            receiver.private_bytes(
                serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
            )
        ),
        "publicKey": b64(receiver_pub),
        "authSecret": b64(auth),
        "senderPublicKey": b64(sender_pub),
        "salt": b64(salt),
        "ciphertext": b64(encrypted),
        "plaintext": b64(content),
    }


def main() -> None:
    payload = json.dumps(
        {
            "notification": {"title": "Pedido listo", "body": "Tu pedido #42 ya salió 🚚", "image": "https://example.com/a.png"},
            "data": {"orderId": "42", "screen": "orders"},
            "from": "1234567890",
            "fcmMessageId": "0f4c9f2e-1111-2222-3333-444455556666",
            "priority": "high",
        },
        ensure_ascii=False,
    ).encode()
    long_payload = (json.dumps({"data": {"blob": "x" * 9000}})).encode()
    fixtures = {
        "androidId": str(ANDROID_ID),
        "securityToken": str(SECURITY_TOKEN),
        "chromeVersion": CHROME_VERSION,
        "proto": {
            "checkinRequestNew": b64(checkin_request(False)),
            "checkinRequestExisting": b64(checkin_request(True)),
            "checkinResponse": b64(checkin_response()),
            "loginRequest": b64(login_request()),
            "loginResponseOk": b64(login_response(False)),
            "loginResponseError": b64(login_response(True)),
            "selectiveAck": b64(selective_ack()),
            "heartbeatEmpty": b64(heartbeat(None)),
            "heartbeatWithStreamId": b64(heartbeat(7)),
            "dataMessage": b64(data_message(b"\x00\x01\x02\xff")),
        },
        "crypto": [
            crypto_case("aesgcm", payload, 4096),
            crypto_case("aesgcm", long_payload, 4096),
            crypto_case("aes128gcm", payload, 4096),
            crypto_case("aes128gcm", long_payload, 1024),
        ],
    }
    out = pathlib.Path(__file__).resolve().parent.parent / "src" / "test" / "resources" / "fixtures.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(fixtures, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
