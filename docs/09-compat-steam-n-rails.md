# 09 — 与 Steam 'n' Rails 的兼容性 & 真实道岔实现参考

> 移植仓：`F:\TCM\Railway-1.21.1` @ `178d1013`（分支 `1.21/dev`）
> 官方仓：`F:\TCM\Railway` @ `7441960`（分支 `1.20/dev`，**无 1.21 分支**）
> 核对的 Create 基线：`7ff2760`（6.0.11）

---

## 0. 两个仓库分别是什么

| | 官方 `Layers-of-Railways/Railway` | 移植 `Porters-of-Railways/Railway-1.21.1` |
|---|---|---|
| 分支 | `1.20/dev`（默认） | `1.21/dev`（默认） |
| 目标 | MC 1.20.1 / Create 6 | **MC 1.21.1 / Create 6** |
| 版本 | — | `0.3.0-beta.2` |
| NeoForge | — | `21.1.233` |
| Create | — | `6.0.11-312` |
| 规模 | 24,527 文件 | 36,156 文件 |

**官方确实没有完成 1.21 移植**（`git ls-remote --heads` 里只有 `1.18/*`、`1.19/*`、`1.20/*`）。所以 1.21.1 上的兼容对象是**移植仓**。

> ⚠ 版本钉死：移植仓要求 NeoForge **21.1.233**、Create **6.0.11-312**。本工作区的 Create 是 dev 分支（`neo 21.1.219`）。集成时要先对齐这两个版本，否则 `MixinTrackEdge#canTravelTo` 这类注入会因为方法体变化而 `require` 失败。

---

## 1. ★ 它的道岔是怎么做的（你点名要参考的部分）

### 1.1 定性：道岔是一个 **EdgePoint**，不是方块逻辑

```java
// registry/CREdgePointTypes.java
public static final EdgePointType<TrackSwitch> SWITCH =
    EdgePointType.register(Railways.asResource("switch"), TrackSwitch::new);
```

```java
// content/switches/TrackSwitch.java:73
public class TrackSwitch extends SingleBlockEntityEdgePoint {
    private TrackNodeLocation switchPoint;                 // 岔心
    private final List<TrackNodeLocation> exits;           // 出口
    private TrackNodeLocation straightExit, leftExit, rightExit;
    private SwitchState switchState = SwitchState.NORMAL;  // ★ 三态
    private boolean automatic, locked, autoTrainsSwitch;
}
```

**这是最关键的架构选择**：道岔不是「一个方块会扳动轨道」，而是**挂在轨道图上的一个逻辑点**。方块只是它的一层外壳。

对我们的直接意义：**我们本来就在设计 `SwitchSite`，而 S&R 已经用 `EdgePointType.register` 给出了可运行的范本**——走的是同一条路，不是巧合。

### 1.2 岔位模型：三态，不是两态

```java
// TrackSwitch.java:117-122
private TrackNodeLocation getExit(SwitchState state) {
    return switch (state) {
        case NORMAL        -> straightExit;
        case REVERSE_RIGHT -> rightExit;
        case REVERSE_LEFT  -> leftExit;
    };
}
```

`NORMAL` / `REVERSE_LEFT` / `REVERSE_RIGHT` —— **直股 / 左开 / 右开**。

这比「定位/反位」准确，也正好对应真实单开道岔。**我已经把这个模型吸收进 API**：

```java
enum SwitchPosition { NORMAL, REVERSE_LEFT, REVERSE_RIGHT, UNKNOWN }
```

### 1.3 实现机制：**禁用未开通的边**

```java
// mixin_interfaces/ISwitchDisabledEdge.java
static boolean isEnabled(TrackEdge edge) {
    return ((ISwitchDisabledEdge) edge.getEdgeData()).isEnabled();
}
```

```java
// mixin/MixinEdgeData.java:26-30
@Mixin(value = EdgeData.class, remap = false)
public class MixinEdgeData implements ISwitchDisabledEdge {
    private boolean enabled = true;
    private boolean automatic = false;
    private int selectPriority = -1;
}
```

→ **道岔不改轨道几何，而是给 `EdgeData` 加一个 `enabled` 标志，把未开通的股道标记为不可通行。**

门控点：

