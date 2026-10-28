# 05 — 整体接管：把 Create 变成「轨道图 + 物理引擎」

> 基线：Create `7ff2760`（6.0.11）、NeoForge 21.1.219、MC 1.21.1
> 配套接口：`mods/ctdapi/src/main/java/top/hibermod/ctdapi/`（可编译的 Java 契约）、`docs/06-dispatch-spi.md`

---

## 0. 先回答「能不能」

**能。而且不需要 `@Overwrite` Create 的任何方法。**

关键洞察：Create 把「决策」和「执行」分得比看起来干净。

| | 内容 | 我们 |
|---|---|---|
| **决策层** | `Navigation.tick` 算 targetSpeed、`SignalBoundary.tickState` 算灯色、`ScheduleRuntime.tick` 推进时刻表 | ✅ **全部换掉** |
| **执行层** | `Carriage.travel` 推进 `TravellingPoint`、contraption 移动实体、张力/间距、脱轨判定、传送门 | ✅ **原样保留** |

**重写物理是自杀；接管决策是可行的。** 本文给出的方案，注入面只有 **5 个 mixin**（外加拓扑镜像的 4 个增删钩子），全部是 `@Inject`，零 `@Overwrite`。

---

## 1. Create 原生 vs 真实铁路：到底缺什么

| 能力 | 真实铁路 | Create 原生 | 差距 |
|---|---|---|---|
| 闭塞 | 固定闭塞 / 准移动闭塞 / 移动闭塞 | **固定闭塞**，粒度 = `SignalEdgeGroup`（两信号机之间） | 粒度粗、无法做移动闭塞 |
| 道岔 | 独立设备，有定位/反位、锁闭 | **不存在**。岔口 = 连接数 ≥3 的 `TrackNode` | 无法表达岔位、无法锁闭 |
| 进路 | 始端→终端 + 锁闭 + 解锁 | **不存在**。只有「去哪个站」和一条路径 | 无法排列进路、无法防挤岔 |
| 联锁 | 信号/道岔/区段的互斥表 | **不存在** | 全靠信号机占用推导 |
| 时刻表 | 图定时刻、运行时分、停站时分 | 简单的「指令 + 等待条件」 | 无时刻校验、无延误传播 |
| 方向 | 上下行、双向运行 | 有 `doubleEnded` / `currentlyBackwards`，但无上下行概念 | 无方向调度 |
| 超速防护 | ATP / CTCS | 无。只有「到障碍前刹停」 | 无限速曲线 |
| PIS | 到站预测、换乘引导、延误播报 | 翻牌显示板 + 简单站名 | 无预测、无换乘 |
| 中心调度 | 调度集中（CTC） | 无。每列车自治 | 无全局优化 |

**结论**：Create 的本质是**有轨电车模型**——一辆车、一条路、前方有信号就停。要支撑超大线网与真 PIS，必须把上面 9 行全部补上。

---

## 2. 接管的七层模型

| 层 | Create 原语 | 接管手段 | 侵入度 |
|---|---|---|---|
| **L1 拓扑** | `TrackGraph` / `TrackNode` / `TrackEdge` | 只读镜像 + `@Mixin` 4 个增删方法做增量事件 | 低 |
| **L2 占用** | `SignalEdgeGroup.trains` / `isOccupiedUnless` | `@Inject(RETURN)` 让原生判定恒 false（**解除**） | 低 |
| **L3 判定** | `Navigation.waitingForSignal` / `currentSignalResolved` | 因为 L2 已解除，原生永不置位（**自然失效**，无需注入） | 零 |
| **L4 执行** | `Train.speed` → `Carriage.travel` | `@Inject(TAIL)` 于 `tickPassiveSlowdown` 写速度（**施加**） | 低 |
| **L5 表现** | `SignalBoundary.cachedStates` | `@Inject(TAIL)` 于 `tickState` 写灯色（**施加**） | 低 |
| **L6 路权** | `Navigation.currentPath` / `destination` | `@Accessor` 直写（**施加**） | 中 |
| **L7 调度** | `ScheduleRuntime` | `runtime.paused = true` 静默 + 自建时刻表（**替换**） | 低 |

---

## 3. ★ 核心策略：解除 + 施加（Neutralize & Impose）

### 3.1 解除原生闭塞

```java
@Mixin(SignalEdgeGroup.class)
public abstract class NeutralizeOccupancyMixin {
    @Inject(method = "isOccupiedUnless(Lcom/simibubi/create/content/trains/entity/Train;)Z",
            at = @At("RETURN"), cancellable = true)
    private void tcm$release(Train train, CallbackInfoReturnable<Boolean> cir) {
        if (CtdControl.isActive()) cir.setReturnValue(false);
    }

    @Inject(method = "isOccupiedUnless(Lcom/simibubi/create/content/trains/signal/SignalBoundary;)Z",
            at = @At("RETURN"), cancellable = true)
    private void tcm$releaseSignal(SignalBoundary b, CallbackInfoReturnable<Boolean> cir) {
        if (CtdControl.isActive()) cir.setReturnValue(false);
    }
}
```

一行返回 false，原生闭塞就整体失效：

- `Navigation.java:165` 的 `occupied` 恒 false → **永不设 `waitingForSignal`** → L3 判定层自然失效，**不需要任何注入**。
- `Navigation.java:325/338` 的 `currentSignalResolved` 不会被调用（`waitingForSignal == null`）。
- `Train.java:484-489` 的越线硬停永不触发（条件要求 `waitingForSignal` 非空）。
- `SignalBoundary.tickState:206` 的 `occupied` 恒 false → 所有信号默认 GREEN（稍后由 L5 覆写）。

> **为什么选这一层而不是 `Navigation`**：`isOccupiedUnless` 是**语义单点**，4 处调用者全都走它。改它等于一次性拆掉整个原生判定，且不会破坏任何数据结构。

### 3.2 但**不**解除红石

`SignalBoundary.isForcedRed(...)` 保持原样。红石供电 = **人工紧急关闭信号**，仍然能让列车硬停（`Navigation:165` 的 `signal.isForcedRed(...)` 不受我们影响）。

这是刻意的：给玩家保留一条「物理上无法被软件覆盖」的扳道手段。

### 3.3 施加速度授权

**这是整套方案里最关键的一个注入点。**

`Train.tick` 的执行顺序（`Train.java:289-460`）：

```java
307  updateConductors();
308  runtime.tick(level);
309  navigation.tick(level);          // ← 原生算 targetSpeed，并可能直写 speed
310
311  tickPassiveSlowdown();           // ← ★ 我们的注入点（TAIL）
312  if (derailed) tickDerailedSlowdown();
...
315  double distance = speed;         // ← 速度在这里被采样
...
423  carriage.travel(level, graph, distance + stress, ...);
```

