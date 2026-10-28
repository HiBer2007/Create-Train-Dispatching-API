# 07 — 换掉整套信号机

> 基线：Create `7ff2760`（6.0.11）
> 前置：`docs/05-full-takeover-design.md`（整体接管）、`docs/06-dispatch-spi.md`（内核接口）

---

## 0. 先纠正一个事实

你说「原生的信号机只有红/绿色灯」——实际情况比这更极端。

**原生信号机只有一个灯，亮红或灭。**

证据链：

```java
// SignalRenderer.java:41-48   唯一的灯色判断
if (signalState.isRedLight(renderTime))
    CachedBuffers.partial(AllPartialModels.SIGNAL_ON,  blockState)...   // 亮
else
    CachedBuffers.partial(AllPartialModels.SIGNAL_OFF, blockState)...   // 灭

// SignalVisual.java:76-79     Flywheel 路径同理
boolean isRedLight = signalState.isRedLight(renderTime);
PartialModel partial = isRedLight ? AllPartialModels.SIGNAL_ON : AllPartialModels.SIGNAL_OFF;
```

全仓只有两个模型：`track_signal/indicator_on` 与 `indicator_off`（`AllPartialModels.java:174`）。

而 `SignalState` 里明明有四个值：

```java
// SignalBlockEntity.java:40-54
enum SignalState {
    RED, YELLOW, GREEN, INVALID;
    public boolean isRedLight(float t)    { return this == RED || this == INVALID && t % 40 < 3; }
    public boolean isYellowLight(float t) { return this == YELLOW; }
    public boolean isGreenLight(float t)  { return this == GREEN;  }
}
```

`isYellowLight` / `isGreenLight` 的**全部调用者只有一个**：`NixieTubeRenderer`（装饰性的数码管模仿信号）。**信号机本身从不调用它们。**

→ 所以 `YELLOW` 与 `GREEN` 在视觉上**完全一致**：都是灭灯。Create 的「绿灯」是用「不亮红灯」表达的。

这解释了为什么你看到的是二值灯——它真的是二值的。

---

## 1. 三处硬编码

| # | 硬编码 | 位置 | 影响 |
|---|---|---|---|
| 1 | `SignalState { RED, YELLOW, GREEN, INVALID }` | `SignalBlockEntity.java:40` | 状态空间只有 4 个值，且渲染只认 RED |
| 2 | `SignalType { ENTRY_SIGNAL, CROSS_SIGNAL }` | `SignalBlock.java:37-38` | 信号机类型只有两种，`EnumProperty` 绑死在方块状态上 |
| 3 | `isRedLight` 单灯渲染 | `SignalRenderer:41`、`SignalVisual:76` | 想多一个灯就必须换渲染器 |

好消息是：**这三处都是「显示层」，与行车安全无关**。
`docs/05` 已经把行车判定整体接管了，信号的灯色对列车**没有任何影响**——它现在纯粹是给玩家看的。

> 换句话说：**换信号机的风险，远低于换闭塞。**

---

## 2. 两条路线

### 路线 A：只补渲染（最小改动，约 30 行）

既然 `isYellowLight` / `isGreenLight` 已经存在，只是没人用——直接 mixin 渲染器补上两个灯即可：

```java
@Mixin(SignalRenderer.class)
public class ThreeLampRendererMixin {
    @Inject(method = "renderSafe", at = @At("HEAD"), cancellable = true)
    private void tcm$threeLamp(SignalBlockEntity be, float pt, PoseStack ms,
                               MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        SignalState s = be.getState();
        float t = AnimationTickHolder.getRenderTime(be.getLevel());
        renderLamp(ms, buffer, be, 0, AllPartialModels.SIGNAL_RED_ON,   s.isRedLight(t));
        renderLamp(ms, buffer, be, 1, MyModels.SIGNAL_YELLOW_ON,        s.isYellowLight(t));
        renderLamp(ms, buffer, be, 2, MyModels.SIGNAL_GREEN_ON,         s.isGreenLight(t));
        // …然后照抄原版的 track overlay 部分，或调原方法并跳过灯
        ci.cancel();
    }
}
```

