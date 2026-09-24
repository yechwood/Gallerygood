/*
 * Copyright 2024 Zakir Sheikh
 *
 * Created by Zakir Sheikh on 20-07-2024.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalFoundationApi::class)

package com.zs.gallery

import android.annotation.SuppressLint
import android.content.Intent
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.util.Log
import android.view.WindowManager.LayoutParams
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.zs.compose.foundation.getText2
import com.zs.compose.theme.snackbar.SnackbarDuration
import com.zs.compose.theme.snackbar.SnackbarHostState
import com.zs.core.billing.Product
import com.zs.core.billing.Purchase
import com.zs.core.common.showPlatformToast
import com.zs.gallery.common.SystemFacade
import com.zs.gallery.common.WindowStyle
import com.zs.gallery.common.domain
import com.zs.gallery.files.RouteFiles
import com.zs.gallery.lockscreen.RouteLockScreen
import com.zs.gallery.settings.Settings
import com.zs.gallery.viewer.RouteIntentViewer
import com.zs.preferences.Key
import com.zs.preferences.Key.Key1
import com.zs.preferences.Key.Key2
import com.zs.preferences.Preferences
import com.zs.preferences.intPreferenceKey
import com.zs.preferences.longPreferenceKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject
import kotlin.time.Duration.Companion.minutes
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen as configSplashScreen
import androidx.navigation.NavController.OnDestinationChangedListener as NavDestListener

private const val TAG = "MainActivity"

// Minimum number of app launches before prompting for a review.
@Composable
private inline fun <S, O> Preferences.observeAsState(key: Key<S, O>): State<O?> {
    val flow = when (key) {
        is Key1 -> observe(key)
        is Key2 -> observe(key)
    }

    val first = remember(key.name) {
        runBlocking { flow.first() }
    }
    return flow.collectAsState(initial = first)
}

/**
 * @property inAppUpdateProgress A simple property that represents the progress of the in-app update.
 *        The progress value is a float between 0.0 and 1.0, indicating the percentage of the
 *        update that has been completed. The Float.NaN represents a default value when no update
 *        is going on.
 * @property timeAppWentToBackground The time in mills until the app was in background state. default value -1L
 * @property isAuthenticationRequired A boolean flag indicating whether authentication is required.
 */
class MainActivity : ComponentActivity(), SystemFacade, NavDestListener {

    private val snackbarHostState: SnackbarHostState by inject()
    private val preferences: Preferences by inject()
    private var navController: NavHostController? = null

        var _style: Int by mutableIntStateOf(WindowStyle.FLAG_STYLE_AUTO)
    override var style: WindowStyle
        get() = WindowStyle(_style)
        set(value) { _style = value.value }
    var inAppUpdateProgress by mutableFloatStateOf(Float.NaN)
        private set

    /**
     * Timestamp (mills) indicating when the app last went to the background.
     *
     * Possible values:
     * - `-1L`: The app has just started and hasn't been in the background yet.
     * - `0L`: The app was launched for the first time (initial launch).
     * - `> 0L`: The time in milliseconds when the app last entered the background.
     */
    private var timeAppWentToBackground = -1L

    /**
     * Checks if authentication is required.
     *
     * Authentication is not supported on Android versions below P.
     *
     * @return `true` if authentication should be shown, `false` otherwise.
     */
    private val isAuthenticationRequired: Boolean
        get() {
            // App lock is not supported on Android versions below P.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                return false
            }

            // If the timestamp is 0L, the user has recently unlocked the app,
            // so authentication is not required.
            if (timeAppWentToBackground == 0L) {
                return false
            }

