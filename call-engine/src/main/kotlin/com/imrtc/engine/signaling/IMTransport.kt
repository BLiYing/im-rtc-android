package com.imrtc.engine.signaling

/**
 * WebSocket 传输的抽象。
 *
 * 抽出来只为一件事：**单测能塞一个假的进去**。信令层最容易出错的是时序
 * （握手超时、心跳、重连退避、应答配对），这些必须用可控的假连接测，
 * 不能靠连真服务端撞运气——那是 iOS/Web 两端都写进已知坑的教训。
 */
internal interface IMTransport {

    /** 连接事件。实现方可以在任意线程回调，[IMSignalConnection] 会自己切回单线程。 */
    interface Listener {
        fun onOpen()
        fun onText(text: String)

        /** 关闭码原样上报，**不许在这里翻译成业务语义**——4401 与 4403 的处置完全不同。 */
        fun onClosed(code: Int, reason: String)
        fun onFailure(error: Throwable)
    }

    fun connect(url: String, listener: Listener)

    fun send(text: String)

    fun close(code: Int, reason: String)
}
