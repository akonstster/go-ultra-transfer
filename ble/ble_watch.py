"""Watch the camera's BLE advertisement and print every change of its manufacturer data.

Manufacturer data (company id included): 01 00 22 [state] [counter] 00 — state 00 = asleep, 01 = awake.
Usage: python ble_watch.py [seconds]
"""
import asyncio
import sys
import time

from bleak import BleakScanner

SERVICE_BE80 = "0000be80-0000-1000-8000-00805f9b34fb"


async def main(seconds: float) -> None:
    t0 = time.monotonic()
    last: dict[str, str] = {}

    def on_adv(dev, adv):
        if SERVICE_BE80 not in adv.service_uuids:
            return
        raw = " | ".join((c.to_bytes(2, "little") + d).hex(" ") for c, d in adv.manufacturer_data.items())
        if last.get(dev.address) != raw:
            last[dev.address] = raw
            print(f"[{time.monotonic() - t0:6.1f}s] {dev.address} rssi={adv.rssi} manuf={raw}", flush=True)

    async with BleakScanner(on_adv):
        await asyncio.sleep(seconds)


if __name__ == "__main__":
    asyncio.run(main(float(sys.argv[1]) if len(sys.argv) > 1 else 60.0))