`tickPassiveSlowdown` 是**唯一一个「所有决策都跑完、物理还没开始」的位置**：

```java
@Mixin(Train.class)
public abstract class TrainActuatorMixin {
    @Inject(method = "tickPassiveSlowdown", at = @At("TAIL"))
    private void tcm$applyAuthority(CallbackInfo ci) {
        Train self = (Train) (Object) this;
        CtdControl.actuate(self);   // 自己算 speed，直接写 self.speed
    }
}
```

为什么是这里，而不是 `Navigation.tick` 的 RETURN：

| 候选点 | 问题 |
|---|---|
| `Navigation.tick` RETURN | `tick` 有 4 条提前 `return`（`:245 :253 :258 :271`）。`@At("RETURN")` 虽然能匹配全部返回，但**当 `destination == null` 时它会立刻返回（`:74`）**，而那时我们又需要控制速度。且原生在同一方法里已调过 `approachTargetSpeed`，我们再调会**加倍加速度** |
| `Train.approachTargetSpeed` HEAD | 只能加速度，不能限速；且原生另有 4 处**绕过它直接写 `speed`**（`Navigation:257`、`:270`、`Train:469`、`:486`） |
| **`Train.tickPassiveSlowdown` TAIL** ✅ | 在 `navigation.tick` 之后、`distance = speed` 之前；一帧一次；原生所有直写都已被我们覆盖；`manualTick` 已在 `:601` 复位，语义干净 |

**注入实现要点**

> ⚠ **绝不可在 `tickPassiveSlowdown` 的 HEAD 处 `cancellable`**。方法体被跳过会导致挂在 TAIL 的硬安全闸**永不执行**；而且 `require = 0` 时这种失效是静默的——列车会以原版逻辑乱跑却没有任何报错。要禁用它的滑行行为，应该在 TAIL 里覆盖结果，不是取消调用。

```java
static void actuate(Train train) {
    if (!isActive() || train.derailed || train.graph == null) return;
    if (train.manualTick) return;                      // 人工驾驶让行

    double allowed = MyAuthority.speedFor(train);       // 由 MA 与限速算出的上限
    double native = train.targetSpeed;                  // 原生意图（含弯道限速）
    // fail-safe：只能收紧
    double target = Math.min(allowed, Math.abs(native) == 0 ? allowed : Math.abs(native));

    double a = train.acceleration();
    double v = train.speed;
    if (v < target)      train.speed = Math.min(v + a, target);
    else if (v > target) train.speed = Math.max(v - a, target);
}
```

> 注意：`train.speed` 是 `public` 字段，可直接写。写完 `tickPassiveSlowdown` 立刻返回，`distance = speed` 会取到新值。

**为什么这个方案比「覆写 targetSpeed」少几个注入点**

`docs/research/kinematics-and-occupancy.md` §9.1 给出的最小接管集是 M1-M6（因为它保留原生 `targetSpeed` 计算并覆写它）。我们选择**先在占用层解除、再在出口钳位**，于是可以省掉：

| 报告推荐的注入 | 我们为什么不需要 |
|---|---|
| M2 `Navigation#tick` 所有 RETURN | 原生算出的 `targetSpeed` 我们**根本不消费**，算错也无所谓 |
| M3 `Train#approachTargetSpeed` TAIL | 出口钳位在它之后，覆盖它的一切写入 |
| M5 `Train#tickPassiveSlowdown` HEAD cancel | 它的作用是「无 destination 时滑行到 0」，正是我们要的保持行为 |

**代价**：多浪费一次原生计算；且必须确保**钳位一定在 `distance = speed` 之前**执行。收益：注入面从 6 降到 4。

### 3.4 施加灯色

```java
@Mixin(SignalBoundary.class)
public abstract class AspectPublishMixin {
    @Inject(method = "tickState", at = @At("TAIL"))
    private void tcm$publishAspects(TrackGraph graph, CallbackInfo ci) {
        if (!CtdControl.isActive()) return;
        SignalBoundary self = (SignalBoundary) (Object) this;
        for (boolean side : Iterate.trueAndFalse) {
            Command.Aspect a = AspectService.aspectFor(self.id, side);
            if (a != null) self.cachedStates.set(side, toNative(a));   // 字段是 public
        }
    }
}
```

`SignalBoundary.cachedStates` 是 **public** 字段（`SignalBoundary.java:37`），无需 `@Shadow` 或 AT。

`Aspect` → 原生三灯的映射：

| 我们的 Aspect | `SignalState` | 渲染 |
|---|---|---|
| `STOP` | `RED` | 红 |
| `RESTRICTING` | `YELLOW` | 黄（调车） |
| `CAUTION` | `YELLOW` | 黄（预告） |
| `PROCEED` | `GREEN` | 绿 |

> 想区分「黄闪 / 双黄」这类进阶灯语，就必须扩 `SignalState` 枚举并改 `isYellowLight` 的渲染分支——工作量远大于收益，见 `docs/02` §2.3。**建议先用三灯打天下**，把信息量放进 PIS 而不是灯。

### 3.5 施加进路

Create 的路径跟随链路：

```
Carriage.travel:151-154
  forwardControl = train.navigation::control          ← 只有 destination != null 才跟随路径
  ...
  point.travel(..., trackSelector, ...)               ← TravellingPoint.travel:198
      → edgeTraversedFrom                            ← 逐边点回调
      → navigateOptions(path, graph, options)        ← Navigation:360
           path.get(0) 与候选边比对，匹配则 path.remove(0) 并前进
           不匹配 / 路径空 → return options.get(0)   ← ⚠ 随手挑第一个分支！
```

**`navigateOptions:362-372` 的两个 `options.get(0)` 是脱轨与挤岔的根源。** 路径一旦耗尽或不匹配，列车会走一条岔线——而且候选列表的顺序来自 `IdentityHashMap` 的迭代序（`TrackGraph.connectionsByNode`），**跨次运行不可复现**。同一张图、同一位置，重开存档后可能走另一条股道。

因此进路管理的铁律：**永远保持 `currentPath` 覆盖到列车前方的下一个目的地，且在列车到达前不断续排。**

写入方式：

```java
@Mixin(Navigation.class)
public interface NavigationAccessor {
    @Accessor("currentPath") List<Couple<TrackNode>> tcm$currentPath();
    @Accessor("currentPath") void tcm$setCurrentPath(List<Couple<TrackNode>> path);
    @Accessor("signalScout") void tcm$setSignalScout(TravellingPoint p);   // 可选
}
```

（`currentPath` 是**包级** `List<Couple<TrackNode>>`，`Navigation.java:58` —— 必须用 `@Accessor`。）

