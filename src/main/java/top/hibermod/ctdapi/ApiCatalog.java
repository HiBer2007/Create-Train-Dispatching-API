package top.hibermod.ctdapi;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import top.hibermod.ctdapi.device.Devices;
import top.hibermod.ctdapi.hud.Hud;
import top.hibermod.ctdapi.pis.Pis;
import top.hibermod.ctdapi.signal.Aspect;
import top.hibermod.ctdapi.signal.LampColor;
import top.hibermod.ctdapi.signal.SignalRegistries;
import top.hibermod.ctdapi.signal.SignalSystem;
import top.hibermod.ctdapi.signal.SignalType;
import top.hibermod.ctdapi.timetable.Service;

/**
 * **统一索引**：回答「现在装了哪些东西、执行时该用哪一个」。
 *
 * <p>分散在四个注册表里的内容（信号、设备、HUD、PIS）在这里汇总成一张扁平的表。
 * 用途：
 *
 * <ul>
 *   <li><b>调试/自检</b>——启动后打印一份，一眼看出哪些扩展加载成功了；</li>
 *   <li><b>调度引擎发现能力</b>——尤其是进程外引擎，它连不上 Java 注册表，
 *       可以由宿主把 {@link #entries()} 序列化后发过去，让引擎知道这台服务器支持什么；</li>
 *   <li><b>配置校验</b>——玩家在配置里写了一个不存在的 id，启动时就能报出来。</li>
 * </ul>
 *
 * <p>本类**不持有任何状态**，每次调用都从各注册表现取。
 */
public final class ApiCatalog {

    private ApiCatalog() {}

    /** 条目类别。 */
    public enum Category {
        SIGNAL_SYSTEM,
        SIGNAL_TYPE,
        ASPECT,
        LAMP_COLOR,
        GROUND_DEVICE,
        ONBOARD_DEVICE,
        HUD_PANEL,
        PIS_BOARD,
        SERVICE,
        DISPATCH_KERNEL
    }

    /**
     * 一条索引项。
     *
     * @param category    类别
     * @param id          注册 id
     * @param displayName 显示名
     * @param detail      附加说明（如「4 灯 / 进站」「可命令」「引擎配置项」）
     */
    public record Entry(Category category, ResourceLocation id, Component displayName, String detail) {

        @Override
        public String toString() {
            return category + " " + id + " — " + detail;
        }
    }

    /** 全部已注册项。顺序稳定（按类别，再按注册顺序）。 */
    public static List<Entry> entries() {
        List<Entry> out = new ArrayList<>();

        for (SignalSystem s : SignalRegistries.systems()) {
            out.add(new Entry(Category.SIGNAL_SYSTEM, s.id(), s.displayName(),
                s.typeIds().size() + " 机型 / " + s.aspectIds().size() + " 语义"));
        }
        for (SignalType t : SignalRegistries.types()) {
            out.add(new Entry(Category.SIGNAL_TYPE, t.id(), t.displayName(),
                t.lampCount() + " 灯 / " + t.semantics().target()
                    + (t.isStoppingPoint() ? "" : " / 非停车点")
                    + " / 系统 " + t.systemId().getPath()));
        }
        for (Aspect a : SignalRegistries.aspects()) {
            out.add(new Entry(Category.ASPECT, a.id(), a.displayName(),
                a.category() + " / 限速 " + Math.round(a.speedFraction() * 100) + "%"));
        }
        for (LampColor c : SignalRegistries.lamps()) {
            out.add(new Entry(Category.LAMP_COLOR, c.id(),
                Component.literal("#" + String.format("%06X", c.rgb())), "灯色"));
        }

        for (Devices.DeviceType d : Devices.of(Devices.Kind.GROUND)) {
            out.add(new Entry(Category.GROUND_DEVICE, d.id(), d.displayName(),
                String.join(",", d.capabilities()) + (d.commandable() ? " / 可命令" : " / 只读")));
        }
        for (Devices.DeviceType d : Devices.of(Devices.Kind.ONBOARD)) {
            out.add(new Entry(Category.ONBOARD_DEVICE, d.id(), d.displayName(),
                String.join(",", d.capabilities()) + (d.commandable() ? " / 可命令" : " / 只读")));
        }

        for (Hud.HudPanelSpec h : Hud.specs()) {
            out.add(new Entry(Category.HUD_PANEL, h.id(), h.title(),
                h.target() + " / 优先级 " + h.priority()));
        }
        for (Pis.BoardSpec b : Pis.boards()) {
            out.add(new Entry(Category.PIS_BOARD, b.id(),
                Component.literal(b.name()),
                (b.station() == null ? "全线" : "车站 " + b.station()) + " / 最多 " + b.maxRows() + " 行"));
        }

        for (Service s : Service.services()) {
            out.add(new Entry(Category.SERVICE, s.id(), s.code(),
                (s.line() == null ? "无线路" : s.line().getString())
                    + " / 信号制度 " + s.signalSystemId().getPath()
                    + " / 优先级 " + s.priority()));
        }

        for (ResourceLocation k : DispatchKernels.registeredIds()) {
            out.add(new Entry(Category.DISPATCH_KERNEL, k, Component.literal(k.getPath()), "调度内核"));
        }

        return List.copyOf(out);
    }

    /** 某一类别的全部条目。 */
    public static List<Entry> of(Category category) {
        return entries().stream().filter(e -> e.category() == category).toList();
    }

    /** 按 id 查（跨类别）。 */
    public static Optional<Entry> find(Category category, ResourceLocation id) {
        return of(category).stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /**
     * 冻结全部注册表。宿主在加载完成后调用一次。
     *
     * <p>顺序有依赖：先冻结信号（交路要校验它引用的制度存在），再冻结交路，最后其余。
     *
     * @throws IllegalStateException 任一注册表校验失败时抛出
     */
    public static void freezeAll() {
        SignalRegistries.freeze();
        Service.freeze();
        Devices.freeze();
        Hud.freeze();
        Pis.freeze();
    }

    /** 是否全部注册表都已冻结。 */
    public static boolean allFrozen() {
        return SignalRegistries.frozen() && Service.frozen()
            && Devices.frozen() && Hud.frozen() && Pis.frozen();
    }
}
