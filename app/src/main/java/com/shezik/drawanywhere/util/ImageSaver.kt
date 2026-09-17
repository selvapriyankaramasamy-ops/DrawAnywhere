/*
DrawAnywhere: An Android application that lets you draw on top of other apps.
Copyright (C) 2025-2026 shezik

This program is free software: you can redistribute it and/or modify it under the
terms of the GNU Affero General Public License as published by the Free Software
Foundation, either version 3 of the License, or any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License along
with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.shezik.drawanywhere.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateFormat
import java.util.Date

/**
 * Writes an in-memory [Bitmap] to the device's shared Pictures collection via
 * [MediaStore], so it shows up in the gallery without needing broad storage
 * permissions on API 29+ (scoped storage).
 */
object ImageSaver {

    private const val RELATIVE_DIR = "DrawAnywhere"

    /**
     * @return the resulting [Uri] on success, or null if the write failed.
     *   On failure, any half-written MediaStore row is cleaned up.
     */
    fun saveToGallery(context: Context, bitmap: Bitmap): Uri? {
        val fileName = "DrawAnywhere_${DateFormat.format("yyyyMMdd_HHmmss", Date())}.png"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$RELATIVE_DIR")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IllegalStateException("Bitmap.compress failed")
                }
            } ?: throw IllegalStateException("openOutputStream returned null")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