更稳妥的做法是走原生的 `startNavigation(DiscoveredPath)`（`Navigation.java:386`），它会一次性做对：
清 `reservedSignalBlocks`、清 `waitingForSignal`、设 `destinationBehindTrain`、校验司机。我们只需构造一个 `DiscoveredPath(distance, cost, path, destination)`。

> **`destination` 的存在 = 授权在场**。原生 `Navigation.tick` 第一行就是 `if (destination == null) return;`（`:74-75`），此时 `control()` 退回 `mp.steer(...)`（`:347-349`），列车自由滑行、岔口随机走。**所以「扣车」必须同时 `speed = 0`，不能只清 destination。**

### 3.5b ⚠ 最后一个硬缺口：怎么把车开到「非车站」目标

`Navigation.control` 的契约是：

```java
public ITrackSelector control(TravellingPoint mp) {
    if (destination == null)
        return mp.steer(train.manualSteer, new Vec3(0, 1, 0));   // :348-349  自由滑行
    return (graph, pair) -> navigateOptions(currentPath, graph, pair.getSecond());   // :350
}
```

而 `destination` 的类型是 **`GlobalStation`**（`Navigation.java:53`），`DiscoveredPath.destination` 也是。→ **Create 的整套路径跟随，只认识「车站」这一种终点。**

这对「精确调度侧线」是致命的：**侧线不是车站**。

两条出路：

**方式 A：寄生在车站上（低侵入，但受限）**

用车牌 / 虚拟站顶替。可以在自己维护的图里用 `graph.addPoint(EdgePointType.STATION, surrogate)` 合成一个「虚拟车站」，让 `startNavigation` 直接指向它。

| 优点 | 缺点 |
|---|---|
| 零新增注入 | 虚拟站会出现在 Create 的车站列表、地图标记、站台 UI 里，需要额外屏蔽 |
| 复用原生到站/停车 | 终点必须是「站」的语义；侧线尽头要人为造站 |

**方式 B：接管 `control`（推荐给「整体接管」）**

```java
@Mixin(Navigation.class)
public abstract class RouteControlMixin {
    @Inject(method = "control", at = @At("RETURN"), cancellable = true)
    private void tcm$route(TravellingPoint mp, CallbackInfoReturnable<TravellingPoint.ITrackSelector> cir) {
        if (!CtdControl.isActive()) return;
        Navigation self = (Navigation) (Object) this;
        cir.setReturnValue((graph, pair) -> CtdRouter.chooseEdge(self.train, graph, pair));
    }
}
```

我们的 `CtdRouter` 自己维护「进路游标」，不依赖 `currentPath` 的 `remove(0)` 消费，也不依赖 `destination` 存在。

| 优点 | 缺点 |
|---|---|
| 终点可以是**任意节点/任意距离**，侧线、尽头线、存车线全都行 | 多一个注入点（第 5 个）；要自己实现「沿进路选边」 |
| 扣车不再依赖「清 destination」，语义解耦 | 与原生 `navigateOptions` 的路径语义并存时要注意不要互相污染 |
| 可以做到「行驶中改派」（换进路不打断） | ⚠ 必须自己保证「路径永不为空」，否则 `chooseEdge` 退化成随机 |

**决策**：整体接管走**方式 B**。理由很简单——用户要的是「精确调度侧线」，而侧线在 Create 里根本不是终点类型。

> 采用方式 B 后，`RouteRequest.destination` 用 `Rail.NodeId`（任意节点）就成立了，见 `docs/06` 的 SPI 设计。

### 3.5c ⚠ 另一个坑：`navigateOptions` 的路径是**会越用越少**的

`navigateOptions` 每前进一条边就 `path.remove(0)`（`Navigation.java:369`）。路径耗尽 → `options.get(0)` 随机分支。

但**好消息**：如果 `runtime.paused = true`，`Train.updateNavigationTarget`（`:546-585`）里的重规划分支会被跳过——

```java
boolean navigatingManually = runtime.paused;      // :556
...
if (!navigatingManually && fullRefresh) {          // :579
    DiscoveredPath preferredPath = runtime.startCurrentInstruction(level);
    ...
}
```

→ 原生**不会**在你背后用时刻表重算路径覆盖你。这是 `paused` 带来的一个隐性红利。

代价：`distanceToDestination` 的扣减仍然每帧发生（`:558`），但既然我们用方式 B 自己算里程，就不依赖它。

### 3.6 静默原生时刻表

```java
train.runtime.paused = true;    // ScheduleRuntime.java:107 → tick 立刻 return
```

不 mixin。`ScheduleRuntime` 的三个「静默开关」见 `docs/03` §4.1。

### 3.7 汇总：每个原生决策点，我们的对策

| 原生决策点 | 位置 | 对策 |
|---|---|---|
| 前方区段占用 → 红灯 | `Navigation:164-165` | L2 解除后恒 false |
| 红灯 → 停车 | `Navigation:174-178` | 同上，`waitingForSignal` 永不置位 |
| 到障碍刹车距离 | `Navigation:278` | 同上，`targetDistance` 恒为 `distanceToDestination` |
| 越线硬停 | `Train:484-489` | 条件不成立；红石强制红时保留（应急） |
| 车站到达硬停 | `Train:465-475` | **保留**（我们复用原生到站） |
| 灯色 | `SignalBoundary:206-207` | L5 覆写 |
| 时刻表推进 | `ScheduleRuntime:104` | `paused = true` |
| 自动重寻路 | `Train:546-585` | destination 由我们管理；`runtime.paused` 后 `startCurrentInstruction` 不会被调 |
| 岔口选边 | `Navigation:360-372` | 由 `currentPath` 决定；**必须持续喂路** |
| 手动驾驶 | `Train:601` / `manualTick` | 尊重，自动系统让行 |

---

## 4. 更新后的每帧时序

