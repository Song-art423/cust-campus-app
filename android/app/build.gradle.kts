// 必须显式 import：Gradle DSL 里 `java` 会被 Java 插件扩展遮蔽，
// 写 java.io.File 这种全限定名会报 Unresolved reference: io
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------- 版本号
// 版本号一律从 git 推导，禁止手写 —— 手写必然和 tag 漂移，过两周就对不上。
//
//   versionName = 最近 tag（去 v）+ 领先提交数 + 脏标记，例如 0.1.0-dev.3-dirty
//   versionCode = 提交总数。天然单调递增、永不重复，覆盖安装不会被系统拒
//
// 没有 git 环境（源码包解压）时退化为 0.0.0 / 1，能编但一眼看出不是正式构建。
fun gitOutput(vararg args: String): String = runCatching {
    val proc = ProcessBuilder(listOf("git") + args)
        .directory(project.projectDir)
        .redirectErrorStream(false)
        .start()
    val text = proc.inputStream.bufferedReader().use { it.readText() }.trim()
    proc.waitFor()
    text
}.getOrDefault("")

val gitTag: String = gitOutput("describe", "--tags", "--abbrev=0")

// git describe --long 形如 v0.1.0-3-gabc1234，中间那个数字是距 tag 的提交数
val gitAhead: Int = Regex("""-(\d+)-g[0-9a-fA-F]+$""")
    .find(gitOutput("describe", "--tags", "--long"))
    ?.groupValues?.get(1)?.toIntOrNull() ?: 0

val gitDirty: Boolean = gitOutput("status", "--porcelain").isNotEmpty()
val gitCount: Int = gitOutput("rev-list", "--count", "HEAD").toIntOrNull() ?: 1

val appVersionName: String = buildString {
    append(gitTag.removePrefix("v").ifEmpty { "0.0.0" })
    if (gitAhead > 0) append("-dev.$gitAhead")
    if (gitDirty) append("-dirty")
}

logger.lifecycle("版本号: $appVersionName ($gitCount)")

android {
    namespace = "com.cust.campus"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cust.campus"
        minSdk = 26
        targetSdk = 34
        versionCode = gitCount
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        // 调试面板的门控依赖 BuildConfig.DEBUG 与 VERSION_NAME。
        // AGP 8 起默认不生成 BuildConfig，必须显式打开。
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// ---------------------------------------------------------------- 版本归档
// 构建输出只有一个 app-debug.apk，每编一次覆盖一次，导致「哪份包对应哪版代码」
// 事后无法回答。归档一份带版本号的副本，配合 tag 就能双向追溯。
tasks.register<Copy>("archiveDebug") {
    group = "distribution"
    description = "构建 debug APK 并按版本号归档到仓库根 dist/"
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("../dist"))
    rename { "cust-campus-$appVersionName-$gitCount-debug.apk" }
}

// ---------------------------------------------------------------- 本地发版
// 发版权威产物落在本地 dist/，不依赖任何平台。GitHub 只做代码备份。
// 一条命令完成：校验 → 构建 → 归档 → 重建清单。
//
// 两条硬约束，缺一不可，否则产出的不是发布包：
//   1. HEAD 必须精确停在 tag 上（否则 versionName 带 -dev.N，那是开发包）
//   2. 工作区必须干净（否则 versionName 带 -dirty）
//
// 已归档的文件默认不覆盖 —— APK 构建不可复现，重复构建会让 CHANGELOG 里
// 记录的 MD5 失效。确实要重发时加 -Prearchive=true。

// 校验放在任务图就绪时执行 —— 否则要等 30 秒构建完才告诉你没打 tag
gradle.taskGraph.whenReady {
    if (!hasTask(":app:releaseLocal")) return@whenReady

    gitOutput("describe", "--tags", "--exact-match").ifEmpty {
        throw GradleException(
            "HEAD 不在任何 tag 上，这不是发布包（当前标识 $appVersionName）。\n" +
                "先按 AGENTS.md §3.1 打 tag，再 checkout 到该 tag 执行本任务。"
        )
    }

    gitOutput("status", "--porcelain").ifEmpty { null }?.let {
        throw GradleException("工作区有未提交改动，先提交再来发版：\n$it")
    }
}

tasks.register("releaseLocal") {
    group = "distribution"
    description = "本地发版：校验 tag 与工作区、构建、归档到 dist/ 并重建版本清单"
    dependsOn("assembleDebug")

    doLast {
        val distDir = rootProject.layout.projectDirectory.dir("../dist").asFile.apply { mkdirs() }

        val apkSrc = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        if (!apkSrc.exists()) throw GradleException("没找到构建产物：$apkSrc")

        val target = File(distDir, "cust-campus-$appVersionName-$gitCount-debug.apk")
        val rearchive = project.findProperty("rearchive")?.toString()?.toBoolean() ?: false

        if (target.exists() && !rearchive) {
            logger.lifecycle("已归档，跳过覆盖：${target.name}（重发请加 -Prearchive=true）")
        } else {
            apkSrc.copyTo(target, overwrite = true)
            logger.lifecycle("已归档：${target.name}")
        }

        // 清单从文件名反推，不维护额外状态文件 —— 手工挪过文件也不会失配
        fun md5Of(f: File): String {
            val digest = MessageDigest.getInstance("MD5")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        val entries = (distDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith("cust-campus-") && it.name.endsWith("-debug.apk") }
            .mapNotNull { f ->
                val core = f.name.removePrefix("cust-campus-").removeSuffix("-debug.apk")
                val dash = core.lastIndexOf('-')
                if (dash <= 0) null else Triple(core.substring(0, dash), core.substring(dash + 1), f)
            }
            .sortedByDescending { it.second.toIntOrNull() ?: 0 }

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        val builder = StringBuilder()
        builder.appendLine("# 本地发布清单")
        builder.appendLine()
        builder.appendLine("由 `./gradlew releaseLocal` 自动生成，**勿手工编辑**。")
        builder.appendLine("安装包就在本目录，`adb install -r <文件名>` 即可安装。")
        builder.appendLine()
        builder.appendLine("| 版本 | code | 文件 | 体积 | 构建时间 | MD5 |")
        builder.appendLine("|---|---|---|---|---|---|")
        entries.forEach { (ver, code, f) ->
            builder.appendLine(
                "| v$ver | $code | ${f.name} | ${"%,d".format(f.length())} " +
                    "| ${stamp.format(Date(f.lastModified()))} | `${md5Of(f)}` |"
            )
        }

        File(distDir, "releases.md").writeText(builder.toString())
        logger.lifecycle("清单已重建：dist/releases.md，共 ${entries.size} 个版本")
    }
}
