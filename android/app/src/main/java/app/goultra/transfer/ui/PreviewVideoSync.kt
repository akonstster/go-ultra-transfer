package app.goultra.transfer.ui

/**
 * Music is the master clock. Small errors only change the rate of the picture player. A stall can leave the
 * picture far behind, where one forward seek avoids spending the rest of the clip catching up.
 */
internal class PreviewVideoSync {
    data class Correction(val speed: Float = 1f, val seekToMs: Long? = null)

    private var lagSinceMs: Long? = null
    private var recovered = false

    fun reset() {
        lagSinceMs = null
        recovered = false
    }

    fun update(pictureMs: Long, musicMs: Long?, elapsedMs: Long, currentSpeed: Float): Correction {
        if (musicMs == null) {
            lagSinceMs = null
            return Correction()
        }
        val lag = musicMs - pictureMs
        if (lag < 500L) recovered = false
        if (lag < 800L || recovered) {
            lagSinceMs = null
        } else {
            val since = lagSinceMs ?: elapsedMs.also { lagSinceMs = it }
            if (elapsedMs - since >= 500L) {
                recovered = true
                lagSinceMs = null
                return Correction(seekToMs = musicMs)
            }
        }
        val speed = when {
            lag >= 80L || lag > 40L && currentSpeed > 1f -> 1.04f
            lag <= -80L || lag < -40L && currentSpeed < 1f -> 0.96f
            else -> 1f
        }
        return Correction(speed)
    }
}
