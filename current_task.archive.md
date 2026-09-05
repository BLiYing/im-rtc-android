# current_task 归档（只读）

> `current_task.md` 是**活快照**，退休的细节挪到这里，别再挪回去。
> 更完整的历史在 `git log`。

## 2026-09-05 · 离房之后仍在发 `room.ice_candidate`（已修）

从 `current_task.md`「下一步」第 7 条退休下来的原文——**当时的判断只对了一半**，
留在这里是为了记住哪一步推错了：

> **一个复核时发现、还没修的真 bug**：**离房之后客户端仍在发 `room.ice_candidate`**，
> 服务端每 5 分钟回两条 `1203 not_in_room`（日志里 20:39:45 / 20:44:45 / 20:49:45… 一路到 21:24）。
> `driveMedia` 在 room JOINED→IDLE 时确实调了 `adapter.stop()` → `peers.stop()` → `dispose()`，
> 所以要么没走到、要么 dispose 之后 native 侧仍在冒候选。**代码里没有 5 分钟的定时器**，
> 嫌疑在 libwebrtc 自己的候选重采集。下一步：在 `onLocalCandidate` 出口按房间状态挡一道
> （治标），再查 PC 到底有没有真被释放（治本）。

**「要么没走到、要么 dispose 之后仍在冒候选」这个二选一是对的，答案是前者。**
后一半的嫌疑（`dispose()` 没先 `close()`）是错的：反编译 M150 的
`PeerConnection.dispose()`，它第一条指令就是 `invokevirtual close()`。
结论与修法见 `current_task.md`「已知坑」。
