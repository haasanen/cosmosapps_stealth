# Stealth Home Feed: Progressive Fan-Out + Local Cache (v2.5.0)

**Goal:** The home feed (73-sub multiredd on the official source) shows cached posts
**instantly**, then streams fresh posts in **as each subreddit arrives** (progress bar +
current subreddit + X/Y counter), merges them with cached results (fresh prioritized),
persists everything to a local cache with strict purge rules (no orphan data → no leak),
supports full **offline** mode, and refreshes open posts in the background.

**Architecture:** A new `feed_cache` Room table (schema v5) stores full post snapshots per
profile+sort. A new `FeedCoordinator` (app-lifetime singleton, one per home-feed page) drives
a refresh cycle: emit cache → fan out 73 subs with randomized order, low concurrency,
per-sub retries/jitter → emit progressive merged pages → persist fresh results + cursors →
purge. The home feed UI switches from Paging `PagingData` to a plain `ListAdapter` over
`StateFlow<List<PostEntity>>` (same `PostViewHolder`/`PostListAdapter` cells), which is what
makes progressive re-emission trivial. Paging stays for single-sub, user and search screens.

**Tech stack:** Kotlin, Room (existing, v4→v5), Hilt (existing), Coroutines/Flow,
Paging 3.1.1 (kept for non-home screens), Coil (existing, memory+disk bounded),
GitHub Actions CI as build gate.

---

## Phase 0 — Version numbers (user request)

**Task 0.1: Bump version to 2.5.0 / code 25**

- Modify: `buildSrc/src/main/kotlin/Config.kt:10-11` → `versionCode = 25`, `versionName = "2.5.0"`.
- From now on: bump `versionCode` every commit, `versionName` per shipped feature.
  Record this convention in `CONTEXT.md`.
- CI releases are still tagged `build-<sha>` (no `v*` tags) so version bumps don't create
  GitHub Releases; the APK `versionName` is the user-visible version.
- Verify: `./gradlew :app:assembleDebug` shows 2.5.0 (or check `AndroidManifest` output).

---

## Phase 1 — Local feed cache (schema v5)

**Why a separate table:** the existing `post` table is "saved posts" (user-curated,
referenced by `History`/`BackupRepository`). Feeding it with 2k transient posts would
pollute saved-posts, backups and history lookups. Cache must be independently purgeable.

**Task 1.1: `FeedCache` entity**

- Create: `app/src/main/java/com/cosmos/unreddit/data/model/db/FeedCache.kt`

```kotlin
@Parcelize
@Entity(
    tableName = "feed_cache",
    primaryKeys = ["post_id", "profile_id"],
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE   // deleting a profile wipes its cache for free
        )
    ],
    indices = [Index("fetched_at"), Index("subreddit", "profile_id")]
)
data class FeedCache @JvmOverloads constructor(
    @ColumnInfo(name = "post_id") val postId: String,
    val subreddit: String,
    val postJson: String,        // full PostData (Moshi JSON) — everything the UI needs
    val fetchedAt: Long,         // epoch ms
    @ColumnInfo(name = "profile_id") val profileId: Int
)
```

Storing `PostData` as JSON (one column) keeps the schema stable if the model grows.
Row size ≈ 2–5 KB → 2,000 posts ≈ 10 MB worst case; capped in Task 1.3.

**Task 1.2: DAO + migration + DI**

- Create: `app/src/main/java/com/cosmos/unreddit/data/local/dao/FeedCacheDao.kt`

```kotlin
@Dao
abstract class FeedCacheDao {
    @Query("SELECT * FROM feed_cache WHERE profile_id = :profileId")
    abstract fun allFromProfile(profileId: Int): List<FeedCache>

    @Query("SELECT * FROM feed_cache WHERE profile_id = :profileId AND subreddit = :sub")
    abstract suspend fun bySubreddit(profileId: Int, subreddit: String): List<FeedCache>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(rows: List<FeedCache>)

    @Query("DELETE FROM feed_cache WHERE profile_id = :profileId AND fetched_at < :cutoff")
    abstract suspend fun purgeOlderThan(profileId: Int, cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM feed_cache WHERE profile_id = :profileId")
    abstract suspend fun count(profileId: Int): Int

    @Query("SELECT post_id FROM feed_cache WHERE profile_id = :profileId ORDER BY fetched_at LIMIT :keep OFFSET :cut")
    abstract suspend fun idsToCut(profileId: Int, keep: Int, cut: Int): List<String>

    @Query("DELETE FROM feed_cache WHERE profile_id = :profileId AND post_id IN (:ids)")
    abstract suspend fun deleteByIds(profileId: Int, ids: List<String>)

    @Query("DELETE FROM feed_cache WHERE profile_id = :profileId")
    abstract suspend fun wipeProfile(profileId: Int)
}
```

