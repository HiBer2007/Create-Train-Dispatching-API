# Create Train Dispatching API

为 Create 的铁路系统提供一套**可插拔的信号与调度接口**。

本仓库只包含**接口**，不含实现。它自身不改变任何游戏行为——由消费它的模组提供调度内核并完成所有集成。

- 目标版本：**Minecraft 1.21.1 / NeoForge 21.1.219**
- 许可证：**LGPL-3.0-or-later**
- Maven：`top.hibermod:create-train-dispatching-api`

---

## 为什么需要它

Create 的列车系统是一套**有轨电车**模型：一辆车、一条路、前方有信号就停。

它没有道岔对象、没有进路、没有联锁、没有时刻表校验、没有对外数据面。信号机在视觉上只有一个灯——亮红或灭（`SignalRenderer` 只判 `isRedLight`，逻辑上的黄/绿从未被渲染）。

要支撑真实规模的线网与 PIS，这些东西必须补上。而补的方式不该是「再写死一套」——所以本 API 只定义**契约**：

| 层 | 内容 |
|---|---|
| `DispatchKernel` | 调度内核：每帧读状态、写指令。**「怎么调度」全在这里** |
| `FrameContext` / `DispatchFrame` | 内核的读写面 |
| `Topology` / `Telemetry` | 只读的拓扑与列车状态 |
| `Command` | 写回指令（距离授权、限速、进路、道岔锁闭、信号显示） |
| `signal.*` | 信号系统的**开放注册表**（灯色 / 语义 / 机型） |

---

## 三条设计原则

### 1. 不给「加价」，只给「授权」

Create 的闭塞是「加价绕路」式的软约束（其它 mod 也走这条路）。本 API 给的是**显式的距离授权**：

```java
record TrainCommand(UUID trainId, Direction direction, double movementAuthority,
                    double speedCap, boolean hold, RouteRequest route)
```

授权是硬约束，加价是建议。两者不可互换——移动闭塞必须是前者。

### 2. 指令只能收紧，不能放宽

实现方在应用指令前取 `min(原生意图, 内核指令)`。内核逻辑出错时最坏退化为原版行为，永远不会越过安全边界。

### 3. API 不依赖 Create

本仓库的所有类型只用到 Minecraft / NeoForge 的公开类。第三方内核**不必随 Create 版本更新而重编译**，这也是它作为独立 API 存在的意义。

---

## 信号系统：语义与灯位分离

不同铁路的信号制度差异极大（中国四显示、英国三显示、日本五显示、调车白/蓝……）。所以不做成枚举，而是**开放注册**：

```java
// 语义跨机型共享
Aspect  = { id, 限速, 类别 }            // STOP / CAUTION / PROCEED / SHUNT ...

// 机型决定「怎么显示这些语义」
SignalType = { id, 灯座布局, 语义→灯位映射, 联锁语义 }
```

「通行」在四显示机上亮第 4 号灯座、在三显示机上亮第 3 号，**但内核说的是同一个 `ctdapi:proceed`**。

注册一个机型即刻可用：

```java
SignalRegistries.registerType(SignalType.builder(rl("myrail", "home_4"))
    .lamp(LampColor.RED).lamp(LampColor.YELLOW)
    .lamp(LampColor.YELLOW).lamp(LampColor.GREEN)
    .expresses(Aspects.STOP.id(), 0)
    .expresses(Aspects.CAUTION.id(), 1)
    .expresses(Aspects.ADVANCE_CAUTION.id(), 1, 2)      // 双黄
    .expresses(Aspects.PROCEED.id(), 3)
    .expresses(Aspects.CALL_ON.id(), LampDisplay.flashing(0))
    .semantics(SignalSemantics.HOME)
    .build());
```

联锁语义是真正决定行车的那部分：

```java
SignalSemantics { target, grantsAuthority, permissiveWhenClosed, stoppingPoint, defaultSpeedFraction }
```

其中 `stoppingPoint = false` 表示预告/复述信号机——**它不产生授权边界**。把它当边界会让列车提前停死在区间里。

---

## 目录

```
src/main/java/top/hibermod/ctdapi/
├── DispatchKernel.java      内核接口（唯一的决策入口）
├── DispatchKernels.java     内核注册表
├── KernelEnv.java           生命周期服务（状态持久化 / 日志 / 配置）
├── FrameContext.java        每帧输入
├── DispatchFrame.java       每帧输出
├── Topology.java            只读拓扑
├── Telemetry.java           列车实时状态
├── Command.java             写回指令
├── RailwayEvent.java        事件
├── Rail.java                稳定标识（NodeId / EdgeId / Span）
└── signal/
    ├── LampColor / LampMode / LampSpec / LampDisplay   灯
    ├── Aspect                                          语义
    ├── SignalType                                      机型
    ├── SignalSemantics                                 联锁语义
    └── SignalRegistries                                开放注册表
```

`docs/` 下是设计文档，其中：

- `06-dispatch-spi.md` — 内核接口说明与需求映射
- `07-custom-signals.md` — 为什么原生信号机只有一个灯，以及怎么换掉
- `08-signal-registry-api.md` — 信号注册 API（语义 / 机型 / 灯位）
- `09-compat-steam-n-rails.md` — 与汽鸣铁道（Steam 'n' Rails）的兼容性，含真实道岔实现剖析

---

## 构建

```bash
./gradlew build
```

> ⚠ 构建脚本按 NeoForge 1.21.1 标准编写，但**尚未在联网环境中实测**（编写环境无法拉取 Gradle/NeoForge 依赖）。首次构建若失败，请先确认 `neo_version` 与本地缓存的 NeoForge 一致。

---

## 许可

GNU Lesser General Public License v3.0 or later。完整文本见 [LICENSE](LICENSE)。

你可以把本 API 用于闭源模组；但如果你修改了本 API 本身的代码，修改部分必须以 LGPL 发布。
