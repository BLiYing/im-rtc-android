# 宿主开 R8 时，livekit.org.webrtc 的类不能被混淆/裁掉：native 层是按名字回调回来的，
# 名字一变就是启动即崩，而且崩在 JNI 里，堆栈基本没有可读信息。
-keep class livekit.org.webrtc.** { *; }
-dontwarn livekit.org.webrtc.**

# jni_zero 是 android-prefixed 一并改名的 JNI 绑定层：native 层用 JniInit 按名字反射调用，
# Java 侧没有任何显式引用，R8 判定「没人用」就会裁掉——症状是编译装机都正常，
# 一调 PeerConnectionFactory.initialize() 直接崩，堆栈还看不出是这里（社区多个项目踩过同一个坑，
# 例如 livekit/react-native-webrtc#107）。裁了这条规则等于把上面这条坑留给宿主自己踩一遍。
-keep class livekit.org.jni_zero.** { *; }
-dontwarn livekit.org.jni_zero.**

# 本模块的前台服务由清单引用，别被裁掉。
-keep class com.imrtc.engine.webrtc.IMCallForegroundService { *; }
