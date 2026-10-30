package top.hibermod.ctdapi.tools;

import java.util.Collection;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import top.hibermod.ctdapi.signal.Aspect;
import top.hibermod.ctdapi.signal.LampColor;
import top.hibermod.ctdapi.signal.SignalRegistries;
import top.hibermod.ctdapi.signal.SignalSystem;
import top.hibermod.ctdapi.signal.SignalType;
import top.hibermod.ctdapi.timetable.Service;

/**
 * 交路注册表与信号注册表的规矩。
 *
 * <h2>为什么和 {@code DeviceRegistryTest} 分开</h2>
 * 设备注册表验的是「注册表的通用规矩」；这里验的是**这两张表特有的那条**：
 *
 * <ul>
 *   <li><b>交路必须指向一套已注册的信号制度</b>。指向不存在的制度时，列车会挂着
 *       一段永远解析不出信号语义的时刻表跑，而运行期没有任何提示。</li>
 *   <li><b>信号制度必须有停车语义的灯位</b>。没有停车语义的「制度」无法让列车停下，
 *       它不是一套制度，是一组颜色。</li>
 * </ul>
 *
 * <p>这两条都只在 {@code freeze()} 时校验——也就是说，**不冻结就永远不校验**。
 */
public final class TimetableSignalRegistryTest {

    private TimetableSignalRegistryTest() {}

    public static void main(String[] args) {
        int failures = 0;

        SignalRegistries.unfreeze();
        Service.unfreeze();
        Service.resetForTests();
        SignalRegistries.resetForTests();

        // ── 信号注册表 ────────────────────────────────────────────────────
        failures += check("空表时没有制度", SignalRegistries.systems().isEmpty());

        // 一条没有停车语义的「制度」必须在冻结时被拒绝
        ResourceLocation noStopId = ResourceLocation.parse("t:nosotp");
        SignalType type = SignalType.builder(ResourceLocation.parse("t:type"))
            .system(noStopId)                     // 类型必须归属某套制度，否则冻结时报「未知制度」
            .lamp(LampColor.RED)
            .lamp(LampColor.GREEN)
            .build();
        SignalRegistries.registerType(type);

        Aspect go = new Aspect(ResourceLocation.parse("t:go"),
            Component.literal("go"), 1.0, Aspect.Category.PROCEED);
        SignalRegistries.registerAspect(go);

        SignalSystem noStop = SignalSystem.builder(noStopId)
            .aspect(go)
            .type(type)
            .build();
        SignalRegistries.registerSystem(noStop);

        boolean rejected = false;
        String why = "";
        try {
            SignalRegistries.freeze();
        } catch (IllegalStateException e) {
            rejected = true;
            why = e.getMessage();
        }

        failures += check("没有停车语义的制度在冻结时被拒绝", rejected, why);
        if (rejected) {
            SignalRegistries.unfreeze();
        }

        // 干净重来：注册一套**合规**的制度（有停车语义），应当能冻结
        SignalRegistries.unfreeze();
        SignalRegistries.resetForTests();

        ResourceLocation okId = ResourceLocation.parse("t:ok");

        SignalRegistries.registerAspect(new Aspect(ResourceLocation.parse("t:stop2"),
            Component.literal("stop"), 0.0, Aspect.Category.STOP));
        SignalRegistries.registerAspect(new Aspect(ResourceLocation.parse("t:go2"),
            Component.literal("go"), 1.0, Aspect.Category.PROCEED));

        SignalRegistries.registerType(SignalType.builder(ResourceLocation.parse("t:type2"))
            .system(okId)
            .lamp(LampColor.RED)
            .lamp(LampColor.GREEN)
            .build());

        SignalSystem ok = SignalSystem.builder(okId)
            .aspect(SignalRegistries.aspect(ResourceLocation.parse("t:stop2")).orElseThrow())
            .aspect(SignalRegistries.aspect(ResourceLocation.parse("t:go2")).orElseThrow())
            .typeId(ResourceLocation.parse("t:type2"))
            .build();
        SignalRegistries.registerSystem(ok);

        boolean froze = true;
        String err = "";
        try {
            SignalRegistries.freeze();
        } catch (IllegalStateException e) {
            froze = false;
            err = e.getMessage();
        }
        failures += check("合规的制度可以冻结", froze, err);
        // ── 交路注册表 ────────────────────────────────────────────────────
        Service.unfreeze();
        Service.resetForTests();

        // 指向未注册制度的交路必须在冻结时被拒绝
        Service orphan = Service.builder(ResourceLocation.parse("t:orphan"))
            .code("G1")
            .signalSystem(ResourceLocation.parse("t:does-not-exist"))
            .build();
        Service.register(orphan);

        boolean serviceRejected = false;
        String serviceWhy = "";
        try {
            Service.freeze();
        } catch (IllegalStateException e) {
            serviceRejected = true;
            serviceWhy = e.getMessage();
        }
        failures += check("交路指向未注册制度时冻结失败", serviceRejected, serviceWhy);

        if (serviceRejected) {
            Service.unfreeze();
        }

        // 指向已注册制度则通过
        Service.unfreeze();
        Service.resetForTests();
        Service good = Service.builder(ResourceLocation.parse("t:good"))
            .code("G2")
            .signalSystem(ok.id())
            .build();
        Service.register(good);

        boolean serviceFroze = true;
        String serviceErr = "";
        try {
            Service.freeze();
        } catch (IllegalStateException e) {
            serviceFroze = false;
            serviceErr = e.getMessage();
        }
        failures += check("指向已注册制度的交路可以冻结", serviceFroze, serviceErr);
        failures += check("冻结后按制度能查到它",
            Service.usingSystem(ok.id()).size() == 1,
            "count=" + Service.usingSystem(ok.id()).size());

        // 冻结后拒绝新注册
        boolean frozenRejected = false;
        try {
            Service.register(Service.builder(ResourceLocation.parse("t:late"))
                .code("G3")
                .signalSystem(ok.id())
                .build());
        } catch (IllegalStateException e) {
            frozenRejected = true;
        }
        failures += check("交路注册表冻结后拒绝注册", frozenRejected);

        // 重复 id
        Service.unfreeze();
        boolean dup = false;
        try {
            Service.register(Service.builder(ResourceLocation.parse("t:good"))
                .code("G2-dup")
                .signalSystem(ok.id())
                .build());
        } catch (IllegalStateException e) {
            dup = true;
        }
        failures += check("重复交路 id 被拒绝", dup);

        Service.resetForTests();
        SignalRegistries.resetForTests();

        System.out.println();
        if (failures > 0) {
            System.out.println("制度/交路注册表验收失败: " + failures + " 项");
            System.exit(1);
        }
        System.out.println("制度/交路注册表验收通过：停车语义、制度指向、冻结校验、重复拒绝");
    }

    private static int check(String name, boolean ok) {
        return check(name, ok, "");
    }

    private static int check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name + (ok ? "" : "  <- " + detail));
        return ok ? 0 : 1;
    }

    @SuppressWarnings("unused")
    private static Collection<SignalSystem> all() {
        return SignalRegistries.systems();
    }
}
