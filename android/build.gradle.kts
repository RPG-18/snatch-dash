allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

// yandex_maps_mapkit's own build.gradle hardcodes compileSdkVersion 35, which
// is now below what flutter_plugin_android_lifecycle requires (36+). It's a
// vendored pub-cache module, not something we can edit in this repo, so force
// every Android library subproject to compile against 36 instead.
subprojects {
    val forceCompileSdk36 = {
        extensions.findByType(com.android.build.gradle.BaseExtension::class.java)
            ?.compileSdkVersion(36)
    }
    // `:app`'s evaluationDependsOn above can mean it's already evaluated by
    // the time this block runs for it — afterEvaluate throws in that case.
    if (state.executed) forceCompileSdk36() else afterEvaluate { forceCompileSdk36() }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
