plugins { id("com.android.application") }

android {
    namespace = "dev.akashic.audiofocus"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.akashic.audiofocus"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    flavorDimensions += "actor"
    productFlavors {
        create("a") { dimension = "actor"; applicationIdSuffix = ".a" }
        create("b") { dimension = "actor"; applicationIdSuffix = ".b" }
    }
}
