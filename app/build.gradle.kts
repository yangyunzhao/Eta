import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val releaseStoreFile = System.getenv("ETA_RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("ETA_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("ETA_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ETA_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

val rawCodexOAuthBuildProperty = providers.gradleProperty("eta.codexOAuthEnabled")
val codexOAuthEnabled = rawCodexOAuthBuildProperty
    .map { rawValue ->
        rawValue.toBooleanStrictOrNull()
            ?: throw GradleException(
                "eta.codexOAuthEnabled must be either true or false",
            )
    }
    .orElse(true)

val upstreamVersionName = "3.0.0"
val upstreamVersionCode = 20_260_831
val downstreamReleaseSequence = 1
val downstreamVersionLabel = "znmlr"
val downstreamVersionCodeMultiplier = 100
val maxDownstreamReleaseSequence = downstreamVersionCodeMultiplier - 1
val maxAndroidVersionCode = 2_100_000_000

require(Regex("""\d+\.\d+\.\d+""").matches(upstreamVersionName)) {
    "upstreamVersionName must contain exactly three numeric components"
}
require(upstreamVersionCode > 0) {
    "upstreamVersionCode must be positive"
}
require(downstreamReleaseSequence in 1..maxDownstreamReleaseSequence) {
    "downstreamReleaseSequence must be between 1 and $maxDownstreamReleaseSequence"
}

val downstreamVersionName =
    "$upstreamVersionName.$downstreamVersionLabel.$downstreamReleaseSequence"
val downstreamVersionCodeLong =
    upstreamVersionCode.toLong() * downstreamVersionCodeMultiplier + downstreamReleaseSequence
require(downstreamVersionCodeLong <= maxAndroidVersionCode) {
    "computed downstream versionCode exceeds Android's supported maximum"
}
val downstreamVersionCode = downstreamVersionCodeLong.toInt()

tasks.withType<Test>().configureEach {
    systemProperty(
        "eta.test.codexOAuthBuildProperty",
        rawCodexOAuthBuildProperty.orElse("<unset>").get(),
    )
    systemProperty("eta.test.upstreamVersionName", upstreamVersionName)
    systemProperty("eta.test.upstreamVersionCode", upstreamVersionCode)
    systemProperty("eta.test.downstreamReleaseSequence", downstreamReleaseSequence)

    if (providers.environmentVariable("CI").isPresent) {
        maxParallelForks = 1
        addTestListener(
            object : TestListener {
                override fun beforeSuite(suite: TestDescriptor) = Unit

                override fun afterSuite(suite: TestDescriptor, result: TestResult) = Unit

                override fun beforeTest(descriptor: TestDescriptor) {
                    logger.lifecycle("ETA_TEST_START ${descriptor.className}.${descriptor.name}")
                }

                override fun afterTest(descriptor: TestDescriptor, result: TestResult) {
                    logger.lifecycle(
                        "ETA_TEST_END ${descriptor.className}.${descriptor.name} ${result.resultType}",
                    )
                }
            },
        )
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

android {
    namespace = "fuck.andes"
    compileSdk = 37

    defaultConfig {
        applicationId = "fuck.andes"
        minSdk = 34
        targetSdk = 36
        versionCode = downstreamVersionCode
        versionName = downstreamVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "boolean",
            "CODEX_OAUTH_ENABLED",
            codexOAuthEnabled.get().toString(),
        )
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isPseudoLocalesEnabled = true
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    androidResources {
        localeFilters += listOf("en", "b+zh+Hans", "b+zh+Hant")
    }

    packaging {
        resources {
            // 合并 Xposed 模块声明，避免 release 裁剪后模块入口失效
            merges += "META-INF/xposed/*"
            // 仅排除会引发打包冲突的签名/版本元数据，避免误伤 Compose 资源
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    // UI 侧 RemotePreferences 写入桥：通过 XposedService 将配置提交到 LSPosed 数据库；
    // Hook 侧用 XposedInterface.getRemotePreferences 读取当前进程持有的配置缓存。
    implementation(libs.libxposed.service)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.lucide.icons)
    implementation(libs.androidx.navigationevent)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    // markdown-renderer-m3 将 material3 作为 compileOnly，需显式引入以满足运行时依赖
    implementation(libs.material3)
    implementation(libs.hidden.api.bypass)

    // DataStore：Provider / Model 结构化 JSON 与当前选中 ID 等键值
    implementation(libs.datastore.preferences)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // OkHttp：替代 HttpURLConnection，支持 SSE
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    // Kotlinx Serialization：Provider 设置与运行时配置 JSON
    implementation(libs.kotlinx.serialization.json)

    // Coroutines：显式引入，避免依赖传递版本不确定
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
