# 06 — 调度内核接口（SPI）说明

> 配套代码：`design/spi/src/main/java/top/hibermod/ctdapi/`（8 个文件，可直接落进 mod）
> 架构背景：`docs/05-full-takeover-design.md`

---

## 1. 设计原则

| # | 原则 | 理由 |
|---|---|---|
| P1 | **SPI 不引用 Create 的任何类型** | 内核 JAR 不应随 Create 更新而重编译。宿主做适配，内核只见值对象 |
| P2 | **不给「加价」接口，只给「授权」接口** | 加价是「建议绕路」，授权是「禁止越界」。CRN 走的是加价（软代价），我们走授权（硬约束），两者不可互换 |
| P3 | **指令只能收紧，不能放宽** | 宿主把所有速度取 `min(原生意图, 内核指令)`。内核逻辑出错时最坏退化为原版，永不越过安全边界 |
| P4 | **零指令 = 不干预** | 内核一帧不写东西，列车就回到 Create 原生行为。这让降级、灰度、排障都变得简单 |
| P5 | **每帧纯函数式写入** | 内核不直接改游戏状态，只往 `DispatchFrame` 里写意图。宿主统一应用，便于审计与回放 |
| P6 | **主线程单线程回调** | 内核不需要考虑并发；外部数据（网络、文件）必须自行缓存后主线程消费 |

---

## 2. 接口全景

```
                    ┌──────────────────────────────────┐
                    │        DispatchKernels           │  注册表（mod init 期）
                    │  register(id, factory)           │
                    └───────────────┬──────────────────┘
                                    │ 玩家在配置里选一个
                                    ▼
   ┌────────────────────────────────────────────────────────────┐
   │                     DispatchKernel                         │  你的大脑
   │  onAttach(env)                                             │
   │  onFrame(FrameContext in, DispatchFrame out)   ← 每 tick    │
   │  onTopologyChanged(delta, env)                             │
   │  onEvent(event, env)                                       │
   │  onDetach()                                                │
   └───────┬───────────────────────────────────────┬────────────┘
           │ 读                                     │ 写
           ▼                                        ▼
   ┌───────────────┐                        ┌───────────────┐
   │ FrameContext  │                        │ DispatchFrame │
   │  topology()   │  Topology（只读拓扑）   │  command()    │  TrainCommand
   │  telemetry()  │  Telemetry（列车状态）  │  aspect()     │  AspectCommand
   │  signalStates │  SignalState[]         │  switchLock() │  SwitchCommand
   │  switchStates │  SwitchState[]         │  event()      │  RailwayEvent
   │  pendingEvents│  RailwayEvent[]        └───────────────┘
   └───────────────┘
           │
           ▼
   ┌───────────────┐
   │  KernelEnv    │  生命周期：state() / markStateDirty() / log() / settings()
   └───────────────┘
```

---

## 3. 逐接口

### 3.1 `DispatchKernel` —— 你的调度大脑

```java
public interface DispatchKernel {
    ResourceLocation id();
    default int apiVersion() { return 1; }
    void onAttach(KernelEnv env);
    void onFrame(FrameContext in, DispatchFrame out);
    default void onTopologyChanged(Topology.TopologyDelta delta, KernelEnv env) {}
    default void onEvent(RailwayEvent event, KernelEnv env) {}
    default void onDetach() {}
}
```

**只有 `onFrame` 是必须实现的。** 其余都是钩子。

调用顺序：

```
onAttach
  └─ 每 tick： [所有 onEvent] → [onTopologyChanged?] → onFrame
onDetach
```

### 3.2 `KernelEnv` —— 生命周期服务

| 成员 | 用途 |
|---|---|
| `state()` | 内核私有 `CompoundTag`，宿主负责存盘与恢复 |
| `markStateDirty()` | 改完 `state()` 必须调 |
| `topology()` | 等价于帧内的 `topology()`，可在事件回调里用 |
| `log(level, msg, args)` | 限速日志（同消息每 tick 最多一条） |
| `settings()` | `maxTrainLength` / `failSafeOnError` / `tickBudgetMillis` / 自由键值 |

### 3.3 `Topology` —— 只读拓扑

| 方法 | 说明 |
|---|---|
| `version()` | 拓扑版本，任何变更递增。**跨帧缓存以此失效** |
| `nodes()` / `node(id)` | 节点。`Node.degree >= 3` 即岔口 |
| `edgesFrom(node)` / `edges()` / `edge(id)` | 边 |
| `signals()` | 信号机（含双面信息） |
| `switches()` | 岔口（含支线列表与 `normal`） |
| `stations()` | 车站（含站台区间与容量） |
| `sections()` / `sectionsOf(edge)` | 逻辑区段（闭塞分区） |
| `maxTrainLength()` | 线路允许最长列车 |

