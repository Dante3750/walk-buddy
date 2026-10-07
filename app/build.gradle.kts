plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Health Connect is optional and OFF by default so the default build cannot be broken by it.
// Enable with: ./gradlew :app:assembleDebug -PhealthConnect=true
val healthConnectEnabled = (project.findProperty("healthConnect") as String?) == "true"

android {
    namespace = "com.walkbuddy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.walkbuddy"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("boolean", "HEALTH_CONNECT", healthConnectEnabled.toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    implementation(libs.androidx.core.ktx)
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Peer-to-peer data channel. Both are real published artifacts; their resolution/compile is UNVERIFIED until CI runs.
    implementation(libs.okhttp)
    implementation(libs.stream.webrtc)

    if (healthConnectEnabled) {
        implementation(libs.health.connect)
    }

    testImplementation(libs.junit)
}
