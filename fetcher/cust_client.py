#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
超星综合教学管理系统 数据客户端

已实测可用的接口（来源：HAR 抓包 + 首页 JS 逆向）：

    GET  /admin/getDayBz?rq=YYYY-MM-DD   -> data.xlrq.zc 当前周次；data.bzList 当天课程
    GET  /admin/getCurrentPkZc           -> 全部教学周次 ["1".."20"]
    POST /admin/getXqByZc   (zc)         -> 该周的日期与星期对应
    POST /admin/getXsdSykb  (type, zc)   -> 完整课表（type=1 本周，type=2 另一周）

认证：仅 Cookie。必须带 X-Requested-With: XMLHttpRequest，否则服务端返回空壳。

用法：
    c = CustClient(open("cookie.txt").read().strip())
    print(c.get_today())
    for course in c.get_courses(9):
        print(course)
"""

from dataclasses import dataclass, asdict
from datetime import date as Date
from typing import Any, Dict, List, Optional

import requests
import urllib3

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

BASE = "https://tls.ccsut.cn"

# 节次时间表，从课表接口的 kcxx 中提取，作为兜底
DEFAULT_PERIODS = {
    1: ("8:20", "9:05"),
    2: ("9:15", "10:00"),
    3: ("10:20", "11:05"),
    4: ("11:15", "12:00"),
    5: ("14:00", "14:45"),
    6: ("14:55", "15:40"),
    7: ("16:00", "16:45"),
    8: ("16:55", "17:40"),
    9: ("19:00", "19:45"),
    10: ("19:55", "20:40"),
}

WEEKDAY_CN = {1: "周一", 2: "周二", 3: "周三", 4: "周四", 5: "周五", 6: "周六", 7: "周日"}


class CustError(Exception):
    """接口返回 ret != 0"""


class AuthError(CustError):
    """登录态失效（401 或重定向到登录页）"""


@dataclass
class Course:
    name: str
    teacher: str
    classroom: str
    weekday: int          # 1-7
    period_start: int     # 起始节次
    period_end: int       # 结束节次
    time_start: str       # "8:20"
    time_end: str         # "10:00"

    def __str__(self):
        return "%s %s 第%d-%d节 (%s-%s) %s @ %s" % (
            WEEKDAY_CN.get(self.weekday, "?"), self.name, self.period_start,
            self.period_end, self.time_start, self.time_end, self.teacher, self.classroom)


@dataclass
class DayCourse:
    """当天课程（来自 getDayBz，比课表接口更适合做提醒）"""
    name: str
    teacher: str
    classroom: str
    period_start: int
    period_end: int
    time_start: str
    time_end: str

    def __str__(self):
        return "%s 第%d-%d节 (%s-%s) %s @ %s" % (
            self.name, self.period_start, self.period_end,
            self.time_start, self.time_end, self.teacher, self.classroom)


@dataclass
class DayInfo:
    week_no: int                # 当前教学周次
    term: str                   # 学期，如 2026-2027-1
    date: str                   # YYYY-MM-DD
    weekday: int                # 1-7
    weekday_cn: str
    courses: List[DayCourse]


# ---------------------------------------------------------------- 纯解析函数
# 与网络解耦，便于用 fixture 离线验证

def _period_times(schedule_data: Dict[str, Any]) -> Dict[int, tuple]:
    """从课表响应里提取节次时间表，失败则用内置兜底"""
    out = dict(DEFAULT_PERIODS)
    for j in schedule_data.get("jcKcxx") or []:
        try:
            no = int(j.get("jcbm"))
        except (TypeError, ValueError):
            continue
        start, end = j.get("kssj"), j.get("jssj")
        if start and end:
            out[no] = (start, end)
    return out


def _clean(v: Any) -> str:
    """接口里用 '-' 表示空值"""
    if v is None:
        return ""
    s = str(v).strip()
    return "" if s in ("-", "_null_", "null") else s


def parse_schedule(data: Dict[str, Any]) -> List[Course]:
    """
    解析 getXsdSykb 的 data 字段 -> 课程列表（已合并连续节次）

    原始结构：
        jcKcxx: [{jcbm:"1", kssj:"8:20", jssj:"9:05",
                  kbxx: [{yzxq:"2", kcxx:[{kcmc, teacher, classroom}]}]}]
    同一门课连上两节时，会出现在 jcbm=1 和 jcbm=2 两处，这里合并成一条。
    """
    periods = _period_times(data)

    # (weekday, name, teacher, classroom) -> [节次...]
    raw: Dict[tuple, List[int]] = {}
    for j in data.get("jcKcxx") or []:
        try:
            period = int(j.get("jcbm"))
        except (TypeError, ValueError):
            continue
        for kb in j.get("kbxx") or []:
            try:
                weekday = int(kb.get("yzxq"))
            except (TypeError, ValueError):
                continue
            if not 1 <= weekday <= 7:
                continue
            for kc in kb.get("kcxx") or []:
                name = _clean(kc.get("kcmc"))
                if not name:
                    continue
                key = (weekday, name, _clean(kc.get("teacher")), _clean(kc.get("classroom")))
                raw.setdefault(key, []).append(period)

    courses = []
    for (weekday, name, teacher, classroom), periods_hit in raw.items():
        periods_hit = sorted(set(periods_hit))
        # 把连续节次切成段，例如 [1,2,5] -> [1-2, 5]
        seg_start = prev = periods_hit[0]
        segments = []
        for p in periods_hit[1:]:
            if p == prev + 1:
                prev = p
                continue
            segments.append((seg_start, prev))
            seg_start = prev = p
        segments.append((seg_start, prev))

        for a, b in segments:
            courses.append(Course(
                name=name, teacher=teacher, classroom=classroom, weekday=weekday,
                period_start=a, period_end=b,
                time_start=periods.get(a, ("", ""))[0],
                time_end=periods.get(b, ("", ""))[1],
            ))

    courses.sort(key=lambda c: (c.weekday, c.period_start, c.name))
    return courses


def parse_day(data: Dict[str, Any]) -> tuple:
    """解析 getDayBz 的 data 字段 -> (week_no, term, weekday, weekday_cn, [DayCourse])"""
    xlrq = data.get("xlrq") or {}
    periods = dict(DEFAULT_PERIODS)

    courses = []
    for item in data.get("bzList") or []:
        name = _clean(item.get("title"))
        if not name:
            continue
        try:
            a = int(item.get("jcst"))
            b = int(item.get("jcnd"))
        except (TypeError, ValueError):
            a = b = 0
        courses.append(DayCourse(
            name=name,
            teacher=_clean(item.get("bz")),
            classroom=_clean(item.get("address")),
            period_start=a, period_end=b,
            time_start=periods.get(a, ("", ""))[0],
            time_end=periods.get(b, ("", ""))[1],
        ))
    courses.sort(key=lambda c: c.period_start)

    return (
        int(xlrq.get("zc") or 0),
        _clean(xlrq.get("xnxqh")),
        int(xlrq.get("xqbh") or 0),
        _clean(xlrq.get("xqmc")),
        courses,
    )


# ---------------------------------------------------------------- 网络客户端

class CustClient:
    def __init__(self, cookie: str, timeout: int = 15):
        if not cookie or "PASTE" in cookie or "粘贴" in cookie:
            raise ValueError("Cookie 为空或仍是占位符")
        self.timeout = timeout
        self.s = requests.Session()
        self.s.verify = False
        self.s.headers.update({
            "User-Agent": ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                           "AppleWebKit/537.36 (KHTML, like Gecko) "
                           "Chrome/131.0.0.0 Safari/537.36"),
            "Cookie": cookie.strip(),
            # 这个头是必需的，服务端靠它区分 AJAX 请求
            "X-Requested-With": "XMLHttpRequest",
            "Accept": "application/json, text/javascript, */*; q=0.01",
            "Referer": BASE + "/admin/?loginType=1",
        })

    # -- 底层 --
    def _get(self, path: str, params: Optional[dict] = None) -> Dict[str, Any]:
        return self._do("GET", path, params=params)

    def _post(self, path: str, data: Optional[dict] = None) -> Dict[str, Any]:
        return self._do("POST", path, data=data)

    def _do(self, method: str, path: str, **kw) -> Dict[str, Any]:
        url = BASE + path
        try:
            r = self.s.request(method, url, timeout=self.timeout, allow_redirects=False, **kw)
        except requests.RequestException as e:
            raise CustError("请求失败: %s" % e)

        if r.status_code in (301, 302, 303, 307, 308):
            raise AuthError("登录态已失效（被重定向到 %s）" % r.headers.get("Location", "登录页"))
        if r.status_code == 401:
            raise AuthError("登录态已失效（401）")
        if r.status_code != 200:
            raise CustError("HTTP %d: %s" % (r.status_code, r.text[:120]))

        try:
            payload = r.json()
        except ValueError:
            raise CustError("返回的不是 JSON，可能被重定向到登录页。前 120 字符: %s" % r.text[:120])

        if payload.get("ret") != 0:
            raise CustError("接口返回异常: ret=%s msg=%s" % (payload.get("ret"), payload.get("msg")))
        return payload

    # -- 高层接口 --
    def get_day(self, day: Optional[Date] = None) -> DayInfo:
        """当天课程 + 当前教学周次。做上课提醒用这个接口最省事。"""
        d = day or Date.today()
        payload = self._get("/admin/getDayBz", params={"rq": d.strftime("%Y-%m-%d")})
        week_no, term, weekday, weekday_cn, courses = parse_day(payload.get("data") or {})
        return DayInfo(week_no=week_no, term=term, date=d.strftime("%Y-%m-%d"),
                       weekday=weekday, weekday_cn=weekday_cn, courses=courses)

    def get_all_weeks(self) -> List[int]:
        payload = self._get("/admin/getCurrentPkZc")
        return [int(x) for x in (payload.get("data") or []) if str(x).isdigit()]

    def get_week_dates(self, zc: int) -> List[Dict[str, Any]]:
        payload = self._post("/admin/getXqByZc", data={"zc": zc})
        return payload.get("data") or []

    def get_schedule_raw(self, zc: int, type_: int = 1) -> Dict[str, Any]:
        payload = self._post("/admin/getXsdSykb", data={"type": type_, "zc": zc})
        return payload.get("data") or {}

    def get_courses(self, zc: int, type_: int = 1) -> List[Course]:
        return parse_schedule(self.get_schedule_raw(zc, type_))


def main():
    import argparse
    import json

    ap = argparse.ArgumentParser(description="超星教务数据客户端")
    ap.add_argument("--cookie", help="Cookie 字符串；不给则读 cookie.txt")
    ap.add_argument("--zc", type=int, help="查询第几周，默认自动取当前周")
    ap.add_argument("--json", action="store_true", help="输出 JSON")
    args = ap.parse_args()

    cookie = args.cookie
    if not cookie:
        try:
            with open("cookie.txt", encoding="utf-8") as f:
                cookie = f.read().strip()
        except FileNotFoundError:
            raise SystemExit("缺少 Cookie：用 --cookie 传入，或在当前目录建 cookie.txt")

    c = CustClient(cookie)

    day = c.get_day()
    zc = args.zc or day.week_no
    courses = c.get_courses(zc)

    if args.json:
        print(json.dumps({
            "week": zc, "term": day.term, "today": [asdict(x) for x in day.courses],
            "schedule": [asdict(x) for x in courses],
        }, ensure_ascii=False, indent=2))
        return

    print("学期: %s   当前第 %d 周   今天: %s %s" % (day.term, day.week_no, day.date, day.weekday_cn))
    print("\n=== 今天的课 ===")
    if day.courses:
        for x in day.courses:
            print("  ", x)
    else:
        print("   今天没课")

    print("\n=== 第 %d 周完整课表 ===" % zc)
    if courses:
        for x in courses:
            print("  ", x)
    else:
        print("   本周无课")


if __name__ == "__main__":
    main()