**关键记录**

```java
record Node(NodeId id, int degree, boolean isSwitch) {}
record Edge(EdgeId id, double length, boolean interDimensional, boolean isTurn, String trackMaterialId) {}
record SignalSite(UUID id, EdgeId edge, double distance, boolean hasFirstFace, boolean hasSecondFace, boolean crossSignal) {}
record SwitchSite(NodeId node, List<EdgeId> branches, int normal) {}
record StationSite(UUID id, Component name, EdgeId edge, double distance, boolean assembled, List<Platform> platforms) {}
record Section(SectionId id, SectionKind kind, List<Span> spans, double length, String displayName, UUID owningStation) {}
enum SectionKind { MAIN, SIDING, PLATFORM, OTHER }
```

> `SectionKind` 由**玩家标注**——Create 没有任何「正线/侧线」概念，无法从拓扑推断。见 `docs/05` §7。

> ⚠ **`SectionId` 是宿主自己发的，不是 Create 的 `SignalEdgeGroup.id`。**
> 后者每次重算分区都会换（`SignalPropagator.java:79` 的 `UUID.randomUUID()`），不可持久。
> 宿主的做法：注册一个自研「绝缘节」`EdgePointType`，用边点 UUID 做稳定锚点，区段 = 两个绝缘节之间的边链。见 `docs/05` §7.5。

### 3.4 `Telemetry` —— 列车实时状态

```java
record TrainState(
    UUID id, Component name,
    double length,
    Rail.EdgeId leadingEdge, double leadingDistance,
    Rail.EdgeId trailingEdge, double trailingDistance,
    List<Rail.Span> footprint,          // ★ 连续占用区间，车尾→车头
    double speed, double targetSpeed,   // ★ 格/tick
    double acceleration, double maxSpeed,
    int carriages, boolean doubleEnded,
    boolean manuallyDriven, boolean derailed,
    double odometer,                    // ★ 累计里程
    RouteState route, double authority  // ★ 当前授权
) {}
```

两个静态工具：

```java
TrainState.stoppingDistance(v, a)   // = v²/(2a)
trainState.overshoots(distance)     // 是否已无法在 distance 内停住
```

### 3.5 `Command` —— 写回指令

```java
record TrainCommand(
    UUID trainId,
    Direction direction,          // KEEP / FORWARD / REVERSE   ← 「方向」
    double movementAuthority,     // 距离授权（格），<=0 即停车  ← 「移动闭塞」
    double speedCap,              // 绝对限速（格/tick）
    boolean hold,                 // 扣车                    ← 「时间/停站」
    RouteRequest route            // 进路申请，null = 不变     ← 「正线/侧线/道岔」
) {}

record RouteRequest(NodeId destination, List<EdgeId> path, RoutePriority priority, String reason) {}
record AspectCommand(UUID signalId, SignalSide side, ResourceLocation aspectId) {}
record SwitchCommand(NodeId node, EdgeId branch, long holder) {}
```

**信号显示只传语义 id，不传灯位。** 因为「通行」在四显示机上亮第 4 号灯座、在三显示机上亮第 3 号，
但那是**同一个语义**——灯位映射属于机型（`SignalType`），不属于内核。

内置语义（可注册扩展）：

| id | 类别 | 限速系数 |
|---|---|---|
| `ctdapi:stop` | STOP | 0.00 |
| `ctdapi:caution` | CAUTION | 0.55 |
| `ctdapi:advance_caution` | ADVANCE_CAUTION | 0.40 |
| `ctdapi:proceed` | PROCEED | 1.00 |
| `ctdapi:shunt` | SHUNT | 0.30 |
| `ctdapi:call_on` | CALL_ON | 0.20 |
| `ctdapi:dark` | DARK | 0.00 ← 视为「无此信号」，**不是红灯** |

内核通过 `Topology.signalType(id)` / `Topology.aspect(id)` 拿到定义，只看
`Aspect.Category` 与 `speedFraction`，**不需要认识任何具体制度**。

