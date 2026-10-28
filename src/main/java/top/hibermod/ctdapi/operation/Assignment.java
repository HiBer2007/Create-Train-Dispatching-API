package top.hibermod.ctdapi.operation;

import java.util.List;
import java.util.Objects;

import net.minecraft.resources.ResourceLocation;

/**
 * 开行授权：把一组车组指派到某个车次上。
 *
 * <p><b>这是「能力」而不是「策略」。</b> API 只负责表达「这些车跑这个车次」，
 * 至于哪组车该跑哪个车次、什么时候发车、怎么排运行图——那是调度算法（内核）的事。
 * 内核通过 {@code DispatchFrame#assign(...)} 提交，宿主负责落地。
 *
 * <h2>为什么是一组车组而不是一列车</h2>
 * 重联、摘挂、分段运行都会让「一个车次」对应多组车。
 * 只支持单车的话，重联就得在内核里做成「两列车各自跑一个车次」，
 * 那会让运行图与实际完全对不上。
 *
 * @param id      本次指派的标识，用于撤销
 * @param run     车次
 * @param sets    参与的车组，**有序**
 * @param source  谁发起的
 * @param issuedAt 发起时刻
 */
public record Assignment(ResourceLocation id, Run run, List<UnitSet> sets, Source source, long issuedAt) {

    public Assignment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(run, "run");
        sets = List.copyOf(sets);
        if (sets.isEmpty()) {
            throw new IllegalArgumentException("assignment " + id + " must reference at least one unit set");
        }
        Objects.requireNonNull(source, "source");
    }

    /** 发起方。宿主据此决定人工指令是否优先于脚本。 */
    public enum Source {
        /** 调度脚本/内核决定。 */
        KERNEL,
        /** 外部系统（Web 调度台、列车自动监控）。 */
        EXTERNAL,
        /** 玩家/命令。优先级最高。 */
        OPERATOR
    }

    /** 参与本车次的全部车辆编号（去重、保序）。 */
    public List<UnitNumber> allUnits() {
        List<UnitNumber> out = new java.util.ArrayList<>();
        for (UnitSet s : sets) {
            for (UnitNumber u : s.units()) {
                if (!out.contains(u)) out.add(u);
            }
        }
        return List.copyOf(out);
    }

    /** 某辆车是否属于本指派。 */
    public boolean includes(UnitNumber unit) {
        for (UnitSet s : sets) {
            if (s.contains(unit)) return true;
        }
        return false;
    }
}
