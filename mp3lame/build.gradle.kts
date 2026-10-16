// Pure-JVM MP3 (MPEG-1/2 Layer III) encoder: a vendored copy of the LGPL Java LAME port (jump3r 1.0.x
// sources, as shipped in lamejs/src/main/java) plus a small Kotlin streaming wrapper and an ID3v2.3
// writer. No NDK and no native libraries, so it runs unchanged on every ABI the app ships.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.junit)
}
