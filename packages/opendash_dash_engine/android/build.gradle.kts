import java.util.Properties

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

// Read from the app's android/maptiler.local.properties (gitignored; see
// android/maptiler.defaults.properties for the bring-your-own-key template).
// This module is pulled in as a subproject of the app's root Gradle build
// (Flutter's plugin loader does `settings.include` + sets projectDir, not a
// separate composite build), so rootProject here IS android/ — same place
// local.properties already lives. Falls back to empty so a fresh checkout
// still builds; TileProvider's fetches just fail until a key is added.
// Explicit `import java.util.Properties` above + unqualified `Properties()`
// here — some plugin in this build registers a Project extension named
// "java", which shadows the `java.*` package root, so `java.util.Properties`
// doesn't resolve.
val maptilerKey: String = run {
    val props = Properties()
    val file = rootProject.file("maptiler.local.properties")
    if (file.exists()) file.inputStream().use { props.load(it) }
    props.getProperty("MAPTILER_API_KEY", "")
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
        buildConfigField("String", "MAPTILER_API_KEY", "\"$maptilerKey\"")
    }

    buildFeatures {
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

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.mockito:mockito-core:5.0.0")
}
