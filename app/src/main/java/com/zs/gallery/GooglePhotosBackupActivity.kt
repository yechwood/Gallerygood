package com.zs.gallery

import android.accounts.Account
import android.app.Activity
import android.content.ContentUris
import android.os.Bundle
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Button
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

    private lateinit var statusView: TextView
    private lateinit var progressView: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var backupButton: Button
    private lateinit var wifiSwitch: Switch
    private var backingUp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildBackupUi()
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(PHOTOS_SCOPE))
            .build()
        val client = GoogleSignIn.getClient(this, options)
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account?.account != null && account.grantedScopes?.any { it.scopeUri == PHOTOS_SCOPE } == true) {
            updateStatus("Connected: " + (account.email ?: "Google account") + "\nReady to back up.")
            backupButton.isEnabled = true
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
            if (account.account != null) {
                updateStatus("Connected: " + (account.email ?: "Google account") + "\nReady to back up.")
                backupButton.isEnabled = true
            } else finish()
        } catch (e: ApiException) {
            Toast.makeText(this, "Google sign-in failed: " + e.statusCode, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startBackup(account: Account) {
        if (backingUp) return
        if (wifiSwitch.isChecked && !isWifiConnected()) {
            updateStatus("Waiting for Wi-Fi. Backup will not use mobile data.")
            return
        }
        backingUp = true
        backupButton.isEnabled = false
        wifiSwitch.isEnabled = false
        progressView.visibility = View.VISIBLE
        updateStatus("Backing up...")
        lifecycleScope.launch {
            try {
                val token = withContext(Dispatchers.IO) {
                    GoogleAuthUtil.getToken(this@GooglePhotosBackupActivity, account, "oauth2:" + PHOTOS_SCOPE)
                }
                val count = uploadAll(token)
                getPreferences(Context.MODE_PRIVATE).edit().putLong("last_backup", System.currentTimeMillis()).putInt("last_count", count).apply()
                updateStatus("Backup complete: " + count + " items uploaded.")
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
                backingUp = false
                backupButton.isEnabled = true
                wifiSwitch.isEnabled = true
            }
        }
    }

    private suspend fun uploadAll(token: String): Int = withContext(Dispatchers.IO) {
        var uploaded = 0
        var processed = 0
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
                    processed++
                    runOnUiThread {
                        progressText.text = "Backing up item " + processed + " • " + uploaded + " uploaded"
                    }
                }
            }
        }
        uploaded
    }

    private fun buildBackupUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        root.addView(TextView(this).apply { text = "Google Photos Backup"; textSize = 24f })
        statusView = TextView(this).apply { textSize = 16f; setPadding(0, 24, 0, 16) }; root.addView(statusView)
        wifiSwitch = Switch(this).apply {
            text = "Wi-Fi only"
            isChecked = getPreferences(Context.MODE_PRIVATE).getBoolean("wifi_only", true)
            setOnCheckedChangeListener { _, checked -> getPreferences(Context.MODE_PRIVATE).edit().putBoolean("wifi_only", checked).apply() }
        }; root.addView(wifiSwitch)
        progressView = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = View.GONE }; root.addView(progressView)
        progressText = TextView(this).apply { setPadding(0, 8, 0, 16) }; root.addView(progressText)
        backupButton = Button(this).apply {
            text = "Back up now"; isEnabled = false
            setOnClickListener { GoogleSignIn.getLastSignedInAccount(this@GooglePhotosBackupActivity)?.account?.let(::startBackup) ?: updateStatus("Connect a Google account first.") }
        }; root.addView(backupButton)
        val last = getPreferences(Context.MODE_PRIVATE).getLong("last_backup", 0L)
        root.addView(TextView(this).apply { text = if (last > 0L) "Last backup: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(last)) else "Last backup: Never" })
        setContentView(root)
    }
    private fun updateStatus(text: String) { if (::statusView.isInitialized) statusView.text = text }
    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
