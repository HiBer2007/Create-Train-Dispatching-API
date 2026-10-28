# 03 — 注入策略：三条路线与 mixin 目标登记表

> 基线：Create `mc1.21.1/dev` @ `7ff2760`
> 前置阅读：`docs/02-extension-points.md`、`docs/research/signal-system.md`、`docs/research/schedule-station-registry.md`

---

## 0. 先接受一个事实

Create 的列车系统**没有对外 SDK**。`com.simibubi.create.api` 里与列车相关的只有 `PortalTrackProvider`、`TrainHatInfoProvider`，其余全在 `content.trains.*` 内部包。所以：

- **想让 Create 原版列车按你的规则行驶 → 必须 mixin。**
- **只想新增一类设施、不影响原版行车 → 可以纯 registry（`EdgePointType.register`）。**
- **想接管调度（时刻表层面）→ 反而不需要 mixin**（见 §4.1 的「静默开关」）。

一句最关键的话：

> **`SignalState`（红黄绿）不是行车数据源。** 行车判定实时读 `SignalEdgeGroup.isOccupiedUnless(...)`。只改灯色，车照跑。
> —— `docs/research/signal-system.md` §8、`SignalBoundary.java:186-209`

---

## 1. 三条路线

| | 路线 A<br>只换显示 | 路线 B<br>替换判定内核（推荐） | 路线 C<br>完全平行系统 |
|---|---|---|---|
| **动什么** | `SignalBoundary#getStateFor`<br>`SignalBlockEntity#getState` | `SignalEdgeGroup#isOccupiedUnless` ×2<br>`Navigation#currentSignalResolved`<br>`Navigation#tick` 占用计算<br>`Train#front/backSignalListener` | 无（只用 `EdgePointType.register`） |
| **灯色** | 你的 | 你的 | 你的 |
| **列车是否遵守** | ❌ 不遵守 | ✅ 遵守 | ❌ 完全透明 |
| **mixin 数量** | 2 | 4–6 | 0 |
| **上游更新抗性** | 中 | 中低（4 个点要跟） | 高 |
| **适用场景** | 只想要不同灯语/UI | **真正换掉闭塞与联锁** | 新增设施，不碰原版行车 |

**推荐路线 B。** 它用最少的注入面换来「列车真的听你的」，同时保留 Create 的物理、寻路、渲染、存档。

---

## 2. 路线 B 的最小可行注入集

### 2.1 语义层：`SignalEdgeGroup#isOccupiedUnless`（收益最大，先做这个）

这是「这个分区现在能不能进」的**唯一语义入口**，共 4 处调用（`Navigation:165,325,338`、`Train:497`、`Navigation:640,739`）。

```java
// 目标：signal/SignalEdgeGroup.java:49
// public boolean isOccupiedUnless(Train train)

@Mixin(SignalEdgeGroup.class)
public abstract class SignalEdgeGroupMixin {
    @Inject(method = "isOccupiedUnless(Lcom/simibubi/create/content/trains/entity/Train;)Z",
            at = @At("RETURN"), cancellable = true)
    private void tcm$overrideTrainOccupancy(Train train, CallbackInfoReturnable<Boolean> cir) {
        SignalEdgeGroup self = (SignalEdgeGroup) (Object) this;
        Boolean mine = MyInterlocking.isPermitted(self.id, train);   // 你的判定
        if (mine != null) cir.setReturnValue(!mine && cir.getReturnValue());  // null = 交还原版
    }

    // 目标：signal/SignalEdgeGroup.java:62  public boolean isOccupiedUnless(SignalBoundary)
    @Inject(method = "isOccupiedUnless(Lcom/simibubi/create/content/trains/signal/SignalBoundary;)Z",
            at = @At("RETURN"), cancellable = true)
    private void tcm$overrideSignalOccupancy(SignalBoundary boundary, CallbackInfoReturnable<Boolean> cir) {
        SignalEdgeGroup self = (SignalEdgeGroup) (Object) this;
        Boolean aspect = MyInterlocking.aspectFor(self.id, boundary);   // null = 交还原版
        if (aspect != null) cir.setReturnValue(aspect);
    }
}
```

