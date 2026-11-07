# 登录态机制与「免重复登录」方案

> 全部结论来自真实请求实测，不是推测。

## 一、核心发现：服务端有 14 天滑动续期

用真实 Cookie 请求 `tls.ccsut.cn`，读响应头 `Set-Cookie`：

```
Max-Age=1209600, Expires=Fri, 20 Nov 2026 08:33:42 GMT
Max-Age=14400,   Expires=Fri, 06 Nov 2026 12:33:42 GMT
Max-Age=0,       Expires=Thu, 05 Nov 2026 08:33:42 GMT
```

| Max-Age | 折算 | 含义 |
|---|---|---|
| `1209600` | **14 天** | 长效登录凭据 |
| `14400` | 4 小时 | 短期会话状态 |
| `0` | 立即失效 | 服务端主动清理的废弃项 |

关键点：**每次请求都会把这个 14 天重新刷新一遍**（滑动过期 / sliding expiration）。

也就是说：

> 只要 14 天内打开过一次这个 App，登录态就永远不会掉。
> 一年不用的话，需要重新登录一次——这个频率与微信「长时间未登录需验证」同级，属于正常范围。

## 二、Cookie 子集实验：到底谁在维持登录

把 Cookie 拆成不同子集分别请求 `/admin/?loginType=1`，看是否仍被识别为已登录：

| Cookie 组合 | 结果 |
|---|---|
| 完整 Cookie（基线） | ✅ 有效 |
| **去掉 JSESSIONID** | ✅ **仍有效** |
| 只有 JSESSIONID | ❌ 303 → 登录页 |
| 只有 puid | ❌ 303 |
| 只有 puid + route | ❌ 303 |
| 只有 twiceAuthSign | ❌ 303 |
| puid + route + twiceAuthSign | ❌ 303 |
| **只有 `jw_uf*` 系列** | ✅ **有效** |

结论：

- **`jw_uf*` 才是真正的登录凭据**，不是 JSESSIONID，也不是 puid / route / twiceAuthSign
- JSESSIONID 是可有可无的，会话断了不影响
- `jw_uf_d` 存的是登录时刻的毫秒时间戳，可用于推算凭据年龄

这意味着「Cookie 过期」这件事比直觉上宽松得多——短命的是不重要的那部分。

## 三、登录态探针

```
GET https://tls.ccsut.cn/admin/?loginType=1
```

| 情况 | 响应 |
|---|---|
| 已登录 | `200`，约 265KB，HTML 中含 `admin_name` 节点 |
| 未登录 | `303`，`Location: /admin/login`，0 字节 |

判定逻辑极其干净，**不需要解析 JSON**，看状态码 + 一个 class 名就够：

```kotlin
r.code == 200 && body.contains("admin_name")
```

成本实测：**1.4KB / 98ms**（用 `getDayBz` 更小）。保活请求可以放心打。

## 四、三层方案

### 第 1 层：WebView 一次登录，之后不用再填

- 用户在 App 内嵌 WebView 里登录一次（该填验证码就填，走网页原生流程）
- Cookie 由系统 `CookieManager` 落盘保管，**App 卸载重装前不会丢**
- OkHttp 通过 `CookieBridge` 拦截器直接复用这批 Cookie
- 服务端下发的新 `Set-Cookie` 立刻写回 `CookieManager`，两边始终一致

关键实现：`CookieBridge.kt`。好处是不用手拼 Cookie 字符串，也不怕 JSESSIONID 轮换。

### 第 2 层：后台保活，让它根本不过期

`KeepAliveWorker` 每 **6 小时**轻碰一次探针接口。

- 6 小时 ≪ 14 天，余量极充足
- 成本：一天 4 次 × 1.4KB ≈ 6KB/天，可忽略
- 实际效果：用户永远碰不到过期

### 第 3 层：静默续期（零操作兜底）

万一会话还是断了（换设备、学校清 token），`silentRenew()` 走一遍 CAS 票据流程：

```
GET /admin/caslogin
  → 302 auth.ccsut.cn/backstage/cas/login?service=...
  → CAS 认出 auth 域下的 CASTGC 有效
  → 302 回 /admin/caslogin?ticket=ST-xxx
  → 教务校验 ticket，下发新会话
```

全程由 `CookieBridge` 自动搬运 Cookie，**用户零操作、连界面都看不到**。

只有 CAS 票据本身也过期了才会落到登录页，此时返回 `false`，走最后兜底。

### 兜底：真需要手动时也不麻烦

- 发一条通知「点一下，通常会自动登录」
- 点开是 WebView，多数情况 CAS 直接放行，**不用输密码**
- 真要输：账号是记住的，Android 自动填充可代填密码

## 五、现实预期

| 场景 | 用户需要做什么 |
|---|---|
| 日常使用 | 无 |
| 后台 App 被系统杀掉 | 无（下次打开自动续） |
| 超过 14 天没打开 App | 无（打开即用，服务端凭据还没到 14 天） |
| 超过 14 天且期间无任何请求 | 可能需点一次通知，通常自动登录成功 |
| 学校主动清理凭据 / 改密码 | 重新登录一次 |

一句话：**正常使用下，一年可能一次都不用重新填验证码。**

## 六、已知限制

1. **小米的省电策略会压制 WorkManager**。用户若开启了「严格省电」，后台保活可能几天不跑。缓解方式：
   - 引导用户把 App 加入「无限制」后台白名单
   - App 每次打开始终先探活并刷新一次
2. **静默续期依赖 CAS 的 CASTGC Cookie**。首次登录必须走 WebView 才能把它存下来，纯手工贴 Cookie 是拿不到 TGC 的。
3. **服务端改了 `Max-Age`**（比如收紧到 1 天）会让保活间隔需要重算。改代码里的 `INTERVAL_HOURS` 即可。
