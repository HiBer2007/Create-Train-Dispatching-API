# 02 — Create 铁路系统：接口与注入点总表

> 基线：Create `mc1.21.1/dev` @ `7ff2760`（6.0.11）、NeoForge 21.1.219、MC 1.21.1
> 本文所有签名与行号均为该基线快照。上游更新后必须复核（见 §8）。
>
> 目标：回答「我要注入一套自己的信号与自动控制系统，能挂在哪、必须 mixin 哪」。

---

## 0. 五层注入面

Create 的铁路系统没有官方的列车 API —— `com.simibubi.create.api` 下只有 `PortalTrackProvider` 与 `TrainHatInfoProvider` 与列车沾边，其余全在 `content.trains.*`（内部包）。所以**注入必然依赖 mixin**，但不同层的「成本」差别极大：

```
┌─────────────────────────────────────────────────────────────┐
│ E 表现层   SignalBlockEntity / SignalRenderer / TrainHUD    │  客户度最高，改动最安全
├─────────────────────────────────────────────────────────────┤
│ D 调度层   GlobalRailwayManager / Schedule / GlobalStation  │  唯一全局每帧钩子
├─────────────────────────────────────────────────────────────┤
│ C 行车层   Navigation / Train / TravellingPoint             │  减速、停车、寻路
├─────────────────────────────────────────────────────────────┤
│ B 信号层   SignalBoundary / SignalEdgeGroup / SignalPropagator │  灯色判定
├─────────────────────────────────────────────────────────────┤
│ A 数据层   EdgePointType / TrackEdgePoint / EdgeData        │  ★ 唯一真正开放的扩展点
└─────────────────────────────────────────────────────────────┘
```

**结论先行**：只有 **A 层是公开可注册的**（`EdgePointType.register` 是 `public static`，`TYPES` 是公开 `HashMap`）。B/C/D 层必须 mixin。E 层可 mixin 或纯客户端事件。

---

## 1. 层 A：数据与持久化（唯一无需 mixin 的扩展点）

### 1.1 `EdgePointType<T extends TrackEdgePoint>`

`content/trains/graph/EdgePointType.java`

```java
public static final Map<ResourceLocation, EdgePointType<?>> TYPES = new HashMap<>();   // :18  公开、可变、无冻结
public static final EdgePointType<SignalBoundary> SIGNAL   = register(Create.asResource("signal"),   SignalBoundary::new);  // :22
public static final EdgePointType<GlobalStation>  STATION  = register(Create.asResource("station"),  GlobalStation::new);   // :24
public static final EdgePointType<TrackObserver>  OBSERVER = register(Create.asResource("observer"), TrackObserver::new);   // :26

public static <T extends TrackEdgePoint> EdgePointType<T> register(ResourceLocation id, Supplier<T> factory);  // :29
public T create();                                   // :40  内部调 factory.get() 并 setType(this)
public ResourceLocation getId();                     // :46
public static TrackEdgePoint read(FriendlyByteBuf, DimensionPalette);  // :50  反序列化
```

**契约**

| 项 | 说明 |
|---|---|
| 注册时机 | 必须在**任何存档/图网络同步之前**完成。`TYPES` 无冻结机制，静态初始化顺序是关键：`EdgePointType` 的静态块会先注册 3 个内置类型，我们的 `register` 只要晚于该类加载即可（mod 构造器 / `FMLCommonSetupEvent` 都安全） |
| `ResourceLocation` 稳定性 | **一旦发布不可改名**。`EdgeData.write` 序列化的是 `type.getId()`（`EdgeData.java:189-191`），改名 = 存量世界数据全部失联 |
| 客户端要求 | `read(FriendlyByteBuf)` 里 `TYPES.get(type).create()` **无 null 检查**（`:52-53`）。客户端没装你的 mod → 加入服务器时**图同步 NPE** |
| 卸载安全性 | 反过来 NBT 侧是安全的：`EdgeData.read` 遇未知类型直接 `return`（`EdgeData.java:210-211`），不崩，但点丢失 |

### 1.2 `TrackEdgePoint`（抽象基类）

`content/trains/signal/TrackEdgePoint.java`

