# 04 — 兼容矩阵：Create / CRN / DragonLib2

> 基线：Create `7ff2760`（6.0.11）｜ CRN `d9aa171a`（0.10.0-C6, `mc1.21.1-DL3`）｜ DragonLib2 `ae7ce60`（3.0.29, `mc1.21.1-v3`）
> CRN = Create Railways Navigator = `Create-Train-Navigator/`（mod id `createrailwaysnavigator`，包 `de.mrjulsen.crn`）

---

## 0. 三方关系

```
        ┌──────────────────────────────┐
        │ Create (NeoForge 1.21.1 only)│
        └───────────┬──────────────────┘
                    │ 被依赖
      ┌─────────────┴──────────────┐
      │                            │
┌─────▼──────────┐        ┌────────▼─────────────┐
│ 我们的 TCM      │        │ CRN (0.10.0-C6)      │
│ 信号 + 自动控制  │        │ 调度后端 + UI + Web API│
└─────┬──────────┘        └────────┬─────────────┘
      │                            │ 可选依赖
      │                    ┌───────▼────────┐
      └──── 可选依赖 ──────│ DragonLib 3    │
                           └────────────────┘
```

**关键判断**：CRN 不是「一个无关的参考实现」——它**已经在做**我们要做的事的一部分（列车调度后端、速度限制、惩罚模型）。我们必须决定：**共存、竞争、还是接管**。

---

## 1. CRN 的实际注入面（已逐文件核实）

配置文件：`Create-Train-Navigator/common/src/main/resources/createrailwaysnavigator.mixins.json`

```json
{
  "required": true,
  "package": "de.mrjulsen.crn.mixin",
  "compatibilityLevel": "JAVA_17",
  "injectors": { "defaultRequire": 1 }
}
```

> ⚠ `required: true` + `defaultRequire: 1` —— **CRN 的注入失败会直接崩游戏**，不是降级。这意味着：任何破坏它锚点的行为都等于「装了 CRN 就崩服」。

| CRN mixin | 目标 | 注入方式 | 锚点 |
|---|---|---|---|
| `NavigationMixin` | `Navigation#search(DDZLjava/util/ArrayList;L...$StationTest;)V` | `@ModifyExpressionValue` ×2 | `TrackEdgePoint.canNavigateVia(TrackNode)` 的 INVOKE，`ordinal = 0` 与 `1` |
| `TrainMixin` | `Train#tickOccupiedObservers` | `@Redirect` | `TrackGraph.getPoint(EdgePointType, UUID)` 的 INVOKE |
| `ScheduleRuntimeMixin` | `ScheduleRuntime#tick(Level)` | `@Inject` HEAD（**非** cancellable） | 每 100 tick 对被红灯拦住的 `PrioritizedDestinationInstruction` 重新规划 |
| `ScheduleRuntimeMixin` | `ScheduleRuntime#tickConditions(Level)` | `@Inject` HEAD **cancellable** | `TrainSeparationCondition` 未满时取消条件推进 |
| `ScheduleEntryMixin` | `ScheduleEntry#fromTag` | `@Inject` RETURN + `LocalCapture` | **过滤掉 `null` condition**（见 §5 的意外之喜） |
| `ScheduleRuntimeAccessor` | `ScheduleRuntime` | `@Accessor` | `cooldown` |
| `TrainStatusAccessor` / `CreateAccessor` / `ContraptionAccessor` | — | `@Accessor` | 只读 |
| `StationBlockEntityMixin` / `ScheduleScreenMixin` / `TrackTargetingClientMixin` / `ScheduleScreenAccessor` / `ModularGuiLineBuilderAccessor` | 客户端类 | 混合 | UI |
| `ItemMixin` / `LecternBlockMixin` / `MountedStorageManagerMixin` | 原版 / Create | 混合 | 与列车无关 |
| `extensions.ScheduledDelayMixin` / `TimeOfDayConditionMixin` | Create 时刻表内置条件 | 混合 | 扩展等待条件 |

