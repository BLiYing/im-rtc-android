#!/usr/bin/env bash
# test.sh —— 本仓唯一测试入口（CONVENTIONS.md §10）。
#
#   ./scripts/test.sh                 全量
#   BUILD_ONLY=1 ./scripts/test.sh    只到编译为止，不跑单测
#   RTC_CONFORMANCE_DIR=/path ./scripts/test.sh   一致性向量不在 ../im-rtc-server 时
#
# **别手拼 gradle 命令行**：参数漂移会导致「本地过了 CI 挂」。要加步骤就加在这里。
set -eu

cd "$(dirname "$0")/.."

# ── JDK 17 ──────────────────────────────────────────────────────────────
# 本机的 Homebrew gradle 把 JAVA_HOME 写成了占位符（@@HOMEBREW_JAVA@@），
# 直接跑会报 "JAVA_HOME is set to an invalid directory"。这里统一兜底。
if [ ! -x "${JAVA_HOME:-/nonexistent}/bin/javac" ]; then
  if [ -x /usr/libexec/java_home ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home)
    export JAVA_HOME
  fi
fi
[ -x "${JAVA_HOME:-/nonexistent}/bin/javac" ] || { echo "✗ 找不到 JDK 17，先装一个再来（brew install openjdk@17）"; exit 2; }

step() { echo ""; echo "──[$1] $2 ──────────────────────────────────────────────"; }

# ── 1. 体量门禁 ─────────────────────────────────────────────────────────
step 1 "单文件体量"
./scripts/check-file-size.sh
./scripts/check-file-size.sh --selftest >/dev/null || { echo "✗ 体量门禁自检未过"; exit 1; }
echo "  ✓ 体量门禁（含自检）"

# ── 2. 分层门禁 ─────────────────────────────────────────────────────────
step 2 "分层与依赖方向"
./scripts/check-layering.sh --selftest
echo "  ✓ 分层门禁（含自检）"

# ── 3. 日志纪律 ─────────────────────────────────────────────────────────
step 3 "日志纪律"
./scripts/check-logging.sh --selftest
echo "  ✓ 日志纪律（含自检）"

# ── 4. 一致性向量可达 ───────────────────────────────────────────────────
# 向量是五仓共用的同一份文件，**禁止手抄到本仓**。找不到就在这里失败，
# 而不是让单测「优雅跳过」——跳过等于闸门不存在。
step 4 "一致性向量可达"
VEC_DIR="${RTC_CONFORMANCE_DIR:-../im-rtc-server/docs/conformance}"
missing=0
for f in call_fsm envelope error_codes reasons room_fsm; do
  if [ -f "$VEC_DIR/$f.json" ]; then echo "  ✓ $f.json"; else echo "  ✗ 缺 $f.json"; missing=1; fi
done
if [ "$missing" -ne 0 ]; then
  echo "  向量目录：$VEC_DIR"
  echo "  把 im-rtc-server 克隆到与本仓同级，或设 RTC_CONFORMANCE_DIR。"
  exit 1
fi

# ── 5. 编译 ─────────────────────────────────────────────────────────────
# assembleDebug 会把四个模块连同 Demo APK 一起编出来——「模块能一起编成 App」
# 这条路在骨架阶段就走通，别等第五刀才发现构建配错了。
step 5 "编译（四模块 + Demo APK）"
./gradlew --console=plain assembleDebug

if [ "${BUILD_ONLY:-0}" = "1" ]; then
  echo ""
  echo "════════════════════════════════════════════════"
  echo "结果：✓ 编译通过（BUILD_ONLY=1，未跑单测）"
  exit 0
fi

# ── 6. 单测 ─────────────────────────────────────────────────────────────
# 纯 JVM，不需要模拟器、不需要真机、不需要 Robolectric。
step 6 "单测（纯 JVM）"
RTC_CONFORMANCE_DIR="$(cd "$VEC_DIR" && pwd)" ./gradlew --console=plain testDebugUnitTest

echo ""
echo "════════════════════════════════════════════════"
echo "结果：✓ 全绿（6 步）"
