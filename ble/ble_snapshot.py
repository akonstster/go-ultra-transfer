"""Read-only snapshot of all camera settings, diffed against the previous snapshot.

Reads Options (GET_OPTIONS, types 1..130) and PhotographyOptions (GET_PHOTOGRAPHY_OPTIONS, types 1..130) for the
given function mode, saves them to snapshots/last.json and prints what changed since the previous run.
Workflow to map a setting: run, change ONE setting on the camera, run again.
Usage: python ble_snapshot.py [function_mode=27]
"""
import asyncio
import json
import os
import sys

from bleak import BleakClient, BleakScanner

import ble_cmd as bc
import ble_probe_settings as p

HERE = os.path.dirname(os.path.abspath(__file__))
QUICK = "quick" in sys.argv  # only the given function mode (0 = the camera's current mode)
SNAP = os.path.join(HERE, "snapshots", "last_quick.json" if QUICK else "last.json")


def all_fields(b: bytes, prefix: str = "") -> dict:
    """Flatten a protobuf message: 'num' or 'num.sub' -> value (hex for bytes that aren't messages)."""
    out, pos = {}, 0
    try:
        while pos < len(b):
            tag, pos = bc.read_varint(b, pos)
            num, wt = tag >> 3, tag & 7
            key = f"{prefix}{num}"
            if wt == 0:
                val, pos = bc.read_varint(b, pos)
                out[key] = out[key] + [val] if key in out and isinstance(out[key], list) else (
                    [out[key], val] if key in out else val)
            elif wt == 2:
                n, pos = bc.read_varint(b, pos)
                sub = b[pos:pos + n]
                pos += n
                nested = all_fields(sub, key + ".") if n else {}
                if nested and "<err>" not in nested:
                    out.update(nested)
                else:
                    out[key] = sub.hex(" ")
            elif wt == 1:
                out[key] = b[pos:pos + 8].hex(" ")
                pos += 8
            elif wt == 5:
                out[key] = b[pos:pos + 4].hex(" ")
                pos += 4
            else:
                return {"<err>": True}
    except (IndexError, ValueError):
        return {"<err>": True}
    return out


async def main(fm: int):
    dev = await BleakScanner.find_device_by_filter(lambda d, a: bc.SERVICE_BE80 in a.service_uuids, timeout=15)
    async with BleakClient(dev, timeout=20) as client:
        cam = p.Cam(client)
        for u in bc.NOTIFY_CHARS:
            await client.start_notify(u, cam.on_notify)
        await asyncio.sleep(1.0)
        if not cam.synced.is_set():
            await cam.write(bc.frame(0x02, p.AUTH_ID.encode(), msg_type=0x0C))
            await asyncio.wait_for(cam.synced.wait(), 15)
        await asyncio.sleep(0.3)
        await cam.req(39, p.ld(1, p.AUTH_ID.encode()))
        snap = {}

        async def read(code: int, extra: bytes, prefix: str):
            # Unknown type numbers make the camera answer 400: ask in blocks of 10, one by one where a block fails.
            for start in range(1, 201, 10):
                block = list(range(start, start + 10))
                for group in ([block] + [[t] for t in block]):
                    r = await cam.req(code, p.ld(1, b"".join(p.v(t) for t in group)) + extra, timeout=4)
                    if r and r[0] == 200:
                        snap.update({f"{prefix}.{k}": x for k, x in all_fields(p.fields(r[1]).get(2, b"")).items()})
                        if len(group) > 1:
                            break
                    elif len(group) > 1:
                        continue

        await read(8, b"", "opt")
        await read(10, p.fld(2, fm), "po")
        # The same known PhotographyOptions types for every function mode: which mode a setting lives in.
        known = sorted({int(k.split(".")[1]) for k in snap if k.startswith("po.")})
        types = p.ld(1, b"".join(p.v(t) for t in known))
        for other in ([] if QUICK else range(0, 31)):
            r = await cam.req(10, types + p.fld(2, other), timeout=4)
            if r and r[0] == 200:
                snap.update({f"po{other}.{k}": x for k, x in all_fields(p.fields(r[1]).get(2, b"")).items()})
    prev = json.load(open(SNAP)) if os.path.exists(SNAP) else None
    os.makedirs(os.path.dirname(SNAP), exist_ok=True)
    json.dump(snap, open(SNAP, "w"), indent=1, sort_keys=True)
    print(f"fields read: {len(snap)}")
    if prev is None:
        for k in sorted(snap, key=lambda s: [int(x) if x.isdigit() else x for x in s.replace(".", " ").split()]):
            print(f"  {k} = {snap[k]}")
        return
    changed = [k for k in sorted(set(snap) | set(prev)) if snap.get(k) != prev.get(k)]
    print("CHANGED:" if changed else "no changes")
    for k in changed:
        print(f"  {k}: {prev.get(k)} -> {snap.get(k)}")


if __name__ == "__main__":
    asyncio.run(main(int(sys.argv[1]) if len(sys.argv) > 1 else 27))