| 成员 | 位置 | 类型 | 说明 |
|---|---|---|---|
| `id` / `edgeLocation` / `position` | `:26-28` | 字段 | `edgeLocation` 是 `Couple<TrackNodeLocation>`，**双向**：两个方向各有一条 TrackEdge |
| `setId/getId/setType/getType` | `:31-45` | — | 由框架维护 |
| `canMerge()` | `:47` | **abstract** | 同一位置重复放置时能否合并成一个点 |
| `canCoexistWith(EdgePointType, boolean front)` | `:49` | 可覆写，默认 `false` | 能否与其它类型的点共存于同一位置。`TrackTargetingBehaviour.createEdgePoint` 会遍历 `TYPES` 逐个问（`:211-227`） |
| `invalidate(LevelAccessor)` | `:53` | **abstract** | 方块被破坏时，把数据搬去迁移目标 |
| `blockEntityAdded(BlockEntity, boolean front)` | `:66` | **abstract** | 一个方块接入此点（双面各一次） |
| `blockEntityRemoved(BlockPos, boolean front)` | `:68` | **abstract** | 方块移除；两侧都空时通常调 `removeFromAllGraphs()` |
| `onRemoved(TrackGraph)` | `:70` | 可覆写 | 从图里摘除后的清理 |
| `getLocationOn(TrackEdge)` | `:77` | — | `isPrimary(node1) ? length - position : position`，**方向敏感** |
| `canNavigateVia(TrackNode side)` | `:81` | 可覆写，默认 `true` | 寻路时这个点是否允许通过。返回 false = 断路 |
| `isPrimary(TrackNode)` | `:85` | — | 判断某个方向是不是「正面」 |
| `read/write(CompoundTag, HolderLookup.Provider, boolean migration, DimensionPalette)` | `:90-110` | 可覆写 | NBT 持久化。**先调 `super`，再读写自己的字段** |
| `read/write(FriendlyByteBuf, DimensionPalette)` | `:100-117` | 可覆写 | 网络同步。`write` 会自动带 `type.getId()`，`read` 时 type 已就绪 |
| `tick(TrackGraph, boolean preTrains)` | `:119` | 可覆写 | 每帧两次：`preTrains=true` 在列车 tick 前，`false` 在后。**你的信号状态机应该在这里算** |
| `removeFromAllGraphs()` | `:121` | protected | 遍历所有图尝试摘除自己 |

**序列化最低实现标准**（新增字段时）
- NBT：`write` 里 `nbt.putXxx(...)`，`read` 里对应读；**`migration == true` 时必须早退**，否则会读到不完整数据。
- 网络：`write` 里按顺序写，`read` 里**严格同序**读。字段增删要兼容旧客户端 → 用版本号前缀（`buffer.writeVarInt(version)`）。
- 参考实现：`SignalBoundary.read/write`（`SignalBoundary.java:262-333`）与 `GlobalStation.read/write`（`GlobalStation.java:62-112`）。

### 1.3 `EdgeData`（一条边上挂着的点集合）

`content/trains/graph/EdgeData.java`

| 方法 | 位置 | 说明 |
|---|---|---|
| `hasSignalBoundaries()` | `:40` | **返回 `singleSignalGroup == null`**。这是「这条边有没有信号分区」的唯一判定 |
| `getGroupAtPosition(TrackGraph, double)` | `:106` | 给定位置返回所属分区 UUID。无信号边界时返回 `getEffectiveEdgeGroupId` |
| `getEffectiveEdgeGroupId(TrackGraph)` | `:129` | `singleSignalGroup == null ? null : (== passiveGroup ? graph.id : singleSignalGroup)` |
| `addPoint(TrackGraph, TrackEdgePoint)` | `:141` | **只有 `point.getType() == EdgePointType.SIGNAL` 才会把 `singleSignalGroup` 置 null**（`:142-143`） |
| `removePoint(TrackGraph, TrackEdgePoint)` | `:133` | 同上；且没有信号点剩下时置回 `passiveGroup` |
| `next(EdgePointType<T>, double minPosition)` | `:155` | 沿边向前找下一个指定类型的点（不含起点） |
| `get(EdgePointType<T>, double exactPosition)` | `:171` | 按精确位置取（±0.5 容差） |
| `getPoints()` | `:125` | 全部点，**已按 position 排序** |

> ⚠ **关键陷阱**：如果你注册了 `SignalBoundary` 的**子类**到**自定义 EdgePointType**，它能通过 `instanceof SignalBoundary` 检查（寻路、行车都会认），但 `EdgeData.addPoint` **不会**把 `singleSignalGroup` 置 null，于是 `hasSignalBoundaries()` 仍是 false，导致：
> - `TrackGraphSyncPacket:269` 按「无分区」序列化 → 客户端看不到你的分区
> - `Train.collectInitiallyOccupiedSignalBlocks:1022` 跳过整段逻辑 → 列车**不会**登记占用
> - `SignalPropagator:46/66/196` 的分区传播不会为它开新分区
>
> 想让自定义信号点被当作「真信号」，要么复用 `EdgePointType.SIGNAL`（不改类型，只 mixin 行为），要么 mixin `EdgeData.addPoint/removePoint` 补上 `singleSignalGroup` 处理。

---

## 2. 层 B：信号判定（必须 mixin）

### 2.1 `SignalBoundary` — 信号机的逻辑体

`content/trains/signal/SignalBoundary.java`，`extends TrackEdgePoint`

**双面模型**：一台信号机管两个方向，`Couple<...>` 的 first/second 分别对应两面。

