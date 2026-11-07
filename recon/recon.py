#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
教务系统侦察脚本 (recon)

用途
    在你已登录的统一身份认证会话下，探测教务系统页面结构，
    定位「个人课表 / 班级课表 / 空教室 / 电量查询」的真实接口与参数。

安全性
    Cookie 只从本地配置文件读取，脚本不向任何第三方发送数据。
    SSL 校验关闭（学校系统常用自签证书），仅影响本地调试。

用法
    1. cp config.example.json config.json
    2. 浏览器登录教务系统 -> F12 -> Network -> 任一请求 -> Request Headers -> 复制 Cookie
    3. 粘贴进 config.json 的 cookie 字段
    4. python recon.py
    5. 看 recon/report.md
"""

import argparse
import io
import json
import os
import re
import sys
import time
from collections import Counter
from datetime import datetime
from urllib.parse import urljoin, urlparse

import requests
import urllib3
from bs4 import BeautifulSoup

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

HERE = os.path.dirname(os.path.abspath(__file__))
DUMP_DIR = os.path.join(HERE, "dumps")
REPORT = os.path.join(HERE, "report.md")

# 已知目标：tls.ccsut.cn = 超星综合教学管理系统，学生登录走 auth.ccsut.cn 统一认证
CANDIDATES = [
    ("入口 / 根路径",          "/"),
    ("超星 / 管理端登录页",    "/admin/login"),
    ("超星 / 统一认证入口",    "/admin/caslogin"),
    ("超星 / 主框架",          "/admin/main"),
    ("超星 / 首页",            "/admin/index"),
    ("超星 / 学生空间",        "/admin/student/index"),
    ("超星 / 课表",            "/admin/timetable"),
    ("超星 / 课程",            "/admin/course"),
    ("超星 / 教室",            "/admin/classroom"),
    ("超星 / 我的课程",        "/admin/myCourse"),
]

# 系统指纹：命中关键词即判定
SIGNATURES = [
    ("超星综合教学管理系统",   ["超星", "chaoxing", "学习通", "综合教学管理"]),
    ("正方教务 (jwglxt)",      ["jwglxt", "正方软件", "xtgl/login_slogin", "gnmkdm"]),
    ("强智教务 (jsxsd)",       ["jsxsd", "强智科技", "xsMain.jsp", "xskb_list"]),
    ("金智教育 (wisedu)",      ["wisedu", "金智", "ehall"]),
    ("清华教育在线 (theol)",   ["theol", "清华教育在线"]),
    ("URP 综合教务",           ["urp", "综合教务管理系统"]),
    ("统一身份认证 CAS",       ["cas/login", "caslogin", "统一身份认证"]),
    ("aTrust 零信任网关",      ["atrust", "零信任", "sangfor"]),
]

UA_DEFAULT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)


def load_config(path):
    if not os.path.exists(path):
        sys.exit(
            "[!] 缺少配置文件: %s\n"
            "    请先复制 config.example.json 为 config.json 并填入 Cookie。" % path
        )
    with io.open(path, "r", encoding="utf-8") as f:
        cfg = json.load(f)

    if not cfg.get("cookie") or "PASTE_YOUR_COOKIE_HERE" in cfg.get("cookie", "") \
            or "粘贴" in cfg.get("cookie", ""):
        sys.exit("[!] 请在 config.json 中填入真实 Cookie（浏览器 F12 复制）。")
    return cfg


def make_session(cfg):
    s = requests.Session()
    s.verify = False
    s.headers.update({
        "User-Agent": cfg.get("user_agent") or UA_DEFAULT,
        "Cookie": cfg["cookie"].strip(),
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9",
        # 注意：不能默认带 X-Requested-With。超星这套系统看到 XHR 头会返回 JSON 空壳，
        # 而不是完整 HTML，导致所有页面都被误判成 1KB 的 SPA 兜底页。
    })
    return s


def probe(sess, url):
    """请求单个 URL，返回结构化结果。永不抛异常。"""
    t0 = time.time()
    try:
        r = sess.get(url, timeout=15, allow_redirects=True)
        elapsed = int((time.time() - t0) * 1000)
        r.encoding = r.apparent_encoding or "utf-8"
        text = r.text or ""
        return {
            "ok": True,
            "status": r.status_code,
            "final_url": r.url,
            "ctype": r.headers.get("Content-Type", ""),
            "len": len(text),
            "elapsed": elapsed,
            "text": text,
            "redirected": (r.url.rstrip("/") != url.rstrip("/")),
        }
    except Exception as e:
        return {
            "ok": False,
            "status": None,
            "final_url": url,
            "ctype": "",
            "len": 0,
            "elapsed": int((time.time() - t0) * 1000),
            "text": "",
            "error": "%s: %s" % (type(e).__name__, e),
        }


def is_valid(res):
    """只有 2xx 且响应体足够大，才算真正拿到了页面。"""
    return bool(
        res.get("ok")
        and res.get("status")
        and 200 <= res["status"] < 300
        and res.get("len", 0) > 200
    )


def title_of(text):
    m = re.search(r"<title[^>]*>(.*?)</title>", text, re.S | re.I)
    return m.group(1).strip()[:80] if m else ""


def detect_system(texts):
    """根据所有抓到的文本判定系统类型。"""
    blob = "\n".join(texts).lower()
    hits = []
    for name, keys in SIGNATURES:
        if any(k.lower() in blob for k in keys):
            hits.append(name)
    return hits


def is_login_page(text):
    t = text.lower()
    markers = 0
    if "验证码" in text or "captcha" in t:
        markers += 1
    if "登录" in text or "login" in t:
        markers += 1
    if 'type="password"' in t or "type='password'" in t:
        markers += 1
    return markers >= 2


def extract_links(text, base):
    """提取所有站内链接和 iframe，去重。"""
    soup = BeautifulSoup(text, "lxml")
    out = []
    seen = set()

    def add(href, label):
        if not href:
            return
        href = href.strip()
        if href.startswith(("javascript:", "#", "mailto:", "tel:")):
            return
        full = urljoin(base, href)
        if full in seen:
            return
        seen.add(full)
        out.append((label.strip()[:40] if label else "", full))

    for a in soup.find_all("a"):
        add(a.get("href"), a.get_text(" ", strip=True))
    for f in soup.find_all("iframe"):
        add(f.get("src"), "[iframe]")
    for f in soup.find_all("frame"):
        add(f.get("src"), "[frame]")
    for form in soup.find_all("form"):
        add(form.get("action"), "[form]")
    return out


def extract_forms(text):
    soup = BeautifulSoup(text, "lxml")
    forms = []
    for fm in soup.find_all("form"):
        fields = []
        for inp in fm.find_all(["input", "select", "textarea"]):
            fields.append({
                "name": inp.get("name") or inp.get("id") or "(无名)",
                "type": inp.get("type") or inp.name,
                "value": (inp.get("value") or "")[:40],
            })
        forms.append({
            "action": fm.get("action") or "",
            "method": (fm.get("method") or "get").upper(),
            "fields": fields,
        })
    return forms


def extract_tables(text):
    """找疑似课表 / 数据表格，输出表头。"""
    soup = BeautifulSoup(text, "lxml")
    found = []
    for i, tb in enumerate(soup.find_all("table")):
        rows = tb.find_all("tr")
        if len(rows) < 2:
            continue
        header_cells = rows[0].find_all(["th", "td"])
        header = [c.get_text(" ", strip=True)[:14] for c in header_cells]
        header = [h for h in header if h]
        if not header:
            continue
        joined = " ".join(header)
        score = sum(k in joined for k in ("星期一", "节次", "课程", "教师", "教室", "学分", "周次"))
        if score >= 1:
            sample = []
            for r in rows[1:3]:
                cells = [c.get_text(" ", strip=True)[:16] for c in r.find_all(["td", "th"])]
                if cells:
                    sample.append(cells)
            found.append({
                "index": i,
                "rows": len(rows),
                "header": header[:14],
                "score": score,
                "sample": sample,
            })
    found.sort(key=lambda x: -x["score"])
    return found[:5]


def save_dump(name, text):
    safe = re.sub(r"[^\w\-]+", "_", name)[:60]
    path = os.path.join(DUMP_DIR, "%s.html" % safe)
    with io.open(path, "w", encoding="utf-8", errors="ignore") as f:
        f.write(text)
    return path


def main():
    ap = argparse.ArgumentParser(description="教务系统页面侦察")
    ap.add_argument("--config", default=os.path.join(HERE, "config.json"))
    ap.add_argument("--url", action="append", default=[], help="额外要探测的完整 URL")
    args = ap.parse_args()

    os.makedirs(DUMP_DIR, exist_ok=True)
    cfg = load_config(args.config)
    sess = make_session(cfg)
    base_root = cfg["base_url"]
    host_root = "{0.scheme}://{0.netloc}".format(urlparse(base_root))

    print("[*] 目标: %s" % base_root)
    print("[*] 开始探测 %d 个候选路径 + %d 个自定义 URL\n" % (len(CANDIDATES), len(args.url) + len(cfg.get("extra_urls") or [])))

    targets = [(n, urljoin(base_root, p)) for n, p in CANDIDATES]
    for u in (cfg.get("extra_urls") or []):
        if u and u.startswith(("http://", "https://")):
            targets.append(("自定义", u))
    for u in args.url:
        targets.append(("命令行", u))

    results = []
    all_texts = []
    for idx, (name, url) in enumerate(targets, 1):
        res = probe(sess, url)
        res["name"] = name
        res["url"] = url
        results.append(res)
        if res["ok"] and res["text"]:
            all_texts.append(res["text"])
            flag = "OK " if is_valid(res) else "-- "
            print("  [%02d/%02d] %s %-22s %4s %6dB %5dms" % (
                idx, len(targets), flag, name,
                res["status"], res["len"], res["elapsed"]))
        else:
            print("  [%02d/%02d] ERR %-22s %s" % (idx, len(targets), name, res.get("error", "")))
        time.sleep(0.35)  # 对学校服务器客气点

    # ---------- 分析 ----------
    systems = detect_system(all_texts)
    ok_results = [r for r in results if is_valid(r)]
    failed = [r for r in results if not is_valid(r)]

    # catch-all 检测：多个不同路径返回同样大小的页面 -> SPA 兜底路由，前端接管了路由
    # 只看长度不看内容：SPA 空壳里常带随机 token，内容 hash 每次都不同
    lens = Counter(r["len"] for r in ok_results)
    dominant_len, dominant_cnt = lens.most_common(1)[0] if lens else (0, 0)
    is_catchall = len(ok_results) >= 4 and dominant_cnt >= max(4, int(len(ok_results) * 0.6))

    home = next((r for r in results if r["name"].startswith("入口 / 根路径")), None)
    home_valid = bool(home and is_valid(home))
    home_text = home["text"] if home_valid else ""

    if is_catchall and not is_login_page(home_text):
        # 所有路径都返回同一份非登录页 -> 服务端不区分路由，登没登录看不出来
        login_state = "无法判定 —— SPA 兜底路由，所有路径返回同一份空壳"
        logged_in = None
    elif not home_valid:
        login_state = "无法判定 —— 根路径没取到有效页面"
        logged_in = False
    elif is_login_page(home_text):
        if is_catchall:
            login_state = "未登录 / Cookie 失效（所有路径都落到登录页）"
        else:
            login_state = "未登录 / Cookie 失效"
        logged_in = False
    else:
        login_state = "**已登录（Cookie 有效）**"
        logged_in = True

    need_tunnel = any(
        "aTrust" in (r.get("text") or "") or "零信任" in (r.get("text") or "")
        for r in results if r["ok"]
    )

    # SPA 判定
    total_links = sum(len(extract_links(r["text"], r["final_url"])) for r in ok_results)
    biggest = max((r["len"] for r in ok_results), default=0)
    is_spa = is_catchall or (biggest > 15000 and total_links < 6)

    # ---------- 写报告 ----------
    L = []
    a = L.append
    a("# 教务系统侦察报告\n")
    a("> 生成时间：%s\n" % datetime.now().strftime("%Y-%m-%d %H:%M:%S"))

    a("## 1. 环境检测\n")
    a("| 项目 | 结果 |")
    a("|---|---|")
    a("| 探测目标 | `%s` |" % base_root)
    a("| 有效页面数 (200 且 >200B) | %d / %d |" % (len(ok_results), len(results)))
    a("| 认证状态 | %s |" % login_state)
    a("| 零信任网关 | %s |" % ("检测到 aTrust 相关页面（不一定影响教务系统）" if need_tunnel else "未见拦截"))
    a("| 页面渲染方式 | %s |" % ("**前端渲染 SPA** —— 数据走 XHR 接口" if is_spa else "服务端渲染 —— 可直接解析 HTML"))
    a("| 系统指纹 | %s |" % ("、".join(systems) if systems else "未识别"))
    a("")

    if is_catchall:
        a("> [!IMPORTANT]")
        a("> 检测到 SPA 兜底路由：%d 个不同路径返回了同样 %dB 的页面。" % (dominant_cnt, dominant_len))
        a("> 前端接管了路由，服务端不区分这些 URL。**硬扫路径没有意义，必须直接对接 XHR 接口。**\n")

    if need_tunnel:
        a("> [!NOTE]")
        a("> 出现了 aTrust 零信任网关页面。这只说明学校有这套网关，**不代表教务系统必须走它**。")
        a("> 判断标准很简单：如果第 2 节里教务系统页面能返回 200 且长度正常，就说明公网可直连，不需要校园网。\n")

    if is_spa:
        a("> [!IMPORTANT]")
        a("> 判定为前端渲染页面：HTML 是空壳，课表数据靠 JS 异步请求 XHR 接口拿。")
        a("> 这种情况下脚本抠不到链接和表格，**必须手动抓一条真实请求**：")
        a("> F12 → Network → Fetch/XHR → 刷新课表页 → 找到返回课程数据的请求 → 右键 Copy as cURL，发给开发同学。\n")

    if logged_in is False:
        a("> [!IMPORTANT]")
        a("> Cookie 似乎无效或已过期。请重新登录教务系统并复制最新 Cookie 后再跑一次。\n")

    a("## 2. 路径扫描结果\n")
    a("| 名称 | 状态 | 长度 | 耗时 | 最终地址 | 标题 |")
    a("|---|---|---|---|---|---|")
    for r in results:
        if is_valid(r):
            a("| %s | %s | %d | %dms | `%s` | %s |" % (
                r["name"], r["status"], r["len"], r["elapsed"],
                r["final_url"][:90], title_of(r["text"]) or "-"))
        elif r["ok"]:
            a("| %s | **%s** | %d | %dms | `%s` | 无效响应 |" % (
                r["name"], r["status"], r["len"], r["elapsed"], r["final_url"][:90]))
        else:
            a("| %s | ERR | - | %dms | - | %s |" % (r["name"], r["elapsed"], r.get("error", "")[:60]))
    a("")
    if failed:
        a("> %d 个路径未命中。**这是正常的**——路径是推测的，系统改版或用了别的路由就会 404。"
          "只要第 2 节里有 200 的页面，就说明网络和 Cookie 都没问题，以实际抓到的为准。\n" % len(failed))

    # 链接汇总
    all_links = []
    seen_links = set()
    for r in ok_results:
        for label, full in extract_links(r["text"], r["final_url"]):
            if full in seen_links:
                continue
            seen_links.add(full)
            all_links.append((r["name"], label, full))

    useful = [x for x in all_links
              if any(k in x[2].lower() for k in
                     ("kb", "kbcx", "cdjy", "classroom", "schedule", "course", "课表", "教室", "xnxq", "xq"))]

    a("## 3. 命中的业务链接（课表 / 教室相关）\n")
    if useful:
        a("| 来源页 | 链接文字 | URL |")
        a("|---|---|---|")
        for src, label, full in useful[:60]:
            a("| %s | %s | `%s` |" % (src, label or "-", full[:130]))
    else:
        a("_未发现课表/教室相关链接。可能是 SPA 动态渲染，需要抓 XHR 接口（见第 6 节）。_")
    a("")

    a("## 4. 全部链接（前 40 条，供人工辨认）\n")
    if all_links:
        for src, label, full in all_links[:40]:
            a("- `%s`  %s  (%s)" % (full[:120], label or "", src))
    else:
        a("_无_")
    a("")

    a("## 5. 表单结构\n")
    has_form = False
    for r in ok_results:
        for fm in extract_forms(r["text"]):
            if not fm["fields"]:
                continue
            has_form = True
            a("**来源：%s** → action=`%s` method=%s" % (r["name"], fm["action"], fm["method"]))
            a("")
            a("| 字段名 | 类型 | 默认值 |")
            a("|---|---|---|")
            for f in fm["fields"]:
                a("| `%s` | %s | %s |" % (f["name"], f["type"], f["value"] or "-"))
            a("")
    if not has_form:
        a("_未发现有效表单（可能是纯前端渲染）_\n")

    a("## 6. 疑似数据表格\n")
    any_table = False
    for r in ok_results:
        for tb in extract_tables(r["text"]):
            any_table = True
            a("**来源：%s** — 第 %d 个表格，%d 行，匹配分 %d" % (r["name"], tb["index"], tb["rows"], tb["score"]))
            a("")
            a("表头：`%s`" % " | ".join(tb["header"]))
            if tb["sample"]:
                a("")
                a("样例行：%s" % json.dumps(tb["sample"][0], ensure_ascii=False)[:200])
            a("")
    if not any_table:
        a("_未发现课表类表格。若系统是 SPA，数据一定走 XHR 接口，需要下一步抓包。_\n")

    a("## 7. 下一步\n")
    a("把 `report.md` 和 `dumps/` 目录发我，我会：")
    a("")
    a("1. 确认系统类型和真实接口地址")
    a("2. 写出能稳定拿到课表 / 空教室 / 电量的解析代码")
    a("3. 再动手写安卓端")
    a("")
    a("若第 3、6 节为空，说明页面由 JS 动态渲染，需要在浏览器 F12 -> Network -> Fetch/XHR 里")
    a("手动找一次课表请求，把那条请求的 **URL + 请求参数 + 响应 JSON** 复制给我。\n")

    with io.open(REPORT, "w", encoding="utf-8") as f:
        f.write("\n".join(L))

    # 存 dump
    for r in ok_results:
        save_dump(r["name"], r["text"])

    print("\n[+] 系统指纹: %s" % ("、".join(systems) if systems else "未识别"))
    print("[+] 认证状态: %s" % login_state.replace("**", ""))
    if is_spa:
        print("[!] 判定为 SPA 前端渲染 —— 必须手动抓 XHR 接口")
    if need_tunnel:
        print("[i] 检测到 aTrust 页面，但教务系统本身公网可直连就没影响")
    print("[+] 报告: %s" % REPORT)
    print("[+] 页面存档: %s" % DUMP_DIR)


if __name__ == "__main__":
    main()
