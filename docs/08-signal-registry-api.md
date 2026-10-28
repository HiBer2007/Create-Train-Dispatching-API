# 08 — 信号系统开放注册 API

> 代码：`src/main/java/top/hibermod/ctdapi/signal/`（8 个文件）
> 背景：`docs/07-custom-signals.md`（原生只有一个灯）

---

## 0. 为什么不做成枚举

我上一版把灯色和信号机类型写成了枚举——那是错的。

现实中的信号制度差异不在「红黄绿」这三个颜色上，而在**组合与语用**上：

| 制度 | 灯位 | 特殊之处 |
|---|---|---|
| 中国四显示自动闭塞 | 红 / 黄 / 黄 / 绿 | 双黄 = 预告两个区间后关闭 |
| 英国三显示 | 红 / 黄 / 绿 | 另有「黄闪」表示 restrictive |
| 日本 | 3 或 5 显示 | 紫色中继信号、`YY` 复式 |
| 调车 | 白 / 蓝 | **关闭时仍允许低速越过** |
| 预告 / 复述 | 黄 / 绿 | **不是停车点** |

每一条都是「注册一个机型」就能表达的，不该改代码。

---

## 1. 三层模型

```
LampColor   灯色      —— 「这是什么颜色」（红/黄/绿/白/蓝/月白/紫，可注册新色）
Aspect      语义      —— 「这意味着什么」（停车 / 注意 / 通行 / 调车 / 引导 / 灭灯）
SignalType  机型      —— 「这台机器怎么表达这些语义」（灯座布局 + 语义→灯位映射 + 联锁语义）
```

### 1.1 为什么 `Aspect` 不带灯位

这是最关键的一条设计决策。

「通行」在四显示机上亮**第 4 号灯座**，在三显示机上亮**第 3 号**。但它们是**同一个语义**。

如果 `Aspect` 里写死灯位，就会退化成「四显示的通行」和「三显示的通行」两个不同的 id —— 内核要认识每一种机型的每一个灯位组合，注册表失去意义。

所以：

```java
// 语义跨机型共享
public record Aspect(ResourceLocation id, Component displayName, double speedFraction, Category category)

// 灯位映射在机型上
public record SignalType(
    ResourceLocation id, Component displayName,
    List<LampSpec> lampLayout,                      // 这台机器物理上有几个灯、什么色
    Map<ResourceLocation, List<LampDisplay>> aspectMap,   // 语义 → 亮哪几个灯座、什么模式
    SignalSemantics semantics, int priority)
```

内核永远只说：

```java
out.aspect(signalId, side, Aspects.PROCEED);   // = ctdapi:proceed
```

宿主按该信号机的 `SignalType` 查表，得出要亮哪些灯。

### 1.2 `SignalSemantics` —— 真正决定行车的那部分

灯好看不重要，重要的是内核知道「这台信号开放了，车能不能走」。

```java
public record SignalSemantics(
    TargetKind target,              // ROUTE / BLOCK / PLATFORM / STATION / SHUNT_ZONE
    boolean grantsAuthority,        // 开放时是否授予行车授权
    boolean permissiveWhenClosed,   // 关闭时是否允许低速越过（调车、引导）
    boolean stoppingPoint,          // 是否是停车点
    double defaultSpeedFraction     // 开放时默认限速系数
)
```

三个**必须理解**的语义差异：

| 语义 | 关键点 | 搞错的后果 |
|---|---|---|
| `SHUNT` | `permissiveWhenClosed = true` | 调车作业会被卡死在关闭的调车信号前 |
| `DISTANT` | `stoppingPoint = false` | 预告机被当边界 → **列车提前停死在区间里** |
| `ROUTE_INDICATOR` | 不产生任何约束 | 表示器被当信号 → 莫名其妙的停车 |

`speedFraction` 也是语义的一部分：中国的调车 30%、引导 20%、注意 55%，这些是制度常量，不是渲染参数。

---

## 2. 注册

### 2.1 入口

```java
SignalRegistries.bootstrap();          // 宿主在 init 期调一次，装载内置内容

SignalRegistries.registerLamp(...);    // 新灯色
SignalRegistries.registerAspect(...);  // 新语义
SignalRegistries.registerType(...);    // 新机型

SignalRegistries.freeze();             // 宿主在加载完成后冻结并校验
```

### 2.2 例：中国四显示进站信号机

