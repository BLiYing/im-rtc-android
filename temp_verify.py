#!/usr/bin/env python3
"""三端静态对齐检查：接听时摄像头权限 / 摄像头关着不采集（交互稿 §01 + §11-10），
以及 2026-09-11 下午五个真机问题（前台服务类型、九宫格填满容差、Web 本端格子、横幅接听键、iOS 来电页预览）。

只读三端 main 的源码，逐条断言关键实现在场；缺哪条就打印哪条并以非零退出。
"""
from __future__ import annotations

import logging
import re
import sys
from dataclasses import dataclass
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
log = logging.getLogger("temp_verify")

BASE = Path("/Users/liying/IOSProject/im-rtc")
WT = ""
ANDROID = BASE / "im-rtc-android" / WT
IOS = BASE / "im-rtc-ios" / WT
WEB = BASE / "im-rtc-web" / WT


@dataclass(frozen=True)
class Rule:
    end: str
    what: str
    root: Path
    glob: str
    pattern: str


RULES: list[Rule] = [
    # 1. 接听申请哪些设备：只有来电页上亲手关掉摄像头才只要麦克风
    Rule("android", "devicesForAnswering 按 cameraOptedOut 决定", ANDROID, "call-uikit/src/main/**/IMPermissionGate.kt",
         r"fun devicesForAnswering\(mediaType: String, cameraOptedOut: Boolean\)[^=]*=\s*devicesFor\(mediaType, withCamera = !cameraOptedOut\)"),
    Rule("android", "answer() 用 devicesForAnswering", ANDROID, "call-uikit/src/main/**/IMCallKit.kt",
         r"devicesForAnswering\(state\.mediaType, state\.cameraOptedOut\)"),
    Rule("ios", "imPermissionDevicesForAnswering 存在", IOS, "Sources/**/*.swift", r"func imPermissionDevicesForAnswering\("),
    Rule("web", "devicesForAnswering 存在", WEB, "packages/call-uikit-react/src/**/permissions.ts", r"export function devicesForAnswering\("),
    # 2. cameraOptedOut 只在来电阶段被切换
    Rule("android", "toggleCamera 只在 INCOMING 改 cameraOptedOut", ANDROID, "call-uikit/src/main/**/IMCallViewState.kt",
         r"if \(state\.phase == IMCallViewState\.Phase\.INCOMING\) !on else state\.cameraOptedOut"),
    Rule("ios", "IMSelfState.cameraOptedOut", IOS, "Sources/**/*.swift", r"cameraOptedOut"),
    Rule("web", "SelfState.cameraOptedOut", WEB, "packages/call-uikit-react/src/**/*.ts", r"cameraOptedOut"),
    # 3. 摄像头关着不采集
    Rule("android", "进房之前把摄像头意图给 Engine", ANDROID, "call-uikit/src/main/**/IMCallKit.kt",
         r"syncCameraIntent\(instance\)\s*\n\s*instance\.accept\(\)"),
    Rule("android", "Engine 进房时摄像头关着就不发视频", ANDROID, "call-engine/src/main/**/IMCallEngine.kt",
         r"publishDefaults\(after, cameraMuted = muteBook\.wanted\(\"video\"\) == true\)"),
    Rule("android", "openCamera 补发视频", ANDROID, "call-engine/src/main/**/IMCallEngine.kt", r"publisher\.publishCameraIfMissing\(ctx\)"),
    Rule("android", "预览只在摄像头开着时接", ANDROID, "call-uikit/src/main/**/IMCallKit.kt", r"!localPreviewStarted && wantsLocalPreview\(\)"),
    Rule("android", "没摄像头权限不起采集", ANDROID, "call-engine-webrtc/src/main/**/IMWebRTCAdapter.kt",
         r"checkSelfPermission\(Manifest\.permission\.CAMERA\)"),
    Rule("ios", "预览只在摄像头开着时起", IOS, "Sources/**/*.swift", r"func startPreviewIfWanted\("),
    Rule("web", "探权限不开摄像头", WEB, "packages/call-uikit-react/src/**/usePermissionGate.ts", r"engine\.probeCamera\(\)"),
    # 4. 群通话默认关摄像头（来电横幅最左那颗按钮是关态）
    Rule("android", "横幅摄像头图标跟 cameraOn", ANDROID, "call-uikit/src/main/**/IMIncomingBanner.kt", r"state\.cameraOn"),
    Rule("web", "defaultCameraOn = video && !group", WEB, "packages/call-uikit-react/src/**/callView.ts", r"defaultCameraOn"),
    # ---- 2026-09-11 下午：五个真机问题 ----
    # 2. Android 前台服务类型跟真实授权走，起不来不许循环崩
    Rule("android", "startInForeground 按授权拼类型", ANDROID, "call-engine-webrtc/src/main/**/IMCallForegroundService.kt",
         r"IMForegroundTypes\.granted\(this, withCamera\)"),
    Rule("android", "start() 一样权限都没有就不起服务", ANDROID, "call-engine-webrtc/src/main/**/IMCallForegroundService.kt",
         r"IMForegroundTypes\.granted\(context, withCamera\)\.isEmpty"),
    Rule("android", "onStartCommand 兜住异常并 stopSelf", ANDROID, "call-engine-webrtc/src/main/**/IMCallForegroundService.kt",
         r"startForeground 失败，收掉服务"),
    Rule("android", "camera 类型要求摄像头已授权", ANDROID, "call-engine-webrtc/src/main/**/IMCallForegroundService.kt",
         r"camera = withCamera && cameraGranted"),
    # 3a/3c. 竖屏源放正方形格子正好压在阈值上：两端同一个容差，iOS 格子边长取整到像素
    Rule("android", "FILL 判据带 FILL_TOLERANCE", ANDROID, "call-engine-webrtc/src/main/**/IMVideoFit.kt",
         r"MIN_VISIBLE_FRACTION - FILL_TOLERANCE"),
    Rule("android", "FILL_TOLERANCE = 0.01", ANDROID, "call-engine-webrtc/src/main/**/IMVideoFit.kt", r"FILL_TOLERANCE = 0\.01f"),
    Rule("ios", "imShouldFillVideo 带 imFillTolerance", IOS, "Sources/IMCallEngine/Media/IMVideoFit.swift",
         r"imMinVisibleFraction - imFillTolerance"),
    Rule("ios", "imFillTolerance = 0.01", IOS, "Sources/IMCallEngine/Media/IMVideoFit.swift", r"imFillTolerance: Double = 0\.01"),
    Rule("ios", "九宫格边长取整到物理像素", IOS, "Sources/IMCallKit/UI/IMCallGridView.swift",
         r"imSquareTileSide\(width: bounds\.width, height: bounds\.height"),
    # 3b. Web 通话中打开摄像头要把 cid 给本端格子
    Rule("web", "通话中发布摄像头后 dispatch localCamera", WEB, "packages/call-uikit-react/src/useCallActions.ts",
         r"cids\.current\.cam = await engine\.publishCamera\(\);\s*\n(?:\s*//[^\n]*\n)*\s*dispatch\(\{ type: 'localCamera', cid: cids\.current\.cam \}\)"),
    # 4a. 横幅接听键恒为听筒，构造时给定
    Rule("android", "横幅接听键构造为 PHONE", ANDROID, "call-uikit/src/main/**/IMIncomingBanner.kt",
         r"acceptButton = roundButton\([^\n]*IMKitIcon\.PHONE"),
    Rule("ios", "横幅接听键构造为 phone", IOS, "Sources/IMCallKit/UI/IMIncomingBanner.swift",
         r"\(acceptButton, [^\n]*IMKitIcon\.phone"),
    # 4b. iOS 来电页起预览；只起过预览的 cid 不算已发布
    Rule("ios", "imShouldPreviewWhileRinging 规则", IOS, "Sources/IMCallKit/State/IMPermissionGate.swift",
         r"cameraOn && !cameraBlocked && cameraStatus == \.granted"),
    Rule("ios", "来电页渲染时起预览", IOS, "Sources/IMCallKit/UI/IMCallOverlayViewController.swift",
         r"if state\.phase == \.incoming \{ controller\.startRingingPreviewIfAllowed\(\) \}"),
    Rule("ios", "toggleCamera 按 cameraPublished 判发布", IOS, "Sources/IMCallKit/State/IMCallController.swift",
         r"guard !cameraPublished, on else"),
    Rule("ios", "预览防重入", IOS, "Sources/IMCallKit/State/IMCallController+Permissions.swift",
         r"guard wanted, self\.cameraCID\.isEmpty, !self\.previewStarting else"),
]