| 字段 | 位置 | 类型 | 说明 |
|---|---|---|---|
| `blockEntities` | `:33` | `Couple<Map<BlockPos, Boolean>>` | 每面的方块及其「上报的红石 power」 |
| `types` | `:34` | `Couple<SignalType>` | 每面的信号类型 |
| `groups` | `:35` | `Couple<UUID>` | 每面所属的 `SignalEdgeGroup` |
| `sidesToUpdate` | `:36` | `Couple<Boolean>` | 脏标记，触发 `propagateSignalGroup` |
| `cachedStates` | `:37` | `Couple<SignalState>` | **对外可读的灯色** |
| `chainedSignals` | `:39` | `Couple<Map<UUID, Boolean>>` | CROSS_SIGNAL 的链式缓存 |

| 方法 | 位置 | 签名 | 用途 |
|---|---|---|---|
| `tick` | `:171` | `void tick(TrackGraph graph, boolean preTrains)` | preTrains 阶段处理 `sidesToUpdate` → 重算分区；postTrains 阶段调 `tickState` |
| `tickState` | `:186` | `private void tickState(TrackGraph graph)` | **★ 灯色唯一计算处**：强制红 → 分区占用 → `resolveSignalChain` |
| `resolveSignalChain` | `:224` | `private SignalState resolveSignalChain(TrackGraph, boolean side)` | 仅 `CROSS_SIGNAL` 生效；全空闲 GREEN / 全红 RED / 混合 YELLOW / 有缺失 INVALID |
| `isForcedRed` | `:211/:215` | `boolean isForcedRed(TrackNode side)` / `(boolean primary)` | 任一方块有红石信号即强制红 |
| `getGroup` | `:133` | `UUID getGroup(TrackNode side)` | 按方向取分区 |
| `getStateFor` | `:161` | `SignalState getStateFor(BlockPos)` | 客户端/渲染用查询 |
| `getTypeFor` | `:156` | `SignalType getTypeFor(BlockPos)` | |
| `getOverlayFor` | `:143` | `OverlayState getOverlayFor(BlockPos)` | 单面/双面渲染 |
| `queueUpdate` | `:129` | `void queueUpdate(TrackNode side)` | 标脏 |
| `canNavigateVia` | `:138` | `boolean canNavigateVia(TrackNode side)` | **该面无方块 → 不可通过**（`:139-140`），寻路会当断路 |
| `setGroup` / `setGroupAndUpdate` | `:50/:73` | | 由 `SignalPropagator` 调用 |
| `cycleSignalType` | `:335` | `void cycleSignalType(BlockPos)` | UI 点击切换类型 |

**灯色计算的真实逻辑**（`tickState:186-209`）

```java
boolean forcedRed = isForcedRed(current);
UUID group = groups.get(current);
if (Objects.equal(group, groups.get(!current))) { cachedStates.set(current, INVALID); continue; }   // 两面同组 = 配置错误
SignalEdgeGroup g = Create.RAILWAYS.signalEdgeGroups.get(group);
if (g == null) { cachedStates.set(current, INVALID); continue; }
boolean occupied = forcedRed || g.isOccupiedUnless(this);
cachedStates.set(current, occupied ? RED : resolveSignalChain(graph, current));
```

**要替换灯色判定，只需覆写这一处**。不要动 `SignalBoundary` 的序列化。

### 2.2 `SignalEdgeGroup` — 闭塞分区

`content/trains/signal/SignalEdgeGroup.java`

| 字段 | 位置 | 说明 |
|---|---|---|
| `id` / `color` | `:23-24` | `EdgeGroupColor` 用于 F3 可视化与颜色分配 |
| `trains` | `:26` | 当前占用该分区的列车。**每帧清空后由 `Train.earlyTick` 重建** |
| `reserved` | `:27` | 本帧被某台信号机（或某列车）预定 |
| `intersecting` / `intersectingResolved` / `adjacent` | `:29-31` | 平交道（`TrackEdgeIntersection`）分区合并 |
| `fallbackGroup` | `:33` | 这是「整张图一个分区」的兜底组 |

| 方法 | 位置 | 语义 |
|---|---|---|
| `isOccupiedUnless(Train)` | `:49` | `reserved != null` 或 `trains.size() > 1` 或「列车集合非空且不含我」→ 占用 |
| `isOccupiedUnless(SignalBoundary)` | `:62` | `!trains.isEmpty()` 或 `reserved != null && reserved != boundary` → 占用 |
| `putIntersection` / `removeIntersection` | `:75/:81` | 平交道合并 |
| `resolveColor` | `:100` | 位掩码算颜色 |

> 注意两个 `isOccupiedUnless` 的语义**不同**：给 `Train` 的判据包含 `trains.size() > 1`（自己不算阻塞），给 `SignalBoundary` 的判据是 `!trains.isEmpty()`（谁都不能有）。

### 2.3 方块层 `SignalBlock` / `SignalBlockEntity`

