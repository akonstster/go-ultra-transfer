"""BLE scan: print every advertising device with name, RSSI, service UUIDs and manufacturer data.

Usage: python ble_scan.py [seconds]
"""
import asyncio
import sys

from bleak import BleakScanner


async def main(seconds: float) -> None:
    found = await BleakScanner.discover(timeout=seconds, return_adv=True)
    rows = sorted(found.values(), key=lambda da: da[1].rssi, reverse=True)
    for dev, adv in rows:
        name = adv.local_name or dev.name or ""
        print(f"{dev.address}  rssi={adv.rssi:4}  name={name!r}")
        for uuid in adv.service_uuids:
            print(f"    service   {uuid}")
        for company, data in adv.manufacturer_data.items():
            print(f"    manuf     0x{company:04x}: {data.hex(' ')}")
        for uuid, data in adv.service_data.items():
            print(f"    svc_data  {uuid}: {data.hex(' ')}")
        if adv.tx_power is not None:
            print(f"    tx_power  {adv.tx_power}")
    print(f"-- {len(rows)} devices")


if __name__ == "__main__":
    asyncio.run(main(float(sys.argv[1]) if len(sys.argv) > 1 else 10.0))
