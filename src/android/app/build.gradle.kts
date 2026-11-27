import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") // AGP 9 has Kotlin support built in
}

// The repository root: this project lives in src/android.
val repoRoot = rootProject.projectDir.resolve("../..").normalize()

// The diagram renderer: draw.io's offline viewer (shapes embedded), from the drawio
// submodule, the same script the macOS Quick Look preview uses. Copied into the app's
// assets at build time; never committed here.
abstract class CopyViewer : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val viewerScript: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val src = viewerScript.get().asFile
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        src.copyTo(out.resolve("viewer-static.min.js"))
    }
}

// From the submodule when it's checked out, else from scripts/fetch-viewer.sh.
val fetched = rootProject.layout.buildDirectory.dir("drawio").get().asFile
fun drawioFile(path: String, fallback: String) =
    repoRoot.resolve("drawio/$path").takeIf { it.isFile } ?: fetched.resolve(fallback)

val viewerSource = drawioFile("src/main/webapp/js/viewer-static.min.js", "viewer-static.min.js")

val copyViewer = tasks.register<CopyViewer>("copyDrawioViewer") {
    if (!viewerSource.isFile) {
        throw GradleException(
            "No draw.io viewer script: run src/android/scripts/fetch-viewer.sh " +
                "(fetches just that file) or git submodule update --init drawio."
        )
    }
    viewerScript.set(viewerSource)
    outputDir.set(layout.buildDirectory.dir("generated/drawioViewer"))
}

// The app's version is draw.io's (drawio/VERSION), like the desktop app's.
val drawioVersion = drawioFile("VERSION", "VERSION").takeIf { it.isFile }?.readText()?.trim() ?: "dev"

android {
    namespace = "io.github.gohenderson.drawio"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.gohenderson.drawio"
        minSdk = 29          // Android 10+
        targetSdk = 35
        // CI can pass -PversionCode=…; local builds use 1.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = drawioVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        animationsDisabled = true
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Lint runs as its own CI step and its report is uploaded; it doesn't block builds.
        abortOnError = false
        checkReleaseBuilds = false
        disable += "ExpiredTargetSdkVersion"
        textReport = true
        textOutput = file("build/reports/lint-results-debug.txt")
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyViewer, CopyViewer::outputDir)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Test-only libraries: never included in the app APK. The app has no dependencies.
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.1")
}
