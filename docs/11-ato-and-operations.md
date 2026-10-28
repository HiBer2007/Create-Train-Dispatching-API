# 11 — ATO、牵引与运营状态

> 配套代码：`src/main/java/top/hibermod/ctdapi/ato`、`traction`、`status`
> 前置：`docs/10-api-reference.md`（契约层总览）

---

## 0. 这一层解决什么

调度引擎回答「**能不能走、能走多远**」。
ATO 回答「**怎么走**」——给多少牵引、什么时候制动、在哪停、什么时候去补给。

这两件事不能混：同一个授权距离，四显示自动闭塞下和调车场调车信号下的操纵完全不同。
所以 ATO 是**独立注册、但与信号制度强绑定**的东西。

运营状态（封路、施工、应急）则是第三个维度：它不改变「怎么操纵」，
而是**改变前提**——路没了、线断了、要停车疏散。

第四件事是**能源**：电力、燃料、受电弓。它同样不是我们的模型——
由其他模组提供，我们只做**联动**（§3–§5）。

---

## 1. ATO 与信号引擎的配对

### 1.1 铁律

```
一个 ATO  ⟷  一套信号制度（SignalSystem）
```

```java
public interface AtoEngine {
    ResourceLocation id();
    ResourceLocation signalSystemId();     // ★ 必填，且唯一
    Decision decide(Context in);
}
```

`AtoEngines.freeze()` 会校验：

| 情况 | 结果 |
|---|---|
| ATO 指向未注册的信号制度 | **启动失败** |
| 同一制度注册了第二个 ATO | **启动失败**（"two ATOs"） |

### 1.2 信号引擎可以没有 ATO

这是常态，不是异常。没有 ATO 时：

- 信号引擎照常给授权、照常点灯；
- **自动驾驶不能启动**；
- 列车只能人工驾驶，或者按信号引擎给出的建议速度跑。

判定入口：

```java
AtoEngine.Availability a = AtoEngines.check(signalSystemId, train.signalSystems());
if (!a.available()) {
    // a.reason() = "signal system ctdapi:yard provides no ATO; autopilot unavailable"
}
```

### 1.3 一列车支持多套信号系统

「支持」不是车说了算，是**车载设备**说了算：

```java
Devices.register(new Devices.DeviceType(
    rl("mymod", "cab_signalling_cn"),
    Component.literal("中国制式车载信号"),
    Devices.Kind.ONBOARD,
    Set.of(Devices.Capabilities.SIGNAL_EQUIPMENT, Devices.Capabilities.ATP),
    false,
    Set.of(SignalRegistries.DEFAULT_SYSTEM_ID, rl("ctdapi", "yard"))));   // ★ 两套
```

宿主把该车所有车载设备的 `signalSystems()` 取并集，填进
`Telemetry.TrainState.signalSystems()`。于是：

```java
topology.service("mymod:g1").signalSystemId()      // → ctdapi:mainline_4
train.signalSystems()                              // → [ctdapi:mainline_4, ctdapi:yard]
AtoEngines.check(...)                              // → ok
```

**用哪套由进路所属交路决定**，不是车的全局属性。同一列车跑干线用干线制式，
进站场调车就切调车制式——切换点就是交路边界。

---

## 2. 速度分级

速度等级属于**车次**，不属于车，也不属于线路。

```java
public record SpeedClass(ResourceLocation id, Component displayName,
                         double maxSpeed,    // 格/tick
                         int rank) {}        // 数值越小越优先
```

`Service`（交路）里带一个：

```java
Service.builder(rl("mymod", "g1"))
    .code("G1")
    .signalSystem(SignalRegistries.DEFAULT_SYSTEM_ID)
    .speedClass(Traction.Builtin.HIGH_SPEED)
    .build();
```

内置：`shunt` 0.40 / `freight` 0.80 / `passenger` 1.20 / `high_speed` 1.40（格/tick）。

> 为什么是格/tick：Create 的 `speed` 就是每 tick 位移。换算成格/秒乘 20。
> 不要在这里引入 km/h——单位混用是这类系统最常见的 bug 来源。

