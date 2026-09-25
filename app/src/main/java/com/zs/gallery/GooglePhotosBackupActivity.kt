package com.zs.gallery

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import android.accounts.Account
import android.accounts.AccountManager
import java.text.DateFormat
import java.util.Date

class GooglePhotosBackupActivity : ComponentActivity() {
    companion object {
        const val WORK_NAME = "google_photos_backup"
        const val CHANNEL_ID = "google_photos_backup"
        private const val RC_NOTIFICATION = 7402
        private const val PHOTOS_SCOPE = "https://www.googleapis.com/auth/photoslibrary.appendonly"
    }

    private lateinit var accountView: TextView
    private lateinit var statusView: TextView
    private lateinit var progressView: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var backupButton: Button
    private lateinit var accountButton: Button
    private lateinit var wifiSwitch: Switch
    private var selectedAccount: Account? = null
    private val backupPrefs by lazy { getSharedPreferences("google_photos_backup", Context.MODE_PRIVATE) }

    private val accountPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val name = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                if (!name.isNullOrBlank()) {
                    selectedAccount = Account(name, "com.google")
                    backupPrefs.edit().putString("account_name", name).apply()
                    accountView.text = name
                    accountButton.text = "Change account"
                    statusView.text = "Google account selected. Tap Back up now to start."
                    backupButton.isEnabled = true
                    return@registerForActivityResult
                }
            }
            statusView.text = "No Google account was selected."
            showAccountState()
        }

    private fun chooseAccount() {
        val intent = Intent("android.accounts.action.CHOOSE_ACCOUNT").apply {
            putExtra("account_types", arrayOf("com.google"))
        }
        accountPickerLauncher.launch(intent)
    }

    private fun showAccountState() {
        val savedName = backupPrefs.getString("account_name", null)
        if (!savedName.isNullOrBlank()) {
            selectedAccount = Account(savedName, "com.google")
            accountView.text = savedName
            accountButton.text = "Change account"
            backupButton.isEnabled = true
            statusView.text = "Google account connected. Tap Back up now to start."
        } else {
            selectedAccount = null
            accountView.text = "No Google account selected"
            accountButton.text = "Connect Google account"
            backupButton.isEnabled = false
            statusView.text = "Choose a Google account to enable backup."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        buildBackupUi()
        requestNotificationPermissionIfNeeded()
        showAccountState()
        observeBackup()
    }

    private fun queueBackup() {
        val account = selectedAccount
        if (account == null) {
            chooseAccount()
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (wifiSwitch.isChecked) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()

        val request = OneTimeWorkRequestBuilder<GooglePhotosBackupWorker>()
            .setConstraints(constraints)
            .setInputData(Data.Builder().putBoolean("wifi_only", wifiSwitch.isChecked).build())
            .addTag(WORK_NAME)
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
        statusView.text = if (wifiSwitch.isChecked)
            "Backup queued. It will run on Wi-Fi only."
        else
            "Backup queued. Mobile data is allowed."
        backupButton.isEnabled = false
    }

    private fun observeBackup() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(WORK_NAME)
            .observe(this) { infos ->
                val info = infos.firstOrNull() ?: return@observe
                val processed = info.progress.getInt("processed", 0)
                val uploaded = info.progress.getInt("uploaded", 0)
                val total = info.progress.getInt("total", 0)

                when (info.state) {
                    WorkInfo.State.ENQUEUED -> {
                        statusView.text = if (wifiSwitch.isChecked)
                            "Waiting for Wi-Fi…"
                        else
                            "Waiting for a network…"
                        progressView.visibility = android.view.View.VISIBLE
                        progressText.text = "Backup queued"
                    }
                    WorkInfo.State.RUNNING -> {
                        progressView.visibility = android.view.View.VISIBLE
                        if (total > 0) {
                            progressView.max = total
                            progressView.progress = processed
                            progressText.text = "$processed of $total checked • $uploaded uploaded"
                        } else {
                            progressText.text = "Preparing backup…"
                        }
                        statusView.text = "Backing up to Google Photos…"
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val count = info.outputData.getInt("uploaded", uploaded)
                        progressView.visibility = android.view.View.GONE
                        progressText.text = ""
                        statusView.text = "Backup complete • $count new item(s) uploaded"
                        backupButton.isEnabled = true
                    }
                    WorkInfo.State.FAILED -> {
                        progressView.visibility = android.view.View.GONE
                        progressText.text = ""
                        statusView.text = "Backup stopped. Tap Back up now to try again."
                        backupButton.isEnabled = true
                    }
                    WorkInfo.State.CANCELLED -> {
                        progressView.visibility = android.view.View.GONE
                        progressText.text = ""
                        statusView.text = "Backup cancelled."
                        backupButton.isEnabled = true
                    }
                    else -> Unit
                }
            }
    }

    private fun buildBackupUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
            setBackgroundColor(AndroidColor.WHITE)
        }

        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(16))
        }
        val title = TextView(this).apply {
            text = "Google Photos Backup"
            textSize = 25f
            setTextColor(AndroidColor.rgb(25, 28, 32))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        toolbar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(toolbar)

        root.addView(TextView(this).apply {
            text = "Securely back up this device's photos and videos to your Google Photos account."
            textSize = 15f
            setTextColor(AndroidColor.rgb(90, 96, 105))
            setPadding(0, 0, 0, dp(18))
        })

        val accountCard = card()
        accountCard.addView(label("GOOGLE ACCOUNT"))
        accountView = TextView(this).apply {
            textSize = 17f
            setTextColor(AndroidColor.rgb(25, 28, 32))
            setPadding(0, dp(6), 0, dp(12))
        }
        accountCard.addView(accountView)
        accountButton = Button(this).apply {
            text = "Connect Google account"
            setOnClickListener { chooseAccount() }
        }
        accountCard.addView(accountButton)
        root.addView(accountCard, marginParams(12))

        val backupCard = card()
        backupCard.addView(label("BACKUP"))
        statusView = TextView(this).apply {
            textSize = 16f
            setTextColor(AndroidColor.rgb(55, 60, 68))
            setPadding(0, dp(6), 0, dp(12))
        }
        backupCard.addView(statusView)

        wifiSwitch = Switch(this).apply {
            text = "Wi-Fi only"
            textSize = 16f
            setTextColor(AndroidColor.rgb(25, 28, 32))
            isChecked = backupPrefs.getBoolean("wifi_only", true)
            setOnCheckedChangeListener { _, checked ->
                backupPrefs.edit().putBoolean("wifi_only", checked).apply()
            }
        }
        backupCard.addView(wifiSwitch)

        progressView = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = android.view.View.GONE
            max = 100
        }
        backupCard.addView(progressView, LinearLayout.LayoutParams(-1, dp(6)))

        progressText = TextView(this).apply {
            textSize = 14f
            setTextColor(AndroidColor.rgb(90, 96, 105))
            setPadding(0, dp(8), 0, dp(12))
        }
        backupCard.addView(progressText)

        backupButton = Button(this).apply {
            text = "Back up now"
            isEnabled = false
            setOnClickListener { queueBackup() }
        }
        backupCard.addView(backupButton)
        root.addView(backupCard, marginParams(12))

        val lastBackup = backupPrefs.getLong("last_backup", 0L)
        val lastCard = card()
        lastCard.addView(label("LAST BACKUP"))
        lastCard.addView(TextView(this).apply {
            textSize = 15f
            setTextColor(AndroidColor.rgb(55, 60, 68))
            text = if (lastBackup > 0L)
                DateFormat.getDateTimeInstance().format(Date(lastBackup))
            else
                "Never"
        })
        root.addView(lastCard)

        root.addView(TextView(this).apply {
            text = "Backups continue in the background and show a notification while they are running. Wi-Fi only is enabled by default."
            textSize = 13f
            setTextColor(AndroidColor.rgb(100, 105, 112))
            setPadding(dp(4), dp(18), dp(4), 0)
        })

        setContentView(root)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(18))
        background = GradientDrawable().apply {
            setColor(AndroidColor.rgb(247, 248, 250))
            cornerRadius = dp(20).toFloat()
        }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(AndroidColor.rgb(70, 90, 120))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        letterSpacing = 0.08f
    }

    private fun marginParams(bottom: Int) =
        LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(bottom)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_NOTIFICATION)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Google Photos backup",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Shows Google Photos backup progress" }
            )
        }
    }
}

class GooglePhotosBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val PHOTOS_SCOPE = "https://www.googleapis.com/auth/photoslibrary.appendonly"
        private const val UPLOAD_URL = "https://photoslibrary.googleapis.com/v1/uploads"
        private const val BATCH_URL = "https://photoslibrary.googleapis.com/v1/mediaItems:batchCreate"
        private const val PREFS = "google_photos_backup"
        private const val UPLOADED_PREFIX = "uploaded_"
        private const val NOTIFICATION_ID = 7403
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo("Preparing Google Photos backup…", 0, 0)

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())

        val savedAccount = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("account_name", null)
        val account = savedAccount?.let { Account(it, "com.google") }
            ?: return Result.failure(Data.Builder().putString("error", "Google account is not connected").build())

        return try {
            val token = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.google.android.gms.auth.GoogleAuthUtil.getToken(
                    applicationContext, account, "oauth2:$PHOTOS_SCOPE"
                )
            }

            val items = queryMedia()
            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val uploadedKey = UPLOADED_PREFIX + (account.name ?: "unknown")
            val uploaded = prefs.getStringSet(uploadedKey, emptySet())?.toMutableSet() ?: mutableSetOf()
            var processed = 0
            var newlyUploaded = 0

            setProgress(Data.Builder().putInt("total", items.size).putInt("processed", 0).putInt("uploaded", 0).build())
            updateNotification(0, items.size, 0)

            for (item in items) {
                if (isStopped) return Result.failure()
                if (!uploaded.contains(item.fingerprint)) {
                    try {
                        val uploadToken = uploadBytes(token, item.uri, item.mime)
                        createMediaItem(token, uploadToken, item.name)
                        uploaded.add(item.fingerprint)
                        newlyUploaded++
                    } catch (_: Exception) {
                        // Continue with remaining media.
                    }
                }
                processed++
                setProgress(
                    Data.Builder().putInt("total", items.size)
                        .putInt("processed", processed)
                        .putInt("uploaded", newlyUploaded).build()
                )
                updateNotification(processed, items.size, newlyUploaded)
                if (processed % 20 == 0) {
                    prefs.edit().putStringSet(uploadedKey, HashSet(uploaded)).apply()
                }
            }

            prefs.edit().putStringSet(uploadedKey, HashSet(uploaded))
                .putLong("last_backup", System.currentTimeMillis())
                .putInt("last_count", newlyUploaded).apply()

            Result.success(Data.Builder().putInt("uploaded", newlyUploaded).build())
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private data class MediaItem(
        val uri: android.net.Uri,
        val name: String,
        val mime: String,
        val fingerprint: String
    )

    private fun queryMedia(): List<MediaItem> {
        val result = ArrayList<MediaItem>()
        val collections = listOf(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
        val projection = arrayOf(
            android.provider.MediaStore.MediaColumns._ID,
            android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
            android.provider.MediaStore.MediaColumns.MIME_TYPE,
            android.provider.MediaStore.MediaColumns.SIZE,
            android.provider.MediaStore.MediaColumns.DATE_MODIFIED
        )

        for (base in collections) {
            applicationContext.contentResolver.query(
                base, projection, null, null,
                android.provider.MediaStore.MediaColumns.DATE_ADDED + " ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.SIZE)
                val modifiedCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "gallery-item"
                    val mime = cursor.getString(mimeCol) ?: "application/octet-stream"
                    val size = cursor.getLong(sizeCol)
                    val modified = cursor.getLong(modifiedCol)
                    val uri = android.content.ContentUris.withAppendedId(base, id)
                    result.add(MediaItem(uri, name, mime, "$uri|$size|$modified"))
                }
            }
        }
        return result
    }

    private fun uploadBytes(token: String, uri: android.net.Uri, mime: String): String {
        val connection = (java.net.URL(UPLOAD_URL).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-type", "application/octet-stream")
            setRequestProperty("X-Goog-Upload-Content-Type", mime)
            setRequestProperty("X-Goog-Upload-Protocol", "raw")
        }
        applicationContext.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot read media" }
            connection.outputStream.use { output -> input.copyTo(output, 64 * 1024) }
        }
        if (connection.responseCode !in 200..299)
            throw IllegalStateException("Upload HTTP ${connection.responseCode}")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun createMediaItem(token: String, uploadToken: String, fileName: String) {
        val item = org.json.JSONObject().apply {
            put("simpleMediaItem", org.json.JSONObject().apply {
                put("fileName", fileName)
                put("uploadToken", uploadToken)
            })
        }
        val body = org.json.JSONObject().apply {
            put("newMediaItems", org.json.JSONArray().put(item))
        }.toString()
        val connection = (java.net.URL(BATCH_URL).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (connection.responseCode !in 200..299)
            throw IllegalStateException("Create media HTTP ${connection.responseCode}")
        connection.inputStream.close()
    }

    private fun foregroundInfo(status: String, processed: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    GooglePhotosBackupActivity.CHANNEL_ID,
                    "Google Photos backup",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notification = NotificationCompat.Builder(
            applicationContext, GooglePhotosBackupActivity.CHANNEL_ID
        ).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Google Photos backup")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, processed, total <= 0)
            .build()

        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(processed: Int, total: Int, uploaded: Int) {
        val status = if (total > 0) "$processed of $total checked • $uploaded uploaded" else "Backing up…"
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, foregroundInfo(status, processed, total).notification)
    }
}
