plugins {
    id("com.android.library")
}

android {
    namespace = "com.huanchengfly.tieba.localai"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // The stock Maven AAR statically selects WebGPU/Vulkan on this device, where Gemma 4 E4B
    // exceeds the driver's 128 MiB single-buffer limit. This local AAR keeps the official 0.17.1
    // JVM API but replaces its arm64 JNI runtime with the dynamic LiteRT/OpenCL build documented
    // in libs/README.md.
    implementation(files("libs/litertlm-android-opencl-0.17.1.aar"))
    implementation(google.gson)
}
