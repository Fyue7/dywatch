import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/*
 * 一份代码，两种打包，逻辑零分叉：
 *   公开版（默认）  gradlew assembleDebug            → 包名 com.dsh.dywatch.oss，应用名 dywatch，首启无主播
 *   个人版          gradlew assembleDebug -Ppersonal=true -PpersonalName=你的名字
 *                   → 包名 com.dsh.dywatch，应用名/标题用你自己的，首启读 app/src/personal/assets/seed_accounts.json
 * 个人版的名单与名字都放在不入库的本地文件里（见 .gitignore），仓库里只有公开版。
 */
val personalBuild = (findProperty("personal") as String?)?.toBoolean() ?: false
val personalName = (findProperty("personalName") as String?) ?: "demo"
// 版本名可覆盖：公开版从 1.0.0 起，个人版接着自己的号往下走（versionCode 两边共用）
val buildVersionName = (findProperty("verName") as String?) ?: "1.0.0"

// 正式签名：根目录有 keystore.properties 才启用（该文件与 jks 都不入库）；
// 没有时 release 退回不签名，debug 仍用本机 debug keystore —— 别人 clone 下来照样能构建
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.dsh.dywatch"
    compileSdk = 34

    defaultConfig {
        applicationId = if (personalBuild) "com.dsh.dywatch" else "com.dsh.dywatch.oss"
        minSdk = 24
        targetSdk = 34
        versionCode = 30
        versionName = buildVersionName

        resValue("string", "app_name", if (personalBuild) personalName else "dywatch")
        resValue("string", "header_title_default", if (personalBuild) personalName else "dywatch")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    sourceSets.getByName("main") {
        // 个人版才有预置主播名单；公开版这一目录不存在，首启就是空列表
        if (personalBuild) assets.srcDir("src/personal/assets")
    }

    buildFeatures {
        // 日志门用 BuildConfig.DEBUG（审计：诊断日志不许裸进 release）
        buildConfig = true
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
        // androidx.core:core-ktx 已删：全仓零 KTX 调用点，base androidx.core:core 由 appcompat 传递提供
        // 但更新链路要用 FileProvider，显式声明 base 包，别依赖传递依赖
        implementation("androidx.core:core:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
