import com.android.build.api.dsl.VariantDimension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// -----------------------------------------------------------------------------
// SECRETS
// -----------------------------------------------------------------------------
// 🔐 Keys or IDs injected into BuildConfig at runtime.
private val secrets = emptyArray<String>()
// -----------------------------------------------------------------------------
// PLUGINS
// -----------------------------------------------------------------------------
plugins {
    alias(libs.plugins.android.library)          // Android Library plugin
    alias(libs.plugins.jetbrains.kotlin.android) // Kotlin Android plugin
}

/** Adds a string BuildConfig field to the project. */
private fun VariantDimension.buildConfigField(name: String, value: String) =
    buildConfigField("String", name, "\"" + value + "\"")

// -----------------------------------------------------------------------------
// KOTLIN COMPILER OPTIONS
// -----------------------------------------------------------------------------
kotlin {
    compilerOptions {
        // Target JVM bytecode version (typed enum instead of raw string)
        jvmTarget = JvmTarget.JVM_17

        // Advanced / experimental compiler flags
        freeCompilerArgs.addAll(
            // "-XXLanguage:+ExplicitBackingFields", // Explicit backing fields (disabled for now)
            "-XXLanguage:+NestedTypeAliases",       // Nested type aliases
            "-Xopt-in=kotlin.RequiresOptIn",        // Opt-in to @RequiresOptIn APIs
            "-Xwhen-guards",                        // Experimental when-guards
            "-Xopt-in=androidx.compose.foundation.ExperimentalFoundationApi", // Compose foundation experimental
            "-Xnon-local-break-continue",           // Allow non-local break/continue
            "-Xcontext-sensitive-resolution",       // Context-sensitive overload resolution
            "-Xcontext-parameters"                  // Context parameters (experimental)
        )
    }
}

// ============================================================================
// ANDROID CONFIGURATION
// ============================================================================
android {
    namespace = "com.zs.core"
    compileSdk { version = release(37) }
    buildFeatures { buildConfig = true }

    // Java compatibility settings
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // DEFAULT CONFIGURATION
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // --------------------------------------------------------------------
        // BUILD CONFIG: SECRETS
        // --------------------------------------------------------------------
        // Inject secrets from environment variables.
        // Missing values default to empty strings to avoid build failures.
        for (secret in secrets)
            buildConfigField(secret, System.getenv(secret) ?: "")

        // 📌 Edition constants (used for comparison in code)
        buildConfigField("FLAVOR_COMMUNITY", "community")
    }
    // -------------------------------------------------------------------------
    // PRODUCT FLAVORS
    // -------------------------------------------------------------------------
    flavorDimensions += "edition"
    productFlavors {
        // Standalone build: no monetization, ads, telemetry, or Play Store features.
        create("community") { dimension = "edition" }
    }
    // -------------------------------------------------------------------------
    // SOURCE SETS CONFIGURATION
    // -------------------------------------------------------------------------
    sourceSets {
        getByName("community") {
            java.srcDirs(
                "src/shared/analytics/stub/java",
                "src/shared/billing/stub/java",
                "src/shared/ads/stub/java",
                "src/shared/market/stub/java"
            )
        }
    }
    // BUILD TYPES
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

// ============================================================================
// DEPENDENCIES
// ============================================================================
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    api(libs.bundles.coil)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.media3)

}