`Service.freeze()` 会校验引用的等级存在。

---

## 3. 电气化 / 燃料 —— 这两件事**不属于本 API**

### 3.1 结论先行

电力、受电弓、第三轨、流体燃料，全部由**其他模组**提供。本 API 不建模它们，
只提供一个**联动接口**，让 ATO 能问出一句话：

> 「这列车现在还能不能给牵引？」

### 3.2 核对过的事实

| 模组 | 分支 / 版本 | 实际提供 | 不是什么 |
|---|---|---|---|
| **Create: Electro Energetics** | `1.21.1` / `1.21.1-1.2.0` | **真实电气系统**：电线、连接器、蓄电池、变压器、电压仿真，**列车受电弓**。mixin 了 `Train`，经 `ICEETrainExtension#getElectricTrainData()` 暴露 `ElectricTrainData`（`lastVoltage` / `accumulatorCharge` / `pantographs`） | — |
| **Create: Diesel Generators** | `1.21.1` | **流体燃料**（柴油/生物柴油/植物油，`CDGTags` 里是 `TagKey<Fluid>`）+ **旋转应力**（`ModularDieselEngineBlockEntity extends GeneratingKineticBlockEntity`） | ❌ 没有 `IEnergyStorage`、❌ 没有受电弓、❌ 没有接触网 |
| **Create: Pantographs & Wires** | `mc/1.21.1` | **纯装饰**接触网与受电弓 | ❌ 不产生、不传输能源 |
| **Steam 'n' Rails** | `1.21/dev` | **流体燃料**（油罐车） | — |

**四者的能源模型互不相同**：电压（V）、流体（mB）、应激（SU）、纯装饰。

这正是本 API 不建模电气化的理由——如果 API 里写死一套「供电制式」，
这四者里至少三个没法接。所以只保留一个问题：**这车现在还能不能给牵引。**

> 顺带一提 Electro Energetics 的电压门限来自它的 `CVoltages`：
> `trainMinVoltage = 1900`、`trainMaxVoltage = 5000`（默认值，服主可改）。
> 低于最小电压 = 无牵引——这是**它的**规则，不是我们的。

### 3.3 联动接口

```java
public interface EnergyProvider {
    ResourceLocation id();
    String modId();                                   // "createdieselgenerators"
    EnergySample sample(TrainState train);            // 这车现在还能不能跑
    default void apply(Command.EnergyCommand cmd) {}  // 可选：接受指令
}
```

```java
public record EnergySample(
    Status status,                       // UNKNOWN / AVAILABLE / LOW / DEPLETED / NOT_APPLICABLE
    double fraction,                     // 0..1 粗余量
    Map<ResourceLocation, Double> gauges, // ★ 开放键值：燃料量、电压、蒸汽压力…
    @Nullable Component summary
)
```

**`gauges` 是开放键值，不是一个燃料枚举。** 汽鸣铁道给的是流体，键就是流体 id：

```java
Map.of(ResourceLocation.parse("railways:diesel"), 320.0)
```

Create Diesel Generators 同理（`createadieselgenerators:diesel` 之类），
以后真有电力模组了，键可以是 `mymod:voltage`、`mymod:charge`。
**API 永远不需要知道这些键是什么意思**，它只负责搬运。

### 3.4 铁律：`UNKNOWN` ≠ `DEPLETED`

```java
if (!train.energy().canTraction()) { ... }
```

- 没有装任何联动模组 → `UNKNOWN` → `canTraction() == true` → **不干预**；
- 装了但适配器出错 → 抛异常会被吞掉 → 仍然是 `UNKNOWN` → 不干预；
- 真的没油/没电 → `DEPLETED` → `canTraction() == false`。

**把「不知道」当成「没电」，会让所有没装能源模组的存档里列车集体趴窝。**
这是这个接口最容易写错的一处。

### 3.5 多个来源会合并

混合动力车同时有电和油，两个 provider 各给一份采样：

```
合并规则：状态取最坏、余量取最小、指标取并集
```

「电还有一半但油见底」→ 正确判成 `LOW`。

### 3.6 写一个适配器：以 Electro Energetics 为例

