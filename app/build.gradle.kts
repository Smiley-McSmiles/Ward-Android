plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "org.ward.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.ward.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.4"
        vectorDrawables.useSupportLibrary = true
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/DEPENDENCIES", "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/versions/**/MANIFEST.MF") }
}

configurations.all {
    exclude(group = "org.igniterealtime.smack", module = "smack-openpgp")
    // The OMEMO 2 engine needs Dino's curve25519 fork (edToMont); the legacy engine works with it too.
    exclude(group = "org.whispersystems", module = "curve25519-java")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.webkit:webkit:1.12.0")
    implementation("androidx.emoji2:emoji2:1.7.0")
    implementation("androidx.emoji2:emoji2-emojipicker:1.7.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.igniterealtime.smack:smack-android-extensions:4.4.8") { exclude(group="xpp3", module="xpp3_min") }
    implementation("org.igniterealtime.smack:smack-tcp:4.4.8")
    implementation("org.igniterealtime.smack:smack-im:4.4.8")
    implementation("org.igniterealtime.smack:smack-omemo:4.4.8")
    implementation("org.igniterealtime.smack:smack-omemo-signal:4.4.8") { exclude(group="xpp3", module="xpp3_min") }
    // OMEMO 2 (urn:xmpp:omemo:2): Dino's libomemo libsignal fork, relocated to org.ward.omemo2.signal
    // so it can coexist with Smack's legacy OMEMO engine. GPL-3.0; see THIRD_PARTY_NOTICES.md.
    implementation(files("libs/omemo2-libsignal.jar"))
    implementation(files("libs/omemo2-protobuf.jar"))
    implementation("im.dino:curve25519-java:0.5.1")
    implementation("im.dino:curve25519-android:0.5.1")
    implementation("im.conversations.webrtc:webrtc-android:129.0.0")
    implementation("org.pgpainless:pgpainless-core:1.7.6")
    implementation("org.jxmpp:jxmpp-jid:1.0.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.21")
}


