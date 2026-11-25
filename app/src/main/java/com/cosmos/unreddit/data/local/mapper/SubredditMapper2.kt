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
                displayName,
                getHeader(),
                // Decorative faux-small-cap titles (r/Piracy's title is written
                // with LATIN LETTER SMALL CAPITAL codepoints) render as tofu
                // boxes where platform fonts lack the glyph (e.g. U+026A).
                // Fold them to the capitals the author intended; see
                // util/DecorativeTitles for the table and evidence.
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
