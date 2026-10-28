package top.hibermod.ctdapi;

import java.util.UUID;

import net.minecraft.resources.ResourceLocation;

import top.hibermod.ctdapi.hud.Hud;
import top.hibermod.ctdapi.pis.Pis;

/**
 * 单次调度帧的**输出**：内核通过它写指令。
 *
 * <p>宿主在本帧结束后统一应用。若内核一条指令都不写，则全部列车维持
 * {@link Command.TrainCommand#hold(UUID)} 的默认语义之外的**原版行为**
 * （即「内核不干预」）。这保证了「零指令 = 退化为 Create 原生」。
 */
public interface DispatchFrame {

    /** 提交一条列车指令。同一列车同帧多次提交以最后一次为准。 */
    void command(Command.TrainCommand command);

    /** 提交一条信号显示指令。 */
    void aspect(Command.AspectCommand command);

    /** 提交一条岔口锁闭指令。 */
    void switchLock(Command.SwitchCommand command);

    /** 提交一条普通设备指令（地面 / 车载）。 */
    void device(Command.DeviceCommand command);

    /** 提交一条受电/供电切换指令。 */
    void power(Command.PowerCommand command);

    /** 提交一条补给指令（加油 / 充电 / 上水）。 */
    void service(Command.ServiceCommand command);

    /** 发布/更新一块 HUD 面板的本帧内容。不发布 = 该面板隐藏。 */
    void hud(Hud.HudPanel panel);

    /** 发布/更新一块 PIS 显示屏的本帧内容。不发布 = 保持上次内容。 */
    void board(Pis.BoardModel board);

    /** 发布一条事件（会进入 PIS 总线与日志）。 */
    void event(RailwayEvent event);

    // ── 便捷方法 ──────────────────────────────────────────────────────────

    /** 让列车停车（授权 0）。{@code reason} 进 PIS。 */
    default void hold(UUID trainId, String reason) {
        command(Command.TrainCommand.hold(trainId));
        event(new RailwayEvent.TrainHeld(trainId, reason));
    }

    /** 给定距离授权与限速。 */
    default void proceed(UUID trainId, double authority, double speedCap) {
        command(Command.TrainCommand.proceed(trainId, authority, speedCap));
    }

    /** 设置某面信号显示（传 Aspect 语义 id，灯位由机型决定）。 */
    default void aspect(UUID signalId, Topology.SignalSide side, ResourceLocation aspectId) {
        aspect(new Command.AspectCommand(signalId, side, aspectId));
    }
}
