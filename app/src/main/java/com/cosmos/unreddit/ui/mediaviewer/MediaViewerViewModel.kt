package com.cosmos.unreddit.ui.mediaviewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cosmos.unreddit.data.model.Media
import com.cosmos.unreddit.data.model.MediaSource
import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.Resource
import com.cosmos.unreddit.data.repository.GfycatRepository
import com.cosmos.unreddit.data.repository.ImgurRepository
import com.cosmos.unreddit.data.repository.PreferencesRepository
import com.cosmos.unreddit.data.repository.RedgifsRepository
import com.cosmos.unreddit.data.repository.StreamableRepository
import com.cosmos.unreddit.di.DispatchersModule.DefaultDispatcher
import com.cosmos.unreddit.util.LinkUtil
import com.cosmos.unreddit.util.LinkUtil.https
import com.cosmos.unreddit.util.extension.updateValue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import com.google.common.net.MediaType as ContentType

@HiltViewModel
class MediaViewerViewModel
@Inject constructor(
    private val imgurRepository: ImgurRepository,
    private val streamableRepository: StreamableRepository,
    private val gfycatRepository: GfycatRepository,
    private val redgifsRepository: RedgifsRepository,
    private val preferencesRepository: PreferencesRepository,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _media: MutableStateFlow<Resource<List<Media>>> =
        MutableStateFlow(Resource.Loading())
    val media: StateFlow<Resource<List<Media>>> = _media

    private val _selectedPage: MutableStateFlow<Int> = MutableStateFlow(0)
    val selectedPage: StateFlow<Int> = _selectedPage

    val isMultiMedia: StateFlow<Boolean> = _media
        .filter { it is Resource.Success }
        .map { (it as Resource.Success).data.size > 1 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isVideoMuted: Flow<Boolean>
        get() = preferencesRepository.getMuteVideo(false)

    var hideControls: Boolean = false

    init {
        viewModelScope.launch { preferencesRepository.getMuteVideo(false).first() }
    }

    fun loadMedia(link: String, mediaType: MediaType, forceUpdate: Boolean = false) {
        if (_media.value !is Resource.Success || forceUpdate) {
            viewModelScope.launch {
                val httpsLink = withContext(defaultDispatcher) { link.https }
                retrieveMedia(httpsLink, mediaType)
            }
        }
    }

    private suspend fun retrieveMedia(link: String, mediaType: MediaType) {
        when (mediaType) {
            MediaType.IMGUR_LINK -> {
                val id = LinkUtil.getImageIdFromImgurLink(link)
                val media = Media(
                    ContentType.JPEG.toString(),
                    MediaSource(LinkUtil.getUrlFromImgurId(id)),
                    Media.Type.IMAGE
                )
                setMedia(media.singletonList())
            }
            MediaType.IMGUR_GIF -> {
                val media = Media(
                    ContentType.MP4_VIDEO.toString(),
                    MediaSource(LinkUtil.getImgurVideo(link)),
                    Media.Type.VIDEO
                )
                setMedia(media.singletonList())
            }
            MediaType.GFYCAT -> {
                val id = LinkUtil.getGfycatId(link)

                gfycatRepository.getGfycatGif(id)
                    .onStart {
                        _media.value = Resource.Loading()
                    }
                    .catch {
                        catchError(it)
                    }
                    .map {
                        Media(
                            ContentType.MP4_VIDEO.toString(),
                            MediaSource(it.gfyItem.contentUrls.mp4.url),
                            Media.Type.VIDEO
                        ).singletonList()
                    }
                    .collect {
                        setMedia(it)
                    }
            }
            MediaType.REDGIFS -> {
                val id = LinkUtil.getGfycatId(link)

                redgifsRepository.getRedgifsGif(id)
                    .onStart {
                        _media.value = Resource.Loading()
                    }
                    .catch {
                        catchError(it)
                    }
                    .map {
                        Media(
                            ContentType.MP4_VIDEO.toString(),
                            MediaSource(it.gif.urls.hd),
                            Media.Type.VIDEO
                        ).singletonList()
                    }
                    .collect {
                        setMedia(it)
                    }
            }
            MediaType.STREAMABLE -> {
                val shortcode = LinkUtil.getStreamableShortcode(link)
                streamableRepository.getVideo(shortcode)
                    .onStart {
                        _media.value = Resource.Loading()
                    }
                    .catch {
                        catchError(it)
                    }
                    .map { video ->
                        Media(
                            ContentType.MP4_VIDEO.toString(),
                            MediaSource(video.files.mp4.url),
                            Media.Type.VIDEO
                        ).singletonList()
                    }
                    .collect {
                        setMedia(it)
                    }
            }
            MediaType.IMGUR_ALBUM, MediaType.IMGUR_GALLERY -> {
                val albumId = LinkUtil.getAlbumIdFromImgurLink(link)
                imgurRepository.getAlbum(albumId)
                    .map { album ->
                        album.data.images.map { image ->
                            val mime: String
                            val type: Media.Type

                            if (image.preferVideo) {
                                mime = ContentType.MP4_VIDEO.toString()
                                type = Media.Type.VIDEO
                            } else {
                                mime = ContentType.JPEG.toString()
                                type = Media.Type.IMAGE
                            }

                            Media(
                                mime,
                                MediaSource(LinkUtil.getUrlFromImgurImage(image)),
                                type,
                                caption = image.description
                            )
                        }
                    }
                    .map {
                        // Some Imgur galleries are empty and actually point to a single image
                        it.ifEmpty {
                            Media(
                                ContentType.JPEG.toString(),
                                MediaSource(LinkUtil.getUrlFromImgurId(albumId)),
                                Media.Type.IMAGE
                            ).singletonList()
                        }
                    }
                    .flowOn(defaultDispatcher)
                    .onStart {
                        _media.value = Resource.Loading()
                    }
                    .catch {
                        catchError(it)
                    }
                    .collect {
                        setMedia(it)
                    }
            }
            MediaType.REDDIT_GALLERY -> {
                // TODO: Get post ID from gallery link and fetch post through Stealth API
//                val permalink = LinkUtil.getPermalinkFromMediaUrl(link)
//                postListRepository.getPost(permalink, Sorting(Sort.BEST)).onStart {
//                    _media.value = Resource.Loading()
//                }.catch {
//                    catchError(it)
//                }.map { listings ->
//                    postMapper.dataToEntity(PostUtil.getPostData(listings)).gallery
//                }.collect {
//                    setMedia(it)
//                }
            }
            else -> {
                _media.value = Resource.Error()
            }
        }
    }

    private fun catchError(throwable: Throwable) {
        when (throwable) {
            is IOException -> _media.value = Resource.Error(message = throwable.message)
            is HttpException -> _media.value = Resource.Error(throwable.code(), throwable.message())
            else -> _media.value = Resource.Error()
        }
    }

    fun setMedia(media: List<Media>) {
        _media.updateValue(Resource.Success(media))
    }

    fun setMuted(mutedVideo: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setMuteVideo(mutedVideo)
        }
    }

    fun setSelectedPage(position: Int) {
        _selectedPage.updateValue(position)
    }
}
