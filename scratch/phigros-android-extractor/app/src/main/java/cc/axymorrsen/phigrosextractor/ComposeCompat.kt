package cc.axymorrsen.phigrosextractor

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent as activitySetContent
import androidx.compose.runtime.Composable

/** Temporary source-level bridge while MainActivity is migrated to Compose. */
fun ComponentActivity.setContent(content: @Composable () -> Unit) {
    this.activitySetContent(content)
}
