package com.cosmos.unreddit.util

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Executes the 2.5.110 handoff code for real (Robolectric = the actual
 * Android framework on the JVM): SearchWebUrls.handOff is invoked from an
 * Activity context exactly as the fragments do (requireContext()), the
 * Intent it really starts is inspected, and the browser->app return leg is
 * resolved against the app's ACTUAL merged manifest. Local proof of the glue
 * layer, no device needed:
 *   - handoff fires an implicit, BROWSABLE VIEW intent with reddit's own URL;
 *   - it never pins the target back to the app itself: the manifest
 *     registers every reddit.com host with no path restriction, so the
 *     resolver lands on Stealth itself here — and on the owner's phone when
 *     reddit.com defaults to the app — the guard must keep the intent
 *     implicit (chooser), never loop the search back into the app;
 *   - result permalinks (what the browser hands back on tap) resolve to the
 *     app natively;
 *   - a non-Activity caller still launches (NEW_TASK added; real Android
 *     throws without it, which this suite proved when the seam lacked it).
 */
@Config(application = android.app.Application::class, sdk = [34])
@RunWith(RobolectricTestRunner::class)
class SearchHandoffLocalTest {

    private val activity: Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `global search handoff starts a browsable view intent with the exact url`() {
        SearchWebUrls.handOff(activity, SearchWebUrls.search("lucia morgan"))
        val started = shadowOf(activity).nextStartedActivity
            ?: error("handOff started no activity")
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals(
            "https://www.reddit.com/search/?q=lucia%20morgan",
            started.data.toString()
        )
        assertNull(started.`package`)
    }

    @Test
    fun `handoff never pins the intent back to the app itself`() {
        // The manifest registers all reddit.com hosts with no paths, so the
        // resolver (MATCH_DEFAULT_ONLY) for a reddit.com search URL lands on
        // this app — the exact situation on the owner's phone when Stealth is
        // set to open reddit.com links by default.
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(SearchWebUrls.search("x")))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val resolver = activity.packageManager.resolveActivity(
            view, PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(activity.packageName, resolver?.activityInfo?.packageName)

        SearchWebUrls.handOff(activity, SearchWebUrls.search("x"))
        val started = shadowOf(activity).nextStartedActivity
            ?: error("handOff started no activity")
        assertNull("self-pin would loop the search back into the app",
            started.`package`)
        assertEquals(Intent.ACTION_VIEW, started.action)
    }

    @Test
    fun `subreddit handoff keeps the scope and restriction`() {
        SearchWebUrls.handOff(
            activity, SearchWebUrls.inSubreddit("r/Piracy", "dji neo"))
        val started = shadowOf(activity).nextStartedActivity
            ?: error("handOff started no activity")
        assertEquals(
            "https://www.reddit.com/r/Piracy/search/?q=dji%20neo&restrict_sr=1",
            started.data.toString()
        )
    }

    @Test
    fun `non-activity context handoff adds new-task instead of crashing`() {
        val app = RuntimeEnvironment.getApplication()
        SearchWebUrls.handOff(app, SearchWebUrls.search("y"))
        val started = shadowOf(app).nextStartedActivity
            ?: error("handOff started no activity from app context")
        assertTrue(
            "non-Activity callers must not hit the framework NEW_TASK crash",
            started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `result permalinks route back into the app via the real manifest`() {
        val permalink = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.reddit.com/r/DJI/comments/1abc123/lucia_morgan/"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val resolved = activity.packageManager.queryIntentActivities(
            permalink, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue(
            "result taps from the browser must land in the app natively",
            resolved.any { it.activityInfo.packageName == activity.packageName })
    }
}
