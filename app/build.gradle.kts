plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "jp.jig.glasses.sample.kmp"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.jig.sabera.app.minemiru"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xskip-prerelease-check")
        }
    }

    sourceSets {
        // 山名と標高はリポジトリ直下の data/ が正。コピーを置くと二重管理になるのでここから読む
        getByName("main").assets.srcDir(rootProject.file("data"))
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    // Sabera App SDK
    implementation(libs.sabera.app.core)
    implementation(libs.kotlinx.coroutines.android)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    // **material-icons-extended は入れない。** debug ビルドには R8 が効かないので、
    // 数千のアイコンが丸ごと dex に入って APK が 85MB → 26MB の差になる。
    // 実機へ何度も入れ直す段階で、この差は待ち時間として効く。
    // 設定パネルでアイコンが要るようになったら、そのとき必要なぶんだけ足す。
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    // 画面の状態は ViewModel が持つ（回転や再コンポーズで消えない・JVM テストから触れる）
    implementation(libs.lifecycle.viewmodel.compose)


    // 座標変換は実機に載せる前に手元で検算する
    testImplementation(libs.junit)
    // 進行係（Runner）は仮想時間で回して検算する
    testImplementation(libs.kotlinx.coroutines.test)
    // android.jar の org.json はスタブで例外を投げるので、テストでは本物を先に読ませる
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
