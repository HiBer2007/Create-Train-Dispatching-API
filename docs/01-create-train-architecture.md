# 01 — Create 列车系统架构总览

> 基线：Create `mc1.21.1/dev` @ `7ff2760`（6.0.11）
> 本文是**俯瞰图**，用于建立心智模型。细节与证据见 `docs/research/*.md`，接口清单见 `docs/02-extension-points.md`。

---

## 1. 对象图：谁持有谁

```
Create.RAILWAYS  (服务端单例；Create.java:101；客户端对应 CreateClient.RAILWAYS:65)
 └─ GlobalRailwayManager
     ├─ Map<UUID, Train>              trains           ← 全部列车
     ├─ Map<UUID, TrackGraph>         trackNetworks    ← 全部连通轨道网
     └─ Map<UUID, SignalEdgeGroup>    signalEdgeGroups ← ★ 信号占用/预定的唯一真相源

Train (Train.java:86)
 ├─ TrackGraph graph                    :110  列车在哪张轨道网（null = 未上轨）
 ├─ Navigation navigation               :111
 │   ├─ GlobalStation destination        Navigation.java:53
 │   ├─ List<Couple<TrackNode>> currentPath        :58   ← 实际行车路径（NBT 持久化）
 │   ├─ TravellingPoint signalScout                :60   ← 前向"探针"，不移动真车
 │   ├─ Pair<UUID,Boolean> waitingForSignal        :61
 │   ├─ double distanceToSignal                    :63
 │   └─ int ticksWaitingForSignal                  :64
 ├─ ScheduleRuntime runtime             :112  时刻表状态机
 ├─ TrainStatus status                  :116  UI 消息队列（不是状态机）
 ├─ double speed                        :99   ★ 格/tick，带符号
 ├─ double targetSpeed                  :100
 ├─ double throttle                     :104
 ├─ boolean manualTick / manualSteer    :120-121
 ├─ Map<UUID,UUID> occupiedSignalBlocks :131  已进入的分区 → 信号 id
 ├─ Set<UUID> reservedSignalBlocks      :132  已预定未进入
 └─ List<Carriage> carriages            :127
      └─ CarriageBogey (Couple<TravellingPoint>)   CarriageBogey.java:55
           └─ TravellingPoint {node1,node2,edge,position,blocked,upsideDown}
```

**关键认知**：列车的「位置」**不是一个坐标**，而是 `2 × (车厢数 × 转向架数)` 个 `TravellingPoint`，每个都带 `(edge, position)`。`Carriage.travel`（`Carriage.java:148-223`）让这些点互相跟随并施加「张力（stress）」修正。

---

## 2. 单位与常量（最容易读错的地方）

| 量 | 单位 | 出处 |
|---|---|---|
| `Train.speed` / `targetSpeed` | **格/tick**（不是 m/s） | `maxSpeed() = config/20`（`Train.java:1143-1146`） |
| `Train.acceleration()` | **格/tick²** | `config/400`（`:1153-1156`） |
| `distanceToDestination` / `distanceToSignal` | 格 | `Navigation.java:492-494` |
| `brakingDistance` | 格 | `v²/(2a)`（`:101`） |
| 「超扫量」`0.25` | 格 | `:238` |
| 阈值 `1/32`、`1/16` | 格 | `:126, :241, :256` |
| `TrackEdge.getLength()` | 格；跨维度边恒 0 | `TrackEdge.java:81-86` |
| `TrackEdge.canTravelTo` | 方向点积 > 7/8（内角 ≈29°）才允许换边 | `TrackEdge.java:74-79` |

> **换算提示**：20 tick = 1 秒，所以 `speed = 0.2` 格/tick 约等于 4 格/秒。做 UI 显示时请注意这个 20 倍关系。

---

## 3. 生命周期

### 3.1 服务端加载

```
LevelEvent.Load                                    CommonEvents.java:192-199
 └─ Create.RAILWAYS.levelLoaded(level)
     └─ RailwaySavedData.load(server)              GlobalRailwayManager.java:91-99
         ├─ trains            = savedData.getTrains()
         ├─ trackNetworks     = savedData.getTrackNetworks()
         └─ signalEdgeGroups  = savedData.getSignalBlocks()
```

`loadTrackData` 有 `savedData != null` 短路（`:92-93`），只在首次加载时执行。

### 3.2 列车重新上轨

世界加载后 `Train.graph` 是 null。`Train.earlyTick`（`:193-200`）：