**注意两个重载语义不同**（别写反）：
- `isOccupiedUnless(Train)`：`reserved != null || trains.size() > 1 || (!trains.contains(train) && !trains.isEmpty())`
- `isOccupiedUnless(SignalBoundary)`：`!trains.isEmpty() || (reserved != null && reserved != boundary)`

用「返回 null 表示交还原版」的约定，可以做到**可开关**：你的系统只接管它认识的信号，其余原样放行。

### 2.2 决策层：`Navigation#currentSignalResolved`

`waitingForSignal` 一旦被设上，`Navigation.tick` 每帧都靠这个方法决定「能不能走了」。它是**唯一**的放行判据，且是 private（`:302`）。

```java
@Mixin(Navigation.class)
public abstract class NavigationMixin {
    @Inject(method = "currentSignalResolved", at = @At("RETURN"), cancellable = true)
    private void tcm$resolve(CallbackInfoReturnable<Boolean> cir) {
        Navigation self = (Navigation) (Object) this;
        if (self.waitingForSignal == null) return;
        Boolean verdict = MyInterlocking.canClear(self.train, self.waitingForSignal);
        if (verdict != null) cir.setReturnValue(verdict);
    }
}
```

### 2.3 决策层（续）：`Navigation#tick` 的「发现」逻辑

`Navigation.java:164-165` 决定「前方这个分区算不算被占用」：

```java
boolean occupied = !train.manualTick
    && (signal.isForcedRed(nodes.getSecond()) || signalEdgeGroup.isOccupiedUnless(train));
```

如果你已经在 2.1 接管了 `isOccupiedUnless`，**这一处通常不用动**。只有当你要改「扫描距离 / 何时认定红灯」时才需要：

```java
@ModifyVariable(method = "tick", at = @At(value = "STORE", ordinal = 0), name = "scanDistance")
private double tcm$scanDistance(double original) {
    return original * MyConfig.scanMultiplier;   // 例如提前 1.5 倍刹车距离发现红灯
}
```

### 2.4 登记层：`Train#frontSignalListener` / `backSignalListener`

你的模型如果要新增占用者（进路锁闭、道岔锁闭、股道预留），在这里登记与释放：

```java
@Mixin(Train.class)
public abstract class TrainMixin {
    @Inject(method = "frontSignalListener", at = @At("RETURN"), cancellable = true)
    private void tcm$wrapFront(CallbackInfoReturnable<TravellingPoint.IEdgePointListener> cir) {
        TravellingPoint.IEdgePointListener original = cir.getReturnValue();
        cir.setReturnValue((distance, pair) -> {
            if (pair.getFirst() instanceof SignalBoundary sb) {
                MyInterlocking.onEnter(((Train) (Object) this), sb, pair.getSecond());
            }
            return original.test(distance, pair);   // 保留原版行为（停车、占用、成就）
        });
    }
}
```

> **关键**：返回的 lambda 里 `pair.getSecond()` 在倒车时已被 `swap()`，`getSecond()` 永远是「正要进入的那一侧」。见 `TravellingPoint.java:373-377`。

### 2.5 兜底层：`SignalBoundary#tickState`（只有你要改「黄灯怎么算」时才需要）

```java
@Mixin(SignalBoundary.class)
public abstract class SignalBoundaryMixin {
    // cachedStates 是 public 的（SignalBoundary.java:37），无需 @Shadow
    @Inject(method = "tickState", at = @At("TAIL"))
    private void tcm$overwriteAspects(TrackGraph graph, CallbackInfo ci) {
        SignalBoundary self = (SignalBoundary) (Object) this;
        for (boolean side : Iterate.trueAndFalse)
            MyAspects.publish(self, side, self.cachedStates);   // 只覆盖你认识的信号
    }
}
```

`tickState` 是 `private`——mixin 可以直接 `@Inject` 到 private 方法，不需要 AT。

