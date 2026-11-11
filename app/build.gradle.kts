plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.akashic.shell"
    compileSdk = 36

        defaultConfig {
        applicationId = "com.akashic.shell"
        minSdk = 30
        targetSdk = 36
        versionCode = 14
        versionName = "0.4.2"
    }

    buildFeatures {
        aidl = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = providers.gradleProperty("testApplicationIdSuffix").getOrElse(".debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 演示版直接使用本机 debug 签名，便于侧载安装。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += listOf("META-INF/**", "kotlin/**", "**/*.kotlin_builtins", "DebugProbesKt.bin")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