> 完整设计见 **[`docs/08-signal-registry-api.md`](08-signal-registry-api.md)**（开放注册：灯色 / 语义 / 机型三层）。
> 多灯信号机的方块与渲染见 **[`docs/07-custom-signals.md`](07-custom-signals.md)**。
> 背景：**Create 原生信号机只有一个灯**，亮红或灭——`SignalRenderer` 只判 `isRedLight`，`isYellowLight`/`isGreenLight` 从未被信号机调用。

### 3.6 `RailwayEvent` —— 事件

| 事件 | 用途 |
|---|---|
| `TrainArrived` / `TrainDeparted` | 时刻表校验、PIS 播报 |
| `TrainHeld` | 扣车原因进 PIS |
| `TrainFaulted` | 脱轨/故障，宿主已停止自动控制 |
| `RouteSet` / `RouteCancelled` | 进路生命周期 |
| `SwitchLocked` | 岔口锁闭变化 |
| `SignalChanged` | 灯色变化 |
| `OccupancyConflict` | **兜底断言**：两车占用重叠。出现即说明调度有 bug |
| `KernelFault` | 内核抛异常被宿主捕获 |

---

## 4. ★ 你的需求 → 用哪个接口

| 需求 | 读 | 写 |
|---|---|---|
| **正线优先** | `Topology.sections()` 里 `kind == MAIN` 的区段；`Telemetry` 的 `footprint` 判断是否被占 | `RouteRequest.path` 里优先选 MAIN 区段；`TrainCommand.movementAuthority` 控制进入时机 |
| **侧线待避** | `Section.kind == SIDING` + `Section.canHold(trainLength)` | `RouteRequest` 把车排进侧线；`TrainCommand.hold = true` 扣停 |
| **道岔锁闭 / 防挤岔** | `Topology.switches()`、`FrameContext.switchStates()` | `Command.SwitchCommand`（`holder` = 你的进路 id） |
| **进路排列** | `Topology.signals()`、`Topology.edgesFrom()` 自己算路径 | `TrainCommand.route` |
| **通行方向（上下行）** | `TrainState.doubleEnded`、`manuallyDriven` | `TrainCommand.direction` |
| **时刻表 / 停站时分** | `RailwayEvent.TrainArrived/Departed`、`TrainState.odometer`（实测区间运行时分） | `TrainCommand.hold` 扣车；解除即可发车 |
| **移动闭塞** | `TrainState.footprint`（前车占位）、`acceleration`（制动能力）、`speed` | `TrainCommand.movementAuthority`（连续距离） |
| **固定闭塞** | `Topology.sections()` + 各车 `footprint` 落在哪些 `Section` | 同上，只是把 MA 算到「下一个空闲区段边界」 |
| **超速防护 / 限速曲线** | `Topology.Edge.isTurn`（弯道）、`maxSpeed` | `TrainCommand.speedCap` |
| **PIS 到站预测** | `odometer` + 历史区间时分 + `movementAuthority` | `DispatchFrame.event(...)`；宿主桥接到显示板 / 网络 |
| **人工优先** | `TrainState.manuallyDriven` | 该车不写指令（或写 `hold=false, MA=∞`） |

> **一句话**：`movementAuthority` 管「能走多远」，`speedCap` 管「能走多快」，`route` 管「走哪条」，`hold` 管「什么时候走」，`direction` 管「往哪头」，`SwitchCommand` 管「岔口归谁」。六个自由度，覆盖你列出的全部需求。

---

## 5. 最小内核示例

一个「固定闭塞 + 距离授权」的 40 行内核：

```java
public final class SimpleBlockKernel implements DispatchKernel {

    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("mymod", "simple_block");

    @Override public ResourceLocation id() { return ID; }

    @Override public void onAttach(KernelEnv env) { }

    @Override
    public void onFrame(FrameContext in, DispatchFrame out) {
        Topology topo = in.topology();

        for (Telemetry.TrainState t : in.telemetry().trains()) {
            if (t.derailed()) continue;
            if (t.manuallyDriven()) continue;          // 人工优先，不干预

            // 1. 找到车头前方的「自己的进路终点」
            if (t.route() == null) {
                out.hold(t.id(), "无进路");            // 扣车
                continue;
            }

            // 2. 前方占用：把其他车的 footprint 投影到本车前方
            double clear = nearestObstacleAhead(t, in.telemetry(), topo);

            // 3. 授权 = min(进路剩余, 到障碍距离 - 安全余量)
            double ma = Math.min(t.route().remaining(), clear) - SAFETY_MARGIN;
            ma = Math.max(0, ma);

            // 4. 速度上限 = 能在 ma 内停下的速度，再与信号显示取小
            double vStop = Math.sqrt(2 * t.acceleration() * ma);
            double vSig = t.maxSpeed() * aspectFractionFor(t, in);
            double cap = Math.min(vStop, vSig);

            out.proceed(t.id(), ma, cap);
        }

        // 5. 灯色：本车进路覆盖到的信号显示 PROCEED，其余 STOP
        for (Topology.SignalSite s : topo.signals()) {
            out.aspect(s.id(), Topology.SignalSide.FIRST,
                       coveredByAnyRoute(s) ? Command.Aspect.PROCEED : Command.Aspect.STOP);
        }
    }
}
```

