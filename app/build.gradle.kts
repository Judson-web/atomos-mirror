plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}
android {
 namespace = "com.atomos.mirror"
 compileSdk = 36
 defaultConfig { applicationId = "com.atomos.mirror"; minSdk = 26; targetSdk = 36; versionCode = 9; versionName = "1.0.0" }
 signingConfigs {
  create("release") {
   storeFile = file(System.getenv("ATOMOS_SIGNING_STORE_FILE") ?: "release.keystore")
   storePassword = System.getenv("ATOMOS_SIGNING_STORE_PASSWORD") ?: ""
   keyAlias = System.getenv("ATOMOS_SIGNING_KEY_ALIAS") ?: "atomos-release"
   keyPassword = System.getenv("ATOMOS_SIGNING_KEY_PASSWORD") ?: ""
  }
 }
 buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("release"); isMinifyEnabled = false } }
 buildFeatures { compose = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.05.00"))
 implementation("androidx.activity:activity-compose:1.10.1")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
 implementation("org.nanohttpd:nanohttpd:2.3.1")
 implementation("org.java-websocket:Java-WebSocket:1.5.7")
 implementation("io.getstream:stream-webrtc-android:1.3.10")
 debugImplementation("androidx.compose.ui:ui-tooling")
}
