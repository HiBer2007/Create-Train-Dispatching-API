package top.hibermod.ctdapi.operation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