> **DragonLib2 对 Create 列车系统零 mixin**（已核实：`MC-DragonLib2` 的 `@Mixin` 全部指向原版类与模型加载）。可视为「列车语义上安全」，但见 §4 的被动风险。

**一个容易被误读的细节**：`NavigationMixin` 的两处 `@ModifyExpressionValue` 把入参**原样返回**（`NavigationMixin.java:27, :33` 都是 `return canNavigate;`）。它并不拦截寻路，只是借 `@Local` 的 `LocalIntRef` 往 Create 的 Dijkstra 代价里**加价**。

> 所以准确说法是：**CRN 没有接管 Navigation，也没有替换信号判定；它是「寄生在 Create 代价函数上的调度层」。** 18 个 mixin 里 `SignalBoundary` / `SignalEdgeGroup` / `SignalPropagator` 一个 target 都没有，零 `@Overwrite`。

### 1.1 它真正的「接管」只有 4 处

| # | 手段 | 性质 |
|---|---|---|
| 1 | 用 `INavigationPenaltyProvider` 给寻路**加软代价** | 影响「选哪条路」，不影响「能不能走」 |
| 2 | `PrioritizedDestinationInstruction` 替代 `DestinationInstruction` 做**多候选选站** | 指令级替换 |
| 3 | `TrainSeparationCondition` 在 `tickConditions` HEAD `cancel()` **压住发车** | 条件级拦截 |
| 4 | 自定义边点 `PenaltyAnchor` 的 `TrackTargetingClient.render` 预览 | 纯客户端 |

---

### 1.2 ★ 最根本的语义冲突：软代价 vs 硬闭塞

这是三方共存的**核心矛盾**，比 mixin 撞点更重要：

| | Create 原版 | CRN | 我们想做的 |
|---|---|---|---|
| 闭塞是什么 | **硬约束**：`SignalEdgeGroup.isOccupiedUnless` 为 true → 车必须停 | **软代价**：给路径加 `penalty`，车会绕路，但不会因此停 | **硬约束**（进路/联锁） |
| 冲突表现 | — | CRN 认为「加价就能绕开」，但若只有一条路，加价无效、车照样开进被占区间 | 我们若只加 mixin 在代价层，就会和 CRN 的加价互相抵消 |

**结论**：我们的闭塞必须落在**占用判定层**（`isOccupiedUnless` / `currentSignalResolved` / `frontSignalListener`），不能只落在代价层。CRN 的加价是「建议」，我们的占用是「禁令」，两者层次不同、可以叠加，但**不能互换**。

---

## 2. 冲突矩阵：我们能做什么、不能做什么

| 我们想注入的位置 | 与 CRN 的关系 | 判定 | 说明 |
|---|---|---|---|
| `Navigation#search` **`@Overwrite`** | ❌ **禁止** | **必崩** | CRN 的两个 `@ModifyExpressionValue` 锚定方法体内的 `canNavigateVia` INVOKE；Overwrite 抹掉 INVOKE → `defaultRequire: 1` 失败 → 崩游戏 |
| `Navigation#search` `@Inject(HEAD, cancellable)` | ✅ 安全 | 可用 | 指令仍在，锚点不失效 |
| `Train#tickOccupiedObservers` 的 `TrackGraph.getPoint` `@Redirect` | ❌ **禁止** | 必崩 | Mixin 同一 CALL 只允许一个 `@Redirect` |
| `Train#tickOccupiedObservers` `@Inject(HEAD)` | ✅ 安全 | 可用 | |
| `ScheduleRuntime#tick` `@Inject(HEAD, cancellable)` | ⚠ **避免** | 静默禁用 CRN | CRN 是非 cancellable HEAD；你一 cancel，它的重新规划逻辑永不执行（不崩，但功能消失，且很难排查） |
| `ScheduleRuntime#tick` `@Inject(TAIL)` 或 `RETURN` | ✅ 安全 | 推荐 | |
| `ScheduleRuntime#tickConditions` `@Inject(HEAD, cancellable)` | ❌ **禁止** | 先到先得 | CRN 已 cancellable HEAD；两个 cancellable HEAD，优先级决定谁生效，另一个永不执行 |
| `ScheduleRuntime#tickConditions` `@Inject(TAIL)` | ✅ 安全 | 推荐 | |
| `SignalEdgeGroup#isOccupiedUnless` | ✅ **无冲突** | 首选 | CRN 完全没碰信号占用判定 |
| `SignalBoundary#tickState` / `resolveSignalChain` | ✅ **无冲突** | 首选 | CRN 没碰灯色结算 |
| `Navigation#currentSignalResolved` | ✅ **无冲突** | 首选 | CRN 没碰 |
| `Navigation#tick` | ⚠ 谨慎 | 可用但与 CRN 的**行为**叠加 | CRN 会在被拦 100 tick 后重规划；你若也改 `waitingForSignal`，两者会互相打架 |
| `Train#front/backSignalListener` | ✅ **无冲突** | 推荐 | CRN 没碰 |
| `GlobalRailwayManager#tick` | ✅ **无冲突** | 推荐 | 最适合插全局调度帧 |
| 新增 `EdgePointType` | ✅ 无冲突 | **推荐** | CRN 自己也这么干（`ModExtras.PENALTY_ANCHOR`） |
| `Schedule.INSTRUCTION_TYPES` / `CONDITION_TYPES` | ✅ 无冲突 | 推荐 | CRN 也是往里 add |

