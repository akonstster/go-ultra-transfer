package app.goultra.transfer.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected bytes were captured from the real camera with ble/ble_cmd.py on 2026-10-23. */
class ProtocolTest {
    @Test
    fun syncReply() {
        assertEquals("ff 07 41 07 00 00 00 00 00 00 00 00 5c 42", BleFrame.syncReply().hex())
    }

    @Test
    fun wakeFrame() {
        assertEquals(
            "ff 0c 02 10 00 67 6f 75 6c 74 72 61 2d 74 72 61 6e 73 66 65 72 aa 21",
            BleFrame.wake("goultra-transfer").hex(),
        )
    }

    @Test
    fun checkAuthorizationCommand() {
        val body = PbWriter().string(1, "goultra-transfer").toByteArray()
        assertEquals(
            "ff 07 40 22 00 22 00 00 00 04 00 00 27 00 02 01 00 00 80 00 00 0a 10 67 6f 75 6c 74 72 61 2d 74 72 61 " +
                "6e 73 66 65 72 f4 5c",
            BleFrame.command(Proto.CMD_CHECK_AUTHORIZATION, 1, body).hex(),
        )
    }

    @Test
    fun getOptionsCommand() {
        val body = PbWriter().packed(1, listOf(36L, 43L, 97L)).toByteArray()
        assertEquals(
            "ff 07 40 15 00 15 00 00 00 04 00 00 08 00 02 02 00 00 80 00 00 0a 03 24 2b 61 5d ba",
            BleFrame.command(Proto.CMD_GET_OPTIONS, 2, body).hex(),
        )
    }

    @Test
    fun cameraResponseParsing() {
        // CHECK_AUTHORIZATION response from the camera (authorized): ff 06 40 18 00 [inner] [crc]
        val frame = "ff 06 40 18 00 18 00 00 00 04 00 00 c8 00 02 02 00 00 80 00 00 08 00 10 01 18 01 22 00 51 4c"
            .split(" ").map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(u16(frame, frame.size - 2), Crc16.modbus(frame, 0, frame.size - 2))
        val msg = Proto.parseMessage(frame.copyOfRange(5, frame.size - 2), 4, seqBytes = 1)!!
        assertEquals(200, msg.code)
        assertEquals(2, msg.seq)
        assertEquals(0L, msg.body.pbFields().field(1)!!.varint)
    }

    @Test
    fun fileNameDate() {
        assertEquals(
            "2026-10-18T13:28:15",
            CameraFile.createdFromName("VID_20261018_132815_001.mp4").toString(),
        )
    }

    @Test
    fun tcpFileListRequest() {
        val body = PbWriter().varint(1, 2).varint(3, 500).toByteArray()
        assertEquals("08 02 18 f4 03", body.hex())
        assertEquals("04 00 00 0d 00 02 01 00 00 80 00 00", Proto.messageHeader(Proto.CMD_GET_FILE_LIST, 1).hex())
    }
}
