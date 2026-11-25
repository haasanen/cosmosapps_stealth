package com.cosmos.unreddit.data.local.mapper

import com.cosmos.unreddit.data.model.db.SubredditEntity
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutData
import com.cosmos.unreddit.di.DispatchersModule
import com.cosmos.unreddit.util.DecorativeTitles
import com.cosmos.unreddit.util.HtmlParser
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubredditMapper2 @Inject constructor(
    @DispatchersModule.DefaultDispatcher defaultDispatcher: CoroutineDispatcher
) : Mapper<AboutData, SubredditEntity>(defaultDispatcher) {

    private val htmlParser: HtmlParser = HtmlParser(defaultDispatcher)

    override suspend fun toEntity(from: AboutData): SubredditEntity {
        with(from) {
            return SubredditEntity(
                wikiEnabled ?: false,
                // The subreddit screen's headline view binds displayName
                // (layout_subreddit_content.xml:49), and reddit's
                // shreddit-subreddit-header display-name attribute carries the
                // decorative faux-small-cap string there (r/Piracy: LATIN LETTER
                // SMALL CAPITAL codepoints → tofu boxes for missing glyphs like
                // U+026A). Fold both display fields: the official source
                // synthesises title=name while display-name holds the fancy
                // string; other sources may put it in either. Fold is idempotent
                // on ASCII; see util/DecorativeTitles for the table and evidence.
                DecorativeTitles.fold(displayName),
                getHeader(),
                DecorativeTitles.fold(title),
                getPrimaryColor(),
                activeUserCount,
                getIcon(),
                subscribers,
                quarantine ?: false,
                htmlParser.separateHtmlBlocks(publicDescriptionHtml),
                getKeyColor(),
                getBackgroundColor(),
                over18 ?: false,
                htmlParser.separateHtmlBlocks(descriptionHtml),
                url,
                getTimeInMillis()
            )
        }
    }
}
