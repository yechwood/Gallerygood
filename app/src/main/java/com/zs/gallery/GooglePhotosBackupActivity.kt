package com.zs.gallery

import android.accounts.Account
import android.app.Activity
import android.content.ContentUris
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class GooglePhotosBackupActivity : ComponentActivity() {
    companion object {
        private const val RC_SIGN_IN = 7401
        private const val PHOTOS_SCOPE = "https://www.googleapis.com/auth/photoslibrary.appendonly"
        private const val UPLOAD_URL = "https://photoslibrary.googleapis.com/v1/uploads"
        private const val BATCH_URL = "https://photoslibrary.googleapis.com/v1/mediaItems:batchCreate"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(PHOTOS_SCOPE))
            .build()
        val client = GoogleSignIn.getClient(this, options)
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account?.account != null && account.grantedScopes?.any { it.scopeUri == PHOTOS_SCOPE } == true) {
            startBackup(account.account!!)
        } else {
            startActivityForResult(client.signInIntent, RC_SIGN_IN)
        }
    }

    @Deprecated("Legacy result callback retained for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_SIGN_IN) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            finish()
            return
        }
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            account.account?.let(::startBackup) ?: finish()
        } catch (e: ApiException) {
            Toast.makeText(this, "Google sign-in failed: " + e.statusCode, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startBackup(account: Account) {
        lifecycleScope.launch {
            try {
                val token = withContext(Dispatchers.IO) {
                    GoogleAuthUtil.getToken(this@GooglePhotosBackupActivity, account, "oauth2:" + PHOTOS_SCOPE)
                }
                val count = uploadAll(token)
                Toast.makeText(
                    this@GooglePhotosBackupActivity,
                    "Backed up " + count + " items to Google Photos",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@GooglePhotosBackupActivity,
                    "Google Photos backup failed: " + (e.message ?: "unknown error"),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                finish()
            }
        }
    }

    private suspend fun uploadAll(token: String): Int = withContext(Dispatchers.IO) {
        var uploaded = 0
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
        for (uri in collections) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE
            )
            contentResolver.query(uri, projection, null, null, MediaStore.MediaColumns.DATE_ADDED + " ASC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                while (cursor.moveToNext()) {
                    val itemUri = ContentUris.withAppendedId(uri, cursor.getLong(idCol))
                    val name = cursor.getString(nameCol) ?: "gallery-item"
                    val mime = cursor.getString(mimeCol) ?: "application/octet-stream"
                    try {
                        val uploadToken = uploadBytes(token, itemUri, mime)
                        createMediaItem(token, uploadToken, name)
                        uploaded++
                    } catch (_: Exception) {
                        // Continue with the remaining media.
                    }
                }
            }
        }
        uploaded
    }

    private fun uploadBytes(token: String, uri: android.net.Uri, mime: String): String {
        val connection = (URL(UPLOAD_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("Content-type", "application/octet-stream")
            setRequestProperty("X-Goog-Upload-Content-Type", mime)
            setRequestProperty("X-Goog-Upload-Protocol", "raw")
        }
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot read media" }
            connection.outputStream.use { output -> input.copyTo(output, 64 * 1024) }
        }
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("Upload HTTP " + connection.responseCode)
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun createMediaItem(token: String, uploadToken: String, fileName: String) {
        val item = JSONObject().apply {
            put("simpleMediaItem", JSONObject().apply {
                put("fileName", fileName)
                put("uploadToken", uploadToken)
            })
        }
        val body = JSONObject().apply {
            put("newMediaItems", JSONArray().put(item))
        }.toString()
        val connection = (URL(BATCH_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("Create media HTTP " + connection.responseCode)
        }
        connection.inputStream.close()
    }
}