```
CommonEvents.onServerWorldTick (LevelTickEvent.Post)        CommonEvents.java:132
└─ Create.RAILWAYS.tick(level)                              GlobalRailwayManager.java:193
   │
   ├─ ① 清空 SignalEdgeGroup.trains / reserved              :197-200
   │
   ├─ ★ TCM.preTick()        [新增，@Inject HEAD]           ← 调度帧：读遥测、算授权、排进路
   │                                                          发出 DispatchFrame
   ├─ ② graph.tickPoints(true)                              :203    分区传播
   ├─ ③ resolveIntersectingEdgeGroups                       :204
   ├─ ④ tickTrains(level)                                   :207
   │   ├─ train.earlyTick  → addToSignalGroups              Train.java:193-208
   │   ├─ train.tick                                        Train.java:289
   │   │   ├─ runtime.tick      → paused，空转                     :308
   │   │   ├─ navigation.tick   → isOccupiedUnless 恒 false，
   │   │   │                      因此不设 waitingForSignal        :309
   │   │   ├─ tickPassiveSlowdown → ★ TCM.actuate(train)          :311
   │   │   │     （写 train.speed = 授权速度）
   │   │   ├─ distance = speed                                    :315
   │   │   └─ carriage.travel → navigateOptions(currentPath)      :423
   │   │        └─ 穿点回调 frontSignalListener                    :462
   │   │             （红灯分支不触发；车站到达照常）
   │   └─ train.tick
   │
   ├─ ⑤ graph.tickPoints(false)                             :210
   │   └─ SignalBoundary.tickState                          SignalBoundary.java:186
   │       └─ ★ TCM 覆写 cachedStates（注入 TAIL）           ← 灯色下发
   │
   └─ ★ TCM.postTick()       [可选，@Inject RETURN]         ← 统计、PIS 推送
```

**一帧的因果链**：`preTick` 算好「谁能走多远」→ 列车按授权移动 → `tickState` 把结果变成灯 → 下一帧 `preTick` 再读。

> 注意 ① 与 ② 之间：`SignalEdgeGroup.trains` 刚被清空，此时读占用一定是空的。**遥测必须从 `Train` 的 `occupiedSignalBlocks` / `footprint` 拿，不能从 `SignalEdgeGroup` 拿。**

---

## 5. 拓扑镜像：稳定 ID 是地基

### 5.1 问题

`TrackNode` / `TrackEdge` 是**短生命周期对象**。轨道被拆改、区块卸载重载、图被拆合，对象都会重建。第三方内核若持有引用 → 必然失效。

### 5.2 解法：值语义 ID

`TrackNode` 的 `location` 是 `TrackNodeLocation extends Vec3i`（`TrackNodeLocation.java:23`），打包方式：

```java
public TrackNodeLocation(double x, double y, double z) {
    super(Mth.floor(Math.round(x * 2)), Mth.floor(y) * 2, Mth.floor(Math.round(z * 2)));   // :33
}
```

即 **水平半格量化 + 垂直半格**，再加 `yOffsetPixels`（1/16 格）和 `dimension`。`equals` 要求同维度同偏移（`:62-69`），`hashCode` 含维度（`:72-74`）。

→ 稳定 ID 直接照抄这套量化，见 `src/main/java/top/hibermod/ctdapi/Rail.NodeId`。

**边 ID** = 两个端点 ID 的**规范化有序对**（Create 内部一条边存两遍，`connectionsByNode` 是双向的）。

### 5.3 增量事件（需要 4 个 mixin）

Create 只在**图合并**时发了事件（`TrackGraphMergeEvent`，`TrackPropagator.java:144`），没有「边被添加/删除」的事件。要维护镜像，需注入：

| 目标 | 位置 | 变更 |
|---|---|---|
| `TrackGraph.addNode` | `:150` | +节点 |
| `TrackGraph.removeNode` | `:165` | -节点（连带边） |
| `TrackGraph.connectNodes` | `:387` | +边（内部经 `putConnection:456` 双向注册） |
| `TrackGraph.disconnectNodes` | `:447` | -边 ×2 |

另外订阅 `TrackGraphMergeEvent`（NeoForge 事件总线，`TrackPropagator.java:144` 发出）与 `GlobalRailwayManager.updateSplitGraph`（`GlobalRailwayManager.java:162`）→ `structureBroke = true`，全量重建。

### 5.4 规模：Create 的真实瓶颈在哪

| 环节 | 复杂度 | 位置 | 影响 |
|---|---|---|---|
| 每帧 tick 边点 | **O(全部边点)** ×2 | `EdgePointStorage.tick`（两次扫全表） | 1000 个信号 = 2000 次 `tick`/帧。**可接受** |
| 每帧 tick 列车 | O(列车) | `GlobalRailwayManager.tickTrains` | 可接受 |
| **铺轨** | **O(全网边数)** **每次放置** | `TrackGraph.connectNodes:397-437`——`for (graph : 所有图) for (node : graph.nodes) for (连接)` 为的是找平交道 | ⚠ **建网总成本 O(N²)**：5000 条边时每次铺轨扫 ~10000 条连接，累计约 5×10⁷ 次。2 万条边时是 4×10⁸，会卡 |
| 信号分区重算 | O(信号子图) | `SignalPropagator.propagateSignalGroup` | 只在拓扑变化时触发，但**批量铺轨会反复触发** |
| 图包围盒 | O(节点) | `TrackGraphBounds` 构造（`:19-30`），变更后失效重算 | 每次铺轨都会失效 |
| 登录全量同步 | 每包 1000 节点分批 | `TrackGraphSync.sendFullGraphTo` | 大图登录会发几十个包，客户端卡顿 |
| 最近节点查询 | **O(节点)** | `TrackGraph.distanceToLocationSqr:465-474` | ⚠ 线性扫描，**禁止每帧调用** |

**结论**：每帧的性能不是问题（O(列车 + 边点)），**建网过程**才是。这正好解释了「Create 像有轨电车」的直觉——它能跑，但撑不起几十平方公里的大线网。

**我们的对策**

1. **自建索引**：`Map<NodeId, Node>`、`Map<EdgeId, Edge>`、`Map<NodeId, List<EdgeId>>`。5000 条边完全够用。
2. **不自建拓扑时就别碰 `distanceToLocationSqr`**；用自己的空间哈希（按 16×16 区块分桶）。
3. **批量铺轨时抑制信号重算**：给 `propagateSignalGroup` 加一个「建造模式」旁路（`@Inject(HEAD, cancellable)`），把重算推迟到收工后一次性做。
4. **不要每帧全量重建 `Topology` 快照**——用 §5.3 的增量事件维护，只在 `version` 变化时重建一次不可变视图。
5. 若要做到 2 万条边以上，需要**自己重写平交道检测**（用空间分桶替代 O(N) 全扫），这是改动 Create 内部逻辑中性价比最高的一处。

---

## 6. 移动闭塞的数据模型

### 6.1 三个基本量

| 量 | 定义 | 来源 |
|---|---|---|
| **里程计 `odometer`** | 列车自分派以来累计前进的距离（单调递增） | 宿主每帧累加 `speed`，并用 `TravellingPoint` 位置校正 |
| **占用区间 `footprint`** | 车尾→车头在图上的连续区间序列 | 由 `getEndpointEdges()`（`Train.java:1085`）与各 bogey 的 `TravellingPoint` 沿图走 |
| **授权 `MA`** | 允许车头前进的最大距离 | **内核计算**，宿主执行 |

### 6.2 占用区间怎么算

