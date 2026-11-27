import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------
// 固定签名：仓库根目录放 keystore.properties（已 gitignore，不入库）
//   storeFile=/abs/path/to/release.keystore
//   storePassword=xxx
//   keyAlias=xxx
//   keyPassword=xxx
// 没有该文件时自动回退 AGP 默认 debug 签名，别人 clone 下来照样能编。
// 发布包与 debug 包都用同一把 key，避免「版本号对了但装不上」。
// ---------------------------------------------------------------
val signingPropsFile = rootProject.file("keystore.properties")
val signingProps = Properties().apply {
    if (signingPropsFile.exists()) signingPropsFile.inputStream().use { load(it) }
}
val hasSigningProps = signingProps.getProperty("storeFile")?.let { File(it).exists() } == true

android {
    namespace = "com.example.shizukukeypad"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.shizukukeypad"
        minSdk = 26
        targetSdk = 34
        versionCode = 33
        versionName = "1.7.3"
    }

    signingConfigs {
        if (hasSigningProps) {
            create("stable") {
                storeFile = File(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigningProps) signingConfig = signingConfigs.getByName("stable")
        }
        debug {
            if (hasSigningProps) signingConfig = signingConfigs.getByName("stable")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
