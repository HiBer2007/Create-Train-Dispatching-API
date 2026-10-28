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

## 3. 电气化与非电气化

### 3.1 别把它做成一个布尔值

「电气化 vs 非电气化」其实是**两个独立事实**：

| 事实 | 属于谁 | 字段 |
|---|---|---|
| 这列车靠什么产生动力 | 车 | `TractionType.needsExternalSupply` / `needsFuel` |
| 这段线路现在有没有电 | 线路 | `PowerState.externalSupplyLive` |

把二者合成一个 `isElectrified` 布尔值，混合动力车、无电区段、受电弓升降就都没法表达。

```java
public record TractionType(ResourceLocation id, Component displayName,
                           boolean needsExternalSupply,   // 必须是电力
                           boolean needsFuel,             // 必须自带燃料
                           boolean canUseExternalSupply)  // 可选受电（混合动力）
```

内置：`electric`（需供电）、`diesel`（需燃料）、`steam`（需燃料）、`hybrid`（两者皆可）。

### 3.2 一列车的能量状态

```java
public record PowerState(
    ResourceLocation tractionTypeId,
    boolean externalSupplyLive,     // 当前所在位置有电（受电弓升起 / 第三轨带电）
    double fuel, double fuelCapacity, ResourceLocation fuelId,
    double energy, double energyCapacity,
    boolean pantographRaised
) {
    boolean canTraction();          // ★ ATO 每次决策前先问这个
    boolean needsRefill(double threshold);
    double estimatedRange(double energyPerBlock);
}
```

### 3.3 ATO 必须处理「跑不动了」

`canTraction() == false` 时，牵引再大也没用。正确顺序是：

```java
if (!ctx.canTraction()) {
    return Decision.stop("no traction power");
}
if (ctx.power().needsRefill(0.15) && approachingDepot) {
    return new Decision(0.4, 0, speed, DoorAction.NONE, false,
        new ServiceRequest(rl("ctdapi", "refuel"), null),
        Component.literal("low fuel, requesting refill"));
}
```

---

## 4. 燃料

```java
public record FuelType(ResourceLocation id, Component displayName, double energyPerUnit) {}
```

`energyPerUnit` 用来把燃料折算成里程，让 ATO 能提前决定「这趟要不要先去加油」：

```java
double range = ctx.power().estimatedRange(0.02);   // 每格消耗 0.02 单位
if (range < ctx.authority()) return Decision.stop("insufficient fuel for the route");
```

燃料与电量**分开记**：内燃车用 `fuel`，电力车用 `energy`，混合动力两者都有。
合成一个「能量」字段会在换挂、补燃料、升弓降弓时算错。

---

## 5. 联控：地面设备与补给设备

### 5.1 补给设施就是普通地设备

不需要新机制，用能力标签即可：

| 标签 | 含义 |
|---|---|
| `fuel-port` | 燃料加注口 |
| `charger` | 充电 / 供电轨段 |
| `water-port` | 上水口 |
| `pantograph` | 受电弓（车载） |

```java
Devices.register(new Devices.DeviceType(
    rl("mymod", "diesel_pump"),
    Component.literal("柴油加注柱"),
    Devices.Kind.GROUND,
    Set.of(Devices.Capabilities.FUEL_PORT),
    true));                                   // 可命令
```

### 5.2 两条联控指令

```java
// 补给：ATO 发起，宿主转交设备
out.service(new Command.ServiceCommand(trainId, deviceId /* 可 null */,
    rl("ctdapi", "refuel"), params, holder));

// 供电：受电弓与牵引通断
out.power(new Command.PowerCommand(trainId, Command.PowerAction.RAISE_PANTOGRAPH));
```

**为什么受电弓要单列一条指令**：它有物理延迟与区间约束（不能带弓进入无电区）。
塞进 `TrainCommand` 会和速度指令混在一起，既没法单独排队，也没法单独失败。

### 5.3 宿主侧的联控顺序

```
ATO: 电量低 → ServiceRequest(charge)
  ↓
宿主: 找附近 charger 设备 → 调度把车引导到该设备 → 停车
  ↓
宿主: out.service(ServiceCommand) → 设备实现执行
  ↓
宿主: 更新 Telemetry.TrainState.power → 下一帧 ATO 看到电量回升
```

注意：**ATO 不负责把车开到充电桩**。它只发请求；引导是调度引擎的进路问题。

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
| 牵引方式 | `Traction.registerTraction` | 重复即抛 |
| 速度等级 | `Traction.registerSpeedClass` | 重复即抛；`maxSpeed > 0` |
| 燃料 | `Traction.registerFuel` | 重复即抛 |
| 供电制式 | `Traction.registerPowerSupply` | 重复即抛 |
| ATO | `AtoEngines.register` | freeze 时校验制度存在且唯一 |
| 交路 | `Service.register` | freeze 时校验制度与速度等级存在 |

`ApiCatalog.freezeAll()` 的冻结顺序：

```
信号 → ATO → 牵引 → 交路 → 设备 → HUD → PIS
```

`ApiCatalog.entries()` 新增类别：`ATO_ENGINE` / `TRACTION_TYPE` / `SPEED_CLASS` / `FUEL` / `POWER_SUPPLY`。

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

        // ⑦ 低于余量就去补给
        if (in.power().needsRefill(0.15)) {
            return new Decision(0.3, 0, target, DoorAction.NONE, false,
                new ServiceRequest(rl("ctdapi", "refuel"), null),
                Component.literal("low fuel"));
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