```java
// mixin/MixinTrackEdge.java:44-65
@Inject(method = "canTravelTo", at = @At("HEAD"), cancellable = true)
private void travelThroughSwitches(TrackEdge other, CallbackInfoReturnable<Boolean> cir) {
    if (MixinVariables.signalPropagatorCallDepth > 0) return;   // 信号传播时不门控
    if (MixinVariables.temporarilySkipSwitches) return;
    TrackEdge relevantEdge = MixinVariables.trackEdgeTemporarilyFlipped
        ? (TrackEdge) (Object) this : other;
    ...
    if (ISwitchDisabledEdge.isDisabled(relevantEdge))
        cir.setReturnValue(false);
}
```

**这个设计的漂亮之处**：`TrackEdge.canTravelTo` 是所有寻路的必经之路（`Navigation.search:698`），所以只要门控这一处，**寻路、列车行进、AI 全部自动尊重道岔**，不需要改任何一方的逻辑。

**代价**（对我们很重要）：

1. **图的可通行性变成动态的**。`edgesFrom(node)` 返回的边里，有些是「存在但不可走」。
2. 需要一套全局状态来区分「谁在调用」（`MixinVariables.signalPropagatorCallDepth` / `navigationCallDepth` / `trackEdgeCarriageTravelling` / `trackEdgeTemporarilyFlipped`）——**这是脆弱点**，任何第三方在 `canTravelTo` 上再注入都会踩到。
3. 信号传播期间必须**关闭**门控（`MixinSignalPropagator` 维护 `signalPropagatorCallDepth`），否则闭塞分区会被道岔切断。

### 1.4 自动选股道与锁闭

| 字段 | 语义 |
|---|---|
| `automatic` | 允许列车接近时**自动选股道** |
| `autoTrainsSwitch` | 列车是否可以自动扳岔 |
| `locked` | 人工锁闭，调度不得改位 |

自动选择靠 `EdgeData.selectPriority`（一个自增的全局序号）实现「最近一次选择胜出」：

```java
// MixinEdgeData.java:52-56
public void setAutomaticallySelected() {
    selectPriority = TrackSwitch.getSelectionPriority();   // 全局自增
    enabled = true;
}
```

### 1.5 与信号共存：改 `canCoexistWith`

```java
// mixin/MixinSignalBoundary.java:42-46
@Mixin(value = SignalBoundary.class, priority = 1023)
@Inject(method = "canCoexistWith", at = @At("RETURN"), cancellable = true)
private void railways$switchOrCouplerCanCoexist(EdgePointType<?> otherType, boolean front,
                                                CallbackInfoReturnable<Boolean> cir) {
    if (otherType == CREdgePointTypes.COUPLER || otherType == CREdgePointTypes.SWITCH)
        cir.setReturnValue(true);
}
```

→ **道岔与信号机可以放在同一个位置**。这是因为 `TrackTargetingBehaviour.createEdgePoint` 会遍历 `EdgePointType.TYPES` 逐个问 `canCoexistWith`（`TrackTargetingBehaviour.java:211-227`），被问方（这里是 `SignalBoundary`）说了算。

> 顺带：S&R 还在同一个 mixin 里给 `SignalBoundary.write/read(FriendlyByteBuf)` 补上了 `blockEntities` 的同步（`:48-70`）——正是我们之前发现的「客户端残缺对象」缺口的补丁。**如果我们也要补，会与它重复序列化，必须放弃或协调。**

---

## 2. 注入冲突矩阵（关键）

S&R 共 **90+ 个 common mixin、70+ 个 client mixin**，其中与列车/信号相关的：