### 2.1 三条铁律

1. **永不 `@Overwrite`** 任何 CRN 锚定过的方法：`Navigation#search`、`Train#tickOccupiedObservers`、`ScheduleRuntime#tick`、`ScheduleRuntime#tickConditions`。
2. **永不与 CRN 抢同一个 `@Redirect` CALL**。
3. **两个 cancellable HEAD 不能重叠**；能放 TAIL 就放 TAIL。

### 2.2 优先级

- CRN 的 mixin json **没有声明 `priority`** → 用默认 `1000`。
- 我们要么也用 `1000`（则 IDE 里出现顺序不定，**必须靠 TAIL/HEAD 的空间分离而非 priority 取胜**），要么显式设 `900`（数值小 = 更早应用）。
- ⚠ **`priority` 对 `@Inject` 回调执行顺序的影响与直觉相反，必须实测**。本工作区的策略是：**不依赖 priority，靠选择不同的 At 位置避免重叠**。

---

## 3. CRN 提供的公开 API（可直接复用，省掉大量 mixin）

CRN 有一套自己的 `api` 包（`de.mrjulsen.crn.api`），是**给我们这种 mod 用的**：

| API | 位置 | 用途 | 我们能怎么用 |
|---|---|---|---|
| `INavigationPenaltyProvider` | `api/core/INavigationPenaltyProvider.java` | 让**自己的 `TrackEdgePoint`** 参与寻路罚分 | 实现它 → 我们的轨道限制会影响 Create 的 A* 代价，**无需 mixin** |
| `SpeedLimitProviderRegistry` + `ISpeedLimitProvider` | `api/core/speed/` | 注册「前方限速」给 CRN 的时间估算 | 我们的信号限速可以喂给 CRN 的到达时间预测，让它的 UI 显示真实值 |
| `RailwayBackendApi` | `api/core/RailwayBackendApi.java` | 查询列车/车站/信号快照 | 我们**不用自己扫 `Create.RAILWAYS`** 就能拿到结构化数据 |
| `RailwayBackendEvents` / `RailwayBackendListener` | `api/event/` | 后端事件订阅 | 到站/发车等事件的现成通道 |
| `CreateSignalSnapshot` / `CreateTrainSnapshot` / … | `api/core/snapshot/` | 只读快照 DTO | 跨 mod 数据交换的安全格式 |

示例（把我们的限速喂给 CRN）：

```java
SpeedLimitProviderRegistry.register(
    ResourceLocation.fromNamespaceAndPath("tcm", "block_speed"),
    query -> MyBlockOccupancy.limitsAhead(query.trainId(), query.horizon())
);
```

