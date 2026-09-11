#!/usr/bin/env python3
"""Android 静态检查：SDK 版本号统一 1.0.0（2026-09-11，设置页补齐那一批的 Android 部分）。

只读本仓（脚本所在目录，worktree 与 main 通用），逐条断言：
- 版本常量唯一真相源在 call-engine 的 IMCallEngineVersion；
- 两处握手 sdk 默认值、Demo「关于」、versionName、JavaApiCheck 都指向它；
- 旧值（"android" 光秃默认、"0.1"）不再出现；
- IMCallEngine.kt 没被这次改动顶过 600 行红线。
缺哪条就打印哪条并以非零退出。iOS / Web / 桌面各自仓里有自己的 temp_verify.py。
"""
from __future__ import annotations

import logging
import re
import sys
from dataclasses import dataclass
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
log = logging.getLogger("temp_verify")

ROOT = Path(__file__).resolve().parent
ENGINE = "call-engine/src/main/kotlin/com/imrtc/engine"
LINE_LIMIT = 600


@dataclass(frozen=True)
class Rule:
    what: str
    path: str
    pattern: str


RULES: list[Rule] = [
    Rule("常量 VERSION = 1.0.0", f"{ENGINE}/IMCallEngineVersion.kt", r'const val VERSION = "1\.0\.0"'),
    Rule("常量 SDK = android/$VERSION", f"{ENGINE}/IMCallEngineVersion.kt", r'const val SDK = "android/\$VERSION"'),
    Rule("Engine Config 默认 sdk 读常量", f"{ENGINE}/IMCallEngine.kt",
         r"val deviceId: String,\s*val sdk: String = IMCallEngineVersion\.SDK,"),
    Rule("SignalConnection Config 默认 sdk 读常量", f"{ENGINE}/signaling/IMSignalConnection.kt",
         r"val sdk: String = IMCallEngineVersion\.SDK,\s*val protocolVersion"),
    Rule("hello 帧发 cfg.sdk", f"{ENGINE}/signaling/IMSignalConnection.kt", r'"sdk" to IMJson\.Str\(cfg\.sdk\)'),
    Rule("设置页 SDK 行读常量", "demo/src/main/kotlin/com/imrtc/demo/SettingsScreen.kt",
         r'detailRow\("SDK", "im-rtc-android \$\{IMCallEngineVersion\.VERSION\}"\)'),
    Rule("设置页 libwebrtc 行仍在", "demo/src/main/kotlin/com/imrtc/demo/SettingsScreen.kt",
         r'detailRow\("libwebrtc", "M150'),
    Rule("Demo versionName 1.0.0", "demo/build.gradle.kts", r'versionName = "1\.0\.0"'),
    Rule("JavaApiCheck 用静态常量", "demo/src/main/java/com/imrtc/demo/JavaApiCheck.java",
         r"IMCallEngineVersion\.VERSION \+ \" \" \+ IMCallEngineVersion\.SDK"),
    Rule("单测断言 hello 帧带版本", "call-engine/src/test/kotlin/com/imrtc/engine/SdkVersionTest.kt",
         r"assertEquals\(IMJson\.Str\(IMCallEngineVersion\.SDK\), sdk\)"),
]

FORBIDDEN: list[Rule] = [
    Rule("光秃默认 sdk = \"android\"", f"{ENGINE}/IMCallEngine.kt", r'sdk: String = "android"'),
    Rule("光秃默认 sdk = \"android\"", f"{ENGINE}/signaling/IMSignalConnection.kt", r'sdk: String = "android"'),
    Rule("设置页旧版本 0.1", "demo/src/main/kotlin/com/imrtc/demo/SettingsScreen.kt", r"im-rtc-android 0\.1"),
    Rule("旧 versionName 0.1", "demo/build.gradle.kts", r'versionName = "0\.1"'),
]


def read(rel: str) -> str | None:
    try:
        return (ROOT / rel).read_text(encoding="utf-8")
    except OSError as err:
        log.error("读不到 %s：%s", rel, err)
        return None


def check_rules() -> int:
    failures = 0
    for rule in RULES:
        text = read(rule.path)
        if text is None or not re.search(rule.pattern, text):
            log.error("缺失  %s  (%s)", rule.what, rule.path)
            failures += 1
        else:
            log.info("在场  %s", rule.what)
    for rule in FORBIDDEN:
        text = read(rule.path)
        if text is None or re.search(rule.pattern, text):
            log.error("残留  %s  (%s)", rule.what, rule.path)
            failures += 1
        else:
            log.info("已清  %s  (%s)", rule.what, rule.path)
    return failures


def check_line_limit(rel: str) -> int:
    text = read(rel)
    if text is None:
        return 1
    lines = text.count("\n")
    if lines > LINE_LIMIT:
        log.error("超红线  %s  %d 行 > %d", rel, lines, LINE_LIMIT)
        return 1
    log.info("体量    %s  %d 行", rel, lines)
    return 0


def main() -> int:
    log.info("检查目录 %s", ROOT)
    failures = check_rules() + check_line_limit(f"{ENGINE}/IMCallEngine.kt")
    if failures:
        log.error("共 %d 条不通过", failures)
        return 1
    log.info("全部通过（%d 条在场 + %d 条已清 + 体量）", len(RULES), len(FORBIDDEN))
    return 0


if __name__ == "__main__":
    sys.exit(main())