- Modify: `RedditDatabase.kt` — add `FeedCache::class` to `@Database.entities`,
  `version = 5`, `abstract fun feedCacheDao(): FeedCacheDao`,
  `MIGRATION_4_5` = `CREATE TABLE feed_cache (...)` (mirrors entity DDL).
- Modify: `DatabaseModule.kt` — `.addMigrations(..., MIGRATION_4_5)`.

**Task 1.3: purge policy (the leak guard — user's explicit requirement)**

Rules, all enforced in `FeedCoordinator` (single writer, no races):
1. **TTL:** rows older than 12 h deleted after each refresh cycle (posts this old are
   worthless for a hot feed; `fetched_at` index makes it O(matches)).
2. **Row cap:** if count > 2,500 after a cycle, delete oldest beyond the cap.
3. **Sub coverage:** a sub with 0 cached rows and 0 fresh rows this cycle → its stale rows
   were already caught by TTL; no separate job needed.
4. **Orphan safety:** nothing else references `feed_cache` — it is pure cache, so deleting
   rows can never strand a reference. Media files: **not** cached by us (Coil's disk cache
   is bounded by its own LRU; we additionally call `Coil` image loader eviction on purge).
   We never download media proactively.
5. **Profile deletion:** FK `ON DELETE CASCADE` already handled by Room FK config.
6. **DB size tripwire:** `sqlite_master` size check in diagnostics; if the table exceeds
   25 MB, purge to 1,000 rows and log a warning.

Offline unit test (new `FeedCachePurgeTest.kt`): populate rows with varying ages, run
purge, assert counts. (In-memory Room; same as existing `RedditOfficialSourceTest` style.)

---

## Phase 2 — Progressive fan-out at the source

**Task 2.1: streaming fan-out API**

- Modify: `app/src/main/java/com/cosmos/unreddit/data/remote/api/reddit/source/RedditOfficialSource.kt`

Add alongside the existing blocking `getSubredditFanOut`:

```kotlin
data class FanOutProgress(
    val total: Int,
    val done: Int,
    val lastFinished: String?,   // subreddit name
    val current: String?,        // currently being fetched (single, for the label)
    val failed: Int
)

/** Emits a merged page after EVERY batch of subs completes (not only at the end). */
suspend fun getSubredditFanOutProgressive(
    multiredd: String,
    sort: Sort,
    onProgress: (FanOutProgress) -> Unit
): Flow<List<PostChild>>   // each emission: merge of all finished subs so far
```

Mechanics:
- `subs.shuffled()` **per refresh cycle** (user requirement: never expose the same subs first).
- Concurrency 4 (from 5) + per-sub 1 retry with 3–8 s jittered backoff (CF throttle shaping —
  user: repeated identical requests get blocked sooner/later unpredictably, so vary timing).
- `callbackFlow`: launch one async per sub under a semaphore; on each sub completion,
  `emit(mergeFanOut(finishedSoFar, sort))` and `onProgress(...)`. Collecting side renders.
- Dead sub → `emptyList()` as today (lenient), counted in `failed`.
- The final emission = today's full merge. Cursor encoding (per-sub `after` joined by `;`)
  is produced from the final per-sub state, unchanged.
- Existing blocking `getSubredditFanOut` is kept for non-home `getSubreddit` callers (single
  callsites: nothing else calls it — home uses the new method via the coordinator; the old
  path stays as the >100-sub fallback, still working).

**Task 2.2: merge with fresh-priority (the "best/hot-like" algorithm)**

The existing `mergeFanOut` (interleave for HOT, date for NEW, score for TOP) stays for
fresh-only merges. New `mergeFreshAndCache(fresh: List<PostChild>, cache: List<PostChild>, sort)`:

1. Dedupe by fullname; fresh wins on collision (fresh has the current score/comment count).
2. Base order = `mergeFanOut(freshPerSub...)` — the balanced interleave (this is the
   hot-like shape: interleaving prevents big subs burying small ones, which is exactly why
   reddit's hot feed isn't pure-score).
3. Cache-only posts: insert at a *score-adjusted* position — ranked by
   `score / (ageHours + 2)` (reddit hot ≈ score^1.5 / age^1.5; this approximation is enough)
   and **capped at 30 positions** so stale posts can't float above fresh content.
   For NEW sort: cache-only posts go after all fresh (they are by definition older).
4. Output capped at 500 rows (UI never needs more; protects RAM).

Pure function → unit-testable (`FeedMergeTest.kt`: small fresh/cache lists, assert order,
dedupe, cap).

---

## Phase 3 — FeedCoordinator (home feed state machine)

**Task 3.1: New class**

- Create: `app/src/main/java/com/cosmos/unreddit/data/feed/FeedCoordinator.kt`

```kotlin
@Singleton
class FeedCoordinator @Inject constructor(
    private val source: CurrentSource,
    private val db: RedditDatabase,
    private val prefs: PreferencesRepository,
    @DefaultDispatcher private val io: CoroutineDispatcher
) {
    data class HomeFeedState(
        val posts: List<PostEntity> = emptyList(),   // merged, ready to render
        val progress: FanOutProgress? = null,        // null when idle
        val refreshing: Boolean = false,
        val fromCacheOnly: Boolean = false,          // true → show "offline/stale" banner
        val lastRefresh: Long = 0L
    )
    val state: StateFlow<HomeFeedState>
    suspend fun refresh(profileId: Int, sort: Sort)   // one full cycle
    suspend fun openPost(permalink: String, profileId: Int) // background post refresh
}
```

Lifecycle: `refresh()` guarded by `activeCycle: Job?` — a new call cancels the previous
cycle (pull-to-refresh / sort change) and starts fresh. **Cache-first:**
1. Load cache (all subs) → map to `PostEntity` → `update { posts = cached, refreshing = true }`.
   This is the instant first paint (also the *entire* content when offline).
2. If offline (no network, or cycle 0/73 succeeded) → `fromCacheOnly = true`, stop.
3. Otherwise run `getSubredditFanOutProgressive`; on each emission → `mergeFreshAndCache` →
   `update { posts = merged }`. UI re-renders incrementally.
4. On completion: `upsertAll(fresh rows)`, purge (Task 1.3), `refreshing = false`,
   `lastRefresh = now`.
5. Background refresh of the page's own list: every cycle also re-checks the newest 20
   cached ids (cheap: one fan-out already fetched them; just `upsert`).

**Task 3.2: post-open background refresh (offline reading)**

- On `PostListViewModel.openPost(permalink)`: call coordinator; coordinator checks cache by
  permalink id; if a cached snapshot is fresh (< 1 h) the detail screen renders it instantly;
  network fetch (existing `getPost()` with redirect-follow) then runs in the background and
  upserts the cache row. Detail screen observes a `StateFlow<PostEntity?>` keyed by
  permalink, so it swaps cache → fresh seamlessly.

**Task 3.3: single-sub & other screens**

Single-sub feeds also write to the same cache (same table, `subreddit` column) so visiting
a sub then returning home benefits, and offline sub browsing works. Non-home screens keep
Paging (their data is one sub, no merge needed) but the repository now
**seeds the PagingSource from cache on page 1** (`SmartPostListDataSource` gets an optional
`seed: List<PostChild>` prepended to page-1 results, deduped by the normal paging key
machinery — paging keys only come from fresh pages).

---

## Phase 4 — Home feed UI

**Task 4.1: switch home feed to ListAdapter**

- Modify: `app/src/main/java/com/cosmos/unreddit/ui/postlist/PostListFragment.kt` and
  `PostListViewModel.kt`.
  - `PostListViewModel`: home path emits `repository`-free `coordinator.state` (mapped to
    `PostEntity` list) instead of `postDataFlow: PagingData`. Keep `PagingData` flow for the
    single-sub case by detecting `subreddit.size == 1` (no merge/caching of home in that case
    is needed; still writes cache as a side effect via the repository).
  - `PostListFragment`: `PostListAdapter` (already a `ListAdapter`-style class — verify its
    base class; if it extends `PagingDataAdapter`, wrap items in `LoadState.NOT_LOADED`
    equivalents or create a thin `HomePostAdapter` reusing `PostViewHolder`/`bind` code —
    reuse the bind function, ~30 lines).
- **No pull-to-refresh removal:** refresh button + coordinator.refresh().

**Task 4.2: progress header (transparency, user requirement)**

- New layout row pinned above the list in `fragment_post_list.xml`:
  - spinner (ProgressBar, small) — visible while `refreshing`
  - text: `Loading r/<current>…  X / Y` (X = finished subs, Y = total; from
    `FanOutProgress`)
  - sub-label: last finished sub name, e.g. `done: r/Steam, r/food, …` (last 3)
  - when `fromCacheOnly`: no spinner, text `Offline — showing cached posts`
  - hidden (GONE) when idle (not refreshing)
- Update via `coordinator.state` `collect` in fragment (lifecycle-aware, `repeatOnLifecycle`).

**Task 4.3: error banner fix (overlapping RETRY — previously noted)**

- `infoRetry` (InfoRetryBar): set `singleLine + ellipsize END` on the message TextView,
  layout weight so the RETRY button keeps its wrap-content width; long messages truncate
  instead of overlapping. One-line layout change.

---

## Phase 5 — Versioning, docs, validation

**Task 5.1:** bump to **2.5.0/25** (Phase 0), `CONTEXT.md` v7.6 with the new architecture
section + versioning convention. `docs/reddit-ssr-contract.md` rewritten to match reality
(fan-out, redirect-follow, CF model, `.rss` fallback role).

**Task 5.2: tests**
- Offline (CI): `FeedCachePurgeTest` (TTL + cap), `FeedMergeTest` (fresh-priority merge,
  dedupe, cap, NEW-sort cache-after-fresh), existing 13 parser + 2 redirect tests.
- Live (diagnostic, print-only — never asserts so a CF-throttled runner can't red the gate):
  `RedditOfficialDiagnostic` gains `liveFanOutProgressive` — asserts nothing, prints
  emission count + merge sizes over time (proves progressive emissions happen).
- **Device verification (you):** install `build-<sha>` of 2.5.0:
  1. fresh install → import profile → home must show *cached* (empty on first run → then
     progressive fill with X/Y counter), then a complete scored feed;
  2. force-close + relaunch → home must appear **instantly** from cache, refresh in bg;
  3. airplane mode → home shows cached feed with "Offline" label, no crash;
  4. open a post → detail from cache immediately, stats refresh in bg;
  5. after 24 h: `adb shell` — DB size < 25 MB, no orphan rows (diagnostic prints counts).

**Task 5.3: commit & push** → CI build → release `build-<sha>` (APK shows 2.5.0).

---

## Risks & trade-offs

1. **73-request cost per refresh** — accepted (same as current build, which you approved by
   installing it); mitigated by: refresh only on home open + manual; 12 h cache TTL means
   most sessions render from cache with a background cycle.
2. **RAM on progressive re-emission** — merged list capped at 500 `PostEntity` rows;
   adapter diffs by id, so re-emission only rebinds changed rows.
3. **Paging removal on home** — the one structural risk (adapter rewiring). Mitigated by
   reusing `PostViewHolder` bind code; all other screens untouched.
4. **Room migration 4→5** — additive table only; existing installs migrate in <10 ms.
5. **CF** — every request is CF-routed by design; the app treats challenges as a normal
   state (solver + retries + jitter + cache fallback). No CF-free endpoint exists or will;
   this design stops *needing* one, because cache carries the user.
6. **reCAPTCHA** — not observed on any read path; if reddit ever serves it, the CF-bypass
   project you mentioned is the escalation path (out of app scope until then).

## Open questions (answer or I default)

- **Refresh trigger:** default = on home open + pull/manual only. (Auto-timer possible but
  wastes requests while app is open; say the word if you want e.g. 30-min auto.)
- **Cache TTL 12 h** for home feed, 1 h for post-detail snapshots. Tunable later in settings.
- **Version 2.5.0** for this release (feature = progressive + cache + offline).
