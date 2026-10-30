package app.goultra.transfer.camera

import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test

class LiveFrameTimesTest {
    @Test
    fun acceptsEqualTimestamps() {
        verifyLiveFrameTimes(sequenceOf(0, 33366, 66733), sequenceOf(0, 33366, 66733))
    }

    @Test
    fun rejectsEmptyUnequalLengthAndUnequalTimestamps() {
        assertThrows(IOException::class.java) { verifyLiveFrameTimes(emptySequence(), emptySequence()) }
        assertThrows(IOException::class.java) { verifyLiveFrameTimes(sequenceOf(0, 1), sequenceOf(0)) }
        assertThrows(IOException::class.java) { verifyLiveFrameTimes(sequenceOf(0), sequenceOf(0, 1)) }
        assertThrows(IOException::class.java) { verifyLiveFrameTimes(sequenceOf(0, 1), sequenceOf(0, 2)) }
    }
}