# 这几条是「旧行为不许回来」
FORBIDDEN: list[Rule] = [
    Rule("android", "answer() 不再按 cameraOn 申请", ANDROID, "call-uikit/src/main/**/IMCallKit.kt",
         r"devicesFor\(state\.mediaType, withCamera = state\.cameraOn\)"),
    Rule("android", "旧 publishDefaults 已删", ANDROID, "call-engine/src/main/**/IMCallEngine.kt", r"private fun publishDefaults\("),
    Rule("android", "横幅接听键不再按 mediaType 换图标", ANDROID, "call-uikit/src/main/**/IMIncomingBanner.kt",
         r"acceptButton\.setImageResource"),
    Rule("ios", "横幅接听键不再按 mediaType 换图标", IOS, "Sources/IMCallKit/UI/IMIncomingBanner.swift",
         r"acceptButton\.setImage"),
    Rule("android", "startInForeground 不再无条件带 MICROPHONE", ANDROID, "call-engine-webrtc/src/main/**/IMCallForegroundService.kt",
         r"var type = ServiceInfo\.FOREGROUND_SERVICE_TYPE_MICROPHONE\b"),
    Rule("ios", "toggleCamera 不再按 cid 非空判已发布", IOS, "Sources/IMCallKit/State/IMCallController.swift",
         r"guard cameraCID\.isEmpty, on else"),
]


def read_all(rule: Rule) -> str:
    files = sorted(rule.root.glob(rule.glob))
    if not files:
        raise FileNotFoundError(f"{rule.end}: {rule.root}/{rule.glob} 一个文件都没匹配到")
    chunks: list[str] = []
    for f in files:
        try:
            chunks.append(f.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError) as err:
            log.warning("跳过读不了的文件 %s：%s", f, err)
    return "\n".join(chunks)


def check(rule: Rule, must_exist: bool) -> bool:
    try:
        found = re.search(rule.pattern, read_all(rule)) is not None
    except FileNotFoundError as err:
        log.error("%s", err)
        return False
    ok = found == must_exist
    (log.info if ok else log.error)("[%s] %-7s %s", "OK" if ok else "FAIL", rule.end, rule.what)
    return ok


def main() -> int:
    results = [check(r, True) for r in RULES] + [check(r, False) for r in FORBIDDEN]
    failed = results.count(False)
    log.info("共 %d 条，失败 %d 条", len(results), failed)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
