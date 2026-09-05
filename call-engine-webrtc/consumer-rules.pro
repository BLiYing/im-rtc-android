# 宿主开 R8 时，org.webrtc 的类不能被混淆/裁掉：native 层是按名字回调回来的，
# 名字一变就是启动即崩，而且崩在 JNI 里，堆栈基本没有可读信息。
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# 本模块的前台服务由清单引用，别被裁掉。
-keep class com.imrtc.engine.webrtc.IMCallForegroundService { *; }
