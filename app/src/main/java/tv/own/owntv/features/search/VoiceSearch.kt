package tv.own.owntv.features.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * Voice search: hands the microphone to the system speech recogniser (Google on nearly every
 * Android TV box) and gives back the first thing it heard, which Search then treats exactly as if
 * it had been typed. Typing a channel name with a remote is the slowest thing in the app; saying
 * "Sky Sports" is the fastest.
 *
 * Returns null when the device has no recogniser, so the button is simply not shown rather than
 * offered and then failing. The `<queries>` entry in the manifest is what lets that check see the
 * recogniser on Android 11 and later.
 */
@Composable
fun rememberVoiceSearch(prompt: String, onResult: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val available = remember { voiceSearchAvailable(context) }
    val deliver by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?.let(deliver)
    }
    if (!available) return null
    return {
        try {
            launcher.launch(voiceSearchIntent(prompt))
        } catch (_: ActivityNotFoundException) {
            // The recogniser went away between the check and the press; nothing to do.
        }
    }
}

private fun voiceSearchIntent(prompt: String): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)

private fun voiceSearchAvailable(context: Context): Boolean =
    runCatching { Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(context.packageManager) != null }.getOrDefault(false)
