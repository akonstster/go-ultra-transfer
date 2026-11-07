package app.goultra.transfer.ui

import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.dp

// The project strip's feel, in one place: sizes, how a carried card moves, and the springs behind it.
// What each value does and which way to turn it: `docs/STRIP_TUNING.md`.

/** A card of the strip: its width (its height is width / 1.5), the room between two cards, and at either end. */
internal val CARD_W = 92.dp
internal val CARD_GAP = 8.dp
internal val STRIP_EDGE = 12.dp

/** How small the other cards get while one is carried: smaller = further away, more of them on screen. */
internal const val CARRY_SCALE = 0.6f

/** The carried card: how much bigger it gets, how high it rises, and how deep its shadow is. */
internal const val LIFT_SCALE = 1.1f
internal val LIFT_RISE = 10.dp
internal val LIFT_SHADOW = 12.dp

/** How much the carried card follows the finger up and down (a share of the movement), and its limit. */
internal const val LIFT_FOLLOW_Y = 0.35f
internal val LIFT_MAX_Y = 28.dp

/**
 * How fast the carried card must be swept sideways (per second) before the other cards gather smaller: held still or
 * moved slowly, the strip stays as it is and nothing changes under the card.
 */
internal val GATHER_SPEED = 900.dp

/** The band at either end of the strip (a share of its width) that slides it along, and the top speed there, px/s. */
internal const val EDGE_ZONE = 0.18f
internal const val EDGE_SPEED = 1400f

/** The longest step one frame may take, ms: a stalled frame must not throw the strip about. */
internal const val TICK_MAX_MS = 48f

/** The other cards parting for the carried one (and the outline of its place). */
internal val PART_SPRING = spring<Float>(dampingRatio = 0.85f, stiffness = 420f)

/** The strip gathering when a card is taken and spreading back when it is let go of. */
internal val ZOOM_SPRING = spring<Float>(dampingRatio = 1f, stiffness = 260f)

/** The card rising when taken and settling into its place when let go of. */
internal val LAND_SPRING = spring<Float>(dampingRatio = 0.78f, stiffness = 320f)