```java
Interop.registerEnergyProvider(new EnergyProvider() {
    private static final ResourceLocation ID = rl("mymod", "electro_energetics");

    @Override public ResourceLocation id() { return ID; }
    @Override public String modId() { return Interop.Known.ELECTRO_ENERGETICS; }

    @Override
    public EnergySample sample(Telemetry.TrainState state) {
        Train train = Create.RAILWAYS.trains.get(state.id());
        if (train == null) return EnergySample.unknown();

        // 它 mixin 了 Train，所以可以直接转型
        ElectricTrainData data = ((ICEETrainExtension) train).getElectricTrainData();
        if (data == null) return EnergySample.notApplicable();

        // 有受电弓且电压够 = 能从接触网取流
        boolean pantographUp = data.pantographs.stream().anyMatch(p -> p.active);
        boolean live = pantographUp && data.lastVoltage >= Interop.Known.EE_TRAIN_MIN_VOLTAGE;

        // 蓄电池兜底（单位：库仑）
        double stored = data.accumulatorCharge;
        double capacity = data.accumulators * 360000.0;   // maxAccumulatorCharge 默认值
        double pct = capacity <= 0 ? 0 : Math.min(1, stored / capacity);

        Map<ResourceLocation, Double> gauges = Map.of(
            rl("electroenergetics", "voltage"), data.lastVoltage,     // V
            rl("electroenergetics", "charge"),  stored);              // C

        if (live) return new EnergySample(Status.AVAILABLE, 1.0, gauges, null);
        if (pct > 0) return new EnergySample(pct < 0.15 ? Status.LOW : Status.AVAILABLE,
                                             pct, gauges, null);
        return new EnergySample(Status.DEPLETED, 0, gauges,
                                Component.literal("无受电、无蓄电"));
    }
});
```

同样的形状能套到流体燃料上：

```java
@Override
public EnergySample sample(Telemetry.TrainState state) {
    double millibuckets = MyFluidAdapter.readTank(state.id(), RAILWAYS_DIESEL);
    if (millibuckets < 0) return EnergySample.notApplicable();   // 这车不归我管
    double pct = millibuckets / MyFluidAdapter.tankCapacity(state.id());
    return pct < 0.15
        ? EnergySample.low(pct, Component.literal("燃料偏低"))
        : EnergySample.available(pct);
}
```

**注意 `notApplicable()` 与 `UNKNOWN` 的区别**：前者是「这车不用我的能源」（电力车遇到燃料提供方），
后者是「我不知道」。两者都不参与合并——但排障时含义完全不同。

---

## 4. 燃料就是 `gauges` 里的一个键

没有 `FuelType` 注册表了。原因很直接：

- 汽鸣铁道的燃料是**流体**；
- Diesel Generators 的燃料也是**流体**（`TagKey<Fluid>`）；
- 将来可能有模组用**物品**当燃料。

API 定义一套「燃料种类」的注册表，等于要求这三种实现按同一个抽象来长。
所以只留 `Map<ResourceLocation, Double>`，键交给联动方。

如果确实需要「把燃料折算成续航里程」，那是 **ATO 自己的事**，写在你自己的配置里：

```java
// 在你自己的 ATO 实现里
double rangeKm = ctx.energy().gauges().getOrDefault(DIESEL, 0.0) * 1.8;
```

---

## 5. 联控：指令怎么送到联动方

两条通道，别混：

| 通道 | 用途 | 谁解释内容 |
|---|---|---|
| `Command.EnergyCommand` | 升弓、切供电、加注 | **联动方**（`EnergyProvider.apply`） |
| `Command.ServiceCommand` | 找地面设备补给（加油柱、充电桩） | **设备实现** |

```java
// 交给联动方
out.energy(new Command.EnergyCommand(trainId, rl("mymod","diesel_generators"), payload, holder));

// 交给地面补给设备
out.service(new Command.ServiceCommand(trainId, deviceId, rl("ctdapi","refuel"), params, holder));
```

两者的区别：**前者是车上的能源系统，后者是地面设施**。
受电弓是有物理延迟与区间约束的车载动作，属于前者；加油柱是站场设施，属于后者。

