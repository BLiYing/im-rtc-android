package com.imrtc.demo;

import android.content.Context;

import com.imrtc.engine.IMCallEngine;
import com.imrtc.engine.IMCallEngineListener;
import com.imrtc.engine.IMNetworkQuality;
import com.imrtc.engine.IMSpeaker;
import com.imrtc.engine.media.IMVideoProfile;
import com.imrtc.engine.webrtc.IMWebRTCAdapter;
import com.imrtc.uikit.IMCallKit;
import com.imrtc.uikit.IMCallKitConfig;

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
        IMCallEngineListener listener = new IMCallEngineListener() {
            @Override
            public void onCallEnd(String callId, String reason, long durationSec, String endedBy) {
                // 只覆盖关心的那个：其余 23 个回调有默认实现（-Xjvm-default=all）。
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
        engine.logout();
        engine.destroy();

        // 通话
        engine.call(Arrays.asList("bob"), "audio");
        engine.call(Arrays.asList("bob", "carol"), "video", true);
        engine.accept();
        engine.reject();
        engine.cancel();
        engine.hangup();
        engine.inviteMore(Arrays.asList("dave"));
        engine.joinCall("call-1");

        // 会议房
        engine.joinRoom("room-1", "room-token");
        engine.leaveRoom();

        // 设备与画面
        engine.openMic();
        engine.closeMic();
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

        IMCallKit.start(context, engine);
        IMCallKit.start(context, engine, kitConfig);
        IMCallEngineListener wrapped = IMCallKit.wrap(listener);
        IMCallKit.notifyOutgoing(Arrays.asList("bob"), "video", false);
        IMCallKit.notifyMeeting("room-1");
        IMCallKit.stop();
    }
}