| 项 | 位置 | 说明 |
|---|---|---|
| `SignalBlock.TYPE` / `SignalBlock.POWERED` | `SignalBlock.java:34-35` | `EnumProperty<SignalType>` + 红石 `BooleanProperty` |
| `SignalType` 枚举 | `SignalBlock.java:37-38` | **只有两个值**：`ENTRY_SIGNAL`, `CROSS_SIGNAL` |
| `SignalState` 枚举 | `SignalBlockEntity.java:40-54` | `RED, YELLOW, GREEN, INVALID`；`isRedLight/isYellowLight/isGreenLight(renderTime)` |
| `OverlayState` 枚举 | `SignalBlockEntity.java:36-38` | `RENDER, SKIP, DUAL` |
| `edgePoint` 字段 | `SignalBlockEntity.java:56` | `TrackTargetingBehaviour<SignalBoundary>` |
| `tick()` | `:115-148` | 服务端：同步 power → 同步 TYPE → `enterState(boundary.getStateFor(pos))` → `setOverlay(...)` |
| `enterState(SignalState)` | `:169-179` | 带 15 帧防抖（`switchToRedAfterTrainEntered`）；触发 CC 事件 |
| `getState()` / `getOverlay()` | `:154/:158` | 客户端渲染读这两个 |

**枚举扩展性实测**

| 枚举 | 序列化方式 | 能否安全扩展 |
|---|---|---|
| `SignalState` | `NBTHelper.writeEnum` → 写 **name 字符串**（`SignalBlockEntity.java:84,92`） | ⚠ **技术上**可 mixin 加常量且不破坏存量存档（name 序列化），但要同步改 `isRedLight` 等渲染分支。**实践上不建议**——见 `docs/07-custom-signals.md`：原生渲染只判 `isRedLight`，加常量也不会多出灯 |
| `SignalType` | 同上（`SignalBoundary.java:285,319`）；但 `cycleSignalType` 用 `values()[ordinal+1]`（`:337`） | ⚠ 可加，但 UI 轮换逻辑会跟着变；且 `EnumProperty` 绑在方块状态上，改了世界里的旧方块会失配 |
| `EdgeGroupColor` | `1 << ordinal()` 位掩码（`EdgeGroupColor.java:28`） | ❌ 超过 31 个值位掩码溢出。不要碰 |

> **要做真的多显示信号机，正确做法是自建方块，不是扩枚举。** 原生信号机在视觉上只有「亮红/灭」两态（`SignalRenderer.java:41`），逻辑上的 YELLOW/GREEN 根本没有灯去显示。完整方案见 **[`docs/07-custom-signals.md`](07-custom-signals.md)**。

---

## 3. 层 C：行车与寻路（必须 mixin）

### 3.1 `Navigation` — 自动驾驶的大脑

`content/trains/entity/Navigation.java`（923 行）

| 字段 | 位置 | 说明 |
|---|---|---|
| `train` | `:51` | 反向引用 |
| `destination` | `:53` | 目标 `GlobalStation`（继承 `TrackEdgePoint`，所以也能当边点目标） |
| `distanceToDestination` | `:54` | 带符号：负数表示目的地**在车后方** |
| `currentPath` | `:58` | `List<Couple<TrackNode>>`，寻路结果的边序列 |
| `signalScout` | `:60` | **复用的** `TravellingPoint`，用于前向盲扫信号 |
| `waitingForSignal` | `:61` | `Pair<UUID, Boolean>`：**认定**前方有红灯（信号 id + 哪一面） |
| `waitingForChainedGroups` | `:62` | 交叉信号链上已收集的分区 |
| `distanceToSignal` | `:63` | 到该红灯的距离 |
| `ticksWaitingForSignal` | `:64` | 已等待帧数，参与寻路惩罚 |

| 方法 | 位置 | 签名 | 说明 |
|---|---|---|---|
| `tick` | `:73` | `void tick(Level level)` | **★ 主入口**。§3.4 拆解 |
| `startNavigation` | `:386` | `double startNavigation(DiscoveredPath pathTo)` | 接受寻路结果，返回 cost；`null` → 取消 |
| `cancelNavigation` | `:375` | `void cancelNavigation()` | 清空 + 取消车站预定 + 中断时刻表 |
| `findPathTo` | `:442/:448` | `DiscoveredPath findPathTo(GlobalStation, double maxCost)` / `(ArrayList<GlobalStation>, double maxCost)` | **★ 寻路入口** |
| `search` | `:547/:551` | `void search(double maxDistance, double maxCost, boolean forward, ArrayList<GlobalStation>, StationTest)` | **★ Dijkstra/A\* 实现**，带信号与车站惩罚 |
| `findNearestApproachable` | `:519` | `GlobalStation findNearestApproachable(boolean forward)` | |
| `control` | `:347` | `ITrackSelector control(TravellingPoint mp)` | 给车厢用的「沿 currentPath 走」选择器 |
| `controlSignalScout` | `:353` | `ITrackSelector controlSignalScout()` | 盲扫用的选择器（拷贝 path 以免消耗） |
| `isActive` | `:343` | `boolean isActive()` | `destination != null` |
| `reserveChain` | `:292` | `private void reserveChain()` | 把整条交叉信号链记进 `train.reservedSignalBlocks` |
| `currentSignalResolved` | `:302` | `private boolean currentSignalResolved()` | 判定等待的信号是否已放行 |