```
front = carriages[0].getLeadingPoint()            // 或倒车时取末车厢尾部
rear  = carriages[last].getTrailingPoint()
```

从 `front` 沿图反向走到 `rear`，累积边长：

```java
List<Rail.Span> footprint(Train train) {
    var front = leading(train);                    // carriages[0].getLeadingPoint()
    var rear  = trailing(train);                   // carriages[last].getTrailingPoint()
    List<Span> out = new ArrayList<>();
    TravellingPoint cursor = front;
    // ★ 不用 train.length 作步进：getTotalLength() 是 int 名义长度，
    //   而耦合压缩（bogeyStress/stress）可差数格。以「走到 rear 为止」为终止条件。
    int guard = 0;
    while (cursor != null && !atSameSpot(cursor, rear) && guard++ < MAX_EDGES) {
        double here = cursor.position;             // 沿当前边，0 端是 node1
        double take = Math.min(here, remainingTo(cursor, rear));  // 向尾部方向取
        out.add(new Span(edgeId(cursor.edge), Math.max(0, here - take), here));
        cursor = stepToward(cursor, rear);         // 沿图退一条边
    }
    // 一致性校验：名义长度与实际走出的总长
    double actual = out.stream().mapToDouble(Span::length).sum();
    if (Math.abs(actual - train.getTotalLength()) > 1.5) {
        CoreState.flagInconsistency(train, actual);   // failSafe：授权降为 0
    }
    return out;
}
```

关键点：
- `TravellingPoint.position` 是沿边的距离（格），`0` 端是 `node1`，`edge.getLength()` 端是 `node2`。
- **⚠ 不要复用 `Train.forEachTravellingPointBackwards`**（`Train.java:877`）做几何——它的 `trailingBogey.leading()` 疑似复制粘贴错误（`docs/research/kinematics-and-occupancy.md` §2.4），道岔处会**欠报**占用。
- **弯道（`isTurn`）的位置是非线性的**：`TrackEdge.incrementT`（`:88-93`）对 `|distance| > 5` 退化为线性。→ **长距离沿边推进会有误差**（`Navigation.java:141-143` 的 `+50` 补丁就是为它打的）。做精确停车时要短步长迭代（≤5 格）。
- 跨维度边的 `getLength()` 恒为 0（`:81-86`）→ 遇到就断开区间，单独上报。
- **间隔记 `TravellingPoint` 而不是只记 `(edge, position)`**：一列车 2×(车厢数×转向架数) 个点，跨边差一条边就可能整体错位。

### 6.3 授权怎么算

```
MA(train) = min(
    gapToLeader,        // 与前车尾部沿图的有向距离
    routeLimit,         // 进路终点 / 下一个必须停的位置
    staticLimit,        // 静态限速点（弯道、站台）
    lookaheadHorizon    // 上限，建议 ≤ 2×制动距离
)
```

**三条硬约束**（来自 `docs/research/kinematics-and-occupancy.md` §4.5-4.6）：

1. **绝不能「一次大 `travel()`」算距离。**
   `TrackEdge.incrementT`（`:88-93`）只有 `|d| <= 5` 时才用贝塞尔弧长修正，超过 5 格退化为线性 t；作者在 `TravellingPoint.java:213-219` 写了 FIXME。`Navigation.java:141-143` 那个 `+50` 就是给这个误差打的补丁。
   → **自研授权必须小步走（每步 ≤5 格）**，逐边累加；或直接按边遍历 `while (remaining > 0) { step = min(remaining, edgeLen - pos); ... }`。

2. **遇到岔路必须截断授权。**
   前方节点在去掉回头 + `canTravelTo` 过滤后仍有 >1 条可选边，且无法从 `currentPath` 唯一确定去向 → 授权止于此。
   否则 `navigateOptions` 会 `options.get(0)` 任取一条（`Navigation.java:372`）。

3. **对向车与同向车一视同仁。**
   闭塞是一维区间，对向车就是「负方向上的占用」。

### 6.4 速度上限与离散修正

连续解：

```
v_cont(M) = sqrt(2 · a · M)        // a = train.acceleration()，格/tick²
```

**量级基准**（`CTrains.java:14-21` + `Train.java:1143-1156`）：

| 参数 | 默认值 |
|---|---|
| `maxSpeed` | 28/20 = **1.4 格/t**（有燃料 40/20 = 2.0） |
| `acceleration()` | 3/400 = **0.0075 格/t²**（加速与制动同值） |
| `maxTurnSpeed` | 14/20 = 0.7 格/t |
| 满速制动距离 | `1.4²/(2×0.0075)` ≈ **130.7 格**（动力车 266.7 格） |

> 记住 130 格这个数：它同时是「原生能看见红灯的距离」和「`signalScout` 单次 `travel()` 的跨度」，也是 §6.5 精度问题的来源。

但 Create 是「先改 `speed`，再按新 `speed` 位移」（`Navigation.java:289` → `Train.java:315`），逐 tick 减 `a` 的实际位移是

```
s_discrete = v²/(2a) − v/2        // 比连续解少走 v/2 格
```

满速时 `v/2 ≈ 0.7 格`，动力车 `≈ 1.0 格`。方向**安全**（永远停在线前），但**到不了线**。

所以正确的实现是三段式：

```java
double vDiscrete = Math.sqrt(2 * a * Math.max(0, M - (vPrev + a)));  // 预留一 tick
double vFinal    = M < 1/32 ? M : vDiscrete;                          // 末段 creep
double cap       = Math.min(vFinal, Math.min(topSpeed, maxTurnSpeed));
```

> `1/32` 的吸附手法复用自 `Navigation.java:256-258`。
> ⚠ 这套闭式解成立的前提是 `a` 为常数。上游若给 `acceleration()` 加了坡度/载荷修正，就得改数值积分 —— 所以**把所有 `a` 的引用收敛进一个 `brakingCurve()` 函数**，别散落。

### 6.5 ★ 硬安全闸：为什么不能靠 listener

`TravellingPoint.travel` 的 `signalListener` **只在「已注册的 EdgePoint」处被回调**（`TravellingPoint.java:364-378`）——图里**没有「任意位置的墙」**。

想用 listener 拦车，只能把授权终点做成一个边点，但边点是**持久化 + 同步到客户端**的，每 tick 增删是灾难性设计。

**正确做法：在 `distance = speed` 之前把 `speed` 钳位。**

```java
// 注入 Train.tick，锚点：tickPassiveSlowdown() 调用之后（shift = AFTER）
double m = authority.remainingFor(train);
double margin = 0.5;                              // 见下
if (m <= margin) {
    train.speed = 0;                              // 必须显式归零，否则 clamp 区间会反转
} else {
    train.speed = Mth.clamp(train.speed, -(m - margin), (m - margin));
}
```

