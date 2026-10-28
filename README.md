# Create Train Dispatching API

为 Create 的铁路系统提供一套**可插拔的信号与调度契约**。

- 目标版本：**Minecraft 1.21.1 / NeoForge 21.1.219**
- 许可证：**LGPL-3.0-or-later**
- Maven：`top.hibermod:create-train-dispatching-api`
- 状态：**已通过真实 NeoForge 环境构建**（Gradle 8.8 / JDK 21，见 §构建）

---

## 这个仓库是什么 / 不是什么

| ✅ 是 | ❌ 不是 |
|---|---|
| 调度**引擎**的接口契约 | 调度引擎本身 |
| 宿主模组的接口契约 | 宿主模组本身 |
| 信号制度的**开放注册表** | 任何信号机方块或渲染 |
| 设备 / HUD / PIS 的**数据契约** | 任何 HUD 绘制、翻牌屏渲染 |
| 进程外引擎的**接入层定义** | 任何网络协议实现 |

**引擎和宿主都由其他模组提供。** 本仓库一行游戏行为都没有：

- 不做 Mixin，不注入任何 Create 类
- 不注册任何方块、物品、实体、数据包、注册表项
- 不做渲染，不含客户端类（`net.minecraft.client.*` 一个引用都没有）
- **不提供任何纹理、模型、语言文件、声音或数据**——`src/main/resources` 里只有 `META-INF/neoforge.mods.toml`

这条边界由构建任务 `checkApiBoundary` **强制**：一旦有人在 `src/main/resources` 下放 `assets/`、`data/`，
或在源码里引用 `net.minecraft.client.*` / `com.mojang.blaze3d.*`，构建直接失败。

```bash
./gradlew checkApiBoundary
```

**依赖只到 Minecraft 层**，全部外部 import 就这 6 个：

```
net.minecraft.nbt.CompoundTag
net.minecraft.network.chat.Component
net.minecraft.resources.ResourceKey
net.minecraft.resources.ResourceLocation
net.minecraft.world.level.Level
org.jetbrains.annotations.Nullable
```

> 刻意**不依赖 Create**——引擎与宿主的模组自己依赖 Create，不会把版本约束传染给 API。

---

## 三层职责

```
① 引擎模组（其他模组）      时刻表 · 调度 · 进路 · 闭塞 · 延误传播
         ↓ 实现 DispatchKernel
② 宿主模组（其他模组）      Mixin · 拓扑镜像 · 遥测 · 指令执行 · 存档
         ↓ 实现 Topology / Telemetry / DispatchFrame
③ Create                  轨道图 · 列车物理 · 信号机方块
         ↓
   ★ 本仓库提供 ①↔② 之间的契约，以及 ②↔③ 所需的值对象
```

---

## 为什么需要它

Create 的列车系统是一套**有轨电车**模型：一辆车、一条路、前方有信号就停。

它没有道岔对象、没有进路、没有联锁、没有时刻表校验、没有对外数据面。信号机在视觉上只有一个灯——亮红或灭（`SignalRenderer` 只判 `isRedLight`，逻辑上的黄/绿从未被渲染）。

要支撑真实规模的线网与 PIS，这些东西必须补上。而补的方式不该是「再写死一套」——所以本 API 只定义**契约**：

| 层 | 内容 |
|---|---|
| `DispatchKernel` | 调度内核：每帧读状态、写指令。**「怎么调度」全在这里** |
| `FrameContext` / `DispatchFrame` | 内核的读写面 |
| `Topology` / `Telemetry` | 只读的拓扑与列车状态 |
| `Command` | 写回指令（距离授权、限速、进路、道岔锁闭、信号显示） |
| `signal.*` | 信号系统的**开放注册表**（制度 / 机型 / 语义 / 灯色） |
| `device.*` | 地面与车载设备（只描述，不实现） |
| `timetable.*` | 交路：绑定信号制度 |
| `hud.*` / `pis.*` | **纯数据**面板与显示屏内容 |
| `remote.*` | 进程外引擎的传输/编解码契约 |

---

## 三条设计原则

### 1. 不给「加价」，只给「授权」

Create 的闭塞是「加价绕路」式的软约束（其它 mod 也走这条路）。本 API 给的是**显式的距离授权**：

```java
record TrainCommand(UUID trainId, Direction direction, double movementAuthority,
                    double speedCap, boolean hold, RouteRequest route)
```

授权是硬约束，加价是建议。两者不可互换——移动闭塞必须是前者。

### 2. 指令只能收紧，不能放宽

实现方在应用指令前取 `min(原生意图, 内核指令)`。内核逻辑出错时最坏退化为原版行为，永远不会越过安全边界。

### 3. API 不依赖 Create

本仓库的所有类型只用到 Minecraft / NeoForge 的公开类。第三方内核**不必随 Create 版本更新而重编译**，这也是它作为独立 API 存在的意义。

---

## 信号系统：语义与灯位分离