补给设施仍然走设备能力标签：`fuel-port` / `charger` / `water-port`。

> ATO 不负责把车开到加油柱。它只发请求，引导是调度引擎的进路问题。

---

## 6. 临时限制与应急

### 6.1 两者是不同的东西

| | `Restrictions` | `Emergency` |
|---|---|---|
| 语义 | 这段路暂时不好走 / 不能走 | 现在别按计划走了 |
| 谁受影响 | 进路计算（可绕行） | 直接压过调度 |
| 典型 | 施工、限速、单线、落石 | 火灾、脱轨、疏散、人工接管 |
| 时限 | 天窗点，通常有起止 | 直到显式解除 |

### 6.2 临时限制

```java
Restrictions.publish(Restrictions.closed(
    UUID.randomUUID(), edgeId, 120.0, 180.0,
    now, now + 1200,                     // 60 秒天窗
    Emergency.Reasons.MAINTENANCE,
    Component.literal("夜间换轨")));
```

| 种类 | 用途 |
|---|---|
| `CLOSED` | 完全封闭 |
| `SPEED_LIMIT` | 限速通过（带 `speedLimit`） |
| `SINGLE_TRACK` | 单线行车（需要交会） |
| `CAUTION` | 注意运行（无硬限速） |
| `WORK_ZONE` | 施工区 |

查询：

```java
Restrictions.active(now)
Restrictions.onEdge(edgeId, now)
Restrictions.isBlocked(edgeId, distance, now)
Restrictions.purge(now)            // 宿主定期调用，防止表无限增长
```

**调度引擎只读不写。** 它决定"怎么走"，不决定"哪里不能走"。

### 6.3 应急

```java
Emergency.stopAll(orderId, now, issuer, Component.literal("接触网起火"));
Emergency.manualTakeover(orderId, trainId, now, issuer, Component.literal("司机接管"));
```

| 动作 | 默认行为 |
|---|---|
| `STOP_ALL` | 范围内全部就地停车 |
| `EVACUATE` | 就近站台停车并开门 |
| `MANUAL_TAKEOVER` | 交出控制权，ATO 与调度停止干预 |
| `DEGRADED_RUN` | 降级继续运行（信号失效时） |
| `RESUME` | 解除 |

内核每帧先问：

```java
if (Emergency.forcesStop(train, now)) {
    out.hold(train.id(), "emergency stop");
    continue;
}
if (Emergency.forcesManual(train, now)) {
    continue;                      // 什么都不写 = 交还人工
}
```

`Emergency.mode(now)` 给出全局模式：`NORMAL` / `ALERT` / `EMERGENCY`，宿主用它决定 HUD 与 PIS 的显示。

**为什么要单独一张表**：应急必须可审计——谁在什么时候因为什么下的令。
混进调度引擎的私有状态里，事后没人说得清。

---

## 7. PIS 与车次信息的归属

### 7.1 分工

| 内容 | 谁提供 | 在哪 |
|---|---|---|
| 车次号、线路、经由、所属交路 | **API** | `timetable.Service` |
| 正点率、晚点预计、到站预测 | **信号系统的 PIS 引擎** | `pis.BoardEntry` 的 `scheduledTicks` / `etaTicks` / `delayTicks` |

也就是说：**谁算的预测，就标谁的名**。

```java
new Pis.BoardEntry(
    "G1", line, destination, via,
    scheduledTicks, etaTicks, delayTicks,
    platform, Pis.Status.DELAYED, note, Pis.Urgency.NORMAL,
    SignalRegistries.DEFAULT_SYSTEM_ID);      // ★ 这套预测来自哪个信号系统
```

一块站台屏上可以同时出现来自两套系统的车次（枢纽站很常见），
每条 entry 自己带来源，渲染方不需要猜。

### 7.2 车次信息只注册一次

`Service` 是「车次」的唯一权威来源。PIS 屏、HUD、外部接口都引用它的 `id` 或 `code`，
不要各自维护一份车次表——那必然对不上。

---

## 8. 注册总表（新增部分）