### 3.2 `Train` — 状态与执行

`content/trains/entity/Train.java`（1310 行）

| 字段 | 位置 | 说明 |
|---|---|---|
| `speed` | — | **每 tick 位移**（不是 m/s）。`distance = speed`（`:315`） |
| `targetSpeed` | `:100` | 由 `Navigation.tick` 写 |
| `throttle` | — | 手动油门系数，`Navigation:275` 乘进 topSpeed |
| `navigation` / `runtime` | — | `Navigation` / `ScheduleRuntime` |
| `manualTick` | `:121` | 本帧是否手动驾驶（会让信号判定失效） |
| `reservedSignalBlocks` | `:132` | `Set<UUID>`，已预定未进入的分区 |
| `occupiedSignalBlocks` | — | `Map<UUID, UUID>`，已进入的分区 → 信号 id |
| `status` | — | `TrainStatus`，驱动 UI |

| 方法 | 位置 | 说明 |
|---|---|---|
| `earlyTick(Level)` | `:193` | 每帧物理 tick **之前**：`addToSignalGroups(occupied)` + `addToSignalGroups(reserved)` |
| `tick(Level)` | `:289` | **★ 主循环**：runtime → navigation → 减速 → 位移 → 碰撞/脱轨 → `updateNavigationTarget` |
| `frontSignalListener()` | `:462` | **★ 车头穿点回调**：车站到达 / 观测器 / 红灯停车 / 占用登记 |
| `backSignalListener()` | `:530` | 车尾穿点回调：释放分区占用、移除观测器 |
| `occupy(UUID groupId, UUID boundaryId)` | `:522` | 登记占用，返回是否是新占用 |
| `approachTargetSpeed(float)` | `:988` | **★ 速度逼近**：以 `acceleration()` 为步长靠向 `targetSpeed` |
| `tickPassiveSlowdown()` | `:593` | 无目的地时自然减速；同时**复位 `manualTick`** |
| `updateNavigationTarget(Level, double)` | `:546` | 按距离阈值触发重寻路；`reservedSignalBlocks` 非空时跳过 |
| `acceleration()` / `maxSpeed()` / `maxTurnSpeed()` | — | 物理参数，可调 |
| `addToSignalGroups(Collection<UUID>)` | `:278` | 把 UUID 集合填进 `group.trains` |
| `collectInitiallyOccupiedSignalBlocks()` | `:1001` | 重建时从车尾向前扫，重建占用（依赖 `hasSignalBoundaries`） |
| `Penalties`（内部类） | `:1093-1108` | 寻路惩罚常量：`RED_SIGNAL=25, REDSTONE_RED_SIGNAL=400, STATION=50, STATION_WITH_TRAIN=300, ...` |

### 3.3 `TravellingPoint` — 唯一的「穿点」回调机制

`content/trains/entity/TravellingPoint.java`

```java
public interface ITrackSelector extends BiFunction<TrackGraph, Pair<Boolean, List<Entry<TrackNode, TrackEdge>>>, Entry<TrackNode, TrackEdge>>;   // :52
public interface IEdgePointListener extends BiPredicate<Double, Pair<TrackEdgePoint, Couple<TrackNode>>>;   // :55  ★
public interface ITurnListener extends BiConsumer<Double, TrackEdge>;                                      // :57
public interface IPortalListener extends Predicate<Couple<TrackNodeLocation>>;                             // :59

public double travel(TrackGraph graph, double distance, ITrackSelector trackSelector,
                     IEdgePointListener signalListener,
                     ITurnListener turnListener,
                     IPortalListener portalListener);         // :198
```

**`IEdgePointListener` 的语义**（`edgeTraversedFrom:356-381`）
- 参数 1：`double` —— 从本次 travel 起点算起的距离
- 参数 2：`Pair<TrackEdgePoint, Couple<TrackNode>>` —— 点本身 + **方向 Couple**（倒车时已 `swap()`，所以 `getSecond()` 永远是「正要进入的那一侧」）
- 返回 `true` → **在此点停下**（`travel` 把 `position` 设为该点并返回）；`false` → 继续

> 这是**唯一**不需要 mixin 就能挂进行车链路的回调：你可以在自己调用 `travel` 时传入组合 listener。但 Create 自己的 `Train.tick → Carriage.travel` 内部写死了 `train.frontSignalListener()`，所以要**影响原版列车**仍然需要 mixin（改写 `frontSignalListener()` 的返回，或 `@Inject` 包装 `Carriage.travel`）。

### 3.4 `Navigation.tick` 逐段拆解（改哪个变量就改哪一段）