| 能做 | 不能做 |
|---|---|
| 三显示（红/黄/绿） | 双黄（四显示自动闭塞） |
| 灯位随意排 | 双面不同显示 |
| 不需要新方块 | 信号机类型扩展、编号、复示 |

**适用**：只想让红绿灯看起来正常。**不适用**：你要的整套新信号系统。

### 路线 B：整台换掉（推荐）

自己做方块 + 方块实体 + 渲染器。核心问题是**怎么仍然接在 Create 的轨道图上**。

答案是 `TrackTargetingBehaviour`——它是方块与轨道图之间的唯一桥梁，而且**构造函数是 public 的**：

```java
// TrackTargetingBehaviour.java:73
public TrackTargetingBehaviour(SmartBlockEntity be, EdgePointType<T> edgePointType)
```

摆放工具也是现成的：

```java
// TrackTargetingBlockItem.java:55-58
public static <T extends Block> NonNullBiFunction<? super T, Item.Properties, TrackTargetingBlockItem>
    ofType(EdgePointType<?> type)
```

Create 自己就是这么用的（`AllBlocks.java:1661`）：

```java
.item(TrackTargetingBlockItem.ofType(EdgePointType.SIGNAL))
```

→ 我们的信号机可以**直接挂到原生的 `EdgePointType.SIGNAL` 上**，和原生信号机共享同一个 `SignalBoundary`。

---

## 3. 路线 B 的实现

### 3.1 最省力的切入点：继承 `SignalBlockEntity`

不要从 `SmartBlockEntity` 白手起家。`SignalBlockEntity` 不是 final，而且它已经把该做的都做了：

| 继承到的东西 | 为什么重要 |
|---|---|
| `edgePoint`（已用 `EdgePointType.SIGNAL` 初始化） | 轨道绑定免费 |
| `state` / `overlay` + `getState()` / `getOverlay()` | 原生语义可复用 |
| `getReportedPower()` + `lastReportedPower` | **红石强制红**依赖它 |
| `enterState()` 的 15 帧防抖 + CC 事件 | 不用重写 |
| `TransformableBlockEntity` | 蓝图/结构变换兼容 |

**关键**：`SignalBoundary.blockEntityAdded` 有一句

```java
// SignalBoundary.java:107
blockEntitiesOnSide.put(blockEntity.getBlockPos(),
    blockEntity instanceof SignalBlockEntity ste && ste.getReportedPower());
```

`instanceof` 检查 → **继承就能吃到红石上报**。如果从 `SmartBlockEntity` 白手起家，你会发现 `updateBlockEntityPower` 的参数类型是 `SignalBlockEntity`（`SignalBoundary.java:110`），根本传不进去——红石失效。

```java
public class CtdSignalBlockEntity extends SignalBlockEntity {
    private long aspectMask;          // 灯座位掩码，最多 64 个灯座

    public CtdSignalBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // 父类在 addBehaviours 里已经建好了 edgePoint，这里只加自己的
    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);   // ← 保留 edgePoint 与 computerBehaviour
        behaviours.add(new CtdSignalAspectBehaviour(this));
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider regs, boolean clientPacket) {
        super.write(tag, regs, clientPacket);
        tag.putLong("TcmAspect", aspectMask);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider regs, boolean clientPacket) {
        super.read(tag, regs, clientPacket);
        if (tag.contains("TcmAspect")) aspectMask = tag.getLong("TcmAspect");
    }

    public long aspectMask() { return aspectMask; }

    public void setAspectMask(long mask) {
        if (aspectMask == mask) return;
        aspectMask = mask;
        notifyUpdate();                // 只在下发客户端时才发包
        invalidateRenderBoundingBox();
    }
}
```

> ⚠ 父类 `tick()` 里有一段会读 `SignalBlock.TYPE` 并回写方块状态（`SignalBlockEntity.java:137-144`）。我们的方块如果没有这个属性，`getOptionalValue` 返回空，整段跳过——安全。