注册：

```java
// 在你的 mod 构造器里
DispatchKernels.register(SimpleBlockKernel.ID, SimpleBlockKernel::new);
```

---

## 6. 边界：模组写什么，内核写什么

「具体调度不必写进模组」——那么模组到底要写什么？

### 宿主（模组）必须提供

| # | 职责 | 关键实现 |
|---|---|---|
| H1 | **拓扑镜像** | `@Mixin` `TrackGraph.addNode/removeNode/connectNodes/disconnectNodes`，维护 `Map<NodeId, Node>` 等索引，产出 `Topology` 快照。**稳定键只能用 `Rail.NodeId`（值语义），不能用 `TrackNode` 引用或 `netId`**——两者在每次编辑与读档后全部重建（`docs/research/topology-and-switches.md` §5.6） |
| H2 | **遥测采样** | 每帧从 `Train` / `Carriage` / `TravellingPoint` 算 `footprint`、`odometer` |
| H3 | **执行器** | 4 个注入点：`isOccupiedUnless`（解除）、`tickPassiveSlowdown`（速度）、`tickState`（灯色）、`Navigation.currentPath`（进路） |
| H4 | **安全信封** | 应用指令前做兜底：速度取 min、授权不超过前车尾部、路径必须覆盖到下一岔口 |
| H5 | **持久化** | 每内核一个 `CompoundTag` + 自己的 `SavedData`（区段标注、时刻表） |
| H6 | **区段标注工具** | 玩家把某段轨道标成 正线/侧线/站线——**Create 没有这个信息，必须玩家给** |
| H7 | **PIS 桥接** | `RailwayEvent` → `DisplaySource` / 显示板 / 网络包 / Web API |
| H8 | **配置与开关** | 选哪个内核、`isActive()` 总开关、`failSafeOnError` |

### 内核（第三方）负责

- 进路怎么排、什么时候排
- 闭塞怎么分、授权给多少
- 道岔给谁锁
- 时刻表怎么生成、延误怎么传播
- 冲突怎么仲裁、死锁怎么解

> **分界线**：宿主负责「与 Create 的接口和物理安全」，内核负责「调度策略」。内核永远不该看见 `TrackNode`、`Train`、`SignalBoundary` 这些类型。

---

## 7. 版本策略

| 项 | 约定 |
|---|---|
| `apiVersion()` | 当前 1。破坏性变更时递增，宿主拒绝加载不匹配的内核 |
| 接口演进 | 只加 `default` 方法，不删不改已有签名 |
| `Command` 记录 | 一旦发布就不改字段顺序（记录是值类型，靠构造器兼容性） |
| 内核降级 | 任一回调抛异常 → 该帧指令全丢 → `failSafeOnError` 时退回原版行为 |
| 多内核 | 同时只激活一个；切换时旧内核 `onDetach()`，其 `state()` 保留不删 |

---

## 8. 与 CRN 的关系

| | CRN | 本方案 |
|---|---|---|
| 抽象层次 | 「加软代价 + 自建时刻表后端」 | 「接管判定 + 显式授权」 |
| 闭塞 | 无（沿用 Create 的 `SignalEdgeGroup`） | 自有，可做移动闭塞 |
| 对 Create 的注入 | 18 个 mixin，含 `Navigation.search` / `ScheduleRuntime.tick` | 4 个 mixin，CRN 一个都没碰 |
| 冲突 | — | `isOccupiedUnless` / `tickPassiveSlowdown` / `tickState` / `TrackGraph.add*` 全无交集 |

**共存方式**：CRN 的 `ScheduleRuntime` 注入会因为 `paused = true` 而空转，它的寻路加价仍然生效。
若要协作，走 CRN 的公开 API（`INavigationPenaltyProvider` / `SpeedLimitProviderRegistry` / `RailwayBackendEvents`），不要 mixin 它。