| 行区间 | 做什么 | 想改什么时的落点 |
|---|---|---|
| `:74-96` | 目的地/司机校验 | 基本不用动 |
| `:98` | `destination.reserveFor(train)` 车站预定 | 改车站竞争语义 |
| `:100-104` | 算 `acceleration` / `brakingDistance` / `speedMod` / `preDepartureLookAhead` | **刹车距离公式**（`brakingDistance = v²/(2a)`） |
| `:107-233` | **信号盲扫**：拷 `leadingPoint` 到 `signalScout`，向前 `travel`，边走边看 `SignalBoundary` | **`scanDistance`（`:134-135`）**、**占用判定（`:164-165`）**、**预定逻辑（`:179-182`）** |
| `:235-247` | 出站闸门：红灯未放行不出站 | 出站条件 |
| `:251-259` | 距离耗尽 / 贴近目标时直接贴速度 | |
| `:265-273` | 10 格内线性限速（进站曲线） | **进站减速曲线** |
| `:275-288` | **最终目标速度**：`topSpeed * throttle`，弯道限速，`targetDistance > brakingDistance ? topSpeed : 0` | **★ 换掉这一段就能换掉整套驾驶策略** |
| `:288-289` | 写 `train.targetSpeed` + `approachTargetSpeed(1)` | |

---

## 4. 层 D：全局调度（必须 mixin）

### 4.1 `GlobalRailwayManager` — 唯一的全局每帧入口

`content/trains/GlobalRailwayManager.java`

| 字段 | 位置 | 说明 |
|---|---|---|
| `trackNetworks` | `:42` | `Map<UUID, TrackGraph>` 所有轨道网络 |
| `signalEdgeGroups` | `:43` | `Map<UUID, SignalEdgeGroup>` 所有分区 |
| `trains` | `:44` | `Map<UUID, Train>` 所有列车 |
| `sync` | `:45` | `TrackGraphSync` 网络同步器 |

| 方法 | 位置 | 说明 |
|---|---|---|
| `tick(Level)` | `:193` | **★ 全局主循环**（见 AGENTS.md §3） |
| `tickTrains(Level)` | `:225` | waiting 优先于 moving 的两段式 tick |
| `getOrCreateGraph` / `putGraph` / `removeGraph` | `:131/:146/:157` | 图生命周期 |
| `putGraphWithDefaultGroup` | `:139` | 新建图时创建 fallback 分区 |
| `updateSplitGraph` | `:162` | 轨道断裂后拆图 |
| `playerLogin` | `:58` | 全量同步给新玩家 |

**注入位置建议**：`@Inject(method = "tick", at = @At("HEAD"))` 做「本帧调度预计算」；`@Inject(at = @At("TAIL"))` 做「本帧结果回收/统计」。

### 4.2 时刻表：四套注册机制，别搞混

Create 内部有**四套互不相同**的注册机制，选错就是坑：

| 类 | 机制 | 载体 | 冻结 | 去重 | 外部可写 |
|---|---|---|---|---|---|
| **A** | `Schedule.INSTRUCTION_TYPES` / `CONDITION_TYPES` | `List<Pair<ResourceLocation, Supplier<?>>>` | ❌ | ❌ | ✅ 裸 `add` |
| **B** | `EdgePointType.TYPES` | `HashMap<ResourceLocation, EdgePointType<?>>` | ❌ | ❌ `put` 静默覆盖 | ✅ |
| **C** | `SimpleRegistry` | `IdentityHashMap` + provider | ❌（但有缓存） | ✅ 抛异常 | ✅ 公开 API |
| **D** | `CreateBuiltInRegistries.*` | NeoForge 动态 Registry | ✅ bake 冻结 | ✅ | 需 `RegisterEvent` |

**关键**：时刻表与边点用的是 A / B，**不在** D 里 —— 所以没有「registry freeze」，没有注册事件，也没有顺序保证。`CreateRegistries` 只有 `ResourceKey`，**没有注册方法**。

#### A 类契约（时刻表指令 / 等待条件）

```java
// Schedule.java:46-49
public static List<Pair<ResourceLocation, Supplier<? extends ScheduleInstruction>>> INSTRUCTION_TYPES = new ArrayList<>();
public static List<Pair<ResourceLocation, Supplier<? extends ScheduleWaitCondition>>> CONDITION_TYPES = new ArrayList<>();
```

| 事实 | 证据 |
|---|---|
| 静态块填 5 个 instruction + 9 个 condition | `Schedule.java:51-66` |
| id 全为 `create:<name>`；第三方必须用**自己的命名空间** | `Schedule.java:68-74` |
| 消费方只有 3 处：`ScheduleInstruction.fromTag`、`ScheduleWaitCondition.fromTag`、`ScheduleScreen` 下拉框 | `ScheduleInstruction.java:44-47`、`ScheduleWaitCondition.java:43-46`、`ScheduleScreen.java:200-245` |
| `Supplier` 会被**反复调用**（每次反序列化、每次 UI 切换类型） | `Schedule.java:46-49` |
| 工厂里**不要碰 `AllBlocks`/`AllItems`**，可能在 `Create.onCtor` 之前被加载成 null | `Create.java:120-126` |

