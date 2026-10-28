package top.hibermod.ctdapi.operation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import top.hibermod.ctdapi.Command;
import top.hibermod.ctdapi.hud.Hud;
import top.hibermod.ctdapi.pis.Pis;
import top.hibermod.ctdapi.RailwayEvent;
import top.hibermod.ctdapi.status.Emergency;
import top.hibermod.ctdapi.status.Restrictions;

/**
 * 运营事件订阅：给监控端一个**推**而不是**拉**的入口。
 *
 * <h2>为什么需要</h2>
 * 调度后台要实时显示「谁跑哪个车次」「哪条区间被封闭」「有没有应急指令」。
 * 纯轮询有两类问题：要么太频繁（每帧读全表），要么太稀疏（看不出变化过程）。
 * 订阅制让变化发生的**那一刻**就通知出去。
 *
 * <h2>约束</h2>
 * <ul>
 *   <li>回调在**触发者的线程**上执行。发布指派的可能是服务端 tick，也可能是网络线程。
 *       订阅方自己负责切线程，API 不做线程池——那会把「为什么我的回调卡了 200ms」
 *       变成一个查不到答案的问题。</li>
 *   <li>回调抛异常会被吞掉并计数，**不影响其它订阅者**。监控端不该有让世界停下来的能力。</li>
 *   <li>不要在这里做重活。需要时间长的处理请自己丢进队列。</li>
 * </ul>
 */
public final class OperationFeed {

    /** 订阅回调。全部方法都有默认空实现，订阅方只覆盖关心的那几个。 */
    public interface Listener {

        /** 指派已发布或覆盖。 */
        default void onAssignmentPublished(Assignment assignment) {}

        /** 指派被撤销。 */
        default void onAssignmentRevoked(Assignment assignment) {}

        /** 新增或修改了线路限制。 */
        default void onRestrictionChanged(Restrictions.Restriction restriction) {}

        /** 限制被撤销。 */
        default void onRestrictionRevoked(Restrictions.Restriction restriction) {}

        /** 新增应急指令。 */
        default void onEmergencyIssued(Emergency.Order order) {}

        /** 应急解除。 */
        default void onEmergencyCleared(Emergency.Order order) {}

        // ── 宿主不执行、只转交的指令 ─────────────────────────────────────
        //
        // 这几类是「不属于宿主」的东西：设备协议、牵引电量、运营服务、
        // HUD 面板、PIS 板面、事件流。它们的实际效果由**别的模组**负责
        // （后台调度模组、供电模组、PIS 渲染模组）。
        //
        // 宿主既不执行也不丢弃，而是**转交**给订阅者。
        // 丢弃会让脚本作者以为指令生效了——这是最难查的一类问题。

        /** 脚本下发了设备指令。 */
        default void onDeviceCommand(Command.DeviceCommand command) {}

        /** 脚本下发了牵引/电量指令。 */
        default void onEnergyCommand(Command.EnergyCommand command) {}

        /** 脚本下发了运营服务指令。 */
        default void onServiceCommand(Command.ServiceCommand command) {}

        /** 脚本要求显示一个 HUD 面板。 */
        default void onHudPanel(Hud.HudPanel panel) {}

        /** 脚本要求更新一块 PIS 板面。 */
        default void onBoardModel(Pis.BoardModel board) {}

        /** 脚本发出了一条铁路事件。 */
        default void onRailwayEvent(RailwayEvent event) {}
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile long failures;

    private OperationFeed() {}

    public static void subscribe(Listener listener) {
        if (listener != null && !LISTENERS.contains(listener)) LISTENERS.add(listener);
    }

    public static void unsubscribe(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static int listeners() {
        return LISTENERS.size();
    }

    /** 回调抛异常的次数，用于自检。 */
    public static long failures() {
        return failures;
    }

    // ── 触发 ──────────────────────────────────────────────────────────────

    public static void assignmentPublished(Assignment a) {
        fire(l -> l.onAssignmentPublished(a));
    }

    public static void assignmentRevoked(Assignment a) {
        fire(l -> l.onAssignmentRevoked(a));
    }

    public static void restrictionChanged(Restrictions.Restriction r) {
        fire(l -> l.onRestrictionChanged(r));
    }

    public static void restrictionRevoked(Restrictions.Restriction r) {
        fire(l -> l.onRestrictionRevoked(r));
    }

    public static void emergencyIssued(Emergency.Order o) {
        fire(l -> l.onEmergencyIssued(o));
    }

    // ── 转交类事件（宿主不执行）────────────────────────────────────────────

    public static void deviceCommand(Command.DeviceCommand c) {
        fire(l -> l.onDeviceCommand(c));
    }

    public static void energyCommand(Command.EnergyCommand c) {
        fire(l -> l.onEnergyCommand(c));
    }

    public static void serviceCommand(Command.ServiceCommand c) {
        fire(l -> l.onServiceCommand(c));
    }

    public static void hudPanel(Hud.HudPanel p) {
        fire(l -> l.onHudPanel(p));
    }

    public static void boardModel(Pis.BoardModel b) {
        fire(l -> l.onBoardModel(b));
    }

    public static void railwayEvent(RailwayEvent e) {
        fire(l -> l.onRailwayEvent(e));
    }

    public static void emergencyCleared(Emergency.Order o) {
        fire(l -> l.onEmergencyCleared(o));
    }

    private static void fire(java.util.function.Consumer<Listener> action) {
        for (Listener l : LISTENERS) {
            try {
                action.accept(l);
            } catch (Throwable t) {
                failures++;
                // 单个订阅者出错不能影响别人，更不能让发布方失败
            }
        }
    }

    public static void clear() {
        LISTENERS.clear();
        failures = 0;
    }
}
