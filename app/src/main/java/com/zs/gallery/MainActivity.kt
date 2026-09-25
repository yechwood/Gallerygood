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

import android.content.Intent
import android.os.Bundle
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

    override fun showToast(message: String, duration: Int) =
        showPlatformToast(message, duration)

    override fun showToast(message: Int, duration: Int) =
        showPlatformToast(message, duration)

    override fun <T> getDeviceService(name: String): T =
        getSystemService(name) as T

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
            Home(
                when {
                    intent.action == Intent.ACTION_VIEW -> RouteIntentViewer
                    else -> RouteFiles
                },
                snackbarHostState,
                navController
            )
            // Manage lifecycle-related events and listeners
            DisposableEffect(Unit) {
                Log.d(TAG, "onCreate - DisposableEffect: $timeAppWentToBackground")
                navController.addOnDestinationChangedListener(this@MainActivity)
                this@MainActivity.navController = navController
                onDispose {
                    navController.removeOnDestinationChangedListener(this@MainActivity)
                    this@MainActivity.navController = null
                }
            }
        }
    }
}