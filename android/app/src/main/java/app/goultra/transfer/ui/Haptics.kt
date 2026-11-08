package app.goultra.transfer.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * A short tick of the phone's own vibration — when a card is taken, or the music track.
 *
 * `LocalHapticFeedback` alone is not enough: on some phones (EMUI among them) it goes nowhere, and a card taken without
 * any sign of it feels like nothing happened. The app asks for the VIBRATE permission for exactly this one tick; a
 * phone without a vibrator simply does nothing.
 */
internal fun tick(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    if (vibrator?.hasVibrator() == true) {
        vibrator.vibrate(VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}