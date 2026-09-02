package com.cosmos.unreddit.data.local

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Regression tests for the 4→5 Room migration, run on the JVM against a real SQLite
 * database (sqlite-jdbc).
 *
 * History: the first version of MIGRATION_4_5 created `feed_cache` with column
 * `post_json` (snake_case) and only 2 of the 3 indexes the entity declares. Room's
 * post-migration verification failed with
 * `IllegalStateException: Migration didn't properly handle: feed_cache`, the database
 * version rolled back to 4, and every launch re-ran the broken migration against the
 * stale table (its CREATE TABLE IF NOT EXISTS was a no-op) and crashed — the app was
 * permanently unlaunchable (326 recorded crashes).
 *
 * These tests execute the REAL migration object against a Proxy implementation of
 * androidx.sqlite.db.SupportSQLiteDatabase backed by a real JDBC connection, so any
 * future divergence between MIGRATION_4_5 and the committed 5.json schema fails CI.
 */
class RedditMigration4to5Test {

    // --- Expected final state, copied from app/schemas/.../5.json (feed_cache) ---
    private val expectedColumns = linkedMapOf(
        "post_id" to "TEXT",
        "subreddit" to "TEXT",
        "permalink" to "TEXT",
        "postJson" to "TEXT",
        "fetched_at" to "INTEGER",
        "profile_id" to "INTEGER"
    )
    private val expectedIndices = linkedMapOf(
        "index_feed_cache_fetched_at" to "fetched_at",
        "index_feed_cache_subreddit_profile_id" to "subreddit,profile_id",
        "index_feed_cache_permalink_profile_id" to "permalink,profile_id"
    )

