package app.pikminbloom.gps.feed

/**
 * Every bloom threshold, in one place. These are a first guess: no zoomed-out bloom frame had been captured when
 * they were written. Tune them here after the device run, nowhere else.
 */
object FeedTuning {

    /** The sample grid over the flower field: GRID_COLS by GRID_ROWS, spanning these fractions of W and H. */
    const val GRID_COLS = 9
    const val GRID_ROWS = 7
    const val GRID_X0 = 0.10
    const val GRID_X1 = 0.90
    const val GRID_Y0 = 0.20
    const val GRID_Y1 = 0.78

    /** A sample is a bloom candidate only when its brightest channel rose by at least this much. */
    const val BLOOM_RISE = 60

    /** A sample counts as changed (for the camera-move test) when its brightest channel moved by at least this much. */
    const val CHANGED_BY = 60

    /** More changed samples than this fraction of the grid means the camera moved, so no bloom is reported. */
    const val CAMERA_MOVE_FRACTION = 0.40

    /** A candidate must be bright in the after frame: value at least this. */
    const val BRIGHT_V = 0.85

    /** Warm: hue within [WARM_HUE_MIN, WARM_HUE_MAX] with saturation at least [WARM_SAT]. */
    const val WARM_HUE_MIN = 30.0
    const val WARM_HUE_MAX = 70.0
    const val WARM_SAT = 0.35

    /** Or white: saturation below this. */
    const val WHITE_SAT = 0.15

    /** Candidates closer than this box (fractions of W and H) are one bloom; the stronger is kept. */
    const val DEDUPE_W = 0.045
    const val DEDUPE_H = 0.035
}
