package com.imrtc.demo;

import com.imrtc.uikit.IMProfileResolver;
import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.content.Context;
import android.net.Uri;

import com.imrtc.engine.IMCallEndReason;
import com.imrtc.engine.IMCallEngine;
import com.imrtc.engine.IMCallEngineListener;
import com.imrtc.engine.IMCallEngineVersion;
import com.imrtc.engine.IMCallOptions;
import com.imrtc.engine.IMKickedOutReason;
import com.imrtc.engine.IMNetworkQuality;
import com.imrtc.engine.IMSpeaker;
import com.imrtc.engine.media.IMVideoProfile;
import com.imrtc.engine.webrtc.IMWebRTCAdapter;
import com.imrtc.uikit.IMCallKit;
import com.imrtc.uikit.IMCallKitConfig;
import com.imrtc.uikit.IMInviteCandidate;
import com.imrtc.uikit.IMInviteCandidatesCallback;
import com.imrtc.uikit.IMInviteContext;
import com.imrtc.uikit.IMInviteMemberProvider;
import com.imrtc.uikit.IMPickedCallback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * **Java 互操作的编译期闸门**（CONVENTIONS §4）。
 *
 * 这个类永远不会被调用——它存在的唯一理由是：**公开面一旦变得 Java 调不了，编译当场就红**。
 * Kotlin 有一堆 Java 用不了的语法：默认参数、suspend、Result、value class……
 * 用 Kotlin 写 Demo 时一切正常，等真有个 Java 宿主来接才发现调不出来，那时改的是公开 API，
 * 五个仓一起动。
 *
 * iOS 侧的等价物是 IMObjCAPICheck.m，它真的抓到过问题（一个方法生成的选择器难用到
 * 宿主得写带空标签的怪语法）。
 *
 * **加了公开 API 就往这里补一行调用。**
 */
@SuppressWarnings("unused")
final class JavaApiCheck {

    private JavaApiCheck() {
    }