```java
Aspect callOn = SignalRegistries.registerAspect(new Aspect(
    ResourceLocation.fromNamespaceAndPath("myrail", "call_on_flashing_red"),
    Component.translatable("aspect.myrail.call_on"),
    0.20, Aspect.Category.CALL_ON));

SignalRegistries.registerType(SignalType.builder(
        ResourceLocation.fromNamespaceAndPath("myrail", "china_home_4"))
    .name(Component.literal("进站信号机"))
    .lamp(LampColor.RED).lamp(LampColor.YELLOW).lamp(LampColor.YELLOW).lamp(LampColor.GREEN)
    .expresses(SignalRegistries.Builtin.STOP.id(), 0)                    // 红灯
    .expresses(SignalRegistries.Builtin.CAUTION.id(), 1)                  // 黄灯
    .expresses(SignalRegistries.Builtin.ADVANCE_CAUTION.id(), 1, 2)       // 双黄
    .expresses(SignalRegistries.Builtin.PROCEED.id(), 3)                  // 绿灯
    .expresses(callOn.id(), LampDisplay.flashing(0))                      // 红闪 = 引导
    .semantics(SignalSemantics.HOME)
    .priority(10)
    .build());
```

### 2.3 例：英国三显示 + 黄闪

```java
SignalRegistries.registerType(SignalType.builder(rl("uk_3aspect"))
    .name(Component.literal("UK 3-aspect"))
    .lamp(LampColor.RED).lamp(LampColor.YELLOW).lamp(LampColor.GREEN)
    .expresses(Aspects.STOP.id(), 0)
    .expresses(Aspects.CAUTION.id(), 1)                                    // 黄
    .expresses(rl("restrictive"), LampDisplay.flashing(1))                 // 黄闪
    .expresses(Aspects.PROCEED.id(), 2)
    .semantics(SignalSemantics.BLOCK)
    .build());
```

> 注意：黄闪不是「另一个颜色」，而是同一个灯座的 `LampMode.FLASHING`。

### 2.4 例：调车信号机

```java
SignalRegistries.registerType(SignalType.builder(rl("shunt"))
    .lamp(LampColor.WHITE).lamp(LampColor.BLUE)
    .expresses(Aspects.SHUNT.id(), 0)       // 白 = 允许调车
    .expresses(Aspects.STOP.id(), 1)        // 蓝 = 禁止调车
    .semantics(SignalSemantics.SHUNT)       // ★ permissiveWhenClosed = true
    .build());
```

---

## 3. 校验与冻结

Create 的注册表是无冻结的裸 List / HashMap，出错方式是「静默覆盖」或「运行到一半才炸」。信号规格一旦不一致，表现是「灯亮错了」——极难排查。

所以这里**强制校验**：

| 检查 | 时机 | 失败方式 |
|---|---|---|
| id 重复 | `register*` 调用时 | 立即抛 `IllegalStateException`（不静默覆盖） |
| 灯位越界 | `SignalType` 构造时 | 立即抛，消息含机型 id 与越界灯位 |
| 机型引用未注册的 Aspect | `freeze()` | 汇总全部问题后抛 |
| 机型没有映射 STOP | `freeze()` | 同上（**没有停车显示的信号机是危险的**） |
| 冻结后再注册 | `register*` | 立即抛 |

`freeze()` 的错误消息会把**所有**问题列出来，而不是遇到第一个就停——注册期排查一次比运行期排查一百次划算。

---

## 4. 内核怎么用

内核**不需要认识任何具体制度**。它只做两件事：

```java
// 1. 问这台信号机是什么类型、能做什么
SignalSite site = ...;
SignalType type = site.type();               // 已含 semantics
if (!type.semantics().stoppingPoint()) {
    // 预告机：不产生授权边界，只影响减速
    return;
}

// 2. 让它显示某个语义
out.aspect(site.id(), side, Aspects.STOP.id());
```

判断「能不能走」只看 `Aspect.Category` 与 `speedFraction`：

```java
Aspect a = topo.aspect(currentAspectId);
if (a == null || a.isStop()) {
    authority = 0;
} else {
    authority = Math.min(authority, maxSpeed * a.speedFraction() * ...);
}
```

这样一套内核可以同时驱动中国四显示、英国三显示、日本五显示——**因为它们都被归结为「语义 + 限速」**。

---

## 5. 渲染

### 5.1 默认：注册即用

宿主提供一个**通用渲染器**：按 `SignalType.lampLayout()` 的灯座数量，在世界里排一列发光立方体，颜色取自 `LampColor.rgb()`，模式取自 `LampMode`。

