#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
离线解析测试 —— 不联网，用 HAR 抓包保存的真实响应验证解析逻辑。
这样即使 Cookie 过期也能确认代码正确。
"""

import io
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cust_client import parse_schedule, parse_day, WEEKDAY_CN

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(HERE, "fixtures")


def load(name):
    with io.open(os.path.join(FIX, name), encoding="utf-8") as f:
        return json.load(f)


def main():
    ok = True

    def check(label, actual, expected):
        nonlocal ok
        good = actual == expected
        ok = ok and good
        print("  [%s] %-42s 实际=%-6s 期望=%s" % ("PASS" if good else "FAIL", label, actual, expected))

    # ---------- 1. 课表解析 ----------
    print("=== 测试 1: 课表解析（schedule_type1.json）===")
    data = load("schedule_type1.json")["data"]
    courses = parse_schedule(data)

    print("  解析出 %d 条课程：\n" % len(courses))
    for c in courses:
        print("    %s" % c)
    print()

    check("课程总数", len(courses), 13)
    check("节次时间表取到", courses[0].time_start != "", True)

    # 连续节次必须合并：第1-2节是同一门大学英语A3
    eng = [c for c in courses if "大学英语A3" in c.name]
    check("大学英语A3 条目数（应合并为1条）", len(eng), 1)
    if eng:
        check("大学英语A3 节次范围", (eng[0].period_start, eng[0].period_end), (1, 2))
        check("大学英语A3 时间范围", (eng[0].time_start, eng[0].time_end), ("8:20", "10:00"))
        check("大学英语A3 星期", WEEKDAY_CN.get(eng[0].weekday), "周二")
        check("大学英语A3 教室", eng[0].classroom, "7-南507")

    # 体育课在周一 7-8 节
    pe = [c for c in courses if "体育" in c.name]
    check("体育课条数", len(pe), 1)
    if pe:
        check("体育课 星期", WEEKDAY_CN.get(pe[0].weekday), "周一")
        check("体育课 节次", (pe[0].period_start, pe[0].period_end), (7, 8))
        check("体育课 教师", pe[0].teacher, "周凡凯")

    # 空值 '-' 必须被清洗成空串
    check("空值老师已被清洗为空", all(c.teacher != "-" for c in courses), True)

    # 排序：按星期、节次
    sorted_ok = all(
        (courses[i].weekday, courses[i].period_start) <= (courses[i+1].weekday, courses[i+1].period_start)
        for i in range(len(courses) - 1)
    )
    check("按星期+节次排序", sorted_ok, True)

    # ---------- 2. 当天课程解析 ----------
    print("\n=== 测试 2: 当天课程解析（day_bz.json）===")
    day_data = load("day_bz.json")["data"]
    week_no, term, weekday, weekday_cn, day_courses = parse_day(day_data)

    print("  当前第 %d 周  学期 %s  %s" % (week_no, term, weekday_cn))
    for c in day_courses:
        print("    %s" % c)
    print()

    check("当前周次", week_no, 9)
    check("学期", term, "2026-2027-1")
    check("星期编号", weekday, 5)
    check("星期中文", weekday_cn, "星期四")
    check("当天课程数", len(day_courses), 4)
    if day_courses:
        check("当天第一节课程名", day_courses[0].name, "创业基础（创新思维）")
        check("当天第一节节次", (day_courses[0].period_start, day_courses[0].period_end), (1, 2))
        check("当天第一节时间", (day_courses[0].time_start, day_courses[0].time_end), ("8:20", "10:00"))
        check("当天第一节教室", day_courses[0].classroom, "7号楼/北栋-7-北502")

    # ---------- 3. 边界情况 ----------
    print("\n=== 测试 3: 边界情况 ===")
    check("空课表返回空列表", parse_schedule({}), [])
    check("空当天返回空列表", parse_day({})[4], [])
    empty = load("schedule_type2.json")["data"]
    check("全 '-' 的课表被过滤", len(parse_schedule(empty)), 0)

    print("\n" + "=" * 50)
    print("结果: %s" % ("全部通过" if ok else "有测试失败"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
