package top.hibermod.ctdapi.tools;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import top.hibermod.ctdapi.Rail;
import top.hibermod.ctdapi.Telemetry;
import top.hibermod.ctdapi.interop.EnergyProvider;
import top.hibermod.ctdapi.interop.Interop;
import top.hibermod.ctdapi.status.Authority;
import top.hibermod.ctdapi.status.SignalStates;

/**
 * 三条「沉默的」不变量。
 *
 * <h2>为什么这三样放一起</h2>
 * 它们都没有画面、不会抛异常，错了只表现为**行为不对**：
 *
 * <ol>
 *   <li><b>能源「未知」不等于「耗尽」</b>。写成相等，没装联动模组的存档里
 *       所有列车会瞬间趴窝——这正是 {@code Interop} 注释里反复强调的那条线。</li>
 *   <li><b>授权 {@code -1} 表示「内核从未说话」</b>，不是「授权为零」。
 *       混淆这两者的后果是列车被一个从未表态的内核拦住。</li>
 *   <li><b>灯态是内核写的、宿主读的</b>，表里没有条目与「表里是灭灯」必须能区分，
 *       否则清空之后所有信号机看起来都被设成了灭灯。</li>
 * </ol>
 */
public final class StatusInteropTest {

    private StatusInteropTest() {}

    private static final ResourceKey<Level> DIM = Level.OVERWORLD;

    public static void main(String[] args) {
        int failures = 0;

        failures += energyTests();
        failures += authorityTests();
        failures += signalStateTests();

        System.out.println();
        if (failures > 0) {
            System.out.println("API 不变量验收失败: " + failures + " 项");
            System.exit(1);
        }
        System.out.println("API 不变量验收通过：未知≠耗尽、授权哨兵、灯态读写");
    }

    // ── 能源 ──────────────────────────────────────────────────────────────

    private static int energyTests() {
        int f = 0;

        Interop.resetForTests();

        Telemetry.TrainState train = state(UUID.randomUUID());

        // ① 一个提供方都没有 → UNKNOWN，而不是 DEPLETED
        EnergyProvider.EnergySample none = Interop.sampleEnergy(train);
        f += check("无提供方时是 UNKNOWN",
            none.status() == EnergyProvider.EnergySample.Status.UNKNOWN,
            "status=" + none.status());
        f += check("UNKNOWN 仍然允许牵引（关键！）", none.canTraction());

        // ② 提供方返回 AVAILABLE
        Interop.registerEnergyProvider(provider("t:ok", () -> EnergyProvider.EnergySample.available(0.8)));
        f += check("有提供方时读到它",
            Interop.sampleEnergy(train).status() == EnergyProvider.EnergySample.Status.AVAILABLE);

        // ③ 合并取「更坏」的那个，且余量取较小值
        Interop.unfreeze();
        Interop.resetForTests();
        Interop.registerEnergyProvider(provider("t:a", () -> EnergyProvider.EnergySample.available(0.9)));
        Interop.registerEnergyProvider(provider("t:b", () -> EnergyProvider.EnergySample.low(0.3, null)));

        EnergyProvider.EnergySample merged = Interop.sampleEnergy(train);
        f += check("合并取更坏的状态",
            merged.status() == EnergyProvider.EnergySample.Status.LOW, "status=" + merged.status());
        f += check("合并取较小余量", Math.abs(merged.fraction() - 0.3) < 1e-9,
            "fraction=" + merged.fraction());

        // ④ 耗尽：唯一会阻止牵引的状态
        Interop.unfreeze();
        Interop.resetForTests();
        Interop.registerEnergyProvider(provider("t:dead",
            () -> EnergyProvider.EnergySample.depleted(Component.literal("empty"))));

        EnergyProvider.EnergySample dead = Interop.sampleEnergy(train);
        f += check("耗尽被识别", dead.status() == EnergyProvider.EnergySample.Status.DEPLETED);
        f += check("耗尽阻止牵引", !dead.canTraction());
        f += check("耗尽需要补给", dead.needsRefill());

        // ⑤ 返回 null 的提供方 = 不适用，不能把「不知道」变成「没电」
        Interop.unfreeze();
        Interop.resetForTests();
        Interop.registerEnergyProvider(provider("t:null", () -> null));
        f += check("提供方返回 null 时仍是 UNKNOWN",
            Interop.sampleEnergy(train).status() == EnergyProvider.EnergySample.Status.UNKNOWN);

        // ⑥ 一个提供方抛异常不能拖垮整体
        Interop.unfreeze();
        Interop.resetForTests();
        Interop.registerEnergyProvider(new EnergyProvider() {
            @Override public ResourceLocation id() { return ResourceLocation.parse("t:boom"); }
            @Override public String modId() { return "t"; }
            @Override public EnergySample sample(Telemetry.TrainState t) {
                throw new IllegalStateException("boom");
            }
        });
        Interop.registerEnergyProvider(provider("t:ok2", () -> EnergyProvider.EnergySample.available(0.5)));

        EnergyProvider.EnergySample survived = Interop.sampleEnergy(train);
        f += check("一个提供方炸了不影响其它",
            survived.status() == EnergyProvider.EnergySample.Status.AVAILABLE,
            "status=" + survived.status());

        Interop.unfreeze();
        Interop.resetForTests();
        return f;
    }