            // Check the app lock timeout setting.
            return when (val timeoutValue = preferences[Settings.KEY_APP_LOCK_TIME_OUT]) {
                -1 -> false // App lock is disabled (timeout value of -1)
                0 -> true // Immediate authentication required (timeout value of 0)
                else -> {
                    // Calculate the time elapsed since the app went to background.
                    val currentTime = System.currentTimeMillis()
                    val timeSinceBackground = currentTime - timeAppWentToBackground
                    timeSinceBackground >= timeoutValue.minutes.inWholeMilliseconds
                }
            }
        }

    override fun onPause() {
        super.onPause()
        // The time when app went to background.
        // irrespective of what value it holds update it.
        Log.d(TAG, "onPause")
        timeAppWentToBackground = System.currentTimeMillis()
    }

    @SuppressLint("NewApi")
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onStart")
        // Only navigate to the lock screen if authentication is required and
        // this is not a fresh app start.

        // On a fresh start, timeAppWentToBackground is -1L.
        // If authentication is required on a fresh start, the app will be
        // automatically navigated to the lock screen in onCreate().
        if (timeAppWentToBackground != -1L && isAuthenticationRequired) {
            Log.d(TAG, "onResume: navigating -> RouteLockScreen.")
            // since navController doesn't support adding new dest at the bottom of topMost dest;
            // remove current destination to insert lock screen below
            if (navController?.currentDestination?.domain == RouteIntentViewer.domain) {
                navController?.popBackStack()
            }
            navController?.navigate(RouteLockScreen()) {
                launchSingleTop = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun showToast(message: String, duration: Int) =
        showPlatformToast(message, duration)

    override fun showToast(message: Int, duration: Int) =
        showPlatformToast(message, duration)

    override fun <T> getDeviceService(name: String): T =
        getSystemService(name) as T

    @RequiresApi(Build.VERSION_CODES.P)
    override fun authenticate(
        subtitle: String?,
        desc: String?,
        onAuthenticated: () -> Unit,
    ) {
        Log.d(TAG, "preparing to show authentication dialog.")
        // Build the BiometricPrompt
        val prompt = BiometricPrompt.Builder(this).apply {
            setTitle(getString(R.string.lock_scr_title))
            if (subtitle != null) setSubtitle(subtitle)
            if (desc != null) setDescription(desc)
            // Set allowed authenticators for Android R and above
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            // Allow device credential fallback for Android Q
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q)
                setDeviceCredentialAllowed(true)
            // On Android P and below, BiometricPrompt crashes if a negative button is not set.
            // We provide a "Dismiss" button to avoid the crash, but this does not offer alternative
            // authentication (like PIN).
            // Future versions might include support for alternative authentication on older Android versions
            // if a compatibility library or API becomes available.
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
                setNegativeButton(getString(R.string.dismiss), mainExecutor, { _, _ -> })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                setConfirmationRequired(false)
            /*if (Build.VERSION.SDK_INT >= 35) {
                setLogoRes(R.drawable.ic_app)
            }*/
        }.build()
        // Start the authentication process
        prompt.authenticate(
            CancellationSignal(),
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                // Implement callback methods for authentication events (success, error, etc.)
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    onAuthenticated()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    showToast(getString(R.string.msg_auth_failed))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    super.onAuthenticationError(errorCode, errString)
                    showToast(getString(R.string.msg_auth_error_s, errString))
                }
            }
        )
    }

    @SuppressLint("NewApi")
    override fun unlock() = authenticate() {
        val navController = navController ?: return@authenticate
        // if it is initial app_lock update timeAppWentToBackground to 0
        if (timeAppWentToBackground == -1L)
            timeAppWentToBackground = 0L
        // Check if the start destination needs to be updated
        // Update the start destination to RouteTimeline
        if (navController.graph.startDestinationRoute == RouteLockScreen()) {
            Log.d(TAG, "unlock: updating start destination")
            navController.graph.setStartDestination(RouteFiles())
            navController.navigate(RouteFiles()) {
                popUpTo(RouteLockScreen()) {
                    inclusive = true
                }
            }
            // return from here;
            return@authenticate
        }
        Log.d(TAG, "unlock: poping lock_screen from graph")
        // If the start destination is already RouteTimeline, just pop back
        navController.popBackStack()
    }

    override fun showSnackbar(
        message: CharSequence,
        icon: ImageVector?,
        accent: Color,
        duration: SnackbarDuration,
    ) {
        lifecycleScope.launch {
            snackbarHostState.showSnackbar(
                message = message,
                icon = icon,
                accent = accent,
                duration = duration
            )
        }
    }

    override fun showSnackbar(
        message: Int,
        icon: ImageVector?,
        accent: Color,
        duration: SnackbarDuration,
    ) = showSnackbar(
        resources.getText2(id = message),
        icon = icon,
        accent = accent,
        duration = duration
    )

    @Composable
    @NonRestartableComposable
    override fun <S, O> observeAsState(key: Key1<S, O>) =
        preferences.observeAsState(key = key)

    @Composable
    @NonRestartableComposable
    override fun <S, O> observeAsState(key: Key2<S, O>) =
        preferences.observeAsState(key = key) as State<O>

    @Composable
    @NonRestartableComposable
    override fun observePurchaseAsState(id: String): State<Purchase?> = remember { mutableStateOf(null) }

    override fun launch(intent: Intent, options: Bundle?) =
        startActivity(intent, options)

    // Standalone build: update, review, and purchase flows are intentionally disabled.
    override fun initiateUpdateFlow(report: Boolean) = Unit

    override fun initiateReviewFlow() = Unit

    override fun initiatePurchaseFlow(id: String): Boolean = false

    override fun getProductInfo(id: String): Product? = null

    override fun onDestinationChanged(cont: NavController, dest: NavDestination, args: Bundle?) = Unit

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != Intent.ACTION_VIEW)
            return
        lifecycleScope.launch {
            // we delay it here because on resume loads lockscreen.
            // we want this to overlay over lockscreen; hence this.

            delay(200)
            navController?.navigate(RouteIntentViewer(intent.data!!, intent.type ?: "image/*")) {
                launchSingleTop = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        // This is determined by checking if savedInstanceState is null.
        // If null, it's a cold start (first time launch or activity recreated from scratch)
        val isColdStart = savedInstanceState == null
        // Configure the splash screen for the app
        configSplashScreen()
        // Initialize
        if (isColdStart && preferences[Settings.KEY_SECURE_MODE]) {
            window.setFlags(LayoutParams.FLAG_SECURE, LayoutParams.FLAG_SECURE)
        }
        // Set up the window to fit the system windows
        // This setting is usually configured in the app theme, but is ensured here
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Set the content of the activity
        setContent {
            val navController = rememberNavController()
            // If the action is VIEW, load the content first, regardless
            // of whether the app is currently locked or not. This allows
            // users to view shared media directly.
            // else If authentication is required, move to the lock screen
            Home(
                when {
                    intent.action == Intent.ACTION_VIEW -> RouteIntentViewer
                    isAuthenticationRequired -> RouteLockScreen
                    else -> RouteFiles
                },
                snackbarHostState,
                navController
            )
            // Manage lifecycle-related events and listeners
            DisposableEffect(Unit) {
                Log.d(TAG, "onCreate - DisposableEffect: $timeAppWentToBackground")
                navController.addOnDestinationChangedListener(this@MainActivity)
                // Cover the screen with lock_screen if authentication is required
                // Only remove this veil when the user authenticates
                if (isAuthenticationRequired) unlock()
                this@MainActivity.navController = navController
                onDispose {
                    navController.removeOnDestinationChangedListener(this@MainActivity)
                    this@MainActivity.navController = null
                }
            }
        }
    }
}