### 3.2 方块

```java
public class CtdSignalBlock extends Block implements IBE<CtdSignalBlockEntity> {

    public static final IntegerProperty LAMPS = IntegerProperty.create("lamps", 1, 8);
    public static final EnumProperty<SignalKind> KIND = EnumProperty.create("kind", SignalKind.class);
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    @Override public Class<CtdSignalBlockEntity> getBlockEntityClass() { return CtdSignalBlockEntity.class; }
    @Override public BlockEntityType<? extends CtdSignalBlockEntity> getBlockEntityType() { return AllCtdBEs.SIGNAL.get(); }
}
```

注册（Registrate 风格，与 Create 一致）：

```java
REGISTRATE.block("tcm_signal", CtdSignalBlock::new)
    .properties(p -> p.noOcclusion().strength(3f))
    .item(TrackTargetingBlockItem.ofType(EdgePointType.SIGNAL))    // ★ 关键
    .blockstate((c, p) -> /* 自定模型 */)
    .register();
```

> **一个方块 = 一个信号头**。想要双面信号机？两面各放一个方块。`SignalBoundary.blockEntities` 是 `Couple<Map<BlockPos, Boolean>>`，**同一面可以挂多个方块**（`SignalBoundary.java:33`），会自动合并到同一个 boundary。

### 3.3 渲染

```java
public class CtdSignalRenderer extends SafeBlockEntityRenderer<CtdSignalBlockEntity> {

    @Override
    protected void renderSafe(CtdSignalBlockEntity be, float pt, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        long mask = be.aspectMask();
        int lamps = be.getBlockState().getValue(CtdSignalBlock.LAMPS);

        // 灯座空间布局：沿垂直于轨道的方向排布
        Vec3 along = be.edgePoint.getTargetDirection().getStep();   // 轨道方向
        for (int i = 0; i < lamps; i++) {
            boolean lit = (mask & (1L << i)) != 0;
            if (!lit) continue;
            ms.pushPose();
            ms.translate(/* 依 i 与朝向计算偏移 */);
            CachedBuffers.partial(lampModel(be, i), be.getBlockState())
                .light(LightTexture.FULL_BLOCK)                     // 自发光
                .renderInto(ms, buffer.getBuffer(RenderType.solid()));
            ms.popPose();
        }
        renderTrackOverlay(be, ms, buffer, light, overlay);         // 照抄原版
    }
}
```

**不注册 Flywheel visual 就会走 BER 路径**——`SignalRenderer` 第一行有 `if (VisualizationManager.supportsVisualization(...)) return;`，那是它自己的选择，我们的渲染器不受影响。

> 想要性能，再补一个 `AbstractBlockEntityVisual`；先不做，BER 就够。

### 3.4 灯座语义映射

灯座顺序就是 `Aspect.lamps()` 的顺序。默认四显示布局：

| 灯座 | 灯 | 对应 `Aspect.Lamp` |
|---|---|---|
| 0 | 红 | `RED` |
| 1 | 黄 | `YELLOW` |
| 2 | 黄（第二黄） | `DOUBLE_YELLOW` |
| 3 | 绿 | `GREEN` |

映射函数放在宿主：

```java
static long toMask(Aspect aspect, int lampCount) {
    long mask = 0;
    for (int i = 0; i < lampCount; i++) {
        if (i < aspect.lamps().size()) mask |= 1L << i;
    }
    return mask;
}
```

> 用「按顺序映射」而不是「按灯色映射」，是因为调车白灯、引导月白这些没有固定灯座位置的信号，只能靠布局约定。

---

## 4. 信号机类型与编号

原生只有 `ENTRY_SIGNAL` / `CROSS_SIGNAL` 两种（`SignalBlock.java:37`），且绑在 `EnumProperty` 上——**扩展它就等于改方块状态，存档会炸**。

我们用自己的 `EnumProperty<SignalKind>`，与原生那个并存、互不干扰：

