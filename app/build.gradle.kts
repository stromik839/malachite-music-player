plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.malachite.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.malachite.player"
        // Android 8.0+: covers practically every phone in use, any vendor/firmware.
        minSdk = 26
        targetSdk = 35
        // Every build on GitHub gets its own, always growing number and the commit it was made
        // from ("0.2.0-46cc06c"): the phone offers "update" for each new APK, refuses an older
        // one, and the settings show which build is installed. Elsewhere (F-Droid) the plain one.
        val ciRun = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        val ciCommit = System.getenv("GITHUB_SHA")?.take(7)
        versionCode = if (ciRun != null) 1000 + ciRun else 2
        versionName = "0.2.0" + (ciCommit?.let { "-$it" } ?: "")
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // The release key is NEVER in the repository. The build takes it from the environment:
        // on GitHub from the repository's Secrets (see docs/PUBLISHING.md), locally from the same
        // variables. Without it a release is signed with the build machine's own debug key –
        // fine for trying things out, but such an APK does not update an installed release.
        val privateStore = System.getenv("MALACHITE_KEYSTORE")
        if (!privateStore.isNullOrBlank() && file(privateStore).isFile) {
            create("private") {
                storeFile = file(privateStore)
                storePassword = System.getenv("MALACHITE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MALACHITE_KEY_ALIAS")
                keyPassword = System.getenv("MALACHITE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // R8 shrinks and optimizes the code: Compose is much faster with it, which a modest
            // phone feels. Names are not changed (readable logs); the tag library, which works
            // by reflection, is kept whole (proguard-rules.pro). Every build is started on
            // Android 8 to 15 emulators by CI to catch anything R8 might break.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // (F-Droid removes the signing section and signs with its own key: nothing here may depend on it)
            signingConfig = signingConfigs.findByName("private") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
    lint {
        // One check runs with every release build, and it stops the build: a call that an older
        // Android (8, 9, 10…) does not have would crash the app there. The rest of lint is not
        // part of the build.
        checkOnly += "NewApi"
        fatal += "NewApi"
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.database)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.documentfile)
    implementation(libs.coil.compose)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
