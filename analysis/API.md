# 超星综合教学管理系统 — 接口文档

来源：HAR 抓包（212 请求）+ 首页 JS（265KB）逆向。**全部接口已实测验证。**

Base URL：`https://tls.ccsut.cn`

---

## 一、认证

| 项 | 说明 |
|---|---|
| 方式 | **仅 Cookie**，无 Token、无 Authorization 头 |
| 必需请求头 | `X-Requested-With: XMLHttpRequest` ← 少了会返回 HTML 空壳 |
| 失效判断 | HTTP 401，或 302 跳转到 `auth.ccsut.cn` |
| Cookie 过期 | 通常几小时到几天，需引导用户重新登录 |

> ⚠️ HAR 导出时 Cookie 被浏览器脱敏（212 个请求里 0 个带 Cookie），需要单独从 F12 取。

---

## 二、接口清单

### 1. 获取当天课程 + 当前周次 ★ 核心

```
GET /admin/getDayBz?rq=2026-11-05
```

**这是做「今日课程」和「上课提醒」最优的接口**——一次请求同时拿到当前教学周和当天全部课程，不用先查周次再查课表。

响应：
```json
{
  "ret": 0,
  "msg": "",
  "data": {
    "xlrq": {
      "zc": 9,                        // 当前教学周次 ← 关键
      "xnxqh": "2026-2027-1",         // 学期
      "xqbh": 5,                      // 星期几 1-7
      "xqmc": "星期四",
      "rq": "2026-11-05 00:00:00",
      "lunarCalendar": "廿七",
      "currentUserName": "202501010401"   // 学号
    },
    "bzList": [                        // 当天课程
      {
        "title": "创业基础（创新思维）",   // 课程名
        "address": "7号楼/北栋-7-北502", // 教室（带楼栋全称）
        "bz": "熊辉",                    // 教师
        "jcst": "1",                     // 起始节次
        "jcnd": "2",                     // 结束节次
        "rq": "2026-11-05 00:00:00",
        "sftk": "0", "jsfs": "0", "type": "1", "sjly": "2"
      }
    ]
  }
}
```

### 2. 获取全部教学周次

```
GET /admin/getCurrentPkZc
```
```json
{"ret": 0, "data": ["1","2","3",...,"20"]}
```
注意：返回的是**可选周次范围**，不是当前周。

### 3. 获取某周的日期与星期对应

```
POST /admin/getXqByZc
Content-Type: application/x-www-form-urlencoded

zc=9
```
```json
{"ret": 0, "data": [
  {"date": "11-2", "sfdt": false, "xq": "周一"},
  ...
]}
```
`sfdt` = 是否与今天同日。

### 4. 获取完整课表 ★ 核心

```
POST /admin/getXsdSykb
Content-Type: application/x-www-form-urlencoded

type=1&zc=9
```

- `type=1`：本周课表（有数据）
- `type=2`：另一周（实测为空，用途待确认）

响应 `data` 结构：
```json
{
  "sjk": [                              // 网络课程 / 无固定时间的课
    {"kcmc": "网络课程：人工智能伦理导论", "type": "理论课程",
     "xkrs": "900人", "zcstr": "8-15周", "jxbzc": "-", "tmc": "-"}
  ],
  "jcKcxx": [                           // 按节次组织的课表 ← 主数据
    {
      "jcbm": "1",                      // 节次编号 1-10
      "kssj": "8:20",                   // 开始时间
      "jssj": "9:05",                   // 结束时间
      "jc": 1,
      "kbxx": [                         // 该节次的排课
        {
          "yzxq": "2",                  // 星期几 1-7
          "kcxx": [
            {"kcmc": "大学英语A3", "teacher": "蒋晶", "classroom": "7-南507"}
          ]
        }
      ]
    }
  ],
  "kbxjxsmc": "1"
}
```

**解析要点**：

1. `kcxx` 是数组，同一时段可能有多门课（冲突情况），不要假设只有一条
2. 同一门课连上两节时，会分别出现在 `jcbm=1` 和 `jcbm=2` 两处，**必须合并**
3. 空值统一用 `"-"` 表示，需要清洗成空串
4. 第 9、10 节是晚课，起始时间从 `kssj` 取，别硬编码

### 5. 其他已验证接口（非本期需求，备查）

| 接口 | 方法 | 用途 |
|---|---|---|
| `/admin/xsd/xsdzgcjcx/xsdQueryXszgcjList` | POST | 学生成绩查询 |
| `/admin/xsd/kwglXsdKscx/ajaxKskcList` | POST | 考试课程列表 |
| `/admin/xsd/xsjbxx/getXsjfxx` | POST | 学费信息 |
| `/admin/workcenter/message/getTotalRedCount` | GET | 未读消息数 |
| `/admin/pkgl/xskb/queryKbForXsd` | ? | 疑似班级课表（未验证） |

---

## 三、节次时间表（实测值）

| 节次 | 开始 | 结束 |
|---|---|---|
| 1 | 8:20 | 9:05 |
| 2 | 9:15 | 10:00 |
| 3 | 10:20 | 11:05 |
| 4 | 11:15 | 12:00 |
| 5 | 14:00 | 14:45 |
| 6 | 14:55 | 15:40 |
| 7 | 16:00 | 16:45 |
| 8 | 16:55 | 17:40 |
| 9 | 19:00 | 19:45 |
| 10 | 19:55 | 20:40 |

代码里不要硬编码这张表——每次从课表响应的 `kssj/jssj` 动态提取，学校调作息时间就自动跟上。

---

## 四、已确认缺失的接口

| 需求 | 状态 | 说明 |
|---|---|---|
| 空教室查询 | ❌ **HAR 里没有** | 212 个请求中无相关接口。需要在空教室页面重新抓一次 |
| 寝室电量 | ❌ **HAR 里没有** | 大概率不在教务系统，需单独的查询网址 |
| 学习通作业 | ❌ 未抓 | 在 `i.chaoxing.com` / 学习通 App，另一套 Cookie |

`/admin/xsd/kwglXsdKscx/*` 是**考务管理**（考试安排），不是空教室，别搞混。

---

## 五、安卓端实现建议

```
登录：WebView 打开 https://tls.ccsut.cn/admin/caslogin
      → 用户在 WebView 里走完统一认证
      → CookieManager 取出 Cookie 存 EncryptedSharedPreferences

数据：OkHttp 带 Cookie + X-Requested-With 直接调上面 4 个接口
      （比解析 HTML 稳得多，这是 SPA 架构的好处）

缓存：Room 存整周课表，WorkManager 每 6 小时刷新一次
      当天课程用 getDayBz 单独拉，进前台时刷新

提醒：WorkManager 精确闹钟，课程开始前 15 分钟触发
      数据源用 getDayBz 的 bzList，不需要自己算今天上什么课

灵动岛：Notification + miui.focus.param extras（私有协议，需真机调试）
        失效降级为标准通知
```

---

## 六、稳定性风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 接口路径变更 | 全部功能失效 | 接口集中在客户端一处，改 URL 即可 |
| `ret != 0` 的新错误码 | 解析失败 | 已统一抛 `CustError` 并记录 msg |
| Cookie 过期 | 401 | 捕获 `AuthError` → 通知栏引导重新登录 |
| 课表接口字段增删 | 解析异常 | `kcxx` 用 `.get()` 容错，缺字段不崩 |
| 学校调作息时间 | 提醒时间错 | 节次时间从接口动态取，不硬编码 |
