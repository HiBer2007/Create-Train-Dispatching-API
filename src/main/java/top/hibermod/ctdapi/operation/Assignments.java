package top.hibermod.ctdapi.operation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.resources.ResourceLocation;

/**
 * 开行授权表：谁在跑哪个车次。
 *
 * <p>与 {@link top.hibermod.ctdapi.status.Restrictions} 同一个形态：进程内注册表，
 * **由宿主负责持久化**。API 不碰存档——存档格式是宿主与世界的约定。
 *
 * <h2>唯一性约束</h2>
 * 同一辆车在同一时刻只能属于一个指派。重复指派不抛异常，而是**后者覆盖前者**
 * 并保留被覆盖的记录，因为调度算法的正常行为就是「改派」，
 * 把改派做成异常会逼内核写一堆先撤销再指派的样板代码。
 */
public final class Assignments {

    private static final Map<ResourceLocation, Assignment> BY_ID = new LinkedHashMap<>();
    /** 车辆编号 → 指派 id。用于 O(1) 反查「这辆车在跑什么」。 */
    private static final Map<UnitNumber, ResourceLocation> BY_UNIT = new LinkedHashMap<>();

    private Assignments() {}

    /**
     * 发布/覆盖一个指派。
     *
     * @return 被覆盖掉的旧指派（如果有）
     */
    public static synchronized Optional<Assignment> publish(Assignment assignment) {
        if (assignment == null) throw new IllegalArgumentException("assignment");
        Assignment previous = BY_ID.put(assignment.id(), assignment);
        // 先把旧的单位映射摘掉
        if (previous != null) {
            for (UnitNumber u : previous.allUnits()) BY_UNIT.remove(u);
        }
        for (UnitNumber u : assignment.allUnits()) {
            ResourceLocation other = BY_UNIT.put(u, assignment.id());
            if (other != null && !other.equals(assignment.id())) {
                Assignment old = BY_ID.get(other);
                if (old != null) {
                    // 这辆车原来在别的指派里：把那个指派里的这辆车移除
                    BY_ID.put(other, withoutUnit(old, u));
                }
            }
        }
        OperationFeed.assignmentPublished(assignment);
        return Optional.ofNullable(previous);
    }

    private static Assignment withoutUnit(Assignment a, UnitNumber unit) {
        List<UnitSet> sets = new ArrayList<>();
        for (UnitSet s : a.sets()) {
            if (s.contains(unit)) {
                List<UnitNumber> rest = new ArrayList<>(s.units());
                rest.remove(unit);
                if (!rest.isEmpty()) {
                    sets.add(new UnitSet(s.id(), s.displayName(), rest));
                }
            } else {
                sets.add(s);
            }
        }
        return new Assignment(a.id(), a.run(), sets, a.source(), a.issuedAt());
    }

    /** 撤销。 */
    public static synchronized boolean revoke(ResourceLocation id) {
        Assignment a = BY_ID.remove(id);
        if (a == null) return false;
        for (UnitNumber u : a.allUnits()) BY_UNIT.remove(u);
        OperationFeed.assignmentRevoked(a);
        return true;
    }

    public static synchronized Optional<Assignment> get(ResourceLocation id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /** 某辆车当前跑的车次；没有则空。 */
    public static synchronized Optional<Assignment> ofUnit(UnitNumber unit) {
        ResourceLocation id = BY_UNIT.get(unit);
        return id == null ? Optional.empty() : Optional.ofNullable(BY_ID.get(id));
    }

    /** 全部车次号（可能重复，重联时分段跑同一车次）。 */
    public static synchronized List<String> runNumbers() {
        List<String> out = new ArrayList<>();
        for (Assignment a : BY_ID.values()) out.add(a.run().runNumber());
        return out;
    }

    /** 某个车次号的全部指派。 */
    public static synchronized List<Assignment> ofRun(String runNumber) {
        List<Assignment> out = new ArrayList<>();
        for (Assignment a : BY_ID.values()) {
            if (a.run().runNumber().equals(runNumber)) out.add(a);
        }
        return out;
    }

    public static synchronized Collection<Assignment> all() {
        return List.copyOf(BY_ID.values());
    }

    public static synchronized int size() {
        return BY_ID.size();
    }

    public static synchronized void clear() {
        BY_ID.clear();
        BY_UNIT.clear();
    }
}
