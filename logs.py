# -*- coding: utf-8 -*-
"""网页版的诊断日志：环形缓冲 + 脱敏，导出为纯文本。

和 APK 里的 Diag.java 保持同样的口径：记流程和状态码，不记密码、token。
"""

from __future__ import annotations

import collections
import datetime as dt
import os
import re
import sys

_BUF: collections.deque[str] = collections.deque(maxlen=600)

_SECRET = re.compile(
    r"(?i)(password|passwd|pwd|token|ticket|execution|idtoken|jsessionid|captcha)=([^&\s\"',;]+)"
)
_JSON_SECRET = re.compile(r'(?i)"(password|token|idToken|execution)"\s*:\s*"[^"]*"')
_STU_NO = re.compile(r"\b(\d{4})\d{4,}\b")


def mask(text: object) -> str:
    """打码：URL 参数、JSON 字段、学号后 6 位。"""
    out = str(text or "")
    out = _SECRET.sub(r"\1=***", out)
    out = _JSON_SECRET.sub(r'"\1":"***"', out)
    out = _STU_NO.sub(r"\1******", out)
    return out


def record(tag: str, message: object) -> None:
    stamp = dt.datetime.now().strftime("%H:%M:%S")
    _BUF.append(f"[{stamp}] {tag} {mask(message)}")


def dump() -> str:
    lines = [
        "科大课表 · 诊断日志（网页版）",
        f"Python {sys.version.split()[0]} · {os.uname().sysname if hasattr(os, 'uname') else ''}",
        f"生成时间：{dt.datetime.now():%Y-%m-%d %H:%M:%S}",
        "说明：password / token / ticket / execution / 学号后 6 位均已打码",
        "-" * 60,
    ]
    lines.extend(_BUF if _BUF else ["(暂无日志)"])
    lines.append("-" * 60)
    lines.append(f"共 {len(_BUF)} 行")
    lines.append("")
    lines.append("图例：R=请求  S=流程  E=错误")
    return "\n".join(lines)
