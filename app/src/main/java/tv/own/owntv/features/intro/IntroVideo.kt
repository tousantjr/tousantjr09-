package tv.own.owntv.features.intro

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import tv.own.owntv.R

/**
 * The MPTV intro clip (res/raw/mptv_intro.mp4, about 6 seconds) shown over the app on a normal
 * launch. Plain Android views on purpose: it sits on top of whatever Compose draws underneath, so the
 * app keeps loading behind it, and it needs no player library.
 *
 * Any remote button, key or tap skips it. A broken clip, a codec the device lacks or a stuck player
 * can never trap the user: errors end it at once and a safety timer ends it regardless.
 */
class IntroVideo(private val activity: Activity) {
    var showing = false
        private set
    private var root: FrameLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private val safety = Runnable { dismiss() }

    fun show() {
        if (showing) return
        showing = true
        val video = VideoView(activity).apply {
            setVideoURI(
                Uri.Builder()
                    .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                    .authority(activity.packageName)
                    .appendPath(R.raw.mptv_intro.toString())
                    .build(),
            )
            setOnPreparedListener { start() }
            setOnCompletionListener { dismiss() }
            setOnErrorListener { _, _, _ -> dismiss(); true }
        }
        val frame = object : FrameLayout(activity) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (!showing) return false // fading out: let touches through
                if (event.action == MotionEvent.ACTION_UP) dismiss()
                return true // the app underneath never sees taps meant to skip the intro
            }
        }.apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        root = frame
        activity.addContentView(frame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        handler.postDelayed(safety, Intro.SAFETY_MS)
    }

    /** Call from Activity.dispatchKeyEvent: true means the key was used to skip the intro. */
    fun consumeKey(event: KeyEvent): Boolean {
        if (!showing) return false
        if (event.action == KeyEvent.ACTION_UP) dismiss()
        return true
    }

    fun dismiss() {
        if (!showing) return
        showing = false
        handler.removeCallbacks(safety)
        root?.let { frame ->
            (frame.getChildAt(0) as? VideoView)?.stopPlayback()
            frame.animate().alpha(0f).setDuration(Intro.FADE_MS).withEndAction {
                (frame.parent as? ViewGroup)?.removeView(frame)
            }.start()
        }
        root = null
    }
}

/** The intro's rules, kept free of Android views so they can be unit tested. */
object Intro {
    const val SAFETY_MS = 10_000L
    const val FADE_MS = 250L

    /**
     * Only a normal start from the launcher: not a rotation or process restore, and not a deep link,
     * notification tap or "open this match/channel" request, which should land where they point.
     */
    fun shouldPlay(firstStart: Boolean, action: String?, categories: Set<String>?, hasData: Boolean, hasExtras: Boolean): Boolean =
        firstStart && action == Intent.ACTION_MAIN && !hasData && !hasExtras &&
            (categories.orEmpty().contains(Intent.CATEGORY_LAUNCHER) || categories.orEmpty().contains(Intent.CATEGORY_LEANBACK_LAUNCHER))

    fun shouldPlay(firstStart: Boolean, intent: Intent?): Boolean =
        intent != null && shouldPlay(firstStart, intent.action, intent.categories, intent.data != null, intent.extras?.isEmpty == false)
}