**注册（零门槛）**
```java
Schedule.INSTRUCTION_TYPES.add(Pair.of(
    ResourceLocation.fromNamespaceAndPath("mymod", "goto_yard"), MyGotoYardInstruction::new));
Schedule.CONDITION_TYPES.add(Pair.of(
    ResourceLocation.fromNamespaceAndPath("mymod", "wait_for_dispatch"), MyWaitCondition::new));
```

**A 类的致命陷阱**（会在线炸服）

| 坑 | 依据 | 后果 |
|---|---|---|
| **缺 condition = NPE** | `ScheduleWaitCondition.java:48-51` 返回 `null` → `ScheduleEntry.java:58-60` 塞进 list → `ScheduleRuntime.java:176-179` 直接调 `tickCompletion` | 任一端缺类型 → 列车 tick 时 NPE，**整个服务端 tick 崩** |
| **缺 instruction = 静默改道** | `ScheduleInstruction.java:49-52` 降级为 `DestinationInstruction`，`data` 无 `"Text"` → 过滤串为空 → 匹配任意站 | 列车**随机开去别的站** |
| 重复 id | `ScheduleInstruction.java:44-47` 反序列化循环无 `break` | 后注册者胜出 + UI 出现两条同名 |
| `@OnlyIn(Dist.CLIENT)` 方法未镜像 | `IScheduleInput.java:55-59` | 专用服务器上可能类加载失败 |

> **结论**：自定义 instruction/condition **必须服务端与客户端同时安装**。要做「服务端独有」的指令，必须 mixin `ScheduleInstruction.fromTag` 提供安全占位符。

#### `SimpleRegistry`（C 类）的缓存陷阱

`SimpleRegistryImpl.registerProvider` 只调用新 provider 自己的 `onRegister(this::invalidate)`（`:50`），**不会**全量失效。而 `SingleImpl.get` 会把「provider 全返回 null」**负缓存**（`:96-98`）。→ 若某 key 在 `registerProvider` 之前被 `get()` 过，将永远返回 null，直到手动 `invalidate()`。

### 4.3 车站与列车绑定

**「车停在站上」是三元组推导出来的，没有任何 `occupied` 布尔位**

| 位置 | 类型 | 写入点 | 语义 |
|---|---|---|---|
| `Train.currentStation` | `UUID`（`Train.java:123`） | `setCurrentStation:961`、`leaveStation:946`（置 null） | **唯一权威状态** |
| `GlobalStation.nearestTrain` | `WeakReference<Train>`（`GlobalStation.java:42`） | `reserveFor:123`、`cancelReservation:130`、`trainDeparted:135` | 预订，只在「更近」时覆盖 |
| `GlobalStation.assembling` | `boolean`（`:43`） | `enterAssemblyMode` / `exitAssemblyMode` | **排斥**：`canApproachFrom` 与 `canNavigateVia` 都 `&& !assembling` |

```java
// GlobalStation.java:139-145  占用是推导出来的
public Train getPresentTrain() {
    Train nearestTrain = getNearestTrain();
    if (nearestTrain == null || nearestTrain.getCurrentStation() != this) return null;  // 双向一致性
    return nearestTrain;
}
// Train.java:965-971  currentStation 不是读字段，是回图里查
public GlobalStation getCurrentStation() {
    if (currentStation == null || graph == null) return null;
    return graph.getPoint(EdgePointType.STATION, currentStation);
}
```

> ⚠ **伪造 station UUID 无效**：`getCurrentStation()` 回图里查，查不到就 null，于是站台认为无车、装配界面禁用、邮件不转运。要「伪造占用」只能造真实边点。

`GlobalStation` 方法：

| 方法 | 位置 | 语义 |
|---|---|---|
| `canApproachFrom(TrackNode side)` | `:114` | `isPrimary(side) && !assembling` |
| `canNavigateVia(TrackNode side)` | `:119` | 同上（覆写基类） |
| `reserveFor(Train)` | `:123` | 只有「更近的列车」才能覆盖预定（`WeakReference`） |
| `cancelReservation(Train)` | `:130` | |
| `getPresentTrain()` | `:140` | 停在站内的列车 |
| `getImminentTrain()` | `:148` | 30 格内正在进站的列车 |
| `getNearestTrain()` | `:162` | |

---

## 5. 层 E：表现与客户端

| 目标 | 落点 | 方式 |
|---|---|---|
| 改信号灯外观 | `SignalState.isRedLight/isYellowLight/isGreenLight(renderTime)`（`SignalBlockEntity.java:43-53`） | mixin 或自建 BE |
| 改信号机贴图/模型 | `SignalRenderer` / `SignalVisual` | 客户端事件 + 自定义 renderer |
| 加新的灯色 | 扩 `SignalState` + 改三个 `isXxxLight` | mixin 枚举（可行，见 §2.3） |
| 列车 HUD | `TrainHUD` / `TrainHUDUpdatePacket` / `TrainStatus` | 自定义 packet 或 mixin |
| 站牌/告示 | `FlapDisplayBlockEntity` + `GlobalTrainDisplayData` | 注册 `DisplaySource`（`api/behaviour/display`） |
| 地图叠加 | `compat/trainmap/*`（Xaero / JourneyMap / FTBChunks） | 参考实现 |

