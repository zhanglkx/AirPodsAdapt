import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 发布签名配置：优先读 keystore.properties（本地，已 gitignore），其次读环境变量（CI）。
// 两者都没有时回退到 debug 签名，保证本地 assembleRelease 仍能跑通。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
fun signingValue(propKey: String, envKey: String): String? =
    (keystoreProps.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }

val hasReleaseSigning = signingValue("storeFile", "KEYSTORE_FILE") != null

android {
    namespace = "com.airpodsadapt"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.airpodsadapt"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(signingValue("storeFile", "KEYSTORE_FILE")!!)
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 有发布密钥就用正式签名，否则回退 debug 签名（仅便于本地/无密钥构建）
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    lint {
        // 反射访问 BluetoothSocket 隐藏构造函数是本 App 的核心机制，属预期行为
        disable += "DiscouragedPrivateApi"
        disable += "SoonBlockedPrivateApi"
        disable += "BlockedPrivateApi"
        abortOnError = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    // 解除系统对非公开 API 的反射限制（创建经典蓝牙 L2CAP socket 必需）
    implementation(libs.hiddenapibypass)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
