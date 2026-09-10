/*
 * Copyright (C) 2026 bikbov-damir
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.bikbovdamir.flironeviewer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a snapshot to the phone's picture gallery.
 *
 * PNG, not JPEG: at 80x60 every pixel is a measurement, and JPEG's ringing around
 * the hard edges of a thermal image would corrupt exactly the parts worth looking
 * at, for no meaningful saving on a file this small.
 *
 * Saved at the sensor's own resolution, with nothing interpolated. A viewer will
 * scale it up, but the file itself stays one pixel per detector.
 */
object SnapshotSaver {

    private const val ALBUM = "FlirOneViewer"

    class Result(val displayPath: String)

    fun save(context: Context, bitmap: Bitmap): Result {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "flir-$stamp.png"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, bitmap, name)
        } else {
            saveToAppStorage(context, bitmap, name)
        }
    }

    /**
     * Android 10+: MediaStore takes the file into shared storage with no storage
     * permission at all. IS_PENDING hides the entry until the bytes are written, so
     * a gallery scanning at the wrong moment never sees a half-file.
     */
    private fun saveViaMediaStore(context: Context, bitmap: Bitmap, name: String): Result {
        val relative = "${Environment.DIRECTORY_PICTURES}/$ALBUM"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, relative)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore refused to create an entry")
        try {
            resolver.openOutputStream(uri).use { out ->
                checkNotNull(out) { "MediaStore returned no output stream" }
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IllegalStateException("PNG encoding failed")
                }
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // no orphaned pending rows
            throw e
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return Result("$relative/$name")
    }

    /**
     * Android 9 and older: shared storage would need WRITE_EXTERNAL_STORAGE, and
     * asking for a broad storage permission to save one small PNG is a bad trade.
     * The app's own external directory needs no permission; the file lands outside
     * the gallery, so the path is reported in full for the user to find it.
     */
    private fun saveToAppStorage(context: Context, bitmap: Bitmap, name: String): Result {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: throw IllegalStateException("no external storage available")
        dir.mkdirs()
        val file = File(dir, name)
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IllegalStateException("PNG encoding failed")
            }
        }
        return Result(file.absolutePath)
    }
}
