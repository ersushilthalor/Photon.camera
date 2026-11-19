plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.styropyr0.prismal"
    compileSdk = 36

    defaultConfig {
        minSdk = 25
        consumerProguardFiles(
            file("../external/PrismalAGSL/Prismal/consumer-rules.pro")
        )
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("../external/PrismalAGSL/Prismal/src/main/AndroidManifest.xml")
            java.srcDir("../external/PrismalAGSL/Prismal/src/main/java")
            assets.srcDir("../external/PrismalAGSL/Prismal/src/main/assets")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xcontext-parameters")
            optIn.add("kotlin.time.ExperimentalTime")
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.runtime)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.startup.runtime)
}
