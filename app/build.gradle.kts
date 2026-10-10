import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.paparazzi)
}

// Health Connect is optional and OFF by default so the default build cannot be broken by it.
// Enable with: ./gradlew :app:assembleDebug -PhealthConnect=true
// The widget module is optional too (see settings.gradle.kts). The app talks to it by component name only.
val widgetEnabled = (project.findProperty("widget") as String?) != "false"
val healthConnectEnabled = (project.findProperty("healthConnect") as String?) == "true"

// Release signing comes from environment variables or from local.properties (both stay on your machine, never in git):
//   WB_KEYSTORE_PATH / wb.keystore.path, WB_KEYSTORE_PASSWORD / wb.keystore.password, WB_KEY_ALIAS / wb.key.alias, WB_KEY_PASSWORD / wb.key.password
// Without them the release build is signed with the debug key so CI and local `assembleRelease` still work (such an APK is for testing only).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: localProps.getProperty(prop)?.takeIf { it.isNotBlank() }
val releaseStorePath = signingValue("WB_KEYSTORE_PATH", "wb.keystore.path")
val releaseStorePassword = signingValue("WB_KEYSTORE_PASSWORD", "wb.keystore.password")
val releaseKeyAlias = signingValue("WB_KEY_ALIAS", "wb.key.alias")
val releaseKeyPassword = signingValue("WB_KEY_PASSWORD", "wb.key.password") ?: releaseStorePassword
val hasReleaseKey = releaseStorePath != null && releaseStorePassword != null && releaseKeyAlias != null && file(releaseStorePath).exists()

android {
    namespace = "com.walkbuddy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.walkbuddy"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "2.0.0-alpha"
        buildConfigField("boolean", "HEALTH_CONNECT", healthConnectEnabled.toString())
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    lint {
        // Lint runs as its own non-blocking CI step; it must not be able to stop a release build.
        checkReleaseBuilds = false
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
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.pickFirsts += "**/libjingle_peerconnection_so.so"
    }
    sourceSets {
        if (healthConnectEnabled) {
            getByName("main").java.srcDir("src/healthconnect/kotlin")
        }
    }
}

dependencies {
    implementation(project(":domain"))
    if (widgetEnabled) {
        implementation(project(":widget"))
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Peer-to-peer data channel. Both are real published artifacts; their resolution/compile is UNVERIFIED until CI runs.
    implementation(libs.okhttp)
    implementation(libs.stream.webrtc)

    // QR scanning for invites. Optional to use: a link or code can always be typed instead.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    if (healthConnectEnabled) {
        implementation(libs.health.connect)
        // connect-client exposes ListenableFuture in its API; this tiny artifact puts the class on the compile classpath.
        implementation("com.google.guava:listenablefuture:1.0")
    }

    testImplementation(libs.junit)
}