```java
if (graph == null && !migratingPoints.isEmpty())
    reattachToTracks(level);
if (graph == null) {
    addToSignalGroups(occupiedSignalBlocks.keySet());
    return;                                   // ← 不 tick 物理
}
```

### 3.3 存档保存

`markTracksDirty()`（`GlobalRailwayManager.java:111-114`）→ `RailwaySavedData.setDirty()`。
`Train.tick` 第一行就调它（`Train.java:290`）——**列车只要 tick 就标脏**，所以不要指望「没变化就不写盘」。

### 3.4 客户端

客户端的 `Train` 由 `AddTrainPacket` 经私有构造（`Train.java:156-158`）创建，`graph = null`，序列化字段只有：
`id / owner / carriages / carriageSpacing / doubleEnded / name / icon / mapColorIndex`。
**没有 speed、没有 targetSpeed、没有 throttle、没有 navigation。**

→ 客户端 `Navigation` 永远是空壳：`destination == null` → `isActive() == false` → `tick()` 第一行 return（`Navigation.java:74-75`）。

---

## 4. 客户端-服务端分工（决定你能做什么）

| 数据 | 同步方式 | 频率 | 证据 |
|---|---|---|---|
| `throttle` / `speed` / `fuelTicks` | `TrainHUDUpdatePacket`（S→C） | **每 5 tick 且仅当有人操作驾驶台** | `CarriageContraptionEntity.java:570-573` |
| `throttle` 修改 | `TrainHUDUpdatePacket.Serverbound` | 玩家拖 HUD 时 | `TrainHUDUpdatePacket.java:87-112` |
| 列车增删 | `AddTrainPacket` / `RemoveTrainPacket` | 事件驱动 | `AddTrainPacket.java:14` |
| 车厢位姿 | 实体同步（`CarriageSyncData`，按车错峰） | 每 `updateInterval` tick | `Train.java:1081-1083` |
| 轨道图 / 分区 | `TrackGraphSyncPacket` 系列 | 图变更时 | `TrackGraphSyncPacket.java` |
| **路径 / 目的地 / 灯色** | **不同步** | — | 见 `docs/research/signal-system.md` §10.3 |

**四条硬结论**

1. **`Navigation` 只在服务端跑**。证据链：`navigation.tick` 唯一调用者 `Train.java:309` ← `Train.tick` 唯一调用者 `GlobalRailwayManager.java:232-234` ← `GlobalRailwayManager.tick` 唯一调用者 `CommonEvents.java:142`（且 `:135-136` 排除客户端）。客户端 `GlobalRailwayManager.clientTick`（`:275-279`）**只做 F3 调试可视化**。
2. **零客户端预测**。想在客户端画「预测轨迹 / 虚拟信号」，必须自己发包；`currentPath` 在客户端是空的。
3. **`TrainHUDUpdatePacket` 的 speed 不是权威值**（卡住时发的是 `speedBeforeStall`，`TrainHUDUpdatePacket.java:82-84`）。
4. **没有「列车 tick」事件**。`api/event/` 只有 `BlockEntityBehaviourEvent`、`PipeCollisionEvent`、`TrackGraphMergeEvent`。→ **介入驾驶必须 mixin**。

---

## 5. 端到端叙事：一列车从 A 站开到 B 站

```
① 玩家在站 A 放时刻表，指令 = "去 B 站"
   └─ ScheduleRuntime.startCurrentInstruction(level)          ScheduleRuntime.java:192
        └─ instruction.createPath(...)  →  Navigation.findPathTo(B)   Navigation.java:442/448
             └─ search(...)            A* + 信号/车站惩罚       :551
                  → DiscoveredPath { distance, cost, path, destination }

② runtime 把路径装进 navigation
   └─ Navigation.startNavigation(pathTo)                       Navigation.java:386
        ├─ currentPath = pathTo.path
        ├─ destinationBehindTrain = pathTo.distance < 0
        ├─ train.reservedSignalBlocks.clear()
        └─ 司机校验（hasForwardConductor / hasBackwardConductor）

③ 每 tick：Train.tick                                            Train.java:289
   ├─ runtime.tick          → 若在途则只累加 ticksInTransit，不动     ScheduleRuntime.java:104-114
   ├─ navigation.tick       → ★ 算这一帧该多快                       Navigation.java:73
   │    ├─ 若 currentStation != null：红灯未放行就不出站（:241-247）
   │    ├─ signalScout 前向扫描：找最近的红灯 → waitingForSignal/distanceToSignal（:143-224）
   │    ├─ 目标速度 = f(剩余距离, 刹车距离, 弯道, throttle)            （:265-288）
   │    └─ train.targetSpeed = ...; train.approachTargetSpeed(1)      （:288-289）
   ├─ tickPassiveSlowdown   → 无目的地时摩擦减速 + 复位 manualTick    （:593-602）
   ├─ 逐车厢 carriage.travel(level, graph, speed + stress, ...)       （:423）
   │    └─ TravellingPoint.travel(..., frontSignalListener)           TravellingPoint.java:198
   │         └─ 穿点时回调：
   │              SignalBoundary → 若是我在等的红灯：speed = 0，截停   Train.java:484-489
   │                             否则：登记占用 occupy(groupId)        :501-502
   │              GlobalStation  → arriveAt()                           :465-475
   │              TrackObserver  → 记录观察器                            :477-480
   └─ updateNavigationTarget → 每 50/100 格触发重寻路                  （:546-585）

④ 到站
   Train.arriveAt(station)                                          Train.java:953-959
   ├─ setCurrentStation(station)                                    :954
   ├─ reservedSignalBlocks.clear()                                  :955
   ├─ runtime.destinationReached()   → IN_TRANSIT → POST_TRANSIT     ScheduleRuntime.java:71-95
   └─ station.runMailTransfer()                                      :957

⑤ 驻站等待条件满足 → runtime 推进到下一条指令（POST_TRANSIT → PRE_TRANSIT）
```

