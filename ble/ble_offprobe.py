"""Probe a powered-off camera: print raw WinRT advertisement type (connectable or not), then try to connect.

Usage: python ble_offprobe.py [connect_timeout_s]
"""
import asyncio
import sys
import time

from bleak import BleakClient, BleakScanner

SERVICE_BE80 = "0000be80-0000-1000-8000-00805f9b34fb"
T0 = time.monotonic()


def log(msg: str) -> None:
    print(f"[{time.monotonic() - T0:7.3f}] {msg}", flush=True)


async def main(timeout: float) -> None:
    seen = {}

    def on_adv(dev, adv):
        if SERVICE_BE80 not in adv.service_uuids and dev.address not in seen:
            return
        raw = adv.platform_data[1]  # bleak RawAdvData(adv, scan): WinRT ReceivedEventArgs
        args = raw.adv or raw.scan
        kind = getattr(args, "advertisement_type", None)
        flags = getattr(getattr(args, "advertisement", None), "flags", None)
        key = (dev.address, str(kind), raw.scan is not None)
        if key not in seen:
            seen[key] = dev
            seen[dev.address] = dev
            log(f"adv {dev.address} type={kind} flags={flags} connectable={getattr(args, 'is_connectable', '?')} "
                f"scannable={getattr(args, 'is_scannable', '?')} directed={getattr(args, 'is_directed', '?')} "
                f"scan_response={raw.scan is not None}")

    async with BleakScanner(on_adv):
        await asyncio.sleep(6)

    targets = [d for k, d in seen.items() if isinstance(k, str)]
    for dev in targets:
        log(f"connecting to {dev.address} (timeout {timeout}s)")
        try:
            async with BleakClient(dev, timeout=timeout) as client:
                log(f"CONNECTED to {dev.address}, services: {[s.uuid[4:8] for s in client.services]}")
                await asyncio.sleep(3)
        except Exception as e:  # noqa: BLE001 - probe tool
            log(f"connect failed: {type(e).__name__}: {e}")


if __name__ == "__main__":
    asyncio.run(main(float(sys.argv[1]) if len(sys.argv) > 1 else 40.0))
