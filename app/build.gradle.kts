import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Keys come from environment variables (CI secrets) or an untracked secrets.properties file.
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// Firebase settings can also come from the google-services.json Firebase gives you (app/google-services.json).
// They are not secret: they ship inside every APK. The database rules and sign-in keep the data safe.
@Suppress("UNCHECKED_CAST")
val googleServices: Map<String, String> = rootProject.file("app/google-services.json").takeIf { it.exists() }?.let { f ->
    runCatching {
        val json = groovy.json.JsonSlurper().parse(f) as Map<String, Any?>
        val project = json["project_info"] as Map<String, Any?>
        val clients = json["client"] as List<Map<String, Any?>>
        val client = clients.firstOrNull {
            ((it["client_info"] as Map<String, Any?>)["android_client_info"] as Map<String, Any?>)["package_name"] == "com.rodeo.hud"
        } ?: clients.first()
        mapOf(
            "FIREBASE_PROJECT_ID" to (project["project_id"] as? String).orEmpty(),
            "FIREBASE_DB_URL" to (project["firebase_url"] as? String).orEmpty(),
            "FIREBASE_APP_ID" to ((client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"] as? String).orEmpty(),
            "FIREBASE_API_KEY" to (((client["api_key"] as List<Map<String, Any?>>).firstOrNull())?.get("current_key") as? String).orEmpty(),
            // The "web" OAuth client (type 3) appears once Google sign-in is on and the SHA-1 is registered.
            "GOOGLE_WEB_CLIENT_ID" to ((client["oauth_client"] as? List<Map<String, Any?>>).orEmpty()
                .firstOrNull { (it["client_type"] as? Number)?.toInt() == 3 }?.get("client_id") as? String).orEmpty(),
        )
    }.getOrDefault(emptyMap())
} ?: emptyMap()

// An unset CI secret arrives as an empty variable, so blank counts as missing.
fun secret(name: String): String = System.getenv(name)?.takeIf { it.isNotBlank() }
    ?: secrets.getProperty(name)?.takeIf { it.isNotBlank() }
    ?: googleServices[name]?.takeIf { it.isNotBlank() }
    ?: ""

// -Prodeo.e2e builds for the x86_64 emulator used by the on-device tests on CI, with a
// mock-location permission so the tests can drive GPS. Phone builds are unaffected.
val e2e = providers.gradleProperty("rodeo.e2e").isPresent

android {
    namespace = "com.rodeo.hud"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rodeo.hud"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"

        // Phones only: the offline speech and hand models ship native code for ARM.
        ndk { abiFilters += if (e2e) listOf("x86_64") else listOf("arm64-v8a", "armeabi-v7a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "FIREBASE_API_KEY", "\"${secret("FIREBASE_API_KEY")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${secret("FIREBASE_APP_ID")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${secret("FIREBASE_PROJECT_ID")}\"")
        buildConfigField("String", "FIREBASE_DB_URL", "\"${secret("FIREBASE_DB_URL")}\"")
        // Optional TURN relay for the pack voice channel (comma-separated turn: / turns: URLs).
        // Emulator test builds skip the sign-in screen (they can't receive the verification email).
        buildConfigField("boolean", "E2E", e2e.toString())
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${secret("GOOGLE_WEB_CLIENT_ID")}\"")
        buildConfigField("String", "TURN_URLS", "\"${secret("TURN_URLS")}\"")
        buildConfigField("String", "TURN_USERNAME", "\"${secret("TURN_USERNAME")}\"")
        buildConfigField("String", "TURN_PASSWORD", "\"${secret("TURN_PASSWORD")}\"")
    }

    // Optional fixed signing key (CI secrets SIGNING_KEYSTORE_B64 / SIGNING_PASSWORD).
    val keystore = System.getenv("SIGNING_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    if (keystore != null) {
        signingConfigs.create("rodeo") {
            storeFile = keystore
            storePassword = secret("SIGNING_PASSWORD")
            keyAlias = "rodeo"
            keyPassword = secret("SIGNING_PASSWORD")
        }
    }

    buildTypes {
        debug {
            if (keystore != null) signingConfig = signingConfigs.getByName("rodeo")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (keystore != null) "rodeo" else "debug")
        }
    }
    if (e2e) sourceSets.getByName("debug").manifest.srcFile("src/e2e/AndroidManifest.xml")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
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
    }
    // The hand model is memory-mapped straight from the APK.
    androidResources {
        noCompress += listOf("onnx", "task")
    }
    // Screenshot tests (Robolectric + Roborazzi) render the HUD at several screen sizes on CI.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.maxHeapSize = "3g"
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Keyless map: OpenStreetMap / CARTO tiles; routing from the free OpenStreetMap servers.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Offline speech for the wake phrase and commands: sherpa-onnx (Apache 2.0), its AAR and the
    // model are downloaded on CI (tools/ci/assets.sh).
    implementation(files("libs/sherpa-onnx.aar"))

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-database")
    // Anonymous sign-in, so the database rules let a rider write only their own position.
    implementation("com.google.firebase:firebase-auth")
    // Continue with Google, through Android's Credential Manager.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Pack mode without mobile signal: phone-to-phone over Bluetooth / Wi-Fi.
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    // Pack QR codes: drawn with ZXing (Apache 2.0), scanned with Google's code scanner (no camera permission).
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Pack voice channel: WebRTC (BSD), peer to peer.
    implementation("io.getstream:stream-webrtc-android:1.3.8")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")

    // On-device tests (emulator on CI): search, route, ride, reroute, music card.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
