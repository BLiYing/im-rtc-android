#!/usr/bin/env bash
# check-layering.sh —— 分层门禁：把 CONVENTIONS.md §1 的三条依赖方向变成硬闸。
#
#   全量：      ./scripts/check-layering.sh
#   门禁自检：  ./scripts/check-layering.sh --selftest
#
#   退出码 1 = 有文件越界；2 = 内部错误。
#
# 守的三条（都是「越界了编译还是过的，所以必须靠 grep 拦」）：
#   ① call-engine 的 protocol/ 与 statemachine/ 不许 import android.* / androidx.*
#      ——它们必须是纯 JVM 代码，用普通 JUnit 就能跑，不需要 Robolectric、不需要设备。
#      这条一破，「跑一次单测」就会变成「起模拟器」，然后就没人跑测试了。
#   ② call-engine 整个模块不许 import org.webrtc.*
#      ——媒体只以 IMMediaAdapter 接口出现，真实现在 call-engine-webrtc。
#      这条一破，跑单测要先拉几十 MB 预编译包。
#   ③ call-uikit 不许 import org.webrtc.*
#      ——画面通过 Engine 的 attachView 挂载，换媒体实现时 UIKit 一行不用改。
set -u

cd "$(dirname "$0")/.." || { echo "无法定位仓库根目录"; exit 2; }

fail=0

# scan <描述> <目录> <禁止的 import 前缀正则>
scan() {
  local what="$1" dir="$2" pattern="$3"
  # 目录还没落地时明说「跳过」，不要静默放行——静默跳过的门禁等于没有门禁。
  if [ ! -d "$dir" ]; then echo "  · $what（目录尚未落地，跳过）"; return 0; fi
  local hits
  hits=$(grep -rnE "^[[:space:]]*import[[:space:]]+($pattern)" "$dir" \
          --include="*.kt" --include="*.java" 2>/dev/null)
  if [ -n "$hits" ]; then
    echo "  ✗ FAIL  $what"
    echo "$hits" | sed 's/^/          /'
    fail=1
  else
    echo "  ✓ $what"
  fi
}

echo "== 分层门禁（CONVENTIONS §1）=="
scan "protocol/ 不依赖 Android"          "call-engine/src/main/kotlin/com/imrtc/engine/protocol"     "android\.|androidx\."
scan "statemachine/ 不依赖 Android"      "call-engine/src/main/kotlin/com/imrtc/engine/statemachine" "android\.|androidx\."
scan "call-engine 不依赖 org.webrtc"     "call-engine/src/main"                                      "org\.webrtc\."
scan "call-uikit 不依赖 org.webrtc"      "call-uikit/src/main"                                       "org\.webrtc\."

# ---- 门禁自检：这是个 fail-open 的闸，回归了会静默放行 ----
if [ "${1:-}" = "--selftest" ]; then
  tmp=$(mktemp -d) || { echo "mktemp 失败"; exit 2; }
  trap 'rm -rf "$tmp"' EXIT
  mkdir -p "$tmp/protocol"
  printf 'package x\nimport android.content.Context\n' > "$tmp/protocol/Bad.kt"
  printf 'package x\nimport java.io.File\n' > "$tmp/protocol/Good.kt"
  echo ""
  echo "== 门禁自检 =="
  bad=$(grep -rnE "^[[:space:]]*import[[:space:]]+(android\.|androidx\.)" "$tmp/protocol" --include="*.kt")
  if [ -n "$bad" ]; then echo "  ✓ 能抓到越界的 import"; else echo "  ✗ 抓不到越界的 import——门禁本身坏了"; fail=1; fi
  good=$(grep -rnE "^[[:space:]]*import[[:space:]]+(org\.webrtc\.)" "$tmp/protocol" --include="*.kt")
  if [ -z "$good" ]; then echo "  ✓ 不误报正常的 import"; else echo "  ✗ 误报了正常的 import"; fail=1; fi
fi

echo ""
if [ "$fail" -ne 0 ]; then
  echo "结果：✗ 有越界——把代码挪到该去的模块，别改门禁。见 CONVENTIONS.md §1。"
  exit 1
fi
echo "结果：✓ 全部通过。"
exit 0
