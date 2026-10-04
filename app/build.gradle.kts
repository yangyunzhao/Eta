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

val upstreamVersionName = "3.1.0"
val upstreamVersionCode = 2_026_100_201
val downstreamReleaseSequence = 2
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
    upstreamVersionCode.toLong() + downstreamReleaseSequence - 1L
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
        // v3.0.2 新增 rootless/语言 UI 尚未覆盖全部资源 locale；Compose 回调中的
        // stringResource 等价替换需由上游单独处理。保留其余 lint 作为发布门禁。
        disable += setOf("MissingTranslation", "LocalContextGetResourceValueCall")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric 在高版本 JDK 下需要访问内部 API，参数只作用于测试 JVM。
        unitTests.all {
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                "--enable-native-access=ALL-UNNAMED",
            )
        }
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    // UI 侧 RemotePreferences 写入桥：通过 XposedService 将配置提交到 LSPosed 数据库；
    // Hook 侧用 XposedInterface.getRemotePreferences 读取当前进程持有的配置缓存。
    implementation(libs.libxposed.service)
    implementation(libs.dexkit)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.lucide.icons)
    implementation(libs.material.icons.extended)
    implementation(libs.androidx.navigationevent)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    // 只使用 GFM 解析器；聊天渲染层由 ui/markdown 自建，按块冻结并接入逐字显现。
    implementation(libs.intellij.markdown)
    implementation(libs.hidden.api.bypass)

    // DataStore：Provider / Model 结构化 JSON 与当前选中 ID 等键值
    implementation(libs.datastore.preferences)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // OkHttp：替代 HttpURLConnection，支持 SSE
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.commons.compress)
    implementation(libs.xz)

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
