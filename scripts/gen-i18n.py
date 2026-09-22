#!/usr/bin/env python3
"""把 im-rtc-server/docs/i18n/strings.json（跨端文案表）生成成 Kotlin 文案表。
`demo.` 开头的 key 是 Demo 页面自己的文案，不进 SDK，单独生成到 demo 模块的 DemoMessages.gen.kt。

  python3 scripts/gen-i18n.py          重新生成
  python3 scripts/gen-i18n.py --check  与已提交的不一致就失败（test.sh 用）
"""
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = Path(os.environ.get("RTC_I18N_FILE", ROOT.parent / "im-rtc-server/docs/i18n/strings.json"))
OUT = ROOT / "call-uikit/src/main/kotlin/com/imrtc/uikit/IMMessages.gen.kt"
DEMO_OUT = ROOT / "demo/src/main/kotlin/com/imrtc/demo/DemoMessages.gen.kt"


def kt(s: str) -> str:
    """Kotlin 字符串字面量：转义 \\ " $，换行写成 \\n。"""
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$").replace("\n", "\\n") + '"'


def render(data: dict, demo: bool) -> str:
    locales = data["locales"]
    strings = {k: v for k, v in data["strings"].items() if k.startswith("demo.") == demo}
    for key, row in strings.items():
        for loc in locales:
            if not row.get(loc):
                sys.exit(f"✗ {key} 缺 {loc}")
    fn = {"zh-CN": "zhCn", "en": "en"}
    pkg, obj = ("com.imrtc.demo", "DemoMessages") if demo else ("com.imrtc.uikit", "IMMessages")
    out = ["// 由 scripts/gen-i18n.py 生成，勿手改。源：im-rtc-server/docs/i18n/strings.json", "",
           f"package {pkg}", "", f"internal object {obj} {{", ""]
    for loc in locales:
        out.append(f"    val {fn[loc]}: Map<String, String> = mapOf(")
        out += [f"        {kt(k)} to {kt(v[loc])}," for k, v in strings.items()]
        out += ["    )", ""]
    out.append("}")
    return "\n".join(out) + "\n"


def main() -> None:
    if not SRC.exists():
        sys.exit(f"✗ 找不到文案表：{SRC}\n  把 im-rtc-server 克隆到本仓同级，或设 RTC_I18N_FILE。")
    data = json.loads(SRC.read_text(encoding="utf-8"))
    targets = [(OUT, render(data, demo=False)), (DEMO_OUT, render(data, demo=True))]
    for path, text in targets:
        if "--check" in sys.argv:
            if not path.exists() or path.read_text(encoding="utf-8") != text:
                sys.exit(f"✗ {path.name} 与文案表不一致，跑 python3 scripts/gen-i18n.py")
        else:
            path.write_text(text, encoding="utf-8")
            print("  已生成", path.name)
    if "--check" in sys.argv:
        print("  文案表与生成物一致")


if __name__ == "__main__":
    main()