    /** Build a pristine v4 database exactly as exported in app/schemas/.../4.json. */
    private fun createV4Database(conn: Connection) {
        fun exec(sql: String) = conn.createStatement().use { it.execute(sql) }
        exec("CREATE TABLE IF NOT EXISTS `subscription` (`name` TEXT NOT NULL COLLATE NOCASE, `time` INTEGER NOT NULL, `icon` TEXT, `profile_id` INTEGER NOT NULL, PRIMARY KEY(`name`, `profile_id`), FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        exec("CREATE TABLE IF NOT EXISTS `history` (`post_id` TEXT NOT NULL, `time` INTEGER NOT NULL, `profile_id` INTEGER NOT NULL, PRIMARY KEY(`post_id`, `profile_id`), FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        exec("CREATE TABLE IF NOT EXISTS `profile` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `name` TEXT NOT NULL)")
        exec("CREATE TABLE IF NOT EXISTS `post` (`id` TEXT NOT NULL, `subreddit` TEXT NOT NULL, `title` TEXT NOT NULL, `ratio` INTEGER NOT NULL, `total_awards` INTEGER NOT NULL, `oc` INTEGER NOT NULL, `score` TEXT NOT NULL, `type` INTEGER NOT NULL, `domain` TEXT NOT NULL, `self` INTEGER NOT NULL, `self_text_html` TEXT, `suggested_sorting` TEXT NOT NULL, `nsfw` INTEGER NOT NULL, `preview` TEXT, `spoiler` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `locked` INTEGER NOT NULL, `poster_type` INTEGER NOT NULL, `author` TEXT NOT NULL, `comments_number` TEXT NOT NULL, `permalink` TEXT NOT NULL, `stickied` INTEGER NOT NULL, `url` TEXT NOT NULL, `created` INTEGER NOT NULL, `media_type` TEXT NOT NULL, `media_url` TEXT NOT NULL, `time` INTEGER NOT NULL, `profile_id` INTEGER NOT NULL, PRIMARY KEY(`id`, `profile_id`), FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        exec("CREATE TABLE IF NOT EXISTS `comment` (`total_awards` INTEGER NOT NULL, `link_id` TEXT NOT NULL, `author` TEXT NOT NULL, `score` TEXT NOT NULL, `body_html` TEXT NOT NULL, `edited` INTEGER NOT NULL, `submitter` INTEGER NOT NULL, `stickied` INTEGER NOT NULL, `score_hidden` INTEGER NOT NULL, `permalink` TEXT NOT NULL, `id` TEXT NOT NULL, `created` INTEGER NOT NULL, `controversiality` INTEGER NOT NULL, `poster_type` INTEGER NOT NULL, `link_title` TEXT, `link_permalink` TEXT, `link_author` TEXT, `subreddit` TEXT NOT NULL, `name` TEXT NOT NULL, `time` INTEGER NOT NULL, `profile_id` INTEGER NOT NULL, PRIMARY KEY(`name`, `profile_id`), FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        exec("CREATE TABLE IF NOT EXISTS `redirect` (`pattern` TEXT NOT NULL, `redirect` TEXT NOT NULL, `service` TEXT NOT NULL, `mode` INTEGER NOT NULL, PRIMARY KEY(`service`))")
        exec("CREATE INDEX IF NOT EXISTS `index_subscription_profile_id` ON `subscription` (`profile_id`)")
        exec("CREATE INDEX IF NOT EXISTS `index_history_profile_id` ON `history` (`profile_id`)")
        exec("CREATE INDEX IF NOT EXISTS `index_post_profile_id` ON `post` (`profile_id`)")
        exec("CREATE INDEX IF NOT EXISTS `index_comment_profile_id` ON `comment` (`profile_id`)")
        exec("INSERT INTO `profile` (`id`, `name`) VALUES (1, 'Stealth')")
    }

    /**
     * Reproduce the exact broken state found on the user's device (from the crash log's
     * "Found:" block): column `post_json`, no `postJson`, only the 2 non-unique indexes.
     */
    private fun createBrokenFeedCache(conn: Connection) {
        fun exec(sql: String) = conn.createStatement().use { it.execute(sql) }
        exec("CREATE TABLE IF NOT EXISTS `feed_cache` (`post_id` TEXT NOT NULL, `subreddit` TEXT NOT NULL, `permalink` TEXT NOT NULL, `post_json` TEXT NOT NULL, `fetched_at` INTEGER NOT NULL, `profile_id` INTEGER NOT NULL, PRIMARY KEY(`post_id`, `profile_id`), FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        exec("CREATE INDEX IF NOT EXISTS `index_feed_cache_fetched_at` ON `feed_cache` (`fetched_at`)")
        exec("CREATE INDEX IF NOT EXISTS `index_feed_cache_subreddit_profile_id` ON `feed_cache` (`subreddit`, `profile_id`)")
        exec("INSERT INTO `feed_cache` (`post_id`, `subreddit`, `permalink`, `post_json`, `fetched_at`, `profile_id`) VALUES ('t3_stale', 'test', '/r/test/comments/x/', '{}', 123, 1)")
    }

    private fun columnsOf(conn: Connection, table: String): Map<String, String> {
        val cols = linkedMapOf<String, String>()
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info($table)").use { rs ->
                while (rs.next()) cols[rs.getString("name")] = rs.getString("type")
            }
        }
        return cols
    }

    private fun indicesOf(conn: Connection): Map<String, Pair<Boolean, List<String>>> {
        val idx = LinkedHashMap<String, Pair<Boolean, List<String>>>()
        // First pass: collect (name, unique) pairs on their own statement.
        val names = mutableListOf<Pair<String, Boolean>>()
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA index_list(feed_cache)").use { rs ->
                while (rs.next()) {
                    val n = rs.getString("name")
                    // Room's schema verification ignores implicit PK auto-indexes
                    if (!n.startsWith("sqlite_autoindex_")) names.add(n to (rs.getInt("unique") == 1))
                }
            }
        }
        // Second pass: one fresh statement per index (reusing a statement invalidates
        // an open ResultSet in JDBC).
        for ((name, unique) in names) {
            val cols = mutableListOf<String>()
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA index_info($name)").use { info ->
                    while (info.next()) cols.add(info.getString("name"))
                }
            }
            idx[name] = unique to cols
        }
        return idx
    }

    private fun assertFeedCacheMatchesExpected(conn: Connection) {
        assertEquals("feed_cache columns must match 5.json exactly", expectedColumns, columnsOf(conn, "feed_cache"))
        val idx = indicesOf(conn)
        assertEquals("feed_cache index names must match 5.json exactly", expectedIndices.keys, idx.keys)
        for ((name, value) in idx) {
            val (unique, cols) = value
            val (expUnique, expCols) = when (name) {
                "index_feed_cache_permalink_profile_id" -> true to listOf("permalink", "profile_id")
                "index_feed_cache_fetched_at" -> false to listOf("fetched_at")
                "index_feed_cache_subreddit_profile_id" -> false to listOf("subreddit", "profile_id")
                else -> error("unexpected index $name")
            }
            assertEquals("index $name unique flag", expUnique, unique)
            assertEquals("index $name columns", expCols, cols)
        }
    }

    /** Test 1: clean v4 → v5 (the normal upgrade path). */
    @Test
    fun migration4to5_cleanUpgradeProducesExpectedSchema() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            createV4Database(conn)
            migrate(conn)
            assertFeedCacheMatchesExpected(conn)
        }
    }

    /** Test 2: the user's actual device state (stale `post_json` table + leftover row). */
    @Test
    fun migration4to5_repairsBrokenInstallWithStalePostJsonTable() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            createV4Database(conn)
            createBrokenFeedCache(conn) // exactly the "Found:" schema from the crash log
            migrate(conn)
            assertFeedCacheMatchesExpected(conn)
            // Cache is disposable: stale rows must be gone, no crash, no orphan data.
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM feed_cache").use { rs ->
                    rs.next()
                    assertEquals("stale cache rows must be dropped", 0, rs.getInt(1))
                }
            }
        }
    }

    /** Test 3: re-running the migration on an ALREADY-correct table must be a safe no-op
     *  (the recovery path must never drop a good table). */
    @Test
    fun migration4to5_reRunOnCorrectTableIsNoOp() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            createV4Database(conn)
            migrate(conn)
            conn.createStatement().use { st ->
                st.execute(
                    "INSERT INTO `feed_cache` (`post_id`, `subreddit`, `permalink`, `postJson`, `fetched_at`, `profile_id`) " +
                        "VALUES ('t3_good', 'test', '/r/test/comments/y/', '{}', 456, 1)"
                )
            }
            migrate(conn) // second run
            assertFeedCacheMatchesExpected(conn)
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM feed_cache").use { rs ->
                    rs.next()
                    assertEquals("good rows must survive a re-run", 1, rs.getInt(1))
                }
            }
        }
    }

    /** Runs the REAL MIGRATION_4_5 object against a JDBC-backed SupportSQLiteDatabase proxy. */
    private fun migrate(conn: Connection) {
        val dbClass = Class.forName("androidx.sqlite.db.SupportSQLiteDatabase")
        @Suppress("UNCHECKED_CAST")
        val db = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(dbClass), DbHandler(conn))
        RedditDatabase.MIGRATION_4_5.migrate(db as androidx.sqlite.db.SupportSQLiteDatabase)
    }

    private class DbHandler(private val conn: Connection) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when {
            method.name == "close" -> Unit
            method.name == "hashCode" -> System.identityHashCode(proxy)
            method.name == "equals" -> proxy === args!![0]
            method.name == "toString" -> "JdbcSupportSqlite(conn=$conn)"
            method.name == "execSQL" -> {
                if (args == null || args.size <= 1) {
                    conn.createStatement().use { it.execute(args!![0] as String) }
                } else {
                    conn.prepareStatement(args[0] as String).use { ps ->
                        (args[1] as Array<out Any?>).forEachIndexed { i, v -> ps.setString(i + 1, v.toString()) }
                        ps.execute()
                    }
                }
                null
            }
            method.name == "query" && args != null && args.size == 1 -> {
                val sql = args[0] as String
                val st = conn.createStatement()
                val rs = st.executeQuery(sql)
                buildCursorProxy(rs)
            }
            else -> throw UnsupportedOperationException("migration used unbridged method: ${method.name}")
        }
    }
}

/** Bridges android.database.Cursor (the methods the migration's PRAGMA loop uses) onto a JDBC ResultSet. */
private fun buildCursorProxy(rs: ResultSet): Any {
    val cursorInterface = Class.forName("android.database.Cursor")
    return Proxy.newProxyInstance(
        cursorInterface.classLoader,
        arrayOf(cursorInterface),
        InvocationHandler { proxy, method, args ->
            when {
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args!![0]
                method.name == "toString" -> "JdbcCursor($rs)"
                method.name == "next" -> rs.next()
                method.name == "moveToNext" -> rs.next()
                method.name == "getColumnIndexOrThrow" -> rs.findColumn(args!![0] as String) - 1
                method.name == "getString" -> rs.getString((args[0] as Int) + 1)
                method.name == "getInt" -> rs.getInt((args[0] as Int) + 1)
                method.name == "close" -> { rs.close(); Unit }
                else -> throw UnsupportedOperationException("migration used unbridged Cursor method: ${method.name}")
            }
        }
    )
}
