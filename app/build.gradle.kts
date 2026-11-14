import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// Fixed debug keystore.
// The debug keystore is a TEXT file in the repo (keystore/debug.keystore.b64)
// and is decoded here at configuration time into the build directory.
// It is the SAME key for every debug build (alias voxflow-debug,
// password "voxflow-debug" — debug-only credentials, public by design).
// It is DISTINCT from the release key, which lives outside the repo and
// arrives via CI secrets. Never per-run generated keys.
// ---------------------------------------------------------------------------
val debugKeystoreB64 = rootProject.file("keystore/debug.keystore.b64")
val debugKeystoreFile = layout.buildDirectory.file("voxflow/debug.keystore").get().asFile
if (debugKeystoreB64.exists()) {
    debugKeystoreFile.parentFile.mkdirs()
    debugKeystoreFile.writeBytes(Base64.getDecoder().decode(debugKeystoreB64.readText().trim()))
}

// Release signing comes from CI secrets (GitHub Actions), passed as
// Gradle properties: -PvoxflowReleaseKeystore=<path to .jks>
// -PvoxflowReleaseStorePassword=... -PvoxflowReleaseKeyAlias=voxflow-release
// -PvoxflowReleaseKeyPassword=...
// Absent locally, the release build stays unsigned (CI always provides
// them). The release key is NEVER in the repo.
val releaseKeystoreFile = (project.findProperty("voxflowReleaseKeystore") as String?)
    ?.let { file(it) }?.takeIf { it.exists() }
if (releaseKeystoreFile == null) {
    logger.warn("VoxFlow: no release keystore supplied — release builds will be unsigned (CI supplies the secrets).")
}

// The sherpa-onnx AAR is vendored, not on Maven. CI downloads it into
// app/libs/ before building (see app/libs/REGENERATE.md). Fail fast with
// a clear message instead of a cryptic resolution error.
val sherpaAar = file("libs/sherpa-onnx-static-link-onnxruntime-1.13.3.aar")
if (!sherpaAar.exists()) {
    throw GradleException(
        "Missing ${sherpaAar.path}. Download it first — see app/libs/REGENERATE.md " +
        "(CI does this automatically before building)."
    )
}

android {
    namespace = "com.abybijo.voxflow"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.abybijo.voxflow"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = debugKeystoreFile
            storePassword = "voxflow-debug"
            keyAlias = "voxflow-debug"
            keyPassword = "voxflow-debug"
        }
        if (releaseKeystoreFile != null) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = project.property("voxflowReleaseStorePassword") as String
                keyAlias = project.property("voxflowReleaseKeyAlias") as String
                keyPassword = project.property("voxflowReleaseKeyPassword") as String
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            if (releaseKeystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // No R8 / no shrinking in release either (user decision:
            // release must stay >= 10 MB and reproducible without R8).
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // AGP 8.10.1 does not generate BuildConfig by default; MainActivity
        // uses BuildConfig.DEBUG for the debug-only JankStats wiring.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.metrics.performance)
    implementation(libs.okhttp)
    implementation(libs.commons.compress)
    implementation(libs.kotlinx.coroutines.android)
    // sherpa-onnx 1.13.3 (static-link ONNX Runtime). No Maven coordinate
    // exists upstream — vendored AAR, fetched in CI (app/libs/REGENERATE.md).
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.3.aar"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
}