| S&R mixin | 目标 | 注入方式 | 我们的计划 | 判定 |
|---|---|---|---|---|
| `MixinTrackEdge` | `TrackEdge#canTravelTo` | `@Inject HEAD cancellable` | 不注入 | ✅ 但**必须尊重**（见 §3） |
| `MixinTravellingPoint` | `TravellingPoint#travel` | `@Inject`×2（INVOKE + RETURN） | 用 `IEdgePointListener` 包装 | ✅ 无直接冲突 |
| `MixinSignalBoundary` | `SignalBoundary#canCoexistWith` | `@Inject RETURN cancellable` | 不注入 | ✅ |
| `MixinSignalBoundary` | `SignalBoundary#write/read(Buf)` | `@Inject RETURN` | 我们**不要**再补同步 | ⚠ 重复序列化 |
| `MixinSignalPropagator` | `SignalPropagator#walkSignals` | `@Inject HEAD/RETURN` | 不注入 | ✅ |
| `MixinSignalBoundary` | `SignalBoundary#tickState` | **未注入** | 我们要 `@Inject TAIL` 写灯色 | ✅ **安全** |
| `MixinNavigation` | `Navigation#tick` | `@WrapOperation`×4 + `@Inject`×3 + `@ModifyVariable`×2 | 我们计划 `@Inject RETURN` | ⚠ **高风险** |
| `MixinNavigation` | `Navigation#currentSignalResolved` | `@WrapOperation`（字段读） | 我们计划 `@Inject RETURN cancellable` | ⚠ 中风险 |
| `MixinNavigation` | `Navigation#search` | `@WrapOperation` + `@ModifyExpressionValue` + `@Inject`×2 | 我们**不碰** | ✅ |
| `MixinNavigation` | `Navigation#findNearestApproachable` | `@Inject HEAD cancellable` | 不碰 | ✅ |
| `MixinTrain` | `Train#frontSignalListener` | `@Inject RETURN cancellable` | 我们**也要** `@Inject RETURN cancellable` | ❌ **直接冲突** |
| `MixinTrain` | `Train#earlyTick` | `@Inject HEAD` + `@Inject INVOKE` | 不碰 | ✅ |
| `MixinTrain` | `Train#write/read` | `@Inject RETURN` | 若要加字段会冲突 | ⚠ |
| `MixinTrain` | `Train#maxSpeed/maxTurnSpeed/acceleration` | `@Inject RETURN cancellable` / `HEAD cancellable` | 不碰 | ✅ |
| `MixinGlobalRailwayManager` | `GlobalRailwayManager#tickTrains` | `@WrapOperation`（`Train.invalid` 字段） | 我们计划 `@Inject HEAD` 于 **`tick`** | ✅ 不同方法 |
| `MixinScheduleRuntime` | `ScheduleRuntime#tickConditions` | `@Inject HEAD cancellable` | 我们不用 | ⚠ CRN 也在这——**两个 mod 已经互相踩** |
| `MixinTrackPlacement` | `TrackPlacement#tryConnect` | `@ModifyExpressionValue` | 不碰 | ✅ |

### 2.1 唯一必须专门处理的冲突：`Train#frontSignalListener`

两边都是 `@Inject(method="frontSignalListener", at=@At("RETURN"), cancellable=true)`。

```java
// S&R MixinTrain.java:175-... 
@Inject(method = "frontSignalListener", at = @At("RETURN"), cancellable = true)
private void frontCouplerListener(CallbackInfoReturnable<IEdgePointListener> cir) { ... }
```

**两个 cancellable RETURN 注入可以共存**，但**执行顺序由 mixin priority 决定**，且后执行的一方拿到的是**已经包装过的** listener。所以正确写法是：

```java
@Inject(method = "frontSignalListener", at = @At("RETURN"), cancellable = true)
private void ctd$wrap(CallbackInfoReturnable<IEdgePointListener> cir) {
    IEdgePointListener prev = cir.getReturnValue();      // ← 可能是 S&R 的
    if (prev == null) return;
    cir.setReturnValue((distance, pair) -> {
        boolean stop = prev.test(distance, pair);        // 先让前面的人决定
        return stop || ctdIntercept(distance, pair);     // 再叠加我们的判定
    });
}
```

**绝不能** `cir.setReturnValue(myListener)` 直接替换——那会把 S&R 的连挂器逻辑（coupler）整个丢掉。

---

## 3. 我们必须改的东西（已落地）

### 3.1 `Topology.Edge` 增加 `enabled`

S&R 的道岔不改几何，只改**可达性**。如果不暴露这个标志，我们的寻路会排进一条已被扳开的股道——**列车会撞道岔或走错线**。

```java
record Edge(Rail.EdgeId id, double length, boolean interDimensional,
            boolean isTurn, String trackMaterialId,
            boolean enabled)          // ← 新增
```

实现方每次读取图时：

```java
boolean enabled = !(SodrCompat.isLoaded()
    && !ISwitchDisabledEdge.isEnabled(edge));
```

### 3.2 `Topology.SwitchSite` 换成真实道岔模型

原来是「度≥3 的节点 + 自动推定的 normal」，现在对齐 S&R：

```java
record SwitchSite(Rail.NodeId node,
                  SwitchPosition position,     // NORMAL / REVERSE_LEFT / REVERSE_RIGHT / UNKNOWN
                  List<Rail.EdgeId> branches,
                  int normal,
                  Rail.EdgeId activeBranch,
                  boolean automatic,
                  boolean locked)
```

