# 编译与运行

## 环境已就绪（不用再装 Android Studio）

工作区里已经搭好一套完整但「不污染系统」的工具链，全部放在 `<工作区>/.toolchain/`，删掉这个目录就等于卸载干净：

| 组件 | 版本 | 路径 |
|---|---|---|
| JDK | OpenJDK 17.0.2 | `.toolchain/jdk-17.0.2` |
| Android SDK Platform | android-34 | `.toolchain/android-sdk/platforms/android-34` |
| Build Tools | 34.0.0 | `.toolchain/android-sdk/build-tools/34.0.0` |
| Platform Tools (adb) | latest | `.toolchain/android-sdk/platform-tools` |
| Gradle | 8.9 | `.toolchain/gradle-8.9` |

没有写注册表，没有改系统环境变量，也没有动你原有的 JDK 11。

## 构建 Debug APK

**方式一：双击 `build.bat`**（自动设置环境变量，跑完窗口不关）

**方式二：命令行**

```bash
cd cust-campus-app/android
export JAVA_HOME="C:/Users/12/WorkBuddy/2026-11-06-13-41-15/.toolchain/jdk-17.0.2"
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 17 MB）

## 装到手机

```bash
# 手机开 USB 调试，连上电脑后
.toolchain/android-sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以直接把 `app-debug.apk` 拷到手机上点安装（需要在系统里允许「安装未知来源应用」）。

## 用 Android Studio 打开

`File → Open` 选 `cust-campus-app/android` 目录即可。

两点需要知道：

1. `gradle.properties` 里的 `org.gradle.java.home` 指向工作区的 JDK 17。Studio 自带的 JDK 会覆盖它，留着不影响；想干净些可以删掉这行。
2. `local.properties` 里的 `sdk.dir` 指向 `.toolchain/android-sdk`。Studio 首次打开通常会提示切换成它自己的 SDK，同意即可。这两个文件都已加入 `.gitignore`，不会带到别的机器上。

## 已验证的事实

- `gradle assembleDebug` → **BUILD SUCCESSFUL**，35 个任务
- `./gradlew assembleDebug`（wrapper 路径）→ **BUILD SUCCESSFUL**
- `build.bat` 双击路径 → 实测通过
- APK 元数据核对：`package=com.cust.campus`、`minSdk=26`、`targetSdk=34`、入口 `com.cust.campus.ui.MainActivity`

编译过程**零错误零警告**。

## 踩过的坑（已修，留档备查）

**1. `LoginActivity` 里 `progress.visibility` 编译不过**

报错是 `Unresolved reference: visibility`，但 `ProgressBar` 明明是 `View` 的子类，看着应该有 `visibility`。

真因是 Kotlin 的隐式接收者优先级：代码写在 `webView = WebView(this).apply { ... }` 块里，而 `WebView` 自己有个 `getProgress()`。于是 `progress` 被解析成 `WebView.progress`（`Int`），`Int` 上没有 `visibility`，自然报错。

修法：字段改名 `loadingBar`，不再和 `WebView` 的成员撞名。

**2. `gradle wrapper` 生成失败**

`Test of distribution url https://services.gradle.org/... failed` —— `services.gradle.org` 在验证分发地址时被墙。

修法：指定腾讯云镜像作为分发地址重新生成：

```bash
gradle wrapper --gradle-distribution-url "https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip"
```

生成后用 `./gradlew` 实测能正常拉取并构建。

**3. 阿里云镜像不可省**

`settings.gradle.kts` 里 google()/mavenCentral() 之前，放了阿里云的 `gradle-plugin`、`google`、`public` 三个仓库。实测首次构建 4 分钟拉完所有依赖，没有一个卡住。

## 已知限制

- **只在编译层面验证过**。没连真机跑过，所以运行时行为（WebView 登录、Cookie 桥接、通知）都还没有实测。
- **未签名的 release 包不能直接分发**，需要配置 keystore，等要发给别人时再弄。
- 空教室、寝室电量、学习通作业三个模块还没接——缺接口。
