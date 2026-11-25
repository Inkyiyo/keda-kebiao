# -*- coding: utf-8 -*-
"""演示数据：一份完整的计算机专业本科课表，用于未登录时预览界面。"""

from __future__ import annotations

import datetime as dt

# (课程, 教师, 地点, 星期, 节次, 周次规则)
COURSES = [
    ("高等数学 A(2)", "刘建平", "十教 205", 1, (3, 4), "1-16"),
    ("数据结构", "陈 立", "八教 302", 1, (5, 6), "1-16"),
    ("形势与政策(四)", "何 嘉", "七教 102", 1, (9, 10), "1-8"),
    ("大学物理 B(1)", "王慧敏", "九教 101", 2, (1, 2), "1-16"),
    ("离散数学", "赵明月", "十教 401", 2, (3, 4), "1-16"),
    ("计算机网络", "罗 毅", "八教 405", 2, (7, 8), "1-16"),
    ("高等数学 A(2)", "刘建平", "十教 205", 3, (1, 2), "1-16"),
    ("大学英语(三)", "李 静", "外语楼 208", 3, (5, 6), "1-16"),
    ("大学生心理健康教育", "邓 琳", "十教 306", 3, (9, 10), "2-14|双"),
    ("数字逻辑", "孙 浩", "九教 207", 4, (1, 2), "1-16"),
    ("数据结构", "陈 立", "八教 302", 4, (3, 4), "1-16"),
    ("大学物理 B(1)", "王慧敏", "九教 101", 4, (5, 6), "1-16"),
    ("军事理论(线上)", "张 岩", "", 4, (9, 10), "1-8"),
    ("中国近现代史纲要", "何 嘉", "七教 101", 5, (1, 2), "1-12"),
    ("体育(三)", "周 强", "第二田径场", 5, (3, 4), "1-16"),
    ("大学英语(三)", "李 静", "外语楼 208", 5, (5, 6), "1-16"),
    ("数据结构实验", "陈 立", "计算中心 B302", 5, (7, 8), "3-15|单"),
    ("程序设计实践", "陈 立", "计算中心 A201", 6, (1, 4), "5-18"),
    ("认识实习", "罗 毅", "八教 报告厅", 7, (5, 6), "17-18"),
]


def in_weeks(rule: str, week: int) -> bool:
    rng, _, flag = rule.partition("|")
    try:
        start, _, end = rng.partition("-")
        lo, hi = int(start), int(end or start)
    except ValueError:
        return True
    if not lo <= week <= hi:
        return False
    if flag == "单":
        return week % 2 == 1
    if flag == "双":
        return week % 2 == 0
    return True


def term_start(today: dt.date | None = None) -> dt.date:
    """演示学期固定让「今天」落在第 12 周，任何时候打开都有一屏好课表。"""
    today = today or dt.date.today()
    monday = today - dt.timedelta(days=today.weekday())
    return monday - dt.timedelta(days=7 * 11)


def demo_week(week: int, start: dt.date) -> list[dict]:
    monday = start + dt.timedelta(days=7 * (week - 1))
    days = []
    for offset in range(7):
        day_courses = []
        for name, teacher, room, weekday, (sec_a, sec_b), rule in COURSES:
            if weekday != offset + 1 or not in_weeks(rule, week):
                continue
            day_courses.append({
                "name": name,
                "teacher": teacher,
                "location": room,
                "start": sec_a,
                "end": sec_b,
                "weeks": rule.split("|")[0],
            })
        day_courses.sort(key=lambda c: (c["start"], c["name"]))
        days.append({"date": (monday + dt.timedelta(days=offset)).isoformat(),
                     "courses": day_courses})
    return days