```java
enum SignalKind { HOME, STARTING, BLOCK, SHUNT, DISTANT, ROUTE_INDICATOR }
```

（定义在 `design/spi/.../Topology.java`，内核能看到。）

真正需要**按类型区分**的是联锁逻辑，不是渲染：

| 类型 | 联锁含义 |
|---|---|
| `HOME` 进站 | 开放 = 进路已排到股道；允许列车从区间进入站内 |
| `STARTING` 出站 | 开放 = 允许进入区间；要检查前方闭塞 |
| `BLOCK` 区间 | 自动闭塞，灯色由前方分区占用直接决定 |
| `SHUNT` 调车 | 只到 30% 限速；可越过关闭的调车信号（要授权） |
| `DISTANT` 预告 | **不产生授权**，只影响减速曲线 |
| `ROUTE_INDICATOR` 进路表示器 | 独立于主体信号，只显示方向 |

`DISTANT` 那条很重要：**预告信号机不应该被当成停车点**。如果内核把预告信号当闭塞边界，列车会提前停死。

---

## 5. 显示数据怎么传到客户端

三层，别搞混：

| 层 | 载体 | 频率 |
|---|---|---|
| 服务端权威状态 | 内核的 aspect 表（key = 信号机 UUID） | 每帧 |
| 方块实体同步 | `aspectMask` 写进 `write(clientPacket)` + `notifyUpdate()` | **仅变化时** |
| 渲染 | 读 `be.aspectMask()` | 每帧 |

> ⚠ **绝对不要每帧 `notifyUpdate()`**。`SmartBlockEntity` 的 client packet 是整包更新的，1000 台信号机每帧发 = 服务器上行爆炸。只在 mask 变化时发。

原生 `SignalBlockEntity` 已经示范了正确的节奏：`enterState` 里 `if (this.state == state) return;` 再 `notifyUpdate()`（`SignalBlockEntity.java:172-176`）。

---

## 6. 旧存档怎么办

世界上已经摆了成百上千台原生信号机。三条路：

| 方案 | 做法 | 评价 |
|---|---|---|
| **并存**（推荐） | 原生 `SignalBlock` 照常工作，我们的内核把它的 `cachedStates` 也翻译成 aspect 显示 | 零迁移成本；缺点是两套外观并存 |
| **转换工具** | 手持工具右键原生信号机 → 替换成我们的方块，保留 `SignalBoundary.id` 与 `edgePoint` 的目标 | 需要小心：换方块会触发 `blockEntityRemoved` / `blockEntityAdded`，boundary 会短暂失去一个面 |
| **接管渲染** | 不换方块，mixin `SignalRenderer` 让原生方块按我们的 aspect 画 | 外观统一但灯座数量仍受模型限制 |

**并存方案下必须做的一件事**：内核要能同时表达「原生信号」与「自研信号」。`Topology.SignalSite` 里加一个 `native` 标志，或者干脆让 `kind == BLOCK` + `lampCount == 1` 表示原生。

---

## 7. 与联锁的关系：信号是显示，不是逻辑

这是整套设计里最容易被搞反的一点。

真实铁路 | Create 原生 | 我们
---|---|---
信号机 = 联锁的输出 | 信号机 = 闭塞状态本身 | **信号机 = 联锁的输出**
「信号开放」由进路决定 | 「绿灯」由 group 占用决定 | 「绿灯」由内核的进路 + 授权决定

在我们的架构里：

```
内核（进路/联锁/授权）
    ├─→ Command.AspectCommand  → 信号机显示什么
    └─→ Command.TrainCommand   → 列车能走多远
```

**两条通道互相独立**。这意味着：

1. 信号机坏了/没装，列车照样能按授权跑（只是没显示）。
2. 信号显示与列车行为不一致时，**以授权为准**——这是 fail-safe 的方向。
3. 可以做「进路已排好但信号未开放」的中间态（现实中不会，但调试时有用）。

> 反过来说：**不要用信号机状态去反推列车行为**。那是原生 Create 的做法，也是它撑不起大线网的原因——把「显示」和「控制」耦合成一个枚举了。

