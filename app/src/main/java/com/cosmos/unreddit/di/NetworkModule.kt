package com.cosmos.unreddit.di

import com.cosmos.unreddit.data.local.adapter.BackupAdapter
import com.cosmos.unreddit.data.remote.api.gfycat.GfycatApi
import com.cosmos.unreddit.data.remote.api.imgur.ImgurApi
import com.cosmos.unreddit.data.remote.api.imgur.adapter.AlbumDataAdapter
import com.cosmos.unreddit.data.remote.api.reddit.adapter.EditedAdapter
import com.cosmos.unreddit.data.remote.api.reddit.adapter.MediaMetadataAdapter
import com.cosmos.unreddit.data.remote.api.reddit.adapter.NullToEmptyStringAdapter
import com.cosmos.unreddit.data.remote.api.reddit.adapter.RepliesAdapter
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutChild
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutUserChild
import com.cosmos.unreddit.data.remote.api.reddit.model.Child
import com.cosmos.unreddit.data.remote.api.reddit.model.ChildType
import com.cosmos.unreddit.data.remote.api.reddit.model.CommentChild
import com.cosmos.unreddit.data.remote.api.reddit.model.MoreChild
import com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
import com.cosmos.unreddit.data.remote.api.redgifs.RedgifsApi
import com.cosmos.unreddit.data.remote.api.streamable.StreamableApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapters.PolymorphicJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
object NetworkModule {

    private val TIMEOUT = 60L to TimeUnit.SECONDS

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class RedditMoshi

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class BasicMoshi

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class ImgurMoshi

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class BackupMoshi

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class GenericOkHttp

    @RedditMoshi
    @Provides
    @Singleton
    fun provideRedditMoshi(): Moshi {
        return Moshi.Builder()
            .add(
                PolymorphicJsonAdapterFactory.of(Child::class.java, "kind")
                    .withSubtype(CommentChild::class.java, ChildType.t1.name)
                    .withSubtype(AboutUserChild::class.java, ChildType.t2.name)
                    .withSubtype(PostChild::class.java, ChildType.t3.name)
                    .withSubtype(AboutChild::class.java, ChildType.t5.name)
                    .withSubtype(MoreChild::class.java, ChildType.more.name)
            )
            .add(MediaMetadataAdapter.Factory)
            .add(RepliesAdapter())
            .add(EditedAdapter())
            .add(NullToEmptyStringAdapter())
            .build()
    }

    @BasicMoshi
    @Provides
    @Singleton
    fun provideBasicMoshi(): Moshi {
        return Moshi.Builder()
            .build()
    }

    @ImgurMoshi
    @Provides
    @Singleton
    fun provideImgurMoshi(): Moshi {
        return Moshi.Builder()
            .add(AlbumDataAdapter())
            .build()
    }

    @BackupMoshi
    @Provides
    @Singleton
    fun provideBackupMoshi(): Moshi {
        return Moshi.Builder()
            .add(BackupAdapter.Factory)
            .build()
    }

    @GenericOkHttp
    @Provides
    @Singleton
    fun provideGenericOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(TIMEOUT.first, TIMEOUT.second)
            .readTimeout(TIMEOUT.first, TIMEOUT.second)
            .writeTimeout(TIMEOUT.first, TIMEOUT.second)
            .build()
    }

    @Provides
    @Singleton
    fun provideImgurApi(
        @ImgurMoshi moshi: Moshi,
        @GenericOkHttp okHttpClient: OkHttpClient
    ): ImgurApi {
        return Retrofit.Builder()
            .baseUrl(ImgurApi.BASE_URL)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .client(okHttpClient)
            .build()
            .create(ImgurApi::class.java)
    }

    @Provides
    @Singleton
    fun provideStreamableApi(
        @BasicMoshi moshi: Moshi,
        @GenericOkHttp okHttpClient: OkHttpClient
    ): StreamableApi {
        return Retrofit.Builder()
            .baseUrl(StreamableApi.BASE_URL)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .client(okHttpClient)
            .build()
            .create(StreamableApi::class.java)
    }

    @Provides
    @Singleton
    fun provideGfycatApi(
        @BasicMoshi moshi: Moshi,
        @GenericOkHttp okHttpClient: OkHttpClient
    ): GfycatApi {
        return Retrofit.Builder()
            .baseUrl(GfycatApi.BASE_URL_GFYCAT)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .client(okHttpClient)
            .build()
            .create(GfycatApi::class.java)
    }

    @Provides
    @Singleton
    fun provideRedgifsApi(
        @BasicMoshi moshi: Moshi,
        @GenericOkHttp okHttpClient: OkHttpClient
    ): RedgifsApi {
        return Retrofit.Builder()
            .baseUrl(RedgifsApi.BASE_URL)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .client(okHttpClient)
            .build()
            .create(RedgifsApi::class.java)
    }
}