---

## 3. mixin 目标登记表（R5 强制）

> 落地前必须在本表登记一行。`require` 默认值见 §5。

| # | 目标 | 选择器 | 基线行 | 目的 | require | 降级 |
|---|---|---|---|---|---|---|
| S-1 | `SignalEdgeGroup#isOccupiedUnless(Train)` | `@Inject RETURN, cancellable` | `:49` | 列车侧占用判定 | 1 | — |
| S-2 | `SignalEdgeGroup#isOccupiedUnless(SignalBoundary)` | `@Inject RETURN, cancellable` | `:62` | 信号侧占用判定 | 1 | — |
| S-3 | `Navigation#currentSignalResolved` | `@Inject RETURN, cancellable` | `:302` | 信号放行 | 1 | 放行=原版 |
| S-4 | `Navigation#tick` | `@ModifyVariable` `scanDistance` | `:135` | 刹车距离 | 0 | 用原值 |
| S-5 | `Train#frontSignalListener` | `@Inject RETURN, cancellable` | `:462` | 进入登记 | 1 | 原版 listener |
| S-6 | `Train#backSignalListener` | `@Inject RETURN, cancellable` | `:530` | 释放登记 | 1 | 原版 listener |
| S-7 | `SignalBoundary#tickState` | `@Inject TAIL` | `:186` | 灯色发布 | 0 | 不覆盖 |
| S-8 | `GlobalRailwayManager#tick` | `@Inject HEAD` | `:193` | 全局调度帧 | 0 | 跳过 |
| S-9 | `ScheduleRuntime#tick` | `@Inject HEAD, cancellable` | `:104` | 接管时刻表（可选） | 0 | 原版推进 |
| S-10 | `ScheduleRuntime#destinationReached` | `@Inject HEAD` | `:71` | 到站事件 | 0 | 事件丢失 |

### 3.1 整体接管路线（`docs/05`）追加的 4 个注入点

若走「整体接管」而非「局部增强」，上表 S-1..S-10 被下列 4 点 + 1 个 accessor 取代：

| # | 目标 | 选择器 | 基线行 | 目的 | CRN 冲突 |
|---|---|---|---|---|---|
| T-1 | `SignalEdgeGroup#isOccupiedUnless(Train)` | `@Inject RETURN, cancellable` → 返回 false | `:49` | **解除**原生闭塞 | 无 |
| T-2 | `SignalEdgeGroup#isOccupiedUnless(SignalBoundary)` | 同上 | `:62` | 同上 | 无 |
| T-3 | `Train#tickPassiveSlowdown` | `@Inject TAIL` | `:593` | **施加**速度授权（硬安全闸） | 无 |
| T-4 | `SignalBoundary#tickState` | `@Inject TAIL` | `:186` | **施加**灯色 | 无 |
| T-5 | `Navigation#control` | `@Inject RETURN, cancellable` | `:347` | **施加**路权（自有进路游标，支持非车站终点） | 无 |
| T-6 | `Navigation#currentPath` | `@Accessor` | `:58` | 读/写进路（方式 A 用） | 无 |
| T-7 | `GlobalRailwayManager#tick` | `@Inject HEAD` | `:193` | 全局快照 pass（**必须**，见 `docs/05` §6.6） | 无 |
| T-8 | `TrackGraph#addNode/removeNode/connectNodes/disconnectNodes` | `@Inject` | `:150/:165/:387/:447` | 拓扑镜像 | 无 |

**这 7 个点 CRN 一个都没碰。** 详细的时序、数学与风险见 `docs/05-full-takeover-design.md`。

> ⚠ **装了汽鸣铁道（Steam 'n' Rails）时的额外禁区**：它已经占据 `TrackEdge#canTravelTo`
> （`@Inject HEAD cancellable`，用于道岔门控）与 `Train#frontSignalListener`（`@Inject RETURN cancellable`）。
> 前者**绝对不能再注入**；后者必须**链式包装**而不是替换返回值。
> 完整冲突矩阵见 **[`docs/09-compat-steam-n-rails.md`](09-compat-steam-n-rails.md) §2**。

