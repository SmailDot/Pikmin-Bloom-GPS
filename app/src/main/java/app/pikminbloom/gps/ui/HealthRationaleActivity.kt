package app.pikminbloom.gps.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.pikminbloom.gps.R

/** Opened by Health Connect to explain why this app writes steps and distance. */
class HealthRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).toInt()
        setContentView(TextView(this).apply {
            text = getString(R.string.health_rationale_body)
            textSize = 16f
            setPadding(padding, padding, padding, padding)
        })
    }
}
