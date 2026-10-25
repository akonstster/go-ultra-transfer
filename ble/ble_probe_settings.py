"""Probe which shooting modes / resolutions / FOVs the camera accepts, then restore the original settings.

Uses SET_OPTIONS (7) for the video sub mode and SET/GET_PHOTOGRAPHY_OPTIONS (9/10) for record_resolution (31)
and fov_type (33): a value is "supported" when the camera lists the type in success_types and reads it back.
Usage: python ble_probe_settings.py
"""
import asyncio
import json
import struct

from bleak import BleakClient, BleakScanner

import ble_cmd as bc

AUTH_ID = "goultra-transfer"

# video_sub_mode -> FunctionMode used for photography options (guessed pairing, checked by reading back)
MODES = {0: 7, 1: 0, 2: 10, 3: 9, 4: 12, 5: 16, 6: 17, 7: 19, 8: 20, 9: 21, 10: 22, 11: 27}
RESOLUTIONS = {
    23: "4K60", 24: "4K30", 48: "4K25", 49: "4K24", 92: "4K50", 214: "4K120", 220: "4K100",
    25: "2.7K100", 38: "2.7K60", 39: "2.7K30", 50: "2.7K25", 51: "2.7K24", 100: "2.7K50", 217: "2.7K120",
    60: "1440p30", 61: "1440p60", 69: "1440p25", 83: "1440p50", 86: "1440p24",
    26: "1080p200", 27: "1080p240", 28: "1080p120", 29: "1080p30", 40: "1080p60", 52: "1080p25",
    53: "1080p24", 81: "1080p50", 150: "1080p100",
}


def v(n: int) -> bytes:
    out = b""
    while True:
        b, n = n & 0x7F, n >> 7
        if n:
            out += bytes([b | 0x80])
        else:
            return out + bytes([b])


def fld(num: int, val: int) -> bytes:
    return v(num << 3) + v(val)


def ld(num: int, data: bytes) -> bytes:
    return v(num << 3 | 2) + v(len(data)) + data


def fields(b: bytes) -> dict:
    """Top-level fields: num -> varint or bytes (last wins)."""
    out, pos = {}, 0
    while pos < len(b):
        tag, pos = bc.read_varint(b, pos)
        num, wt = tag >> 3, tag & 7
        if wt == 0:
            out[num], pos = bc.read_varint(b, pos)
        elif wt == 2:
            n, pos = bc.read_varint(b, pos)
            out.setdefault(num, b[pos:pos + n]) if num == 1 else out.__setitem__(num, b[pos:pos + n])
            pos += n
        elif wt == 1:
            out[num] = b[pos:pos + 8]
            pos += 8
        elif wt == 5:
            out[num] = b[pos:pos + 4]
            pos += 4
    return out