    static void check(Context context) {
        // 构造：三参数与两参数（媒体可省）两种形态，Java 都要能写出来。
        IMCallEngine.Config config = new IMCallEngine.Config("ws://host:8787/v1/ws", "device-1");
        // 版本号是 const val：Java 侧直接是静态常量，不用 INSTANCE。
        String sdkVersion = IMCallEngineVersion.VERSION + " " + IMCallEngineVersion.SDK;
        IMCallEngineListener listener = new IMCallEngineListener() {
            @Override
            public void onCallEnd(String callId, IMCallEndReason reason, long durationSec, String endedBy) {
                // 只覆盖关心的那几个：其余回调有默认实现（-Xjvm-default=all）。
                // 枚举在 Java 侧要能 switch/比较——这正是不用字符串的理由。
                if (reason == IMCallEndReason.HANGUP) {
                    // 正常挂断
                }
            }

            @Override
            public void onCallBegin(
                    String callId,
                    String roomId,
                    String mediaType,
                    boolean isGroup,
                    String role,
                    String caller,
                    String chatGroupId,
                    String userData) {
                // 群号 / opaque 数据在这里第一次（或再一次）拿到——`call.join` 进来的人没收过
                // onCallReceived，只能靠这里。
            }

            @Override
            public void onCallReceived(
                    String callId,
                    String caller,
                    String inviter,
                    List<String> calleeIds,
                    String mediaType,
                    boolean isGroup,
                    String chatGroupId,
                    String userData) {
                // 被叫侧：chatGroupId 决定「添加成员」该向宿主要哪个群的候选人；
                // inviter 是把你加进来的人（中途加邀时不是 caller），来电界面显示他。
            }

            @Override
            public void onKickedOut(IMKickedOutReason reason) {
                // 枚举在 Java 侧要能 switch —— 这正是不用字符串的理由。
                if (reason == IMKickedOutReason.AUTH_EXPIRED) {
                    // 取新票重登
                } else if (reason == IMKickedOutReason.TAKEN_OVER) {
                    // 回登录页
                } else if (reason == IMKickedOutReason.CONFIG_REJECTED) {
                    // 去改配置：换票和重试都救不了（device_id 不合规、协议版本不支持、应用被停用）。
                    // Java 侧没有穷尽性检查，加枚举值时**必须手动补这一支**——
                    // 漏了的症状是宿主什么都不做，而 Engine 已经不再重连了，界面就那么挂着。
                }
            }

            @Override
            public void onTokenWillExpire(long expiresAtMs) {
                // 去自家后台换票，然后 engine.updateToken(token, expiresAtMs)
            }

            @Override
            public void onActiveSpeakers(List<IMSpeaker> speakers) {
                for (IMSpeaker speaker : speakers) {
                    int volume = speaker.getVolume();
                    String uid = speaker.getUid();
                }
            }

            @Override
            public void onNetworkQuality(List<IMNetworkQuality> entries) {
                for (IMNetworkQuality entry : entries) {
                    int level = entry.getLevel();
                }
            }
        };

        // 画质档位是宿主策略：Java 宿主既要能用预设，也要能自己造一档。
        IMVideoProfile preset = IMVideoProfile.P720;
        IMVideoProfile custom = new IMVideoProfile("540p", 960, 540, 24, 900_000);
        int bitrate = preset.getMaxBitrateBps();
        for (IMVideoProfile.Layer layer : custom.getSimulcastLayers()) {
            String rid = layer.getRid();
        }

        IMCallEngine engine = new IMCallEngine(config, listener, new IMWebRTCAdapter(context));
        IMCallEngine withProfile =
                new IMCallEngine(config, listener, new IMWebRTCAdapter(context, preset));
        IMCallEngine bare = new IMCallEngine(config, listener);

        // 连接
        engine.login("token");
        engine.updateToken("new-token");
        // @JvmOverloads：带到期时刻的两参数形态 Java 也要能写出来。
        engine.updateToken("new-token", System.currentTimeMillis() + 3_600_000L);
        // 自画 UI（没引 call-uikit）的宿主要能自己喂前后台状态，见方法注释「后台重连节奏」。
        engine.setAppForeground(false);
        engine.setAppForeground(true);
        engine.logout();
        engine.destroy();

        // 通话
        engine.call(Arrays.asList("bob"), "audio");
        engine.call(Arrays.asList("bob", "carol"), "video", true);
        // 带选项（群号 / opaque 数据 / 振铃超时）：Java 侧用具名参数的构造器。
        IMCallOptions options = new IMCallOptions(true, "chat-group-1", "{\"k\":1}", 45);
        engine.call(Arrays.asList("bob", "carol"), "video", options);
        engine.accept();
        engine.reject();
        engine.cancel();
        engine.hangup();
        engine.forceEnd();
        engine.inviteMore(Arrays.asList("dave"));
        engine.joinCall("call-1");

        // 会议房
        engine.joinRoom("room-1", "room-token");
        engine.leaveRoom();

        // 设备与画面
        engine.openMicrophone();
        engine.closeMicrophone();
        engine.openCamera();
        engine.closeCamera();
        engine.switchCamera();
        engine.setSpeakerOn(true);
        engine.attachView("bob", engine.createVideoView(context));
        engine.startLocalPreview(engine.createVideoView(context));

        // UIKit 的入口。两参数（默认配置）与三参数（自带配置）两种形态 Java 都要能写。
        IMCallKitConfig kitConfig = new IMCallKitConfig();
        kitConfig.setBannerFirst(false);
        kitConfig.setFloatingWindow(true);
        boolean banner = kitConfig.getBannerFirst();
        // 「添加成员」的候选名单：一参数与两参数两种构造 Java 都要能写。
        kitConfig.setInviteCandidates(Arrays.asList(new IMInviteCandidate("bob"), new IMInviteCandidate("carol", "卡罗尔")));
        String candidateName = kitConfig.getInviteCandidates().get(0).getName();
        // 扩字段：avatarUrl / subtitle / selectable / unselectableReason，Java 也要能全填。
        IMInviteCandidate fullCandidate = new IMInviteCandidate(
                "dave", "戴夫", "https://example.com/a.png", "产品组", false, "已被禁言");
        boolean selectable = fullCandidate.getSelectable();

        // 按通话向宿主要候选人的钩子：Java 只需要实现 loadCandidates，其余两个方法有默认实现。
        kitConfig.setInviteMemberProvider(new IMInviteMemberProvider() {
            @Override
            public void loadCandidates(
                    IMInviteContext ctx, String query, String cursor, IMInviteCandidatesCallback callback) {
                List<IMInviteCandidate> page = new ArrayList<>();
                page.add(new IMInviteCandidate("erin"));
                callback.onResult(page, null);
            }

            @Override
            public boolean presentInvitePicker(Activity activity, IMInviteContext ctx, IMPickedCallback onPicked) {
                // 返回 true 表示接管；这里演示不接管，走 Kit 自带选人页。
                return false;
            }

            @Override
            public boolean canInvite(IMInviteContext ctx) {
                return true;
            }
        });
        kitConfig.setAllowsManualUidInput(true);
        // 来电铃声 / 回铃音 / 静音（默认 null = 内置素材）。Java 侧要能设也要能读回来。
        kitConfig.setIncomingRingtone(Uri.parse("android.resource://host.app/raw/custom_ringtone"));
        kitConfig.setRingbackTone(null);
        kitConfig.setRingtoneMuted(false);
        Uri incomingRingtone = kitConfig.getIncomingRingtone();
        boolean ringtoneMuted = kitConfig.getRingtoneMuted();

        IMCallKit.start(context, engine);
        IMCallKit.start(context, engine, kitConfig);
        // 身份解析：Java 侧要能实现这个接口并挂到 config 上。
        kitConfig.setProfileResolver(new IMProfileResolver() {
            @Override
            public String displayName(String uid) {
                return "小明";
            }

            @Override
            public Drawable avatar(String uid) {
                return null; // 宿主自己加载好再给；Kit 不下载
            }
        });
        IMCallKit.reloadProfiles(Arrays.asList("u1", "u2"));

        IMCallEngineListener wrapped = IMCallKit.wrap(listener);
        IMCallKit.notifyOutgoing(Arrays.asList("bob"), "video", false);
        IMCallKit.notifyMeeting("room-1");
        // 经 Kit 拨出 / 进会议：先过权限门再发帧。两参数与三参数两种形态。
        IMCallKit.placeCall(Arrays.asList("bob"), "audio");
        IMCallKit.placeCall(Arrays.asList("bob", "carol"), "video", true);
        IMCallKit.placeCall(Arrays.asList("bob", "carol"), "video", options);
        IMCallKit.joinMeeting("room-1", "room-token");
        // 主动加入一通进行中的群通话（M8：宿主拿 webhook / 群横幅自己判断「有通话在进行中」）。
        IMCallKit.joinCall("call-1");
        IMCallKit.stop();
    }
}
