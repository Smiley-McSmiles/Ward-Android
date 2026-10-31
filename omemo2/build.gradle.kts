plugins {
    `java-library`
    id("com.gradleup.shadow")
}

// OMEMO 2 needs Dino's libsignal fork, but Smack's legacy OMEMO engine needs the
// unmodified signal-protocol-java classes. Relocate the fork (and its protobuf
// runtime) into private packages so both can coexist in the same APK.
//
// This module replaces the two prebuilt JARs that used to live in app/libs/.
// Both inputs come from trusted source repositories: dino/libomemo-java is built
// by JitPack, protobuf-javalite comes from Maven Central. Nothing is checked in
// as a binary.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // GPL-3.0-only. See THIRD_PARTY_NOTICES.md and LICENSES/GPL-3.0-only.txt.
    implementation("com.github.dino.libomemo-java:omemo-java:v0.5.2") {
        // curve25519 is a normal app dependency and must not be shaded twice.
        exclude(group = "im.dino", module = "curve25519-java")
    }
    implementation("com.google.protobuf:protobuf-javalite:3.10.0")
    compileOnly("im.dino:curve25519-java:0.5.1")
}

val shaded by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

tasks.shadowJar {
    archiveBaseName.set("omemo2")
    archiveClassifier.set("")
    archiveVersion.set("")
    relocate("org.whispersystems.libsignal", "org.ward.omemo2.signal")
    relocate("com.google.protobuf", "org.ward.omemo2.protobuf")
}

tasks.withType<org.gradle.api.tasks.bundling.AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

artifacts {
    add(shaded.name, tasks.shadowJar)
}
