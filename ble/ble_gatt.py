"""Connect to the camera over BLE and dump its GATT table (reads every readable characteristic).

Usage: python ble_gatt.py [name_or_address]   (default: first device advertising service BE80)
"""
import asyncio
import sys

from bleak import BleakClient, BleakScanner

SERVICE_BE80 = "0000be80-0000-1000-8000-00805f9b34fb"


async def find_camera(target: str | None):
    def match(dev, adv):
        if target:
            return target.lower() in ((adv.local_name or "").lower(), dev.address.lower())
        return SERVICE_BE80 in adv.service_uuids

    dev = await BleakScanner.find_device_by_filter(match, timeout=15)
    if dev is None:
        sys.exit("camera not found")
    return dev


async def main(target: str | None) -> None:
    dev = await find_camera(target)
    print(f"connecting to {dev.address} {dev.name!r}")
    async with BleakClient(dev, timeout=20) as client:
        print(f"connected, mtu={client.mtu_size}")
        for service in client.services:
            print(f"service {service.uuid}  ({service.description})")
            for ch in service.characteristics:
                props = ",".join(ch.properties)
                line = f"    char {ch.uuid}  handle={ch.handle}  [{props}]  ({ch.description})"
                if "read" in ch.properties:
                    try:
                        value = await client.read_gatt_char(ch)
                        line += f"\n        value: {value.hex(' ')}  {value!r}"
                    except Exception as e:  # noqa: BLE001 - probe tool, report everything
                        line += f"\n        read error: {e}"
                print(line)
                for d in ch.descriptors:
                    print(f"        desc {d.uuid}  handle={d.handle}")


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else None))
