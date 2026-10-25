"""Send Insta360 commands to the camera over BLE (BE81 -> BE82) and log everything that comes back.

Packet format (Go2BlePacket, from xaionaro-go/insta360ctl, GO 3):
    FF 07 40 [size u16 LE] [inner] [CRC16/MODBUS LE over all preceding bytes]
    inner = [u32 LE 16+len] 04 00 00 [code u16 LE] 02 [seq u8] 00 00 80 00 00 [protobuf]
Sync: camera sends subtype 0x41, we answer FF 07 41 07 00 + 7 zero bytes + CRC.

Usage: python ble_cmd.py [--wait S] [--char be81|b001] CODE[:HEXBODY] | wake:AUTH_ID | raw:HEX ...
    python ble_cmd.py 39:0a0474657374 113
    wake:ID  sends the sleep-mode wake-up authorization FF 0C 02 [len u16 LE] [ID ascii] [CRC]
    raw:HEX  sends bytes as is
"""
import argparse
import asyncio
import time

from bleak import BleakClient, BleakScanner

SERVICE_BE80 = "0000be80-0000-1000-8000-00805f9b34fb"
CHAR_WRITE = "0000be81-0000-1000-8000-00805f9b34fb"
NOTIFY_CHARS = [
    "0000be82-0000-1000-8000-00805f9b34fb",
    "0000b002-0000-1000-8000-00805f9b34fb",
]

T0 = time.monotonic()


def log(msg: str) -> None:
    print(f"[{time.monotonic() - T0:7.3f}] {msg}", flush=True)