    // ── 授权 ──────────────────────────────────────────────────────────────

    private static int authorityTests() {
        int f = 0;

        UUID train = UUID.randomUUID();

        // ① 没说过话：distance=-1，has=false
        f += check("未表态时 has=false", !Authority.has(train));
        // 约定：distance 没有哨兵值（0 是合法授权），靠 has 区分；
        // 只有 speedCap 用 -1 表示「内核没说过」。
        f += check("未表态时 distance=0（靠 has 区分）",
            Math.abs(Authority.distance(train)) < 1e-9,
            "distance=" + Authority.distance(train));
        f += check("未表态时 speedCap=-1", Authority.speedCap(train) < 0,
            "speedCap=" + Authority.speedCap(train));

        // ② 表态之后
        Authority.put(train, 320.0, 0.4);
        f += check("表态后 has=true", Authority.has(train));
        f += check("距离被记录", Math.abs(Authority.distance(train) - 320.0) < 1e-9);
        f += check("限速被记录", Math.abs(Authority.speedCap(train) - 0.4) < 1e-9);

        // ③ 授权 0 与「没说过」必须能区分
        Authority.put(train, 0.0, 0.0);
        f += check("授权 0 仍然算表过态", Authority.has(train));
        f += check("授权 0 的 distance 是 0",
            Math.abs(Authority.distance(train)) < 1e-9, "distance=" + Authority.distance(train));
        f += check("speedCap 0 也算表过态", Authority.speedCap(train) >= 0,
            "speedCap=" + Authority.speedCap(train));

        // ④ 清除
        Authority.clear(train);
        f += check("清除后 has=false", !Authority.has(train));
        f += check("清除后 speedCap 回到 -1", Authority.speedCap(train) < 0,
            "speedCap=" + Authority.speedCap(train));

        return f;
    }

    // ── 灯态 ──────────────────────────────────────────────────────────────

    private static int signalStateTests() {
        int f = 0;

        SignalStates.clear();

        UUID signal = UUID.randomUUID();
        String clear = "ctd:clear";

        // ① 空表：取不到，且 size=0（不是「所有信号都是灭灯」）
        f += check("空表 size=0", SignalStates.size() == 0);
        // 约定：没有条目返回空串，而不是 null——调用方不必到处判空
        f += check("空表取到空串", SignalStates.get(signal).isEmpty(),
            "got=" + SignalStates.get(signal));

        // ② 写入后可读
        SignalStates.put(signal, clear);
        f += check("写入后可读", clear.equals(SignalStates.get(signal)),
            String.valueOf(SignalStates.get(signal)));
        f += check("写入后 size=1", SignalStates.size() == 1);

        // ③ 快照是独立副本
        var snapshot = SignalStates.snapshot();
        SignalStates.clear();
        f += check("快照不受之后 clear 影响", snapshot.containsKey(signal) && snapshot.size() == 1,
            "snapshot=" + snapshot.size());
        f += check("clear 之后表为空", SignalStates.size() == 0);

        // ④ 覆盖写
        String stop = "ctd:stop";
        SignalStates.put(signal, clear);
        SignalStates.put(signal, stop);
        f += check("后写的覆盖前写的", stop.equals(SignalStates.get(signal)));

        SignalStates.clear();
        return f;
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    private static EnergyProvider provider(String id, java.util.function.Supplier<EnergyProvider.EnergySample> s) {
        return new EnergyProvider() {
            private final ResourceLocation rid = ResourceLocation.parse(id);

            @Override public ResourceLocation id() { return rid; }
            @Override public String modId() { return "test"; }
            @Override public EnergySample sample(Telemetry.TrainState train) { return s.get(); }
        };
    }

    private static Telemetry.TrainState state(UUID id) {
        Rail.NodeId a = new Rail.NodeId(DIM, 0, 64, 0, 0);
        Rail.NodeId b = new Rail.NodeId(DIM, 8, 64, 0, 0);

        return new Telemetry.TrainState(
            id, Component.literal("test"), 8.0,
            new Rail.EdgeId(a, b), 4.0,
            new Rail.EdgeId(a, b), 4.0,
            List.of(), 0.0, 0.0, 0.01, 1.4, 1.4,
            2, false, false, false, 0.0, null, 100.0,
            EnergyProvider.EnergySample.unknown(),
            Set.of());
    }

    private static int check(String name, boolean ok) {
        return check(name, ok, "");
    }

    private static int check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name + (ok ? "" : "  <- " + detail));
        return ok ? 0 : 1;
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> none() {
        return Map.of();
    }
}