**有 S&R 时**：从 `CREdgePointTypes.SWITCH` 读真实状态。
**没有时**：`position = UNKNOWN`，`activeBranch` 由 `enabled` 的边反推。

### 3.3 `SwitchCommand` 要**翻译**，不要并行维护

```java
public record SwitchCommand(Rail.NodeId node, Topology.SwitchPosition position,
                            Rail.EdgeId branch, long holder)
```

实现方在应用时：

```java
if (SodrCompat.isLoaded()) {
    TrackSwitch sw = sodrSwitchAt(node);
    sw.setSwitchState(toSodrPosition(cmd.position()));   // ← 走它的 API
} else {
    myInterlocking.setBranch(node, cmd.branch());
}
```

**绝不要**同时维护「我们的岔位」和「S&R 的岔位」——两套状态一定会分叉，表现是「信号显示开通但道岔没扳」。

### 3.4 我们自己**不要**再实现道岔

既然 S&R 已经有了可运行的道岔（含真实的左右开、自动选股道、锁闭），我们再做一套就是重复劳动，而且会和它的 `canTravelTo` 门控打架。

**定位调整**：我们做**联锁与调度**（谁在什么时候能占用哪段路），道岔的物理执行交给 S&R（如果装了）。没装 S&R 时，我们退回「进路锁闭 + 授权截断」的纯逻辑方案。

---

## 4. 兼容规则（写进实现约定）

| # | 规则 | 理由 |
|---|---|---|
| C1 | **`TrackEdge#canTravelTo` 绝不注入** | S&R 已在 HEAD 门控；再注入必冲突 |
| C2 | **`Navigation#tick` 只做 `@Inject(TAIL)`，不改已有变量** | S&R 在该方法上有 4 个 `@WrapOperation` + 2 个 `@ModifyVariable` |
| C3 | **`Train#frontSignalListener` 必须链式包装**（`prev.test() || mine`） | 否则丢掉连挂器逻辑 |
| C4 | **不补 `SignalBoundary.write/read(Buf)`** | S&R 已补；重复序列化会让客户端读错位 |
| C5 | **寻路必须过滤 `enabled == false` 的边** | 否则排进已扳开的股道 |
| C6 | **信号传播期间不要看 `enabled`** | S&R 自己也是这么做的（`signalPropagatorCallDepth`） |
| C7 | **道岔状态以 S&R 为准**（若在场） | 双状态必然分叉 |

---

## 5. 它还给了我们什么现成答案

| 我们的问题 | S&R 的答案 | 可复用性 |
|---|---|---|
| 自定义 `EdgePointType` 怎么做 | `TrackSwitch extends SingleBlockEntityEdgePoint` + `EdgePointType.register` | ✅ 直接照抄结构 |
| 道岔怎么表达 | 三态岔位 + 禁用未开通边 | ✅ 模型可复用（但别重复实现） |
| 与信号机共存 | 改 `SignalBoundary.canCoexistWith` | ✅ 我们若要放自定义边点在信号旁，需同样处理 |
| 客户端缺字段 | 补 `SignalBoundary.write/read(Buf)` | ⚠ 已被占用 |
| 别的地图/显示集成 | `SwitchDisplaySource` 挂到翻牌屏 | ✅ `DisplaySource` 模式的完整范例 |
| 多 loader | 单文件结构（`neoforge` 子包 + `TrackSwitchBlockImpl`） | ✅ 1.21.1 只有 NeoForge，可简化 |

---

## 6. 结论

1. **S&R 的道岔是我们设计的最佳验证**：`EdgePointType.register` + 逻辑点 + 图可达性，正是我们 `docs/05` §7 推的方向。
2. **但它已经占据了「道岔」这个生态位**。我们不该重复实现，应该**消费**它：读 `enabled`、读/写 `SwitchState`。
3. **注入冲突只有一个真正麻烦点**：`Train#frontSignalListener`。其余靠「选不同的 At 位置」就能躲开。
4. **`TrackEdge#canTravelTo` 是新的禁区**（此前只记了 `Navigation#search`）。CRN 占 `search`，S&R 占 `canTravelTo`——我们两个都不能碰。
5. **版本必须对齐到 NeoForge 21.1.233 / Create 6.0.11-312** 才能和移植仓共存。
