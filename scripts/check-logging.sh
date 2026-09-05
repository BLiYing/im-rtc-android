#!/usr/bin/env bash
# check-logging.sh —— 日志纪律门禁（CONVENTIONS.md §6）。
#
#   全量：      ./scripts/check-logging.sh
#   门禁自检：  ./scripts/check-logging.sh --selftest
#
#   退出码 1 = 有违规；2 = 内部错误。
#
# 禁的两类：
#   ① android.util.Log / Log.d(...) —— 日志必须走 Engine 的统一入口 IMRTCLog（第三刀落地），
#      否则脱敏、必带字段、热路径静默这几条规矩全都落空。
#      顺带一提：**别指望 R8 帮你去掉 Log.d**——不显式配 assumenosideeffects，它会原样留在包里，
#      参数字符串照样拼、照样有开销。
#   ② println / System.out / System.err —— 同上，且在 Android 上根本看不见。
#
# 测试代码不在管辖范围（用例里打点很正常）。
set -u

cd "$(dirname "$0")/.." || { echo "无法定位仓库根目录"; exit 2; }

MODULES="call-engine call-engine-webrtc call-uikit demo"
PATTERN='android\.util\.Log|(^|[^A-Za-z0-9_.])Log\.[vdiwe]\(|(^|[^A-Za-z0-9_.])println\(|System\.(out|err)\.'

fail=0
echo "== 日志纪律（CONVENTIONS §6）=="
for m in $MODULES; do
  dir="$m/src/main"
  [ -d "$dir" ] || continue
  hits=$(grep -rnE "$PATTERN" "$dir" --include="*.kt" --include="*.java" 2>/dev/null)
  if [ -n "$hits" ]; then
    echo "  ✗ FAIL  $m"
    echo "$hits" | sed 's/^/          /'
    fail=1
  else
    echo "  ✓ $m"
  fi
done

if [ "${1:-}" = "--selftest" ]; then
  tmp=$(mktemp -d) || { echo "mktemp 失败"; exit 2; }
  trap 'rm -rf "$tmp"' EXIT
  printf 'package x\nfun f() { Log.d("t", "x") }\n' > "$tmp/Bad.kt"
  printf 'package x\nfun f() { IMRTCLog.d("t", "x") }\n' > "$tmp/Good.kt"
  echo ""
  echo "== 门禁自检 =="
  if grep -qE "$PATTERN" "$tmp/Bad.kt"; then echo "  ✓ 能抓到 Log.d"; else echo "  ✗ 抓不到 Log.d——门禁坏了"; fail=1; fi
  if grep -qE "$PATTERN" "$tmp/Good.kt"; then echo "  ✗ 把 IMRTCLog.d 误判成违规"; fail=1; else echo "  ✓ 不误伤 IMRTCLog"; fi
fi

echo ""
if [ "$fail" -ne 0 ]; then
  echo "结果：✗ 有违规——改走 IMRTCLog，别加豁免。见 CONVENTIONS.md §6。"
  exit 1
fi
echo "结果：✓ 全部通过。"
exit 0
