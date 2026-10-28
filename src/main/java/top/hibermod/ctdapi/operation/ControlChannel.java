package top.hibermod.ctdapi.operation;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.ResourceLocation;

import top.hibermod.ctdapi.Command;

/**
 * 外部控制通道：让**不是内核**的东西也能下发指令。
 *
 * <h2>为什么不能直接复用 {@code DispatchFrame}</h2>
 * {@code DispatchFrame} 是内核每帧拿到的写入端，生命周期只有一帧、只属于一个内核。
 * 而调度后台（监看/排图那个模组）、网页控制台、玩家命令是**随时**想插一句话的，
 * 它们既不是内核，也不该被迫实现一个内核接口。
 *
 * <h2>优先级：operator 压过 kernel</h2>
 * 宿主每帧的处理顺序是「先内核、后控制通道」，且控制通道的结果**覆盖**内核的。
 * 否则玩家在控制台扣了车，脚本下一帧就把它放行了——那会让人觉得控制台是坏的。
 *
 * <h2>线程安全</h2>
 * 队列是线程安全的：外部系统可能从网络线程下发。指令在服务端 tick 里被 {@link #drain()} 取走，
 * 不要在别的线程里直接改世界。
 */
public final class ControlChannel {

    /** 指令来源。数值越小越优先。 */
    public enum Source {
        /** 玩家/命令方块。最高优先级。 */
        OPERATOR(0),
        /** 外部系统：调度后台、网页控制台。 */
        EXTERNAL(1),
        /** 自动化规则（例如定时扣车）。 */
        AUTOMATION(2);

        private final int rank;

        Source(int rank) { this.rank = rank; }

        public int rank() { return rank; }

        public boolean outranks(Source other) { return this.rank < other.rank; }
    }

    /** 一条待下发的列车指令。 */
    public record PendingTrainCommand(Source source, long issuedAt, String note, Command.TrainCommand command) {}

    /** 一条待下发的信号指令。 */
    public record PendingAspect(Source source, long issuedAt, String note, Command.AspectCommand command) {}

    /** 一条待发布的指派。 */
    public record PendingAssignment(Source source, long issuedAt, Assignment assignment) {}

    private static final List<PendingTrainCommand> TRAINS = new ArrayList<>();
    private static final List<PendingAspect> ASPECTS = new ArrayList<>();
    private static final List<PendingAssignment> ASSIGNMENTS = new ArrayList<>();

    private static long dropped;

    private ControlChannel() {}

    // ── 提交 ──────────────────────────────────────────────────────────────

    public static synchronized void hold(Source source, long now, Command.TrainCommand command, String note) {
        TRAINS.add(new PendingTrainCommand(source, now, note == null ? "" : note, command));
    }

    public static synchronized void aspect(Source source, long now, Command.AspectCommand command, String note) {
        ASPECTS.add(new PendingAspect(source, now, note == null ? "" : note, command));
    }

    public static synchronized void assign(Source source, long now, Assignment assignment) {
        ASSIGNMENTS.add(new PendingAssignment(source, now, assignment));
    }

    // ── 取用 ──────────────────────────────────────────────────────────────

    /** 取走并清空本帧全部待下发指令。宿主每帧调用一次。 */
    public static synchronized Drained drain() {
        Drained d = new Drained(
            List.copyOf(TRAINS), List.copyOf(ASPECTS), List.copyOf(ASSIGNMENTS));
        TRAINS.clear();
        ASPECTS.clear();
        ASSIGNMENTS.clear();
        return d;
    }

    /** 一帧的控制指令快照。 */
    public record Drained(
        List<PendingTrainCommand> trains,
        List<PendingAspect> aspects,
        List<PendingAssignment> assignments
    ) {
        public boolean isEmpty() {
            return trains.isEmpty() && aspects.isEmpty() && assignments.isEmpty();
        }

        /** 按来源优先级排序后的列车指令（高优先在前，同来源保持提交顺序）。 */
        public List<PendingTrainCommand> orderedTrains() {
            List<PendingTrainCommand> copy = new ArrayList<>(trains);
            copy.sort((a, b) -> Integer.compare(a.source().rank(), b.source().rank()));
            return copy;
        }
    }

    /** 队列里还有多少条未取走。 */
    public static synchronized int pending() {
        return TRAINS.size() + ASPECTS.size() + ASSIGNMENTS.size();
    }

    /** 累计被拒绝的提交数（例如来源非法、内容为空）。 */
    public static synchronized long dropped() {
        return dropped;
    }

    /** 撤销一个外部指派：只能撤销来源为外部的，内核的指派不归控制通道管。 */
    public static synchronized boolean revokeAssignment(ResourceLocation id) {
        boolean removed = ASSIGNMENTS.removeIf(a -> a.assignment().id().equals(id));
        if (Assignments.revoke(id)) removed = true;
        return removed;
    }

    public static synchronized void clear() {
        TRAINS.clear();
        ASPECTS.clear();
        ASSIGNMENTS.clear();
        dropped = 0;
    }
}
