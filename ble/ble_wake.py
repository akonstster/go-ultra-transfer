"""Try to wake a powered-off camera with the Insta360 remote iBeacon (from xaionaro-go/insta360ctl pkg/remote).

iBeacon manufacturer data (company 0x004C): 02 15 | "ORBIT" 00 <6-byte camera id> 00 00 00 00 | major 00 01 | minor 00 01 | C5
While advertising, watch the camera's own advertisement: manufacturer data 22 00 .. = off, 22 01 .. = on.

Usage: python ble_wake.py [--seconds S] ID ...
    ID = 6 ASCII chars (tail of the serial, e.g. ABC123) or a MAC (AA:BB:CC:DD:EE:FF, also tried byte-reversed with suffix 'r')
"""
import argparse
import asyncio
import time

from bleak import BleakScanner
from winrt.windows.devices.bluetooth.advertisement import (
    BluetoothLEAdvertisementPublisher,
    BluetoothLEManufacturerData,
)
from winrt.windows.storage.streams import DataWriter

SERVICE_BE80 = "0000be80-0000-1000-8000-00805f9b34fb"
T0 = time.monotonic()


def log(msg: str) -> None:
    print(f"[{time.monotonic() - T0:7.3f}] {msg}", flush=True)


def parse_id(text: str) -> bytes:
    reverse = text.endswith("r")
    text = text.rstrip("r") if reverse else text
    raw = bytes.fromhex(text.replace(":", "")) if ":" in text else text.encode("ascii")
    if len(raw) != 6:
        raise SystemExit(f"camera id must be 6 bytes: {text}")
    return raw[::-1] if reverse else raw


def beacon_payload(camera_id: bytes) -> bytes:
    uuid = b"ORBIT" + b"\x00" + camera_id + bytes(4)
    return b"\x02\x15" + uuid + b"\x00\x01" + b"\x00\x01" + b"\xc5"


def camera_state(adv) -> str:
    parts = []
    for company, data in adv.manufacturer_data.items():
        raw = company.to_bytes(2, "little") + data
        parts.append(raw.hex(" "))
    return " | ".join(parts)


async def watch(seconds: float, label: str) -> set[str]:
    seen: dict[str, str] = {}

    def on_adv(dev, adv):
        if SERVICE_BE80 in adv.service_uuids:
            state = camera_state(adv)
            if seen.get(dev.address) != state:
                seen[dev.address] = state
                log(f"   [{label}] camera {dev.address} rssi={adv.rssi} manuf={state}")

    async with BleakScanner(on_adv):
        await asyncio.sleep(seconds)
    return set(seen.values())


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("ids", nargs="+")
    ap.add_argument("--seconds", type=float, default=12.0)
    args = ap.parse_args()

    log("baseline (no beacon):")
    await watch(5, "baseline")
    for text in args.ids:
        cam_id = parse_id(text)
        payload = beacon_payload(cam_id)
        md = BluetoothLEManufacturerData()
        md.company_id = 0x004C
        w = DataWriter()
        w.write_bytes(payload)
        md.data = w.detach_buffer()
        pub = BluetoothLEAdvertisementPublisher()
        pub.advertisement.manufacturer_data.append(md)
        pub.start()
        await asyncio.sleep(0.5)
        log(f"beacon id={text} ({cam_id.hex(' ')}) payload={payload.hex(' ')} publisher status={pub.status}")
        try:
            await watch(args.seconds, text)
        finally:
            pub.stop()
        log(f"stopped, status={pub.status}")


if __name__ == "__main__":
    asyncio.run(main())