→ **注册一个机型就能看到效果，不需要任何美术资源**。这对第三方扩展者极其友好。

### 5.2 可选：自定义外观

`SignalVisuals`（客户端）允许按机型注册自定义渲染：

```java
SignalVisuals.register(typeId, (be, masks, poseStack, buffer, light) -> { ... });
```

其中 `masks` 是 `Map<Integer, LampMode>`——已经被宿主解析好的「哪些灯座该亮、怎么亮」。

> 这样第三方可以把渲染做得很好看，但**不做也能用**。

### 5.3 客户端同步

方块实体只同步**语义 id**，不同步灯位：

```java
// 服务端 → 客户端
tag.putString("Aspect", aspectId.toString());
tag.putString("Type", typeId.toString());     // 机型可能在放置时被玩家改
```

客户端拿到后，用**两端一致的注册表**解析出灯位。这样即使以后改了某个机型的灯位映射，老存档也会自动跟着变——**存档里存的是语义，不是像素**。

---

## 6. 存档与网络契约

| 数据 | 存什么 | 稳定性要求 |
|---|---|---|
| 信号机方块 | `typeId`（ResourceLocation 字符串） | **一旦发布不可改名** |
| 当前显示 | `aspectId` | 同上（但可以重新映射，见上） |
| 灯位映射 | **不存** | 由机型定义，随 mod 版本演进 |

未知 `typeId` → `SignalRegistries.typeOrFallback(id)` 回退到三显示区间机，并写一次 WARN。**旧存档永远能读**。

---

## 7. 与 Create 原生信号的桥接

原生 `SignalBlock` 仍然存在。桥接方式：

```java
// 原生 State → 我们的语义
static ResourceLocation bridge(SignalState nativeState) {
    return switch (nativeState) {
        case RED     -> Aspects.STOP;
        case YELLOW  -> Aspects.CAUTION;
        case GREEN   -> Aspects.PROCEED;
        case INVALID -> Aspects.DARK;
    };
}
```

反过来，我们的语义 → 原生 `SignalState`（用于 `cachedStates` 覆写）：

```java
static SignalState toNative(Aspect a) {
    return switch (a.category()) {
        case STOP, DARK -> SignalState.RED;
        case CAUTION, ADVANCE_CAUTION, SHUNT, CALL_ON -> SignalState.YELLOW;
        case PROCEED -> SignalState.GREEN;
    };
}
```

> **注意**：原生渲染只判 `isRedLight`（`docs/07` §0），所以 `YELLOW` 和 `GREEN` 在原生方块上都表现为「灭」。桥接的价值在于让 **CRN 等第三方**能读到合理的 `cachedStates`，而不是让原生方块好看。

---

## 8. 内置内容清单

`SignalRegistries.bootstrap()` 注册：

**灯色**：`red` `yellow` `green` `white` `lunar_white` `blue` `purple`

**语义**：
| id | 类别 | 限速 |
|---|---|---|
| `ctdapi:stop` | STOP | 0.00 |
| `ctdapi:caution` | CAUTION | 0.55 |
| `ctdapi:advance_caution` | ADVANCE_CAUTION | 0.40 |
| `ctdapi:proceed` | PROCEED | 1.00 |
| `ctdapi:shunt` | SHUNT | 0.30 |
| `ctdapi:call_on` | CALL_ON | 0.20 |
| `ctdapi:dark` | DARK | 0.00 |

**机型**：
| id | 灯位 | 语义 |
|---|---|---|
| `ctdapi:block_3` | 红黄绿 | 区间通过（**回退机型**） |
| `ctdapi:block_4` | 红黄黄绿 | 区间通过，支持双黄 |
| `ctdapi:home_4` | 红黄黄绿 | 进站，支持红闪引导 |
| `ctdapi:starting_4` | 红黄黄绿 | 出站 |
| `ctdapi:shunt_2` | 白蓝 | 调车（关闭可越过） |
| `ctdapi:distant` | 黄绿 | 预告（**非停车点**） |
| `ctdapi:route_indicator` | 白 | 进路表示器 |

这些只是**起点**。任何铁路制度都可以在此基础上注册自己的机型，不需要改动本 mod。

---

## 9. 一句话

> **语义归内核，灯位归机型，颜色归注册表。**
> 内核永远只说「通行 / 注意 / 停车」，剩下的事交给注册表——
> 于是同一套调度逻辑可以驱动任何国家的信号系统，而第三方扩展只需要调一次 `registerType`。