不同铁路的信号制度差异极大（中国四显示、英国三显示、日本五显示、调车白/蓝……）。所以不做成枚举，而是**开放注册**：

```java
// 语义跨机型共享
Aspect  = { id, 限速, 类别 }            // STOP / CAUTION / PROCEED / SHUNT ...

// 机型决定「怎么显示这些语义」
SignalType = { id, 灯座布局, 语义→灯位映射, 联锁语义 }
```

「通行」在四显示机上亮第 4 号灯座、在三显示机上亮第 3 号，**但内核说的是同一个 `ctdapi:proceed`**。

注册一个机型即刻可用：

```java
SignalRegistries.registerType(SignalType.builder(rl("myrail", "home_4"))
    .lamp(LampColor.RED).lamp(LampColor.YELLOW)
    .lamp(LampColor.YELLOW).lamp(LampColor.GREEN)
    .expresses(Aspects.STOP.id(), 0)
    .expresses(Aspects.CAUTION.id(), 1)
    .expresses(Aspects.ADVANCE_CAUTION.id(), 1, 2)      // 双黄
    .expresses(Aspects.PROCEED.id(), 3)
    .expresses(Aspects.CALL_ON.id(), LampDisplay.flashing(0))
    .semantics(SignalSemantics.HOME)
    .build());
```

联锁语义是真正决定行车的那部分：

```java
SignalSemantics { target, grantsAuthority, permissiveWhenClosed, stoppingPoint, defaultSpeedFraction }
```

其中 `stoppingPoint = false` 表示预告/复述信号机——**它不产生授权边界**。把它当边界会让列车提前停死在区间里。

---

## 目录

```
src/main/java/top/hibermod/ctdapi/
├── DispatchKernel.java      内核接口（唯一的决策入口）
├── DispatchKernels.java     内核注册表（查找表，不是 NeoForge registry）
├── KernelEnv.java           生命周期服务（状态持久化 / 日志 / 配置）
├── FrameContext.java        每帧输入
├── DispatchFrame.java       每帧输出
├── Topology.java            只读拓扑
├── Telemetry.java           列车实时状态
├── Command.java             写回指令
├── RailwayEvent.java        事件
├── Rail.java                稳定标识（NodeId / EdgeId / Span）
├── ApiCatalog.java          统一索引（有哪些、该用哪个）
├── signal/                  制度 / 机型 / 语义 / 灯色
├── device/                  地面与车载设备
├── timetable/               交路（绑定信号制度）
├── hud/                     HUD 面板（纯数据）
├── pis/                     PIS 显示屏（纯数据）
└── remote/                  进程外引擎的传输 / 编解码契约

src/main/resources/
└── META-INF/neoforge.mods.toml    ← 唯一的资源文件
```

`docs/` 下是设计文档，其中：

- `06-dispatch-spi.md` — 内核接口说明与需求映射
- `07-custom-signals.md` — 为什么原生信号机只有一个灯，以及怎么换掉
- `08-signal-registry-api.md` — 信号注册 API（语义 / 机型 / 灯位）
- `09-compat-steam-n-rails.md` — 与汽鸣铁道（Steam 'n' Rails）的兼容性，含真实道岔实现剖析
- `10-api-reference.md` — **完整参考手册**（按 PIS / 信号 / 地面设备 / 车载设备 / HUD / 注册分类）

---

## 构建

```bash
./gradlew build
```

**已实测通过**（2026-10，Gradle 8.8 / JDK 21 / NeoForge 21.1.219 / MC 1.21.1，`BUILD SUCCESSFUL in 2m 22s`）：

```
build/libs/create-train-dispatching-api-0.1.0.jar           119.7 KB
build/libs/create-train-dispatching-api-0.1.0-sources.jar    52.5 KB
```

### 国内网络的两个坑

**① NeoForged maven 可能 TCP 不可达**

`maven.neoforged.net` 在部分网络下连握手都完不成（DNS 正常，443 超时）。ForgeCDN 有同构镜像，
路径完全一致，`build.gradle` 里已自动重写：

```
maven.neoforged.net/…  →  neoforged.forgecdn.net/…
```

重写挂在 `repositories.whenObjectAdded` 上——ModDevGradle 是**延迟注册**仓库的，
只在 plugins 块之后遍历一次会漏掉它。

**② Gradle 发行版走阿里云**

`gradle/wrapper/gradle-wrapper.properties` 指向 `mirrors.aliyun.com`，不用 `services.gradle.org`。

### 离线结构自检

拉不到 NeoForge 时，可以用桩做一次纯语法/类型检查（**不校验 Minecraft API 兼容性**）：

```bash
pwsh -File tools/verify-offline.ps1
```

---

## 校验

```bash
./gradlew checkApiBoundary   # 边界：无客户端类、无 assets/data
./gradlew build              # 编译 + 打包
```

---

## 许可

GNU Lesser General Public License v3.0 or later。完整文本见 [LICENSE](LICENSE)。

你可以把本 API 用于闭源模组；但如果你修改了本 API 本身的代码，修改部分必须以 LGPL 发布。
