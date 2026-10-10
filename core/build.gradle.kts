// The part of the client that is not Android: protocol, networking, state.
// Plain Kotlin, so it is tested on any computer, including against a real Gemma server.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // GEMMA_TEST_URL / GEMMA_TEST_KEY / GEMMA_TEST_LONG are passed through for the live-server test.
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