### 7.1 ⚠ 对外发布信号状态时的语义陷阱

如果你把灯色喂给 PIS 网页 / 外部订阅 / CRN，**必须同时标注「这是显示，不代表授权」**。

原因：`SignalBoundary.cachedStates` 在整体接管后是**我们写进去的显示值**，它与 `TrainCommand.movementAuthority` 之间没有强制一致性。极端情况下会出现：

> 灯是绿的，但车停了（因为授权是 0）。

如果对外只说「信号 = 绿灯」，外部系统会推断「列车应该通过」，从而做出错误判断（比如站台屏显示「即将通过」但车根本没动）。

**正确做法**：对外数据结构里 `signalState` 与 `authority` 是两个独立字段，且明确 `authority` 才是权威。

> 这条对应的现成反例：CRN 的 `CreateSignalSnapshot` 直接读 `signal.cachedStates`（`CreateSignalSnapshot.java:51`），如果你与它共存，它的 UI 会显示我们写入的灯色，而它并不知道我们的授权状态。

---

## 8. SPI 的改动

原来的 `Command.Aspect` 是个四值枚举（`STOP / RESTRICTING / CAUTION / PROCEED`），对于四显示自动闭塞不够用。已改为独立的 **`Aspect` 记录**：

```java
public record Aspect(ResourceLocation id, List<Aspect.Lamp> lamps, double speedFraction)
```

| 灯 | 用途 |
|---|---|
| `RED` | 停车 |
| `YELLOW` | 注意 |
| `DOUBLE_YELLOW` | 双黄（预告两区间后关闭） |
| `FLASHING_YELLOW` | 黄闪 |
| `GREEN` | 通行 |
| `WHITE` | 调车 |
| `BLUE` | 禁止调车 |
| `LUNAR_WHITE` | 引导 |

标准预置在 `Aspect.Standard`：

```java
STOP            = {RED}              0.00
CAUTION         = {YELLOW}           0.55
ADVANCE_CAUTION = {YELLOW, DOUBLE_YELLOW}  0.40
PROCEED         = {GREEN}            1.00
SHUNT           = {WHITE}            0.30
CALL_ON         = {LUNAR_WHITE}      0.20
DARK            = {}                 0.00   ← 无效/未启用，宿主应视为「无此信号」
```

`Topology.SignalSite` 也扩展了：

```java
record SignalSite(UUID id, EdgeId edge, double distance,
                  boolean hasFirstFace, boolean hasSecondFace, boolean crossSignal,
                  SignalKind kind, int lampCount, String label)
```

新增 `canShow(aspect)`——灯座不够就显示不出来，内核可以据此降级：

```java
public boolean canShow(Aspect aspect) {
    if (aspect.isStop()) return true;          // 红永远能显示
    return lampCount >= aspect.lamps().size(); // 四显示机装不下三灯组合
}
```

---

## 9. 落地顺序

```
第 1 步  只做渲染（路线 A）：让原生三显示看起来正常。不改任何逻辑，零风险。
第 2 步  自研方块 + BE（继承 SignalBlockEntity）+ 挂 EdgePointType.SIGNAL。
         此时它还只是「外观不同的原生信号机」，行为完全由原生决定 —— 先验证图绑定成功。
第 3 步  接管 tickState，用内核的 aspect 覆写 cachedStates（docs/05 §3.4）。
         此时灯色归内核，但仍是单灯。
第 4 步  上 aspectMask + 多灯渲染。此时才真正是多显示信号机。
第 5 步  SignalKind 分类 + 联锁按类型区别对待。
第 6 步  可选：转换工具、路签、方向表示器。
```

每一第都能单独回退到上一步。

---

## 10. 一句话

> 原生信号机的灯色**与行车完全无关**，所以换它没有安全风险——
> 它现在只是一块「会亮红灯的牌子」。
> 真正决定列车走不走的是 `docs/05` 里的那套授权。
> 把牌子换掉，是这套架构里最自由、最不容易踩坑的部分。
