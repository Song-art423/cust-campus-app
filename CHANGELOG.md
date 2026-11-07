# 更新日志

本文件记录所有**已发布**版本（即打过 tag 的版本）。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

约定：每个版本条目必须写明对应的 APK **文件名、构建时间、MD5** ——
这是「安装包 ↔ 代码」之间唯一的硬链接，缺了它这个文件就只是流水账。

---

## [Unreleased]

### 待做
- 空教室查询（缺接口，需抓包）
- 寝室电量查询（缺接口，需抓包）
- 学习通作业列表（缺接口，需抓包）

---

## [0.1.0] - 2026-11-07

首个可用版本。**注意：真机端到端尚未验证**，标记为 0.1.0 是因为功能面完整，
而不是因为已确认稳定。

### 新增
- 整学期课表一次性全量拉取并落盘，日常打开零加载、无加载页
- 并发同步（4 路并行），同步过程静默，不打断浏览
- 三页面 Compose UI：概览、课表、我的
- 课表网格：按节次绝对偏移 + 跨节次自动合并
- 课前提醒：系统闹钟 + 广播接收器 + 高优先级通知
- 熄屏全屏提醒页（`AlarmActivity`），可在面板中开关
- 提醒调试面板：体检 → 3 秒预演 → 10 秒真链路 → 快进排程，四层递进，不用等真实上课时间
- 登录态持久化：`EncryptedSharedPreferences` + 网络拦截器捕获 Cookie 快照
- 提醒排程算法抽为纯函数，10 个单元测试覆盖边界（00:00 课程、跨节次连堂）

### 修复
- **通知被系统静默丢弃**：发送前先 `deleteNotificationChannel` 再 `createNotificationChannel`，
  新渠道尚未落库就 `notify()`，系统找不到渠道直接丢弃且不抛异常。
  改为渠道只增不删，删除动作挪到 App 启动时执行一次
- 体检漏查 `areNotificationsEnabled()`：运行时权限 granted 不代表能发通知，
  用户在系统设置关掉总开关后权限依然 granted，面板全绿却一条都发不出去
- Cookie 持久化失效：应用层拦截器看不到重定向中途的 `Set-Cookie`，改用网络拦截器
- `AuthStore` 是死代码从未接线，登录态因此每次都要重登
- 闹钟残留：`cancelAll` 依赖缓存，缓存缺失时旧闹钟清不掉

### 移除
- 小米焦点通知（超级岛）相关代码。该能力需小米服务端白名单（企业认证 →
  上架商店 → 场景审核），个人开发者走不通，参数写对了系统也忽略

### APK
```
文件  dist/cust-campus-0.1.0-5-debug.apk
源码  tag v0.1.0 (c80cd5d)
构建  2026-11-07 23:08:36
体积  17.7 MB
MD5   a83c3f5e8793d0bec0111392f371fac7
```

[Unreleased]: https://github.com/Song-art423/cust-campus-app/compare/v0.1.0...develop
[0.1.0]: https://github.com/Song-art423/cust-campus-app/releases/tag/v0.1.0
