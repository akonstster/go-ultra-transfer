"""Bond (BLE pairing, Just Works) with the camera, or remove the bond.

Usage: python ble_pair.py [--unpair]
"""
import argparse
import asyncio
import time

from bleak import BleakClient, BleakScanner

SERVICE_BE80 = "0000be80-0000-1000-8000-00805f9b34fb"
T0 = time.monotonic()


def log(msg: str) -> None:
    print(f"[{time.monotonic() - T0:7.3f}] {msg}", flush=True)


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--unpair", action="store_true")
    args = ap.parse_args()

    dev = await BleakScanner.find_device_by_filter(lambda d, a: SERVICE_BE80 in a.service_uuids, timeout=15)
    if dev is None:
        raise SystemExit("camera not found")
    log(f"connecting to {dev.address} {dev.name!r}")
    async with BleakClient(dev, timeout=30) as client:
        log("connected")
        if args.unpair:
            await client.unpair()
            log("unpaired")
            return
        try:
            await client.pair()
            log("pair() returned OK")
        except Exception as e:  # noqa: BLE001 - probe tool
            log(f"pair failed: {type(e).__name__}: {e}")
        await asyncio.sleep(2)
    log("disconnected")


if __name__ == "__main__":
    asyncio.run(main())
