# AGENTS.md — 长理课表 App 工程约定

> 面向所有参与本项目的 AI Agent / 协作者。**每次动手前先读这个文件。**

---

## 1. 项目结构

```
cust-campus-app/
├── android/          Android 客户端（Kotlin + Compose）
│   └── app/src/main/java/com/cust/campus/
│       ├── data/     数据层：缓存、同步、登录态、Cookie 桥接
│       ├── ui/       界面层：三个主页面 + 登录页 + 熄屏提醒页
│       ├── notify/   通知构建
│       └── work/     闹钟调度、广播接收器、调试工具
├── fetcher/          教务系统接口探测（Python）
├── recon/            抓包分析与接口还原
└── analysis/         接口 / 会话机制的分析文档
```

构建产物路径（**给用户报路径时必须用绝对路径**）：

```
# Gradle 原始输出，每次构建覆盖，只用于安装测试
C:\Users\12\WorkBuddy\2026-11-06-13-41-15\cust-campus-app\android\app\build\outputs\apk\debug\app-debug.apk

# 按版本归档，文件名即版本号，配合 tag 可追溯。见 §3.2
C:\Users\12\WorkBuddy\2026-11-06-13-41-15\cust-campus-app\dist\cust-campus-<versionName>-<versionCode>-debug.apk
```

---

## 2. 分支模型

采用 **GitFlow-lite**：保留 GitFlow 的主干结构，但砍掉单人开发用不上的仪式。

### 2.1 常驻分支

| 分支 | 职责 | 谁能写 |
|---|---|---|
| `main` | **只存已发布状态**。每个 commit 都对应一个 tag、一份 APK。 | 只能由 `release/*`、`hotfix/*` 合入，**禁止直接提交** |
| `develop` | 集成分支，反映「下一个版本」的最新状态。日常开发的落脚点。 | 直接提交 / 由 `feature/*`、`fix/*` 合入 |

### 2.2 工作分支（生命期短，合完即删）

| 分支 | 从哪切 | 合到哪 | 用途 |
|---|---|---|---|
| `feature/<scope>-<desc>` | `develop` | `develop` | 新功能 |
| `fix/<scope>-<desc>` | `develop` | `develop` | 修非紧急 bug |
| `release/v<X.Y.Z>` | `develop` | `main` + `develop` | 版本收敛：只改版本号、写 CHANGELOG、修阻断发布的问题，**冻结新功能** |
| `hotfix/v<X.Y.Z>` | `main` | `main` + `develop` | 线上紧急问题，绕过 develop 直接修 |

```
feature/fix ──► develop ──► release/vX.Y.Z ──► main (打 tag) ──► 回灌 develop
                                                   ▲
                             hotfix/vX.Y.Z ────────┘
```

### 2.3 单人开发快速通道

改动 ≤ 3 个文件且不涉及发版时，**直接在 `develop` 提交**，不必开 `feature/` 分支 ——
开完立刻合的分支只会让 `git log --graph` 变糊。

但 `release/*` 和 `hotfix/*` **必须走分支**：它们要打 tag，必须有一个明确的
合并点（`--no-ff`）把「这次发布包含哪些改动」记录成一条边界。

### 2.4 命名规则

- 全小写，用连字符 `-` 分隔，不用下划线、不用中文、不用斜杠套斜杠
- `<scope>` 取自 §4.2 的模块名：`android` `data` `ui` `notify` `work` `api` `build` `tools` `docs`
- 示例：`feature/ui-term-switch`、`fix/notify-channel-race`、`release/v0.2.0`

---

## 3. 发版流程

版本号 **SemVer**：`MAJOR.MINOR.PATCH`

- `MAJOR` — 不兼容变更（1.0.0 之前不考虑）
- `MINOR` — 新功能，向后兼容
- `PATCH` — 纯修 bug，无新功能

### 3.1 标准发版（六步）

```bash
# 1. 切发布分支
git checkout -b release/v0.2.0 develop

# 2. 写 CHANGELOG：把 Unreleased 的内容定稿成 [0.2.0]
#    并核对 §3.2 的 APK 元信息

# 3. 验证（必须全绿才继续）
cd android && ./gradlew assembleDebug testDebugUnitTest --offline

# 4. 提交并合入 main
git commit -am "chore(release): 发布 v0.2.0"
git checkout main && git merge --no-ff release/v0.2.0

# 5. 打带注释的 tag（禁止轻量 tag）
git tag -a v0.2.0 -m "v0.2.0 — <一句话主题>

APK: dist/cust-campus-0.2.0-<code>-debug.apk
构建: YYYY-MM-DD HH:MM:SS
MD5: <32位十六进制>"

# 6. 回灌 develop，然后一起推
git checkout develop && git merge --no-ff main
git push origin main develop --tags
```

### 3.2 版本号与安装包的对应关系

版本号**由 git 自动推导**，禁止在 `build.gradle.kts` 里手写：

| 字段 | 来源 | 示例 |
|---|---|---|
| `versionName` | 最近 tag（去 `v`）+ 领先提交数 + 脏标记 | `0.2.0` / `0.2.0-dev.3` / `0.2.0-dev.3-dirty` |
| `versionCode` | `git rev-list --count HEAD`，单调递增、永不重复 | `7` |

`-dev.N` 表示距上一个 tag 已经有 N 个提交，`-dirty` 表示工作区有未提交改动 ——
**带这两个后缀的构建都不是正式版**，只能自己装。