> **客户端拿到的信号状态**走的是 `SignalBlockEntity` 的 `state` 字段（`write/read` 里 `NBTHelper.writeEnum(tag,"State",...)`，`:84/:92`），**不是** `SignalBoundary.cachedStates`。客户端 `SignalBoundary` 只在图同步时拿到 `groups`（`SignalBoundary.write(FriendlyByteBuf)` 只写 groups，`:324-333`）。所以**改灯色必须在服务端改 `cachedStates`，再靠 BE 的 `notifyUpdate` 下发**；直接改客户端字段不会持久。

---

## 6. 「我想做什么」→ 用哪个接口

| 需求 | 推荐落点 | 介入方式 | 风险 |
|---|---|---|---|
| 加一种**全新设施**（如自研信号机方块） | `EdgePointType.register(...)` + 自定义 `TrackEdgePoint` 子类 | Registry | 低（但见 §1.3 陷阱） |
| **替换信号灯判定**（闭塞/联锁/进路） | `SignalBoundary.tickState` | mixin `@Inject` HEAD cancellable | 中（`cachedStates` 是 private，需 `@Shadow` 或 AT） |
| **替换减速曲线** | `Navigation.tick` 的 `:265-288` | mixin `@Inject` TAIL 覆写 `train.targetSpeed` | 低（最省事的切入点） |
| **替换寻路算法** | `Navigation.search` | mixin `@Overwrite`（或 `@Inject` 在 HEAD 设 cancel + 自己填 `reachedVia`） | 高（还要接管 `findPathTo` 的回溯构造） |
| **接管整台车的驾驶** | `Navigation.tick` HEAD `ci.cancel()` + 自己写 `targetSpeed` | mixin | 高（会丢失车站预定、出站检查、弯道限速） |
| **加一套全局调度器** | `GlobalRailwayManager.tick` HEAD | mixin `@Inject` | 低 |
| **加自己的时刻表指令** | `Schedule.INSTRUCTION_TYPES` | List 注册 | 中（无冻结/无去重） |
| **改车站竞争** | `GlobalStation.reserveFor` | mixin | 低 |
| **让列车「看见」你的新信号类型** | `Train.frontSignalListener` + `Navigation.tick` 的 `instanceof SignalBoundary` | mixin 或让你的点**继承** `SignalBoundary` | 中 |
| **纯观察/记录** | `IEdgePointListener` 包装 | 组合（但需先能拿到 `Carriage.travel` 的调用点） | 低 |

---

## 7. 明确做不到的事

1. **没有「列车控制 API」可注册**。`api/` 下无 signal/navigation 相关接口，官方不打算开放。所有扩展都是「内部类的 mixin」。
2. **不能在不 mixin 的情况下改变原版列车的停车行为**。`Train.frontSignalListener()` 内联写死了 `speed = 0`。
3. **不能给 `Train`/`Navigation` 加持久化字段而不 mixin**。二者是普通类，NBT 读写在自己内部（`Train.write/read` `:1173+`）。加字段要 mixin `read/write` 并自带版本兼容。
4. **不能跨维度调度**。`GlobalRailwayManager.tick` 只跑主世界（`:194`）。
5. **不能保证与 CRN 共存于同一 mixin 目标**。见 `docs/04-compat-matrix.md`。

---

## 8. 复核清单（上游更新后逐条 grep）

```powershell
$c='F:\TCM\Create\src\main\java\com\simibubi\create\content\trains'
# 1. 灯色计算是否仍单点
Select-String -Path "$c\signal\SignalBoundary.java" -Pattern 'tickState|resolveSignalChain|cachedStates'
# 2. 停车回调是否仍在此
Select-String -Path "$c\entity\Train.java" -Pattern 'frontSignalListener|speed = 0|occupy\('
# 3. targetSpeed 写入点
Select-String -Path "$c\entity\Navigation.java" -Pattern 'targetSpeed|brakingDistance|waitingForSignal'
# 4. 全局 tick 顺序
Select-String -Path "$c\GlobalRailwayManager.java" -Pattern 'tickPoints|tickTrains|signalEdgeGroups'
# 5. 边点注册面
Select-String -Path "$c\graph\EdgePointType.java" -Pattern 'TYPES|register'
# 6. singleSignalGroup 的唯一驱动者
Select-String -Path "$c\graph\EdgeData.java" -Pattern 'singleSignalGroup|hasSignalBoundaries'
```

任何一条 grep 的结果与本文表格不符 → 先更新本文，再改代码。