---

## 6. 三条数据流（记住这三条就够了）

### 6.1 信号流（谁点亮灯）

```
SignalBlock.POWERED ─► SignalBlockEntity.tick ─► SignalBoundary.tickState ─► cachedStates
                                                        ▲                        │
                          SignalEdgeGroup.isOccupiedUnless()                  getStateFor()
                          （列车占用 + 本帧预定）                                  │
                                                                          SignalBlockEntity.state
                                                                                 │
                                                                          客户端渲染
```

### 6.2 占用流（谁挡住谁）

```
Train.earlyTick ─► addToSignalGroups(occupiedSignalBlocks)   ─┐
                 └► addToSignalGroups(reservedSignalBlocks)   ─┤
                                                              ▼
                                    SignalEdgeGroup.trains（本帧重建）
                                                              │
Train.frontSignalListener ─► occupy(groupId, signalId) ───────┤
                                                              │
                                    SignalEdgeGroup.reserved  │
                                                              ▼
                                          isOccupiedUnless(...) → 红灯 / 寻路惩罚
```

### 6.3 速度流（谁让它动）

```
想走多远（path / waitingForSignal / destination）
    │
    ▼
Navigation: targetDistance = min(到目的地, 到红灯)
    │
    ▼
targetSpeed = targetDistance > brakingDistance ? topSpeed : 0
    │            （topSpeed = maxSpeed() × throttle，且受 maxTurnSpeed 约束）
    ▼
Train.approachTargetSpeed：speed 以 acceleration() 为步长逼近 targetSpeed
    │
    ▼
Train.tick: distance = speed → 逐车厢 travel → TravellingPoint.position 推进
```

---

## 7. 与其它子系统的边界

| 子系统 | 关系 | 关键类 |
|---|---|---|
| Contraption（动态结构） | 每节车厢是一个 contraption 实体 | `CarriageContraptionEntity`、`CarriageContraption` |
| 传送门 | 跨维度轨道由 portal provider 提供 | `api/contraption/train/PortalTrackProvider`、`AllPortalTracks` |
| 观察器 | 列车上另有独立的观察器触发机制 | `TrackObserver`、`Train.occupiedObservers` |
| 邮件 / 包裹 | 到站时由站台触发转运 | `GlobalStation.runMailTransfer`、`GlobalPackagePort` |
| 显示板 | 站名/时刻表/列车状态 | `GlobalTrainDisplayData`、`FlapDisplayBlockEntity` |
| 计算机外设 | CC:Tweaked 的 signal / station / observer 外设 | `compat/computercraft/**`、`SignalPeripheral` |
| 地图 mod | Xaero / JourneyMap / FTBChunks 叠加 | `compat/trainmap/**` |

---

## 8. 一句话总结

> Create 的列车系统 = **一张图（TrackGraph）+ 一堆边点（EdgePoint）+ 一个全局每帧循环（GlobalRailwayManager.tick）**。
> 「信号」是边点的一种、「自动控制」是 `Navigation.tick` 里的一段算术、「调度」是 `ScheduleRuntime` 的三态机。
> 三者之间**只通过 `SignalEdgeGroup` 的占用状态耦合**。想换掉它们，就换掉这三处。
