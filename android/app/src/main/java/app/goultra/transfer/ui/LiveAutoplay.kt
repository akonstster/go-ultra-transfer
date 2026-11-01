package app.goultra.transfer.ui

/** Once per visible page; a temporary background transition does not restart playback. */
internal class LiveAutoplay {
    private var started = false

    fun update(active: Boolean, foreground: Boolean, ready: Boolean, editing: Boolean): Boolean {
        if (!active) {
            started = false
            return false
        }
        if (!foreground || !ready || editing || started) return false
        started = true
        return true
    }

    fun suppress() {
        started = true
    }
}