**为什么 S-4/S-7/S-8/S-9/S-10 的 `require = 0`**：它们是「增强」不是「基线」。上游改签名时，我们希望**降级成原版行为**而不是崩服。S-1..S-3、S-5..S-6 是「语义基线」，硬性要求；但按 R5，仍要提供 `require = 0` + 自检告警的备选方案（见 §5.2）。

> **装上 CRN 时的额外约束**：上表 S-9 与 CRN 的 `ScheduleRuntimeMixin` 冲突（两个 cancellable HEAD）；S-3 之外的任何 `Navigation#search` 改写都要避开 CRN 的锚点。
> 完整冲突矩阵见 **[`docs/04-compat-matrix.md`](04-compat-matrix.md) §2**，动手前必读。

---

## 4. 更省事的两个「无 mixin」手法

### 4.1 用「静默开关」停掉 Create 原生调度

`ScheduleRuntime.tick` 的控制流（`ScheduleRuntime.java:104-114`）：

```java
if (schedule == null) return;                          // :105  ← 开关 ③
if (paused) return;                                    // :107  ← 开关 ①
if (train.derailed) return;
if (train.navigation.destination != null) {            // :111  ← 开关 ②
    ticksInTransit++;
    return;
}
```

三个开关任一成立，Create 的时刻表状态机**完全不推进**：

| 开关 | 写法 | 副作用 |
|---|---|---|
| ① 暂停 | `train.runtime.paused = true` | 干净，但 UI 显示「已暂停」 |
| ② 有目的地 | `train.navigation.destination != null` | 最自然：Create 认为车在途 |
| ③ 空时刻表 | `train.runtime.schedule = null` | 会丢失时刻表本身 |

**推荐组合**：`paused = true` + 自己驱动 `navigation.startNavigation(...)`。
→ 物理、寻路、渲染、存档仍由 Create 负责，你只接管「下一站去哪、什么时候走」。
→ 这比 `@Inject(cancellable)` 掉整个 `ScheduleRuntime.tick` 更抗版本升级（你只依赖 3 个 public 字段）。

> ⚠ **副作用（必须知道）**：`GlobalTrainDisplayData.refresh()` 第 24 行会**跳过所有 `paused` 或 `schedule == null` 的列车**。
> 也就是说一旦用这三个开关静默原生调度，**Create 自带的翻牌显示屏与站台信息板会全部变空**。
> 对策见 `docs/05-full-takeover-design.md` §9.1-9.2（覆写 `refresh` 或注册自己的 `DisplaySource`）。

### 4.2 用 `IEdgePointListener` 做只读观察

如果你只是想**记录**列车穿过某点（统计、日志、外部 API），不需要改行为：

```java
// 前提：你需要在 Carriage.travel 的调用链上插一脚 —— 这仍需 mixin
// 或在你自己调用 TravellingPoint.travel 时传入组合 listener
TravellingPoint.IEdgePointListener observer = (dist, pair) -> {
    MyMetrics.onPoint(this, dist, pair.getFirst());
    return false;   // 永不拦截
};
```

⚠ Create 自己的 `Carriage.travel` 内部写死 `train.frontSignalListener()`，所以观察**原版列车**仍需 mixin 包装（§2.4 就是干这个的）。

---

## 5. 顺序、优先级、失效

