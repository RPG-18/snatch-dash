group = "com.opendash.opendash_dash_engine"
version = "1.0-SNAPSHOT"

buildscript {
    val kotlinVersion = "2.3.20"
    repositories {
        google()
        mavenCentral()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:9.0.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.library")
}

// ── Build provenance ─────────────────────────────────────────────────────────
//
// The commit the APK was compiled from, stamped into every ride log next to
// [BuildId.sha12]. The two answer different questions and neither replaces the
// other: the APK's SHA says WHICH BINARY ran (so a log cannot be misattributed
// to a build that was never installed), this says WHICH SOURCE it came from.
//
// Added 2026-09-06, when a field question — the map on the dash showing one
// static frame — turned into "which build did that start on", and the only
// identifiers in the logs were APK hashes with no way back to a commit. That is
// a bisect that cannot be read.
//
// `--untracked-files=no` deliberately: a stray untracked file next to the repo
// (a scratch note, an editor backup) is not a source change and must not mark an
// otherwise-clean build dirty. A tracked edit does, and that is the case worth
// shouting about — a "+dirty" build cannot be matched to anything.
fun gitOrNull(vararg args: String): String? = runCatching {
    val out = providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }
    if (out.result.get().exitValue == 0) {
        out.standardOutput.asText.get().trim().ifEmpty { null }
    } else {
        null
    }
}.getOrNull()

// "unknown" rather than a failed build: this module is also consumable from a
// pub cache checkout with no .git at all, and provenance is diagnostics, not a
// build requirement.
val gitLabel: String = gitOrNull("rev-parse", "--short=9", "HEAD")?.let { sha ->
    if (gitOrNull("status", "--porcelain", "--untracked-files=no").isNullOrEmpty()) sha else "$sha+dirty"
} ?: "unknown"

android {
    namespace = "com.opendash.opendash_dash_engine"

    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    defaultConfig {
        minSdk = 24
        buildConfigField("String", "GIT_SHA", "\"$gitLabel\"")
    }

    buildFeatures {
        // Still needed for BuildConfig.DEBUG, which DebugLog.kt gates all native
        // logging on. MAPTILER_API_KEY used to be the other field here, back when
        // the dash frame was raster tiles from MapTiler; that whole path — key,
        // proxy, TileProvider.kt — is gone, replaced by local `.pmtiles` packs.
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.useJUnitPlatform()

                it.outputs.upToDateWhen { false }

                it.testLogging {
                    events("passed", "skipped", "failed", "standardOut", "standardError")
                    showStandardStreams = true
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // DashConfig — encrypted storage for the dash WiFi SSID/password.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Offscreen map rendering for the dash frame (spec/drawing_from_local_tiles.md).
    //
    // `-opengl`, not the plain `android-sdk`: since 13.0.0 the default artifact
    // renders through Vulkan and OpenGL ES moved to this one. The snapshotter's
    // path to a Bitmap goes through glReadPixels and is long-settled on OpenGL,
    // and the frame loop runs in a background service on whatever phone the
    // rider owns — the wrong place to be an early adopter of a new backend.
    //
    // Not the floor version either: `pmtiles://` needs 11.8.0, but
    // MapSnapshotter's padding — which carries the rider's offset into the
    // lower third — only arrived in 12.0.1.
    implementation("org.maplibre.gl:android-sdk-opengl:13.6.0")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    // `org.json` ships with Android but is stubbed in JVM unit tests; the real
    // implementation lets the style assembler be tested without a device.
    testImplementation("org.json:json:20240303")
}