**为什么必须留 margin**：`Carriage.travel` 里每个轮点的实际位移是

```java
// Carriage.java:180-196
toMove += correction + bogeyCorrection;    // distance + 耦合修正
```

`bogeyStress` 与 `stress` 是压缩量，量级亚格到数格。`maxStress > 4` 才脱轨（`Train.java:449`），所以**理论上单 tick 可多走约 4 格**。

→ 工程取值 `margin = 0.5`（正常耦合），并断言 `maxStress <= 1`，超过就降级为「授权 0」。

**这个位置的价值**：它是唯一能覆盖**所有**绕过 `targetSpeed` 的写入点的地方（`Navigation:257/:270`、`Train:469/:486`、stall 恢复 `:386-389` 全部失效）。

### 6.6 全局 pass 是必须的，不是优化

`tickTrains` **逐车顺序执行**（`GlobalRailwayManager.java:231-234`），而 `SignalEdgeGroup.trains` 是**帧初快照**（`:197-200` 清空 + `Train.earlyTick:207-208` 重建）。

后果：如果让每辆车在 `Train.tick` 里现场读 `Create.RAILWAYS.trains` 算前车位置，你会读到**部分更新**的状态，结果取决于 List 顺序。

→ **所有跨车判定必须在 `GlobalRailwayManager.tick` HEAD 的那个全局 pass 里一次算完**，`Train.tick` 里只做「应用」。

### 6.7 安全责任 100% 在我们身上

`Train.tick` 的移动是**纯运动学**的（`distance = speed` → `carriage.travel`），移动前不检查前方是否有车。

`collideWithOtherTrains`（`Train.java:623-653`）是**移动之后**的探测器：线段相交 → `level.explode(...)` + 双方 `crash()`（`graph = null`，退出物理世界）。

而且它有三个漏检：
1. **只检查首节车厢**（`Train.java:434`）——中间/尾部刮蹭检测不到
2. **`graph` 不同就不比**（`:662-663`）
3. **脱轨车不检测**（`:624-625`）

→ **两车可以物理重叠至少 1 tick，然后爆炸。** 这不是防撞，是撞了之后的惩罚。

因此我们的冲突检测**绝不能复用 `findCollidingTrain`**，必须基于 §6.2 的 `footprint` 做图坐标区间相交（自然处理弯道、上下层、Graph 隔离）。

---

## 7. 正线 / 侧线 / 道岔：为什么必须由玩家标注

### 7.1 Create 没有道岔

全仓搜索 `switch` / `turnout` / `junction`，只有 `TrackBlock.shape.isJunction()`（`TrackBlock.java:158`）这类**方块外形**判断，与调度无关。

**岔口 = `connectionsByNode` 里该节点的连接数 ≥ 3。** 就这么多。

判度数时必须**排除跨维度边**（`TrackEdge.isInterDimensional()` 或 `getLength() == 0`）——传送门不是股道。

| 度数 | 含义 |
|---|---|
| 2 | 普通区间 |
| 3 | **单开道岔**（Create 里唯一能自然形成的道岔形态） |
| 4 | 两组道岔共用同一个节点（双开/交叉渡线的一种搭法） |
| — | ⚠ **真菱形交叉不是度数 4**：`CR_*` 十字块在图上只是两条**互不相连**的直边 |

> 最后一行很关键：平面十字交叉在 Create 里根本没有拓扑连通性，`TrackEdgeIntersection` 也不会为它生成记录（见 §7.6）。要建真正的交叉渡线，必须用贝塞尔曲股。

### 7.2 而且物理上分歧角很浅

`TrackEdge.canTravelTo`（`TrackEdge.java:74-79`）：

```java
return getDirection(false).dot(newDirection) > 7 / 8f;   // cos ≈ 29°
```

**岔线方向必须在约 29° 以内**，否则原生寻路根本不允许通过。这是真实的铁路道岔几何（实际最大也就 1/6.6 号道岔 ≈ 17°），反而是件好事——但它意味着「90° 侧线」在 Create 里不存在。

### 7.3 所以「正线/侧线」只能是玩家标注

拓扑上无法区分「这是正线还是侧线」——都是边。必须引入**区段标注层**：

```
Section（区段）= 一段连续的边
  kind ∈ { MAIN 正线, SIDING 侧线, PLATFORM 站线, OTHER }
  displayName（可选，如「3 道」）
  owningStation（可选）
```

标注由玩家用工具刷（类似 Create 的 `TrackPaver` 那种交互），存进我们自己的 `SavedData`，key 用 `Rail.EdgeId`。

**侧线容量** = `floor(sectionLength / trainLength)`（`Topology.Section.canHold`）。

### 7.4 道岔的锁闭语义

因为轨道不会动，「道岔定位/反位」只是一个**逻辑锁**：

```
SwitchSite { node, branches[], normal }
  - normal = 与来车方向点积最大的那条支线（几何自动推定）
  - 锁闭 = { node → branch, holder = routeId }
  - 冲突 = 同一 node 被两条进路锁到不同 branch
```

「挤岔」（车从反位方向冲过来）的防护：进路排列时，检查该岔口是否被**任何**其他进路锁闭，包括对向。这是纯软件检查，Create 不会帮你。

> ⚠ Create 连「直股/侧股」这个概念都没有。`normal` 只能靠几何推定（最顺直的那条），或被玩家显式指定。**不要假设一定会得到正确的股道划分**。

### 7.5 ⚠ 闭塞分区的 UUID 不稳定——别用它做键

`SignalPropagator.propagateSignalGroup` 每次重算分区都 `new SignalEdgeGroup(UUID.randomUUID())`（`SignalPropagator.java:79`），然后 `signal.setGroup(...)` 换上去。

→ **`SignalEdgeGroup.id` 不是持久标识**。拓扑一变（哪怕只是补一段侧线），整条线路的 group UUID 全部重排。

因此我们的 `Topology.SectionId` **必须自己发**，不能取自 `SignalEdgeGroup.id`。

**推荐做法：注册自己的「绝缘节」边点**

```java
EdgePointType<BlockJoint> BLOCK_JOINT =
    EdgePointType.register(ResourceLocation.fromNamespaceAndPath("tcm", "block_joint"), BlockJoint::new);
```

好处（`docs/research/topology-and-switches.md` §9.2）：

| 好处 | 原因 |
|---|---|
| UUID 持久稳定 | 边点 UUID 由方块实体持有并写进 NBT（`TrackTargetingBehaviour.java:90, :112`） |
| 自动双向镜像 | `EdgePointManager.onEdgePointAdded` 会把它加到两个方向的边上（`:17-24`），省一半工作 |
| 位置可直接查询 | `TrackEdgePoint.getLocationOn(edge)`（`:77-79`） |
| 可复用 `TrackTargetingBehaviour` | 照抄信号机的方块模式即可 |