归档产物：

```bash
cd android && ./gradlew archiveDebug
# → dist/cust-campus-<versionName>-<versionCode>-debug.apk
```

`dist/` 被 gitignore，二进制不进仓库。正式包上传到
[GitHub Releases](https://github.com/Song-art423/cust-campus-app/releases)，
tag 名与 Release 标题保持一致。

### 3.3 发版禁止事项

- ❌ 直接往 `main` 提交
- ❌ 打轻量 tag（`git tag v0.2.0` 不带 `-a`）—— 没有作者和时间就没有追溯价值
- ❌ 修改或删除**已推送**的 tag
- ❌ tag 缺 `v` 前缀 —— `build.gradle.kts` 的解析规则依赖它
- ❌ tag message 里不写 APK 的 MD5 与构建时间
- ❌ `release/*` 分支里加新功能

---

## 4. 提交规范（强制）

### 4.1 粒度：一个需求小点一条 commit

**这是硬性要求。** 判定标准：一条 commit 只回答一个问题 ——「这次改了什么，为什么」。

- ✅ 修好「通知总开关没被检测」→ 单独一条 `fix(notify): ...`
- ✅ 加「震动开关」→ 单独一条 `feat(notify): ...`
- ❌ 把「删超级岛 + 改渠道 + 加开关 + 改 UI」塞进一条 `update`

一次会话里做了 N 个独立改动，就提交 N 次。**不要攒到最后一起提交。**

例外：一个改动必须跨多个文件才能成立（比如新增字段要同时改 model / viewmodel / UI），算**一个**小点，一条 commit。

### 4.2 格式：Conventional Commits

```
<type>(<scope>): <简短描述>

<可选正文：为什么这么改、踩了什么坑>

<可选 footer：关联 issue>
```

**type 只能是这几个**：

| type | 用途 |
|---|---|
| `feat` | 新功能 |
| `fix` | 修 bug |
| `refactor` | 重构，不改变外部行为 |
| `perf` | 性能优化 |
| `style` | 格式、缩进、命名，不影响逻辑 |
| `docs` | 文档 |
| `test` | 测试 |
| `build` | 构建配置、依赖版本 |
| `chore` | 杂务：gitignore、脚本、工具 |

**scope 用项目里的模块名**：

`android` `data` `ui` `notify` `work` `api` `build` `tools` `docs`

**描述**：中文，动词开头，不超过 50 字，结尾不加句号。

### 4.3 示例

```
feat(notify): 增加震动开关，走独立渠道 course_remind_vib

渠道的震动属性创建后永久锁死，改代码不生效，只能新建渠道 id。
默认关闭，避免影响已验证可用的普通通知。

fix(notify): 体检补上 areNotificationsEnabled 检测

运行时权限 granted 不代表能发通知。用户在系统设置里关掉总开关后
权限依然是 granted，之前面板全绿但一条通知都发不出去。

refactor(ui): 把提醒调试面板的超级岛诊断区整块移除

个人开发者拿不到小米焦点通知白名单，参数写对了系统也忽略，
留着只会误导排查方向。

docs: 补充 AGENTS.md 提交规范
```

### 4.4 禁止事项

- ❌ 不写 `--no-verify` 跳过钩子
- ❌ 不 `push --force`（除用户明确要求）
- ❌ 不提交 `cookie.txt`、`*.har`、`local.properties`、任何密钥
- ❌ 不用 `git commit --amend` 修改已推送的提交
- ❌ 一条 commit 不混入无关的格式化改动

---

## 5. 提交前必做

```bash
cd android
./gradlew assembleDebug testDebugUnitTest --offline
```

要求：`BUILD SUCCESSFUL`，0 error，单测全绿。**编译不过不许提交。**

改动了通知 / 闹钟相关代码时，额外确认：

- `alarm-debug` 相关单测是否仍然覆盖边界（00:00 课程、跨节次连堂）
- APK 里不该出现 ROM 私有 extras（如 `miui.focus.*`）

---

## 6. 代码约定

### 6.1 Kotlin

- 缩进 4 空格，不留行尾空格
- 关键逻辑写**为什么**，不写**是什么**；禁止逐行注释
- 所有对外 API 用 `runCatching` 兜住异常，**但必须把失败原因返回给调用方**，
  不许静默吞掉（本项目的通知问题就是这么被藏了两轮的）

### 6.2 通知相关（血泪教训，别重犯）

- **不写任何 ROM 私有通知 extras**。写过 `miui.focus.param`，MIUI SystemUI
  解析失败导致整条通知不渲染，连普通样式都没有，日志干净得像什么都没发生
- **通知渠道 id 只增不改**。importance / 震动 / 声音创建即锁死
- **不查 `areNotificationsEnabled` 的体检是假体检**
- **`getActiveNotifications()` 不能紧跟 `notify()` 同步查**，系统可能还没写进
  列表，会得到假阴性，至少要延迟几百毫秒

### 6.3 调试面板优先

排查手机端问题时，先把「能不能自证」做出来（体检项 + 失败原因回显 + 二分按钮），
再改业务代码。不要靠猜。

---

## 7. 用户偏好

- 交付文件一律给**完整绝对路径**，附大小、构建时间、MD5
- 命令里也用绝对路径，不让用户在相对路径上猜
- 输出精简，代码为主，不复述已知结论
