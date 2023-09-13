package com.cosmos.unreddit.ui.mediaviewer

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.Coil
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.cosmos.unreddit.data.model.Media
import com.cosmos.unreddit.databinding.ItemImageBinding
import com.cosmos.unreddit.databinding.ItemVideoBinding
import com.cosmos.unreddit.util.ExoPlayerHelper
import com.cosmos.unreddit.util.LinkUtil
import com.cosmos.unreddit.util.extension.asBoolean
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.upstream.HttpDataSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.HttpURLConnection

class MediaViewerAdapter(
    context: Context,
    var muteVideo: Boolean,
    private val onMediaClick: () -> Unit,
    private val showControls: (Boolean) -> Unit,
    private val hasAudio: (Boolean) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val media: MutableList<Media> = mutableListOf()

    private val players: MutableList<Player> = mutableListOf()

    private val exoPlayerHelper by lazy { ExoPlayerHelper(context) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return when (viewType) {
            Media.Type.IMAGE.value -> ImageViewHolder(
                ItemImageBinding.inflate(inflater, parent, false)
            )
            Media.Type.VIDEO.value -> VideoViewHolder(
                ItemVideoBinding.inflate(inflater, parent, false)
            )
            else -> throw IllegalArgumentException("Unknown type $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (getItemViewType(position)) {
            Media.Type.IMAGE.value -> (holder as ImageViewHolder).bind(media[position])
            Media.Type.VIDEO.value -> (holder as VideoViewHolder).bind(media[position])
        }
    }

    override fun getItemCount(): Int {
        return media.size
    }

    override fun getItemViewType(position: Int): Int {
        return getItem(position)?.type?.value ?: -1
    }

    fun getItem(position: Int): Media? {
        return media.getOrNull(position)
    }

    fun submitData(images: List<Media>) {
        this.media.clear()
        this.media.addAll(images)
        notifyDataSetChanged()
    }

    fun clear() {
        for (player in players) {
            player.release()
        }
        players.clear()
        exoPlayerHelper.clearCache()
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class ImageViewHolder(
        private val binding: ItemImageBinding
    ) : RecyclerView.ViewHolder(binding.root), View.OnTouchListener {

        init {
            binding.image.run {
                setOnTouchListener(this@ImageViewHolder)
                setOnClickListener { onMediaClick.invoke() }
            }
        }

        fun bind(image: Media) {
            loadImage(image)
            binding.infoRetry.setActionClickListener { loadImage(image) }
        }

        private fun loadImage(image: Media) {
            with(binding.image) {
                Coil.imageLoader(context).enqueue(
                    ImageRequest.Builder(context).apply {
                        data(image.source.url)
                        crossfade(true)
                        scale(Scale.FILL)
                        precision(Precision.AUTOMATIC)
                        memoryCachePolicy(CachePolicy.READ_ONLY)
                        diskCachePolicy(CachePolicy.READ_ONLY)
                        listener(
                            onStart = {
                                binding.run {
                                    loadingCradle.isVisible = true
                                    infoRetry.hide()
                                }
                            },
                            onCancel = {
                                binding.run {
                                    loadingCradle.isVisible = false
                                }
                            },
                            onError = { _, _ ->
                                binding.run {
                                    loadingCradle.isVisible = false
                                    showControls.invoke(false)
                                    infoRetry.show()
                                }
                            },
                            onSuccess = { _, _ ->
                                binding.run {
                                    loadingCradle.isVisible = false
                                    showControls.invoke(true)
                                }
                            }
                        )
                        target { drawable -> setImageDrawable(drawable) }
                    }.build()
                )
            }
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            return if (
                event.pointerCount >= 2 ||
                view.canScrollHorizontally(1) &&
                binding.image.canScrollHorizontally(-1)
            ) {
                // Multi-touch event
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        // Disallow RecyclerView to intercept touch events.
                        binding.image.parent.requestDisallowInterceptTouchEvent(true)
                        // Disable touch on view
                        false
                    }
                    MotionEvent.ACTION_UP -> {
                        // Allow RecyclerView to intercept touch events.
                        binding.image.parent.requestDisallowInterceptTouchEvent(false)
                        true
                    }
                    else -> true
                }
            } else {
                true
            }
        }
    }

    inner class VideoViewHolder(
        private val binding: ItemVideoBinding
    ) : RecyclerView.ViewHolder(binding.root), Player.Listener {

        fun bind(video: Media) {
            val url = video.source.url.toHttpUrlOrNull() ?: return

            if (url.host.contains("redgifs", ignoreCase = true)) {
                val requestProperties = url
                    .queryParameterNames
                    .associateWith { url.queryParameter(it) ?: "" }

                exoPlayerHelper.setRequestProperties(requestProperties)
            }

            val player = SimpleExoPlayer.Builder(binding.video.context)
                .setMediaSourceFactory(exoPlayerHelper.defaultMediaSourceFactory)
                .build()

            val videoItem = exoPlayerHelper.getMediaItem(video.source.url)

            val sound = video.alternatives
                ?.firstOrNull()
                ?.takeIf { it.type == Media.Type.AUDIO }
                ?.source
                ?.url

            if (sound != null) {
                val videoSource = exoPlayerHelper.getMediaSource(videoItem)
                val audioSource = exoPlayerHelper.getMediaSource(sound)
                val mergedSource = MergingMediaSource(videoSource, audioSource)

                player.setMediaSource(mergedSource)

                // Add special listener for Reddit videos with audio
                player.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (isErrorFromAudio(error)) {
                            // Retry without audio if an error is thrown
                            player.setMediaItem(videoItem)
                            player.prepare()
                        } else {
                            binding.infoRetry.show()
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        initAudioVolume(tracks)
                    }
                })
            } else {
                player.setMediaSource(exoPlayerHelper.getMediaSource(videoItem))
                player.addListener(this)
            }

            player.apply {
                repeatMode = Player.REPEAT_MODE_ALL
                prepare()
                play()
            }

            players.add(player)

            binding.video.run {
                this.player = player
                setControllerVisibilityListener { controllerVisibility ->
                    showControls.invoke(controllerVisibility.asBoolean)
                }
            }

            binding.infoRetry.setActionClickListener { player.prepare() }
        }

        private fun isErrorFromAudio(error: PlaybackException): Boolean {
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                val cause = error.cause as? HttpDataSource.InvalidResponseCodeException
                cause?.dataSpec?.key?.let { link ->
                    return (cause.responseCode == HttpURLConnection.HTTP_FORBIDDEN ||
                            cause.responseCode == HttpURLConnection.HTTP_NOT_FOUND) &&
                            LinkUtil.isRedditSoundTrack(link)
                }
            }
            return false
        }

        private fun initAudioVolume(tracks: Tracks) {
            if (hasAudio(tracks)) {
                muteAudio(muteVideo)
                hasAudio.invoke(true)
            } else {
                hasAudio.invoke(false)
            }
        }

        fun muteAudio(shouldMute: Boolean) {
            (binding.video.player as? SimpleExoPlayer)?.volume = if (shouldMute) 0F else 1F
        }

        private fun hasAudio(tracks: Tracks): Boolean {
            val groups = tracks.groups
            if (!groups.isEmpty()) {
                for (arrayIndex in 0 until groups.size) {
                    for (groupIndex in 0 until groups[arrayIndex].length) {
                        val sampleMimeType = groups[arrayIndex].getTrackFormat(groupIndex)
                            .sampleMimeType
                        if (sampleMimeType != null && sampleMimeType.contains("audio")) {
                            return true
                        }
                    }
                }
            }
            return false
        }

        override fun onPlayerError(error: PlaybackException) {
            binding.infoRetry.show()
        }

        override fun onTracksChanged(tracks: Tracks) {
            initAudioVolume(tracks)
        }
    }
}