代价：多一个 `EdgePointType`，**客户端必须同装**（`EdgePointType.read` 对未注册类型 NPE，`:50-56`）。

**区段 = 两个绝缘节之间的边链**，`SectionId` 由两端绝缘节的 UUID 规范化哈希得出。

### 7.6 平交道的真相：只有曲线才会产生交点

`TrackGraph.connectNodes` 建边时会做平交道检测，但：

```java
if (!bezier && !otherEdge.isTurn()) continue;      // TrackGraph.java:416  ★ 至少一侧必须是弯轨
```

→ **两条纯直线交叉（包括 `CR_*` 十字块）不会产生任何 `TrackEdgeIntersection`**。

这意味着「平面十字交叉」在 Create 里**根本不是道岔**，也不会有任何互斥保护。真正的渡线必须用贝塞尔曲股建成。

对我们的影响：**十字交叉口必须自己建模成「两个岔口 + 一段连接」，或者用自研边点补上互斥**。登录/拆图时 `resolveIntersectingEdgeGroups`（`TrackGraph.java:480-515`）也只处理已记录的交点。

---

## 8. 时刻表与方向

### 8.1 静默原生

```java
train.runtime.paused = true;
```

之后 `ScheduleRuntime.tick` 在 `:107` 直接 return，原生状态机完全停摆。

### 8.2 自建数据模型

```
Line（线路）      → name, color, sections[]
  └ Service（交路）→ line, direction, trips[]
      └ Trip（车次）→ departureTime, TrainRef, stops[]
          └ Stop   → station, scheduledArrival, scheduledDeparture, dwell
```

- **运行时分**用 `odometer` 差分实测，不要按直线距离估（弯道很多）。
- **延误** = 实际到站时刻 − 图定时刻；延误要沿交路向后传播（后续站点的到达时间顺延）。
- 停站：到达后设 `destination = null` 扣车，到点再 `startNavigation` 放行。

### 8.3 方向

| 概念 | Create 字段 | 用法 |
|---|---|---|
| 是否双端车 | `Train.doubleEnded`（`Train.java:126`） | 决定能否反向运行 |
| 当前朝向 | `Train.currentlyBackwards`（`:124`） | 由 `Navigation` 根据 `destinationBehindTrain` 设置 |
| 倒车授权 | `DiscoveredPath.distance < 0` | 负数表示目的地**在车后方** |
| 司机 | `hasForwardConductor()` / `hasBackwardConductor()` | 非暂停状态下缺司机会取消导航 |

上下行 = 我们自己的 `Service.direction`，与 `currentlyBackwards` 独立。

---

## 9. PIS 数据流

### 9.1 ⚠ 接管时刻表的隐藏代价：Create 原生显示板会全空

`GlobalTrainDisplayData.refresh()`（`GlobalTrainDisplayData.java:21-32`）：

```java
for (Train train : Create.RAILWAYS.trains.values()) {
    if (train.runtime.paused || train.runtime.getSchedule() == null)
        continue;                                   // :24-25  ← 这里
    ...
    for (TrainDeparturePrediction prediction : train.runtime.submitPredictions())   // :28
        statusByDestination.computeIfAbsent(...).add(prediction);
}
```

**三个「静默开关」全都会让列车被跳过**：

| 开关 | 后果 |
|---|---|
| `runtime.paused = true` | `:24` 跳过 → 显示板空 |
| `runtime.schedule = null` | `:24` 跳过 → 显示板空 |
| `runtime = 空转`（不暂停但也没内容） | `submitPredictions()` 返回空 → 显示板空 |

也就是说：**只要你接管时刻表，Create 自带的翻牌显示屏（`FlapDisplayBlock`）与站台信息板就全部罢工。**

这不是可以忽略的小问题——它意味着 PIS 必须**由我们重新喂数据**，而这本来就是我们要做的事。

### 9.2 对策：接管显示数据源

两条路，建议同时做：

**（A）复用 Create 的 `DisplaySource` 机制**

```java
// DisplaySource.java:41-42 —— 绑定表是公开可写的 SimpleRegistry.Multi
public static final SimpleRegistry.Multi<Block, DisplaySource> BY_BLOCK = SimpleRegistry.Multi.create();
public static final SimpleRegistry.Multi<BlockEntityType<?>, DisplaySource> BY_BLOCK_ENTITY = SimpleRegistry.Multi.create();
```

⚠ **但绑定表只是三层里的第三层，光做它不够**。完整契约：

| 层 | 内容 | 位置 |
|---|---|---|
| L1 **真注册表** | `CreateRegistries.DISPLAY_SOURCE`，`RegistryBuilder(...).sync(true)` → 可以 `DeferredRegister` | `CreateBuiltInRegistries.java:37, :61-62` |
| L2 **便捷入口** | `CreateRegistrate.displaySource(name, supplier)`，自动 `.byBlock(...).byBlockEntity(...)` | `CreateRegistrate.java:170-174` |
| L3 **绑定表** | `BY_BLOCK` / `BY_BLOCK_ENTITY`（`SimpleRegistry.Multi`：可叠加、线程安全、无 freeze） | `DisplaySource.java:41-42` |

> **坑**：只做 L3 不做 L1 → `writeGatheredData()` 会跳过 Id（`DisplayLinkBlockEntity.java:166-173`），**存档重载后你的数据源就丢了**，而且不报错。
> 现成范例：CRN 的 `ModExtras.java:25-27` 注册到 `TRACK_STATION`（`:61-64`）。

→ 注册一个自定义 `DisplaySource`，把我们的「车次 / 终点 / 预计到达 / 延误」喂给翻牌屏。
现有实现可参考：`TrainStatusDisplaySource`、`StationSummaryDisplaySource`、`ObservedTrainNameSource`。

**（B）覆写刷新入口**

```java
@Mixin(GlobalTrainDisplayData.class)
public class DisplayRefreshMixin {
    @Inject(method = "refresh", at = @At("HEAD"), cancellable = true)
    private static void tcm$feedOurPredictions(CallbackInfo ci) {
        if (!CtdControl.isActive()) return;
        GlobalTrainDisplayData.statusByDestination.clear();
        CtdPis.feed(GlobalTrainDisplayData.statusByDestination);   // 直接写入
        ci.cancel();
    }
}
```

`TrainDeparturePrediction`（`:47-77`）的字段是 `train / ticks / scheduleTitle / destination`，其中 `ticks == -1` 表示「未知/无限等待」，`< 200` 被视为「即将到达」排在最前（`:60-66`）。我们照填即可。

