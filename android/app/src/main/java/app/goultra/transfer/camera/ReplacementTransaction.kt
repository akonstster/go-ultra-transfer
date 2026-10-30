package app.goultra.transfer.camera

/** Publishes a prepared replacement without deleting the old item until the new one is published. */
internal object ReplacementTransaction {
    fun commit(
        hasOld: Boolean,
        backupOld: () -> Unit,
        publishNew: () -> Unit,
        discardNew: () -> Unit,
        restoreOld: () -> Unit,
        deleteOld: () -> Unit,
    ) {
        var backupAttempted = false
        try {
            if (hasOld) {
                backupAttempted = true
                backupOld()
            }
            publishNew()
        } catch (e: Exception) {
            try {
                discardNew()
            } catch (cleanup: Exception) {
                e.addSuppressed(cleanup)
            }
            if (backupAttempted) {
                try {
                    restoreOld()
                } catch (restore: Exception) {
                    e.addSuppressed(restore)
                }
            }
            throw e
        }
        if (hasOld) deleteOld()
    }
}