> **策略价值**：这些 API 让我们在「不碰 CRN 内部」的前提下与它互通。**优先用它们，而不是去 mixin CRN 的类。**

---

## 4. DragonLib2：要不要依赖？

| 维度 | 事实 | 结论 |
|---|---|---|
| 性质 | 跨 loader 基础库（Architectury，Fabric + NeoForge），`mod_id = dragonlib`，3.5 万行 | 纯基建 |
| 对 Create 列车的侵入 | **零 mixin**（全部指向原版类/模型加载） | 语义不冲突 |
| **被动风险** | 自己的 mixin 是 `required: true` + `defaultRequire: 1`，其中 `LevelChunkMixin` 注入 `clearAllBlockEntities` / `addAndRegisterBlockEntity` | ⚠ 与 Create 的 BE 活动区重叠，历史上引发过多次崩溃修复 |
| 网络 | `DLNetworkManager` / `NetworkPacker` / `PacketType`，1618 行，含请求-响应配对、超时、线程池、**自动分片** | 唯一真有价值的部分 |
| 「跨 loader」卖点 | **伪需求**：Create 1.21.1 是纯 NeoForge 工程（`Create/build.gradle` 只有 `neoForge{}`，无 fabric 源集） | 价值归零 |
| 注册 / 配置 / mixin 基建 | **不存在**（`DLRegistries` 是 5 行空类；配置就是它自己用 `ModConfigSpec`；mixin 无插件） | 零可复用资产 |
| GUI | `client/gui` 89 文件 / 12,975 行，多窗口/模态/富文本/数据表格/HUD | 只有做调度台才值得 |

**决策：不依赖。**

理由：
1. **Create 1.21.1 只有 NeoForge**，跨 loader 抽象没有收益。
2. 网络层那 1618 行确实省事，但附带 4 笔债：handler 默认在**工作线程**跑（碰 `Train` / `SignalEdgeGroup` 必须先 `ctx.queue`）、`protocolVersion` 是从不校验的死参数、一包一 `channel/name`（改名即失联）、线程池在 `CLIENT_PLAYER_JOIN`/`SERVER_STARTED` 才初始化（早到的包抛 `IllegalStateException`）。
3. 自写 NeoForge 原生 payload 只需 **150~250 行**，且没有 `LevelChunkMixin` 的被动风险。
4. CRN 依赖 `dragonlib beta-3.0.28`；我们若也依赖就被钉死在同一版本节奏。**软依赖不可行**（类加载即崩）。

**例外**：后续要做「CRN 级调度台 UI」时，才为**客户端模块**引入，且严格限定 `network` / `client.gui` / `client.util` / `client.ber` / `util`，**避开 `client.model`**（近期崩溃源）。

> ⚠ 落地前必须核实 Maven 坐标：版本串有两套（maven 带 MC 前缀如 `1.21.1-3.0.29`、jar 内 mod 版本不带），公网 `https://maven.mrjulsen.net` 目录列表 404，**必须用 gradle 实际解析验证**。

---

## 5. 三个必须知道的跨 mod 事实

### 5.1 CRN 顺手修掉了 Create 的一个崩溃向量

Create 的 `ScheduleWaitCondition.fromTag` 缺失类型时返回 `null`（`ScheduleWaitCondition.java:48-51`），而 `ScheduleRuntime.tickConditions` 会直接调它 → **NPE 崩服**。

CRN 的 `ScheduleEntryMixin` 在 `ScheduleEntry.fromTag` 返回后**过滤掉所有 `null` condition**：

```java
// CRN ScheduleEntryMixin.java:16-19
@Inject(method = "fromTag", at = @At("RETURN"), locals = LocalCapture.CAPTURE_FAILHARD)
private static void onFromTag(..., CallbackInfoReturnable<ScheduleEntry> cir, ScheduleEntry entry) {
    entry.conditions.forEach(x -> x.removeIf(y -> y == null));
}
```

**含义**：装了 CRN 时这个坑被悄悄填了；**没装 CRN 时它还在**。→ 我们自己的 mod **不能依赖 CRN 的这个补丁**，必须自己处理（见 `docs/03` §6 与 research 报告 L4）。

