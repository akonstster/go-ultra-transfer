package app.goultra.transfer.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class FileSafetyTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun readsHeadAndTrailerFromTheSameOpenStream() {
        val file = folder.newFile()
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        file.inputStream().use { input ->
            assertArrayEquals(byteArrayOf(1, 2), readFullyAt(input, 0, 2))
            assertArrayEquals(byteArrayOf(4, 5), readFullyAt(input, 3, 2))
            assertThrows(EOFException::class.java) { readFullyAt(input, 4, 2) }
        }
    }

    @Test
    fun http416RequiresExactRemoteSize() {
        assertEquals(123L, HttpRanges.unsatisfiedSize("bytes */123"))
        assertEquals(0L, HttpRanges.unsatisfiedSize("Bytes */0"))
        assertNull(HttpRanges.unsatisfiedSize("bytes 0-122/123"))
        assertNull(HttpRanges.unsatisfiedSize("bytes */*"))
        assertTrue(HttpRanges.isComplete(123, 123))
        assertFalse(HttpRanges.isComplete(124, 123))
        assertFalse(HttpRanges.isComplete(122, 123))
        assertFalse(HttpRanges.isComplete(123, null))
    }

    @Test
    fun resumedLiveVideoRequiresMatchingRangeAndSize() {
        assertTrue(HttpRanges.matchesRange("bytes 50-99/100", 50, 100))
        assertTrue(HttpRanges.matchesRange("Bytes 50-99/100", 50, 100))
        assertFalse(HttpRanges.matchesRange("bytes 49-99/100", 50, 100))
        assertFalse(HttpRanges.matchesRange("bytes 50-99/101", 50, 100))
        assertFalse(HttpRanges.matchesRange("bytes 50-100/100", 50, 100))
        assertFalse(HttpRanges.matchesRange("bytes 50-49/100", 50, 100))
        assertFalse(HttpRanges.matchesRange(null, 50, 100))
    }

    @Test
    fun exactCopyCopiesOnlyTheRequestedBytes() {
        val output = ByteArrayOutputStream()
        copyExactly(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), output, 3)
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
    }

    @Test
    fun exactCopyRejectsTruncatedVideo() {
        val output = ByteArrayOutputStream()
        assertThrows(EOFException::class.java) {
            copyExactly(ByteArrayInputStream(byteArrayOf(1, 2)), output, 3)
        }
    }
}