| 类别 | 注册方法 | 校验 |
|---|---|---|
| 牵引标签 | `Traction.registerTraction` | 重复即抛 |
| 速度等级 | `Traction.registerSpeedClass` | 重复即抛；`maxSpeed > 0` |
| 能源联动方 | `Interop.registerEnergyProvider` | 重复即抛 |
| 联动档案 | `Interop.describe` | 需要 modId |
| ATO | `AtoEngines.register` | freeze 时校验制度存在且唯一 |
| 交路 | `Service.register` | freeze 时校验制度与速度等级存在 |

**没有**燃料注册表、**没有**供电制式注册表——见 §3、§4。

`ApiCatalog.freezeAll()` 的冻结顺序：

```
信号 → ATO → 牵引 → 交路 → 设备 → HUD → PIS → 联动
```

`ApiCatalog.entries()` 新增类别：`ATO_ENGINE` / `TRACTION_TYPE` / `SPEED_CLASS` / `ENERGY_PROVIDER` / `INTEROP_TARGET`。

> `INTEROP_TARGET` 只打印「这个模组应该装、装没装」，不参与任何决策。
> 它的用处是启动时一眼看出「Ato 说没能源信息，原来是没装联动模组」。

---

## 9. 完整示例：一个最小 ATO

```java
public final class SimpleAto implements AtoEngine {

    private static final ResourceLocation ID = rl("mymod", "simple_ato");

    @Override public ResourceLocation id() { return ID; }
    @Override public ResourceLocation signalSystemId() {
        return SignalRegistries.DEFAULT_SYSTEM_ID;
    }

    @Override
    public Decision decide(Context in) {
        // ① 应急优先
        if (in.underEmergency()) return Decision.stop("emergency");

        // ② 没动力就别给牵引
        if (!in.canTraction()) return Decision.stop("no traction power");

        // ③ 前方封路
        if (in.blockedAhead()) return Decision.stop("track closed ahead");

        // ④ 授权为 0 就是停
        if (in.authority() <= 0) return Decision.stop("no movement authority");

        // ⑤ 速度上限 = min(等级, 信号限速, 临时限速, 授权允许)
        double limit = in.permittedSpeed();
        if (in.speedClass() != null) limit = Math.min(limit, in.speedClass().maxSpeed());
        limit = Math.min(limit, in.strictestLimit());

        double allowed = Math.sqrt(2 * in.train().acceleration() * in.authority());
        double target  = Math.min(limit, allowed);

        // ⑥ 停车点对位
        if (in.distanceToTarget() < 0.5) {
            DoorAction doors = in.atPlatform() && !in.doorsOpen() ? DoorAction.OPEN : DoorAction.NONE;
            return new Decision(0, 1, 0, doors, true, null, Component.literal("arrived"));
        }

        // ⑦ 余量偏低就去补给（能源状态来自联动方，未知时 needsRefill() 为 false）
        if (in.energy().needsRefill()) {
            return new Decision(0.3, 0, target, DoorAction.NONE, false,
                new ServiceRequest(rl("ctdapi", "refuel"), null),
                Component.literal("low energy"));
        }

        double dv = target - in.train().speed();
        if (dv >= 0) {
            return new Decision(Math.min(1, dv / 0.05), 0, target, DoorAction.NONE, false, null,
                Component.literal("accelerate"));
        }
        return new Decision(0, Math.min(1, -dv / 0.05), target, DoorAction.NONE, false, null,
            Component.literal("brake"));
    }
}
```

注册：

```java
AtoEngines.register(new SimpleAto());
```

---

## 10. 职责边界（重申）

| 谁 | 负责 | **不负责** |
|---|---|---|
| API（本仓库） | 契约：ATO 接口、牵引模型、限制表、应急表 | 任何操纵算法、任何渲染 |
| 信号引擎模组 | 授权、进路、闭塞、PIS 预测 | 牵引操纵 |
| ATO 引擎模组 | 牵引/制动/开关门/补给请求 | 授权（不得越权） |
| 宿主模组 | 采样、执行、联控设备、存档 | 决策 |

**ATO 永远不得把车开出授权范围。** 授权是信号引擎的事，ATO 只在其内优化。
