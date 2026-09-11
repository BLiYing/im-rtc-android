#!/usr/bin/env python3
"""三端静态对齐检查：接听时摄像头权限 / 摄像头关着不采集（交互稿 §01 + §11-10），
以及 2026-09-11 下午五个真机问题（前台服务类型、九宫格填满容差、Web 本端格子、横幅接听键、iOS 来电页预览），
以及 2026-09-11 晚「来电页 + 进房前关摄像头停采集」那一批：六步（含第 5 步 iOS 单飞、第 6 步三端 stopLocalPreview）
与两条延后项（通话中关摄像头也停采集；桌面后台来电提醒只查文档，代码断言在 desktop 段）。

只读三端 main 的源码与 server 仓的设计稿，逐条断言关键实现在场；缺哪条就打印哪条并以非零退出。
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
SERVER = BASE / "im-rtc-server"
DESKTOP = BASE / "im-rtc-desktop"
FLOWS = "docs/design/sketches/RTC_CALL_UX_FLOWS.html"
SPEC = "docs/design/sketches/RTC_CALL_UI_SPEC.html"
SKETCH = "docs/design/sketches/RTC_CALL_UX_SKETCH.html"


@dataclass(frozen=True)
class Rule:
    end: str
    what: str
    root: Path
    glob: str
    pattern: str


RULES: list[Rule] = [
    # 09-11 晚 第 4 步（Android）：来电页点开摄像头不弹权限框；进房前结束的通话也停采集 / 前台服务
    Rule("android", "来电页不当场申请摄像头", ANDROID, "call-uikit/src/main/**/IMPermissionGate.kt",
         r"fun asksCameraOnToggle\(phase: IMCallViewState\.Phase\): Boolean =\s*phase != IMCallViewState\.Phase\.INCOMING"),
    Rule("android", "toggleCamera 走 asksCameraOnToggle 只翻意图", ANDROID, "call-uikit/src/main/**/IMCallKit.kt",
         r"if \(!IMPermissionGate\.asksCameraOnToggle\(state\.phase\)\) \{\s*//[^\n]*\n\s*engine\?\.openCamera\(\)\s*update\(IMCallViewReducer\.toggleCamera\(state\)\)\s*return"),
    Rule("android", "adapter stop() 未 start 也停采集与前台服务", ANDROID, "call-engine-webrtc/src/main/**/IMWebRTCAdapter.kt",
         r"if \(!running\) \{\s*/\*[\s\S]*?\*/\s*stopCapture\(\)\s*IMCallForegroundService\.stop\(appContext\)\s*return\s*\}"),
    Rule("android", "单测覆盖来电页不申请", ANDROID, "call-uikit/src/test/**/KitRulesTest.kt",
         r"assertFalse\(\"来电页点开摄像头不申请权限\", IMPermissionGate\.asksCameraOnToggle"),
    # 延后项 2（桌面）：后台来电提醒——窗口不在前台时跳 Dock / 闪任务栏 + 系统通知，点了才前置并展开
    Rule("desktop", "后台判据：看不见 / 最小化 / 非活动窗口", DESKTOP, "demo/IncomingAlert.cpp",
         r"return !presence\.visible \|\| presence\.minimized \|\| !presence\.active;"),
    Rule("desktop", "来电按窗口在不在前台决定系统提醒", DESKTOP, "demo/MainWindow.cpp",
         r"alert_->ring\(incomingalert::presenceOf\(this\), caller, mediaType, isGroup\)"),
    Rule("desktop", "点通知前置并展开来电浮层", DESKTOP, "demo/MainWindow.cpp",
         r"connect\(alert_, &IncomingAlert::openRequested[\s\S]*?activateWindow\(\);\s*if \(banner_->isVisible\(\)\) showOverlay\(\)"),
    Rule("desktop", "接通收提醒", DESKTOP, "demo/MainWindow.cpp", r"overlay_->markConnected\(role\);\s*alert_->clear\(\);"),
    Rule("desktop", "结束 / 取消收提醒", DESKTOP, "demo/MainWindow.cpp", r"banner_->hide\(\);\s*alert_->clear\(\);\s*//"),
    Rule("desktop", "旧通知不再展开", DESKTOP, "demo/IncomingAlert.cpp",
         r"void IncomingAlert::notificationClicked\(\) \{[\s\S]*?if \(!alerting_\) return;"),
    Rule("desktop", "macOS 跳 Dock 可撤", DESKTOP, "demo/SystemAlertAttention_mac.mm",
         r"\[NSApp cancelUserAttentionRequest:gRequest\]"),
    Rule("desktop", "通知点击走 messageClicked", DESKTOP, "demo/SystemAlertSink.cpp", r"&QSystemTrayIcon::messageClicked"),
    Rule("desktop", "IncomingAlert 测试进构建", DESKTOP, "demo/CMakeLists.txt", r"foreach\(_case [^)]*\bIncomingAlert\b[^)]*\)"),
    # 09-11 晚 第 3 步（桌面）：窗内来电横幅（不抢焦点）→ 点开是来电浮层；浮层来电态加禁用的摄像头
    Rule("desktop", "横幅不抢焦点", DESKTOP, "demo/IncomingBanner.cpp", r"setFocusPolicy\(Qt::NoFocus\);\n  setCursor"),
    Rule("desktop", "横幅按钮也不抢焦点", DESKTOP, "demo/IncomingBanner.cpp", r"button->setFocusPolicy\(Qt::NoFocus\)"),
    Rule("desktop", "横幅本体点击展开", DESKTOP, "demo/IncomingBanner.cpp", r"emit expandRequested\(\)"),
    Rule("desktop", "横幅摄像头仅视频来电", DESKTOP, "demo/IncomingBanner.cpp", r"camera_->setVisible\(isVideo_\)"),
    Rule("desktop", "横幅接听键恒 phone", DESKTOP, "demo/IncomingBanner.cpp", r"setSymbols\(icons::Name::Phone, icons::Name::Phone\)"),
    Rule("desktop", "来电先出横幅", DESKTOP, "demo/MainWindow.cpp", r"banner_->showCall\(caller, mediaType, isGroup\)"),
    Rule("desktop", "showOverlay 收横幅", DESKTOP, "demo/MainWindow.cpp", r"void MainWindow::showOverlay\(\) \{\n  banner_->hide\(\);"),
    Rule("desktop", "横幅上接通换浮层", DESKTOP, "demo/MainWindow.cpp", r"if \(banner_->isVisible\(\)\) showOverlay\(\)"),
    Rule("desktop", "横幅开着时进房不再弹浮层", DESKTOP, "demo/MainWindow.cpp", r"overlay_->isVisible\(\) \|\| banner_->isVisible\(\)"),
    Rule("desktop", "浮层来电态显示摄像头", DESKTOP, "demo/CallOverlay.cpp",
         r"\(phase_ == Phase::Connected \|\| phase_ == Phase::Incoming\) && isVideo_"),
    Rule("desktop", "浮层来电态标题留空", DESKTOP, "demo/CallOverlay.cpp", r"Phase::Incoming\) \{\n.*\n    title_->clear\(\)"),
    Rule("desktop", "邀请语共用一张表（含群）", DESKTOP, "demo/CallStrings.cpp", r"if \(isGroup\) return tr\(\"邀请你加入群通话\"\)"),
    Rule("desktop", "英文翻译有 IncomingBanner 上下文", DESKTOP, "demo/i18n/imrtc_demo_en.ts", r"<name>IncomingBanner</name>"),
    Rule("desktop", "Incoming 测试进构建", DESKTOP, "demo/CMakeLists.txt", r"foreach\(_case [^)]*\bIncoming\b[^)]*\)"),
    Rule("desktop", "按钮点击不展开的测试在场", DESKTOP, "demo/tests/IncomingTest.cpp", r"void IncomingTest::buttonClicksDoNotExpand\(\)"),
    # 09-11 晚 第 2 步（Web）：来电页 + 点横幅展开 + bannerFirst + 仅已授权预览 + adapter 单飞
    Rule("web", "adapter 预览单飞 previewOpening", WEB, "packages/call-engine/src/media/webrtcAdapter.ts",
         r"if \(this\.previewOpening === null\) this\.previewOpening = this\.openPreview\(this\.closeGeneration\)"),
    Rule("web", "close() 作废在起的预览", WEB, "packages/call-engine/src/media/webrtcAdapter.ts",
         r"this\.previewOpening = null;\s*this\.closeGeneration \+= 1;"),
    Rule("web", "迟到的预览流当场 stop", WEB, "packages/call-engine/src/media/webrtcAdapter.ts",
         r"generation !== this\.closeGeneration\) \{\s*//[^\n]*\n\s*for \(const track of stream\.getTracks\(\)\) track\.stop\(\)"),
    Rule("web", "probeCamera 等在起的预览", WEB, "packages/call-engine/src/media/webrtcAdapter.ts",
         r"if \(this\.previewOpening !== null\) \{\s*await this\.previewOpening;"),
    Rule("web", "shouldPreviewWhileRinging 只认 granted", WEB, "packages/call-uikit-react/src/state/permissions.ts",
         r"mediaType === 'video' && cameraOn && !cameraBlocked && status === 'granted'"),
    Rule("web", "showsIncomingPage 判据", WEB, "packages/call-uikit-react/src/state/callView.ts",
         r"state\.phase === 'incoming' && \(!bannerFirst \|\| state\.isBannerExpanded\)"),
    Rule("web", "CallProvider bannerFirst 默认 true", WEB, "packages/call-uikit-react/src/CallProvider.tsx",
         r"bannerFirst = true"),
    Rule("web", "横幅本体点击展开", WEB, "packages/call-uikit-react/src/components/IncomingCall.tsx",
         r"onClick=\{\(\) => actions\.expandIncoming\(\)\}"),
    Rule("web", "横幅按钮栏 stopPropagation", WEB, "packages/call-uikit-react/src/components/IncomingCall.tsx",
         r"style=\{styles\.toastActions\} onClick=\{\(e\) => e\.stopPropagation\(\)\}"),
    Rule("web", "来电页走 ActiveCall", WEB, "packages/call-uikit-react/src/components/CallOverlay.tsx",
         r"showsIncomingPage\(state, bannerFirst\) \? <ActiveCall /> : <IncomingCall />"),
    Rule("web", "来电页不给小窗键 + 换成来电控制条", WEB, "packages/call-uikit-react/src/components/ActiveCall.tsx",
         r"showsMinimize=\{!incoming\}[\s\S]*incoming \? <IncomingControls />"),
    Rule("web", "来电页预览只在页上起、失败不置 cameraBlocked", WEB, "packages/call-uikit-react/src/useRingingPreview.ts",
         r"if \(!pageShown \|\| localCameraCid !== '' \|\| starting\.current\) return;[\s\S]*logger\.warn\('来电页预览起不来"),
    Rule("web", "来电页测试在场", WEB, "packages/call-uikit-react/test/incomingPage.test.tsx",
         r"查权限还没回来通话就结束了"),
    Rule("web", "单飞测试在场", WEB, "packages/call-engine/test/localPreview.test.ts",
         r"起到一半 close\(\)"),
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
    # ---- 2026-09-11 晚：来电页 + 进房前关摄像头停采集 ----
    # 第 1 步 设计稿
    Rule("docs", "§01 来电页点开摄像头不申请", SERVER, FLOWS,
         r"<b>来电页点开摄像头</b>（被叫，还没接听）</td><td><b>不申请</b>"),
    Rule("docs", "§01 摄像头开着接听必须问摄像头", SERVER, FLOWS, r"<b>摄像头开着接听就必须问摄像头</b>"),
    Rule("docs", "§01 v3.7 进房前关摄像头停采集", SERVER, FLOWS, r"<b>进房之前（拨出中 / 来电页）关掉摄像头 = 真的停采集</b>"),
    Rule("docs", "§06 点横幅本体展开、四端一样", SERVER, FLOWS, r"<b>点横幅本体展开成全屏来电页</b>[\s\S]{0,200}<b>四端一样</b>"),
    Rule("docs", "§07 桌面窗内横幅说明块", SERVER, FLOWS, r"桌面来电：窗内横幅 → 来电浮层"),
    Rule("docs", "§09 第 37 条改为全部", SERVER, FLOWS, r"<td>全部（Web / 桌面 v3.7 起有来电页）</td>"),
    Rule("docs", "§09 新增到第 45 条", SERVER, FLOWS, r'<td class="num">45</td><td>接通之后关摄像头'),
    Rule("docs", "界面规范组件表有 Web / 桌面来电页", SERVER, SPEC, r"<b>来电页（Web 页内 / 桌面窗内）</b>"),
    Rule("docs", "草图横幅接听键 📞", SERVER, SKETCH, r'<div class="rb no">✕</div><div class="rb yes">📞</div>'),
    Rule("docs", "§7.5 有 stopLocalPreview", SERVER, "docs/design/RTC_CALL_DESIGN.md", r"\*\*`stopLocalPreview`\*\*"),
    # 第 5 步（iOS）：预览 / 发布共用一次打开；作废在途的那次
    Rule("ios", "adapter 单飞：在起的那次一起等", IOS, "Sources/IMCallEngineWebRTC/IMWebRTCAdapter.swift",
         r"if live \{ return try await opening\.task\.value \}"),
    Rule("ios", "起到一半被作废当场 halt", IOS, "Sources/IMCallEngineWebRTC/IMWebRTCAdapter.swift",
         r"guard generation == captureGeneration else \{[\s\S]{0,200}Self\.halt\(camera, synthetic\)"),
    Rule("ios", "摄像头只 addTransceiver 一次", IOS, "Sources/IMCallEngineWebRTC/IMWebRTCAdapter.swift",
         r"publishedCameraCID = cid"),
    # 第 6 步：进房前关摄像头停采集（没发布才停）
    Rule("ios", "adapter stopLocalPreview 已发布的不停", IOS, "Sources/IMCallEngineWebRTC/IMWebRTCAdapter.swift",
         r"func stopLocalPreview\(\) \{[\s\S]{0,200}guard publishedCameraCID == nil else"),
    Rule("ios", "Kit 没发布就关摄像头 = 停预览", IOS, "Sources/IMCallKit/State/IMCallController.swift",
         r"if !on, !cameraPublished \{\s*stopLocalPreview\(\)"),
    Rule("ios", "Kit previewEpoch 作废在途预览", IOS, "Sources/IMCallKit/State/IMCallController+Permissions.swift",
         r"func stopLocalPreview\(\) \{\s*previewEpoch \+= 1"),
    Rule("ios", "预览回来先对 epoch", IOS, "Sources/IMCallKit/State/IMCallController+Permissions.swift",
         r"guard epoch == self\.previewEpoch else \{ return \}"),
    Rule("ios", "发布完补关时没推成的也停", IOS, "Sources/IMCallKit/State/IMCallController.swift",
         r"\} else if !cameraCID\.isEmpty \{\s*await MainActor\.run \{ self\.stopLocalPreview\(\) \}"),
    Rule("ios", "门面 stopLocalPreview 同步转给媒体层", IOS, "Sources/IMCallEngine/Facade/IMCallEngine+Preview.swift",
         r"@objc public func stopLocalPreview\(\) \{\s*media\?\.stopLocalPreview\(\)\s*\}"),
    Rule("ios", "ObjC 可用性检查带 stopLocalPreview", IOS, "Demo/**/IMObjCAPICheck.m", r"\[self->_engine stopLocalPreview\];"),
    Rule("ios", "门面单测在场", IOS, "Tests/IMCallEngineTests/FacadeTests.swift",
         r"func testStopLocalPreviewReachesMediaSynchronously\("),
    Rule("android", "Engine stopLocalPreview 走引擎线程", ANDROID, "call-engine/src/main/**/IMCallEngine.kt",
         r"fun stopLocalPreview\(\) = scheduler\.post \{ media\?\.stopLocalPreview\(\) \}"),
    Rule("android", "adapter stopLocalPreview 已发布的不停、作废在途预览", ANDROID,
         "call-engine-webrtc/src/main/**/IMWebRTCAdapter.kt",
         r"override fun stopLocalPreview\(\) \{\s*synchronized\(captureLock\) \{\s*if \(videoTrack != null\) return\s*previewIntent\.cancel\(\)"),
    Rule("android", "停预览后前台服务降回不带 camera", ANDROID, "call-engine-webrtc/src/main/**/IMWebRTCAdapter.kt",
         r"IMCallForegroundService\.start\(appContext, withCamera = false\)"),
    Rule("android", "Kit 关摄像头 = closeCamera + stopLocalPreview", ANDROID, "call-uikit/src/main/**/IMCallKit.kt",
         r"private fun turnCameraOff\(\) \{\s*engine\?\.closeCamera\(\)\s*engine\?\.stopLocalPreview\(\)"),
    Rule("web", "adapter stopLocalPreview 听后来的、发布中的不停", WEB, "packages/call-engine/src/media/webrtcAdapter.ts",
         r"if \(intent !== this\.previewIntent \|\| this\.cameraClaimed\) return;"),
    Rule("web", "uikit 进房前关摄像头清 cid 再停", WEB, "packages/call-uikit-react/src/useCallActions.ts",
         r"dispatch\(\{ type: 'localCamera', cid: '' \}\);\s*await engine\.stopLocalPreview\(\);"),
    Rule("web", "uikit 关着摄像头接通停掉残留预览", WEB, "packages/call-uikit-react/src/useCallActions.ts",
         r"if \(!withCamera\) \{\s*//[^\n]*\n\s*await engine\.stopLocalPreview\(\);"),
    # 延后项 1：通话中关摄像头也停采集（Track / transceiver / cid 不动，不重协商）
    Rule("ios", "setMuted 已发布摄像头才切采集", IOS, "Sources/IMCallEngineWebRTC/IMWebRTCAdapter.swift",
         r"let toggles = cid == publishedCameraCID && capturePaused != muted"),
    Rule("ios", "关着时不翻转", IOS, "Sources/IMCallEngineWebRTC/IMWebRTCAdapter.swift", r"guard !paused else"),
    Rule("android", "setMuted 视频连采集一起停 / 开", ANDROID, "call-engine-webrtc/src/main/**/IMWebRTCAdapter.kt",
         r"track\.setEnabled\(!muted\)\s*setCapturePaused\(muted\)"),
    Rule("android", "重开采集前查权限，没有报 2001", ANDROID, "call-engine-webrtc/src/main/**/IMWebRTCAdapter.kt",
         r"if \(!cameraPermitted\(\)\) \{ events\?\.onMediaError\(2001"),
    Rule("android", "关着时不翻转", ANDROID, "call-engine-webrtc/src/main/**/IMWebRTCAdapter.kt",
         r"if \(capturePaused\) \{ IMRTCLog\.w\(\"media\", \"摄像头关着，不翻转\"\); return \}"),
    Rule("web", "通话中关摄像头 stop 轨道", WEB, "packages/call-engine/src/media/webrtcAdapter.ts",
         r"camera\.track\.stop\(\);\s*logger\.info\('通话中关摄像头，采集已停"),
    Rule("web", "重开用 replaceTrack 换到同一个 sender", WEB, "packages/call-engine/src/media/webrtcAdapter.ts",
         r"await this\.cameraSender\?\.replaceTrack\(track\)"),
    Rule("docs", "§7.5 已发布摄像头关掉也停采集", SERVER, "docs/design/RTC_CALL_DESIGN.md",
         r"已发布的摄像头 `closeCamera`（媒体层 `setMuted\(cid, true\)`）同样停采集、灯灭"),
    Rule("docs", "§01 v3.7 说明块：通话中也停采集", SERVER, FLOWS, r"<b>同批也改成了停采集、灯灭</b>"),
    Rule("docs", "§09 第 45 条灯灭", SERVER, FLOWS, r"<b>摄像头指示灯灭</b>（停采集、不 unpublish）"),
    Rule("docs", "§07 桌面后台提醒已做", SERVER, FLOWS, r"窗口在后台（最小化 / 不是活动窗口）时再跳 Dock"),
]

# 这几条是「旧行为不许回来」
FORBIDDEN: list[Rule] = [
    Rule("android", "adapter stop() 不再一上来就按 running 早退", ANDROID, "call-engine-webrtc/src/main/**/IMWebRTCAdapter.kt",
         r"override fun stop\(\) \{\s*if \(!running\) return\b"),
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
    Rule("docs", "不再有「5s 不处理升级为全屏」", SERVER, "docs/design/sketches/*.html", r"<b>5s 不处理升级为全屏"),
    Rule("docs", "草图横幅接听键不再是 📹", SERVER, SKETCH, r'class="rb yes">📹'),
    Rule("docs", "草图不再说视频来电横幅接听键是摄像头", SERVER, SKETCH, r"视频来电是摄像头图标 📹"),
    Rule("desktop", "来电不再直接弹浮层", DESKTOP, "demo/MainWindow.cpp",
         r"beginIncoming\(caller, callees, mediaType, isGroup\);\s*showOverlay\(\)"),
    Rule("desktop", "CallOverlay 不再自己写邀请语", DESKTOP, "demo/CallOverlay.cpp", r"tr\(\"邀请你视频通话\"\)"),
    Rule("docs", "不再说通话中关摄像头灯还亮", SERVER, "docs/design/**/*.html", r"灯还亮|指示灯今天还亮着"),
    Rule("docs", "桌面后台提醒不再标 later", SERVER, FLOWS, r"用户点了才前置 <span class=\"pill later\">later</span>"),
    Rule("android", "current_task 不再说预览关了采集不停", ANDROID, "current_task.md", r"拨出中起了预览再关摄像头，采集不停"),
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