class Cam:
    def __init__(self, client):
        self.client, self.rx, self.seq = client, bytearray(), 0
        self.pending: dict[int, asyncio.Future] = {}
        self.synced = asyncio.Event()
        self.loop = asyncio.get_running_loop()

    def on_notify(self, char, data):
        if not char.uuid.startswith("0000be82"):
            return
        self.rx += data
        while len(self.rx) >= 5 and self.rx[0] == 0xFF:
            total = int.from_bytes(self.rx[3:5], "little") + 7
            if len(self.rx) < total:
                return
            pkt, self.rx = bytes(self.rx[:total]), self.rx[total:]
            inner = pkt[5:-2]
            if pkt[2] == 0x41:
                self.loop.create_task(self.write(bc.SYNC_REPLY))
                self.loop.call_soon(self.synced.set)
            elif pkt[2] == 0x40 and len(inner) >= 16:
                code, seq = int.from_bytes(inner[7:9], "little"), inner[10]
                fut = self.pending.pop(seq, None)
                if fut and code < 1000 and not fut.done():
                    fut.set_result((code, inner[16:]))
        if self.rx and self.rx[0] != 0xFF:
            self.rx.clear()

    async def write(self, data):
        await self.client.write_gatt_char(bc.CHAR_WRITE, data, response=False)

    async def req(self, code, body=b"", timeout=4.0):
        self.seq = self.seq % 254 + 1
        fut = self.loop.create_future()
        self.pending[self.seq] = fut
        await self.write(bc.command(code, self.seq, body))
        try:
            return await asyncio.wait_for(fut, timeout)
        except asyncio.TimeoutError:
            return None

    async def get_mode(self):
        r = await self.req(8, ld(1, v(41)))
        return fields(fields(r[1]).get(2, b"")).get(41) if r else None

    async def set_mode(self, m):
        await self.req(7, ld(1, v(41)) + ld(2, fld(41, m)))
        await asyncio.sleep(0.6)
        return await self.get_mode()

    async def get_po(self, fm, types=(31, 33)):
        r = await self.req(10, ld(1, bytes(types)) + fld(2, fm))
        return fields(fields(r[1]).get(2, b"")) if r and r[0] == 200 else None

    async def set_po(self, fm, num, val):
        """val: int, or float for double fields (zoom_scale 53). True if accepted and read back."""
        value = (v(num << 3 | 1) + struct.pack("<d", val)) if isinstance(val, float) else fld(num, val)
        r = await self.req(9, ld(1, bytes([num])) + ld(2, value) + fld(3, fm))
        succ = fields(r[1]).get(1) if r else None  # one type comes back as a plain varint, several as packed
        ok = r is not None and r[0] == 200 and (succ == num or (isinstance(succ, bytes) and num in succ))
        back = (await self.get_po(fm, (num,)) or {}).get(num)
        if isinstance(val, float) and isinstance(back, bytes):
            back = struct.unpack("<d", back)[0]
        return ok and back == val, back


async def main():
    dev = await BleakScanner.find_device_by_filter(lambda d, a: bc.SERVICE_BE80 in a.service_uuids, timeout=15)
    async with BleakClient(dev, timeout=20) as client:
        cam = Cam(client)
        for uuid in bc.NOTIFY_CHARS:
            await client.start_notify(uuid, cam.on_notify)
        await asyncio.sleep(1.0)
        if not cam.synced.is_set():
            await cam.write(bc.frame(0x02, AUTH_ID.encode(), msg_type=0x0C))
            await asyncio.wait_for(cam.synced.wait(), 15)
        await asyncio.sleep(0.3)
        await cam.req(39, ld(1, AUTH_ID.encode()))
        result = {}
        orig_mode = await cam.get_mode()
        print("original video_sub_mode", orig_mode)

        accepted = [m for m in (0, 2, 3, 4, 5, 6, 9, 11)]
        try:
            for m in accepted:
                fm = MODES[m]
                if await cam.set_mode(m) != m:
                    print("mode", m, "not accepted")
                    continue
                cur = await cam.get_po(fm, (31, 33, 53)) or {}
                res_ok, fov_ok, zoom_ok = [], [], []
                try:
                    for code, name in RESOLUTIONS.items():
                        if (await cam.set_po(fm, 31, code))[0]:
                            res_ok.append(f"{code}:{name}")
                    for code in range(0, 17):
                        if (await cam.set_po(fm, 33, code))[0]:
                            fov_ok.append(code)
                    for z in (1.0, 2.0):
                        if (await cam.set_po(fm, 53, z))[0]:
                            zoom_ok.append(z)
                finally:
                    # Restore this mode's settings (FOV before resolution: some FOVs limit resolutions).
                    if isinstance(cur.get(33), int):
                        await cam.set_po(fm, 33, cur[33])
                    if isinstance(cur.get(31), int):
                        await cam.set_po(fm, 31, cur[31])
                    if isinstance(cur.get(53), bytes):
                        await cam.set_po(fm, 53, struct.unpack("<d", cur[53])[0])
                after = await cam.get_po(fm, (31, 33))
                result[m] = {"fm": fm, "orig": {k: x for k, x in cur.items() if isinstance(x, int)},
                             "resolutions": res_ok, "fov": fov_ok, "zoom": zoom_ok, "after": after}
                print("mode", m, json.dumps(result[m], ensure_ascii=False, default=str), flush=True)
        finally:
            if orig_mode is not None:
                print("restore mode ->", await cam.set_mode(orig_mode))
        print(json.dumps(result, ensure_ascii=False, default=str))


if __name__ == "__main__":
    asyncio.run(main())
