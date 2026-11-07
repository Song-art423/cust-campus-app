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