### 5.2 CRN 自己就注册了一个自定义 `EdgePointType`

`ModExtras.PENALTY_ANCHOR`，并通过 `TrainMixin` 的 `@Redirect` 在 `tickOccupiedObservers` 里做 fallback 查找：

```java
TrackEdgePoint point = graph.getPoint(type, id);
return point != null ? point : graph.getPoint(ModExtras.PENALTY_ANCHOR, id);
```

**含义**：`EdgePointType.register` 这条路是**经过实战验证的**，CRN 已经在生产环境用它。我们可以放心走同一条路（记得换自己的命名空间）。

### 5.3 三方的「行为叠加」风险

CRN 会做这些事，我们必须知道：
- 被红灯拦住 100 tick 后，**主动重新规划**（`ScheduleRuntimeMixin.crn$reconsiderWhileHeld`），把车导向别的站。
- 用 `TrainSeparationCondition` 在站内**扣住发车**。
- 给寻路加**自有罚分**。

我们的系统若也做「红灯超时后改派」，就会和 CRN 的重新规划**双重触发**。规避办法：
- 检测 `crn` mod 是否加载 → 若加载，把「超时改派」交给它，我们只做信号判定。
- 或者我们接管 `ScheduleRuntime#tick`（但按 §2 那是禁止项）→ 所以**只能靠让步**。

---

## 6. 推荐的整体架构（三方共存）

```
┌──────────────────────────────────────────────────────────┐
│ 我们的 TCM                                                │
│                                                          │
│  【信号判定内核】                                          │
│    mixin: SignalEdgeGroup#isOccupiedUnless ×2  (无冲突)    │
│    mixin: Navigation#currentSignalResolved     (无冲突)    │
│    mixin: SignalBoundary#tickState (TAIL)      (无冲突)    │
│                                                          │
│  【登记与兜底】                                            │
│    mixin: Train#front/backSignalListener       (无冲突)    │
│    mixin: GlobalRailwayManager#tick (HEAD)     (无冲突)    │
│                                                          │
│  【数据层】                                                │
│    EdgePointType.register(自己的类型)          (无冲突)    │
│    Schedule.INSTRUCTION_TYPES.add(...)        (无冲突)    │
│                                                          │
│  【与 CRN 的握手】—— 优先走它的公开 API                     │
│    实现 INavigationPenaltyProvider  → 影响它的寻路         │
│    注册 ISpeedLimitProvider         → 影响它的时间估算     │
│    订阅 RailwayBackendEvents        → 拿到到站/发车事件     │
└──────────────────────────────────────────────────────────┘
              │ 只在必要时才碰这些「雷区」
              ▼
    ✗ Navigation#search 的 @Overwrite
    ✗ Train#tickOccupiedObservers 的 @Redirect
    ✗ ScheduleRuntime#tick HEAD cancellable
    ✗ ScheduleRuntime#tickConditions HEAD cancellable
```

**一句话**：**信号判定是我们的地盘（CRN 不碰），调度是 CRN 的地盘（我们让出）**。这个分工几乎零冲突。

---

## 7. 上线前自检清单

```powershell
# 1. 三个参考仓库仍然干净
foreach ($r in 'Create','Create-Train-Navigator','MC-DragonLib2') { git -C "F:\TCM\$r" status --short }

# 2. 我们的 mixin 目标里没有 CRN 的锚点
#    （在 IDE 里全局搜索我们 mixin 配置的方法名，对照 §1 表）

# 3. 四种加载组合都要能起服
#    TCM only / TCM+CRN / CRN only / 都不装
```

| 组合 | 期望 |
|---|---|
| 只有 Create | 原版行为 |
| Create + TCM | 我们的信号与驾驶生效 |
| Create + CRN | CRN 全功能 |
| **Create + TCM + CRN** | **不崩、不抢锚点、信号归我们、调度归 CRN** |

最后一行是**唯一真正需要测试的目标组合**。其余三种只是退化。