### 9.3 Create 原生 PIS 的三处硬伤（量化）

| # | 事实 | 后果 |
|---|---|---|
| 1 | `GlobalTrainDisplayData.refresh()` **每 100 tick 才跑一次**（`GlobalRailwayManager.java:213-215`：`gameTime % 100 == 0`） | 到站信息最多滞后 **5 秒**。真 PIS 要求 1 秒级 |
| 2 | 数据是 `static Map<String, Collection<TrainDeparturePrediction>>`，**按终点字符串分组**（`GlobalTrainDisplayData.java:18`） | 没有「线路 / 车次 / 站台 / 方向」维度，无法做换乘引导 |
| 3 | ETA 只有 `int ticks` 一个数（`:49`），且 `< 200` 时统一显示为「即将到达」（`:60-66`） | 没有置信度、没有延误原因、没有历史准点率 |

外加 §9.1 那条：一旦你接管时刻表，**这三样一起失效**。

→ 真 PIS 必须自建：**每秒级的列车位置 + 实测区间时分 + 图定时刻差 + 事件流**。

### 9.4 PIS 数据分层

| 通道 | 频率 | 内容 |
|---|---|---|
| 每帧同步（游戏内） | 20Hz | 列车位置、速度、当前区间、下一站 |
| 事件驱动 | 事件发生时 | 到站、发车、延误、改道、扣车（`RailwayEvent`） |
| 静态数据 | 加载时 | 线路、站点、时刻表、区段标注、站台 |
| 对外推送 | 外部请求 | HTTP 查询 / WebSocket 订阅（参考 CRN 的 Jetty 实现，40 条路由 + OpenAPI 3.1） |

### 9.5 ★ 三个必须自建的「一等公民」

调研下来，Create 与 CRN **都没有**这三样，而它们是真 PIS 的地基：

| # | 缺口 | 为什么绕不过去 |
|---|---|---|
| **1** | **权威计划（Plan）与预测（Prediction）分离** | CRN 的 `scheduled` 是**学习值**——`StopSnapshot.java:28-30` 明说「没有时刻表时用投影顶替」。以学习值当基准，准点率就没有意义。必须由内核下发带 `revision` 的权威计划，实时值只从「计划 + 实绩」算出 |
| **2** | **股道 / 站台是实体** | Create 的站名是唯一 key，平台号靠 destination 字符串里剥（`StationSummaryDisplaySource.java:96-102`）。→ 站台屏做不出「3 站台」，车门侧提示也无从谈起 |
| **3** | **车门 / 停站状态机** | 「请勿靠近车门」「车门即将关闭」是站台 PIS 标配，而 Create 连「车门开着」这个概念都没有 |

配套的两条：

- **多语言载体**：CRN 的 snapshot 里是裸 `String`，跨语言客户端拿到的是服务端语言。要用 `LocalizedText{key, args[], fallback}`。`DelayInstance` 已经是这个范式，推广即可。
- **推送**：CRN 只有轮询（无 WS/SSE）。车内屏与实时告警撑不住。

> 一句话：**Create 提供「物理与显示」，CRN 提供「语义与网络」，而「权威计划 + 股道 + 推送」是三块空白** —— 正好是我们自研内核的立足点。
> 完整字段设计与端点建议见 `docs/research/pis-and-external-api.md` §C3-C4。

---

## 10. 风险与降级

| 风险 | 影响 | 对策 |
|---|---|---|
| 内核抛异常 | 列车乱跑 | 宿主捕获 → 丢弃该帧全部指令 → 不解除原生闭塞（`isActive()` 转 false）→ **退回原版行为** |
| 路径耗尽 | 岔口随机走（`navigateOptions:372`） | 宿主执行前校验：`currentPath` 必须覆盖到下一个岔口之后，否则**拒绝放行** |
| fail-safe 方向搞反 | 内核 bug 导致放行过头 | 所有速度取 `min(原生, 我们)`，永不取 max |
| 上游改签名 | 注入失效 | 4 个注入全部 `require = 1`（宁可崩也不静默错行为），启动时自检 |
| 与 CRN 抢注入点 | 崩服 | 本方案 4 个点 CRN 一个都没碰：`isOccupiedUnless`、`tickPassiveSlowdown`、`tickState`、`TrackGraph.add/remove*`。**零冲突** |
| 跨维度 | 边长为 0，占用计算断链 | 遇到 `interDimensional` 边即视为「区间终止」，单独处理 |

**与 CRN 的关系**：CRN 是「在 Create 代价函数上加软价 + 自己的时刻表后端」。我们整体接管后，CRN 的寻路加价仍然生效（它注入 `Navigation.search`，而我们的方案不接管 `search`），但它的 `ScheduleRuntime` 注入会因为我们 `paused = true` 而空转。→ **共存但不协作**。若要协作，走 CRN 的 `INavigationPenaltyProvider` / `RailwayBackendEvents` 公开 API。

---

## 11. 分阶段落地

| 阶段 | 内容 | 可验收的行为 |
|---|---|---|
| **P0** | 拓扑镜像 + 遥测采样 + 只读面板 | 能在 HUD 上看到每列车精确的 footprint 与 odometer，不改变任何行为 |
| **P1** | 解除原生闭塞（3.1）+ 原生灯色仍由 Create 算 | 列车不再因信号停车（危险！仅在测试世界开） |
| **P2** | 施加速度授权（3.3）+ 自建固定闭塞 | 列车按我们的闭塞停车/放行，灯色仍走原生 |
| **P3** | 施加灯色（3.4） | 灯与我们的判定一致 |
| **P4** | 施加进路（3.5）+ 道岔锁闭 | 能排进路、能锁岔、能防挤岔 |
| **P5** | 区段标注 + 正线/侧线 | 能让车进侧线待避 |
| **P6** | 自建时刻表（3.6） | 图定时刻、停站时分、延误传播 |
| **P7** | 移动闭塞（6.2-6.4） | 授权连续、无固定分区 |
| **P8** | PIS + 对外 API | 站台显示、到站预测、外部订阅 |

**每一阶段都必须能单独回退**（`isActive()` 开关）。P1 之后如果没有 P2，世界是危险的——所以 P1 与 P2 **必须同一个版本**发布，中间不插别的。

---

## 12. 一句话总结

> Create 提供的是**一张图 + 一堆会动的点 + 一个物理循环**。
> 信号、闭塞、时刻表都只是它自带的「默认决策实现」。
> 把 `isOccupiedUnless` 拆掉，把 `speed` 接过来，把 `cachedStates` 写上——
> 它就退化成一个纯粹的「铁路物理引擎」，剩下的全归你。
