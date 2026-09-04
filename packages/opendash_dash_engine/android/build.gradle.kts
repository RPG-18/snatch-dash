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
    testImplementation("org.mockito:mockito-core:5.0.0")
}