def crc16_modbus(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc


def frame(subtype: int, inner: bytes, msg_type: int = 0x07) -> bytes:
    head = bytes([0xFF, msg_type, subtype]) + len(inner).to_bytes(2, "little")
    return head + inner + crc16_modbus(head + inner).to_bytes(2, "little")


def command(code: int, seq: int, body: bytes) -> bytes:
    inner = ((16 + len(body)).to_bytes(4, "little") + bytes([0x04, 0, 0]) + code.to_bytes(2, "little")
             + bytes([0x02, seq & 0xFF, 0, 0, 0x80, 0, 0]) + body)
    return frame(0x40, inner)


SYNC_REPLY = frame(0x41, bytes(7))


# ---------- protobuf pretty printer (schema-less) ----------
def read_varint(b: bytes, pos: int) -> tuple[int, int]:
    v = shift = 0
    while True:
        x = b[pos]
        pos += 1
        v |= (x & 0x7F) << shift
        if not x & 0x80:
            return v, pos
        shift += 7
        if shift > 63:
            raise ValueError("varint too long")


def decode_pb(b: bytes, indent: str = "    ", depth: int = 0) -> list[str] | None:
    out, pos = [], 0
    try:
        while pos < len(b):
            tag, pos = read_varint(b, pos)
            field, wt = tag >> 3, tag & 7
            if field == 0:
                raise ValueError("field 0")
            if wt == 0:
                v, pos = read_varint(b, pos)
                out.append(f"{indent}{field}: {v}")
            elif wt == 1:
                out.append(f"{indent}{field}: fixed64 {b[pos:pos + 8].hex(' ')}")
                pos += 8
            elif wt == 5:
                out.append(f"{indent}{field}: fixed32 {b[pos:pos + 4].hex(' ')}")
                pos += 4
            elif wt == 2:
                n, pos = read_varint(b, pos)
                sub = b[pos:pos + n]
                if len(sub) != n:
                    raise ValueError("truncated")
                pos += n
                nested = decode_pb(sub, indent + "  ", depth + 1) if n and depth < 4 else None
                text = sub.decode("utf-8", "replace")
                if n and all(32 <= c < 127 for c in sub):
                    out.append(f'{indent}{field}: "{text}"')
                elif nested:
                    out += [f"{indent}{field}: {{", *nested, f"{indent}}}"]
                else:
                    out.append(f"{indent}{field}: bytes[{n}] {sub.hex(' ')}")
            else:
                raise ValueError(f"wire type {wt}")
    except (ValueError, IndexError) as e:
        if depth:
            return None
        out.append(f"{indent}<decode error at {pos}: {e}>")
    return out


class Camera:
    def __init__(self, client: BleakClient, char_write: str = CHAR_WRITE):
        self.client = client
        self.char_write = char_write
        self.rx = bytearray()

    async def write(self, data: bytes, what: str) -> None:
        log(f">> {what} -> {self.char_write[4:8]}: {data.hex(' ')}")
        await self.client.write_gatt_char(self.char_write, data, response=False)

    def on_notify(self, char, data: bytearray) -> None:
        log(f"<< notify {char.uuid[4:8]} ({len(data)} B): {bytes(data).hex(' ')}")
        if char.uuid.startswith("0000be82"):
            self.rx += data
            self.parse()

    def parse(self) -> None:
        while self.rx:
            if self.rx[0] != 0xFF:
                if self.rx[:2] == b"\xab\xba":
                    log("   raw AB BA sync -> reply")
                    asyncio.get_event_loop().create_task(self.write(SYNC_REPLY, "SYNC reply"))
                else:
                    log(f"   non-frame data: {bytes(self.rx).hex(' ')}")
                self.rx.clear()
                return
            if len(self.rx) < 5:
                return
            size = int.from_bytes(self.rx[3:5], "little")
            total = size + 7
            if len(self.rx) < total:
                return
            pkt, self.rx = bytes(self.rx[:total]), self.rx[total:]
            crc_ok = crc16_modbus(pkt[:-2]) == int.from_bytes(pkt[-2:], "little")
            subtype, inner = pkt[2], pkt[5:-2]
            log(f"   frame type={pkt[1]:#04x} subtype={subtype:#04x} size={size} crc_ok={crc_ok}")
            if subtype == 0x41:
                log(f"   SYNC payload {inner.hex(' ')} -> reply")
                asyncio.get_event_loop().create_task(self.write(SYNC_REPLY, "SYNC reply"))
                continue
            if len(inner) >= 16:
                code = int.from_bytes(inner[7:9], "little")
                log(f"   MSG code={code} ctype={inner[9]} seq={inner[10]} hdr=[{inner[:16].hex(' ')}]")
                body = inner[16:]
                if body:
                    for line in decode_pb(body) or []:
                        log(line)
            else:
                log(f"   short inner: {inner.hex(' ')}")


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("commands", nargs="*", help="CODE[:HEXBODY]")
    ap.add_argument("--wait", type=float, default=4.0, help="seconds to listen after each command")
    ap.add_argument("--target", help="BLE name or address (default: first device with service BE80)")
    ap.add_argument("--char", default="be81", help="write characteristic short id: be81 or b001")
    args = ap.parse_args()

    def match(dev, adv):
        if args.target:
            return args.target.lower() in ((adv.local_name or "").lower(), dev.address.lower())
        return SERVICE_BE80 in adv.service_uuids

    dev = await BleakScanner.find_device_by_filter(match, timeout=15)
    if dev is None:
        raise SystemExit("camera not found")
    log(f"connecting to {dev.address} {dev.name!r}")
    async with BleakClient(dev, timeout=20) as client:
        log(f"connected, mtu={client.mtu_size}")
        cam = Camera(client, f"0000{args.char}-0000-1000-8000-00805f9b34fb")
        for uuid in NOTIFY_CHARS:
            await client.start_notify(uuid, cam.on_notify)
        await asyncio.sleep(1.0)
        for seq, cmd in enumerate(args.commands, start=1):
            code_s, _, arg = cmd.partition(":")
            if code_s == "wake":
                await cam.write(frame(0x02, arg.encode("ascii"), msg_type=0x0C), f"wake-up auth id={arg!r}")
            elif code_s == "raw":
                await cam.write(bytes.fromhex(arg), "raw")
            else:
                await cam.write(command(int(code_s, 0), seq, bytes.fromhex(arg)), f"cmd {code_s} seq={seq}")
            await asyncio.sleep(args.wait)
        await asyncio.sleep(1.0)
    log("disconnected")


if __name__ == "__main__":
    asyncio.run(main())
