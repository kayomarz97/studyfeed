package dev.kayomarz.studyfeed

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.kayomarz.studyfeed.ui.theme.StudyFeedTheme

/**
 * The app's ONLY component (BUILD_PROMPT §8 A0).
 *
 * There is no Service, no BroadcastReceiver, no ContentProvider, and there never will be —
 * `verifyOfflineGuarantees` fails the build if one appears in the merged manifest. Adding a second
 * Activity later is fine; adding a background component is not.
 *
 * A0 is a skeleton on purpose: this placeholder exists only to prove the toolchain produces an
 * installable APK that opens. The Study Feed itself is A2, and pack import is A1 — do not grow
 * this file into either.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StudyFeedTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    A0Placeholder()
                }
            }
        }
    }
}

@Composable
private fun A0Placeholder() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "StudyFeed",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "A0 skeleton — offline, no background work.\nNo pack imported yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun A0PlaceholderPreview() {
    StudyFeedTheme { A0Placeholder() }
}
