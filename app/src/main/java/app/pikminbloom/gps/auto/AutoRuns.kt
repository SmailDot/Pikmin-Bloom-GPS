package app.pikminbloom.gps.auto

import app.pikminbloom.gps.expedition.ExpeditionRunner
import app.pikminbloom.gps.feed.FeedRunner

/**
 * The auto runs: 自動探險 and 自動餵精華. Only one goes at a time, and one Stop cancels whichever is busy.
 */
object AutoRuns {
    val isBusy: Boolean get() = ExpeditionRunner.isBusy || FeedRunner.isBusy

    fun cancel() {
        ExpeditionRunner.cancel()
        FeedRunner.cancel()
    }
}