### 5.1 mixin 配置

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "com.yourmod.tcm.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": [ ... ],
  "injectors": { "defaultRequire": 1 }
}
```

- Create 自己的配置是 `priority = 1000`（`create.mixins.json:4`）。**不要设成比它低又做 `@Overwrite`**——`@Overwrite` 撞车会直接崩。
- 我们只做 `@Inject`，不 `@Overwrite`，priority 用默认即可。
- **`@Overwrite` 禁令**：本工作区不使用 `@Overwrite`。理由：与 CRN 等第三方 mod 的冲突不可预测，且上游一改就静默失效（`@Overwrite` 不校验签名兼容性）。

### 5.2 失效检测（R5 的落地点）

`require = 0` 的注入失败**不会报错**。必须自检：

```java
// 在 ModList / FMLLoadCompleteEvent 之后
public static boolean verifyInjections() {
    // 方案 A：反射检查你的 @Unique 字段是否被织入
    // 方案 B：调用一个你加了 @Inject 的方法，看副作用是否发生
    // 方案 C：读取 Mixin 的 audit 结果（复杂，不推荐）
}
```

**约定**：任何 `require = 0` 的注入，都必须有对应的 `TCM_FEATURES` 布尔开关，启动时检测失败则置 false 并记 WARN，**功能降级为原版行为**，不允许「半接管」状态。

### 5.3 状态存放

| 需求 | 做法 | 理由 |
|---|---|---|
| 每列车临时状态 | `Map<UUID, MyTrainState>`（key 用 `train.id`） | 不给 `Train` 加 `@Unique` 字段，避免与 CRN 的 `@Unique` 撞名；uuid 在存档中稳定 |
| 每列车需要持久化 | mixin `Train#write/read` 加 `@Unique` 字段 + **自带版本号** | ⚠ Create 的存档格式**没有** `compatVersion`，靠 `tag.contains("key")` 做可选字段（`Train.java:1158-1253`）。你的新字段必须同样用 `contains` 守卫，否则旧存档读不出来 |
| 全局状态 | 自己的 `SavedData`（照抄 `RailwaySavedData`） | 不要塞进 Create 的存档结构 |
| 信号 → 你的模型 | key 用 `SignalBoundary.id`（UUID，稳定） | 不要用 `BlockPos`（轨道会被拆改） |

> **不要用 `WeakHashMap<Train, ...>` 做持久状态**：`Train` 对象在世界重载时会被重建，引用会丢。

---

## 6. 客户端要求（重要）

| 事实 | 后果 |
|---|---|
| `EdgePointType.read(FriendlyByteBuf)` 对未注册类型 **NPE**（`EdgePointType.java:52-53`） | 客户端必须装你的 mod，否则加入服务器**图同步崩溃** |
| `SignalBoundary.write(FriendlyByteBuf)` **只写 groups**（`:325-333`） | 客户端 `SignalBoundary` 是残缺对象：`blockEntities` 空、`cachedStates` 恒 `INVALID`、`types` 恒 `ENTRY_SIGNAL`。**不要试图在客户端读这些字段** |
| 客户端看到的灯色来自 `SignalBlockEntity.state` 字段（BE update tag） | 你的新灯色要靠 `notifyUpdate()` / 自建 payload 下发 |
| `EdgeData.read` 对未知类型**静默跳过**（`:210-211`） | 卸载你的 mod 不崩，但你的边点数据丢失 |

**结论**：本工作区的产出必须是 **client + server 双端 mod**，不是 server-side only。

---

## 7. 落地顺序建议

```
第 0 步  搭最小可跑骨架：NeoForge 1.21.1 + Create 6.0.11 依赖 + mixin 配置 + 一个空 mixin 验证织入
第 1 步  只做观察：S-5/S-6 包装 listener，把「谁穿过了哪台信号机」打进日志。不改变任何行为。
第 2 步  接管占用：S-1/S-2，先做到「与原版判定完全一致」（回归测试），再加你自己的规则。
第 3 步  接管放行：S-3，让 waitingForSignal 的清除走你的判定。
第 4 步  接管灯色：S-7，把你在第 2/3 步算出的状态反写到 cachedStates。
第 5 步  接管调度：4.1 的「静默开关」，替换 ScheduleRuntime 的推进。
第 6 步  接管驾驶曲线（可选）：S-4 或 Navigation.tick TAIL 覆写 targetSpeed。
```

每一步都要能**单独回退到原版行为**。这是对抗上游更新与第三方冲突的唯一可靠策略。
