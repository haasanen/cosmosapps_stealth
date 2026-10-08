package com.cosmos.unreddit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.request.ImageRequest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class ImageLoaderHardwareBitmapTest {

    @Test
    fun appImageLoaderProducesSoftwareBitmaps() {
        val png = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).let { bmp ->
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }

        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext
        assertTrue(
            "Application must implement ImageLoaderFactory (app-wide Coil config)",
            app is ImageLoaderFactory
        )
        val loader: ImageLoader = (app as ImageLoaderFactory).newImageLoader()

        val result = runBlocking {
            loader.execute(
                ImageRequest.Builder(context).data(png).allowHardware(true).build()
            )
        }
        val drawable = result.drawable
        assertNotNull("image failed to load", drawable)
        val bitmap = (drawable as BitmapDrawable).bitmap
        assertTrue(
            "bitmap must not be HARDWARE config (software-render crash)",
            bitmap.config != Bitmap.Config.HARDWARE
        )
    }
}
