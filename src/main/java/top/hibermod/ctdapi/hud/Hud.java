package top.hibermod.ctdapi.hud;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * HUD 面板：**调度引擎发布数据，宿主负责渲染**。
 *
 * <p>这样设计的原因很实际：调度引擎可能在**游戏进程之外**（见 {@code remote} 包），
 * 它不可能链接任何客户端渲染类。所以 HUD 的契约只能是纯数据。
 *
 * <h2>两段式</h2>
 * <ol>
 *   <li><b>声明</b>（一次）：{@link HudPanelSpec} 告诉宿主「有这么一个面板，标题是什么、
 *       给谁看、优先级多少」。宿主据此决定放在屏幕哪个位置、用什么风格。</li>
 *   <li><b>填充</b>（每帧）：引擎通过 {@link HudPanel} 推当帧内容。不推就等于该面板隐藏。</li>
 * </ol>
 *
 * <p>内置目标见 {@link Target}。驾驶台面板与调度台面板天然冲突，所以必须区分。
 */
public final class Hud {

    private Hud() {}

    /** 严重度。宿主据此配色（正常/警告/危险）。 */
    public enum Severity {
        NORMAL,
        INFO,
        WARN,
        CRITICAL
    }

    /** 面板给谁看。宿主按目标决定显示与隐藏。 */
    public enum Target {
        /** 司机台 / 车内。只在玩家坐在驾驶位时显示。 */
        DRIVER,
        /** 调度台。只在玩家打开调度界面时显示。 */
        DISPATCHER,
        /** 调试。只在 F3 或配置开启时显示。 */
        DEBUG
    }

    /**
     * 面板声明。
     *
     * @param id       稳定标识
     * @param title    标题
     * @param target   给谁看
     * @param priority 同目标内的排序，小的在上
     * @param maxRows  宿主最多渲染几行（0 = 不限）
     */
    public record HudPanelSpec(
        ResourceLocation id,
        Component title,
        Target target,
        int priority,
        int maxRows
    ) {
        public HudPanelSpec {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(target, "target");
        }
    }

    /** 面板的一行。 */
    public record HudRow(Component label, Component value, Severity severity) {
        public HudRow {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(value, "value");
            severity = severity == null ? Severity.NORMAL : severity;
        }
    }

    /**
     * 一帧的面板内容。
     *
     * @param panelId 对应已声明的 {@link HudPanelSpec}
     * @param rows    本帧的行。空列表 = 面板可见但无内容；不发布 = 面板隐藏
     * @param subtitle 副标题（可为 {@code null}），例如「列车 1024 / 晚点 3 分」
     */
    public record HudPanel(
        ResourceLocation panelId,
        List<HudRow> rows,
        @Nullable Component subtitle
    ) {
        public HudPanel {
            Objects.requireNonNull(panelId, "panelId");
            rows = List.copyOf(rows);
        }
    }

    // ── 注册表 ────────────────────────────────────────────────────────────

    private static final Map<ResourceLocation, HudPanelSpec> SPECS = new LinkedHashMap<>();
    private static boolean frozen;

    public static HudPanelSpec register(HudPanelSpec spec) {
        if (frozen) {
            throw new IllegalStateException("HUD registry is frozen; register during mod init instead");
        }
        if (SPECS.putIfAbsent(spec.id(), spec) != null) {
            throw new IllegalStateException("Duplicate HUD panel id: " + spec.id());
        }
        return spec;
    }

    public static Optional<HudPanelSpec> spec(ResourceLocation id) {
        return Optional.ofNullable(SPECS.get(id));
    }

    public static Collection<HudPanelSpec> specs() {
        return SPECS.values();
    }

    public static Collection<HudPanelSpec> specs(Target target) {
        return SPECS.values().stream().filter(s -> s.target() == target).toList();
    }

    public static boolean frozen() {
        return frozen;
    }

    public static void freeze() {
        frozen = true;
    }

    public static void resetForTests() {
        SPECS.clear();
        frozen = false;
    }
}
