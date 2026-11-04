package top.hibermod.ctdapi.route;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 运行图：线路站序（是否环线）+ 车次径路。挡板按「下一趟车次」取它要走的路线。 */
public final class RouteStore {

    private RouteStore() {}

    public record Line(String id, List<String> stations, boolean loop) {}

    public record RunPlan(String train, String line, String from, String to) {}

    private static final Map<String, Line> LINES = new ConcurrentHashMap<>();
    private static final Map<String, RunPlan> PLANS = new ConcurrentHashMap<>();

    public static void putLine(String id, List<String> stations, boolean loop) {
        LINES.put(id, new Line(id, List.copyOf(stations), loop));
    }

    public static Line line(String id) {
        return LINES.get(id);
    }

    public static void putPlan(String train, String line, String from, String to) {
        PLANS.put(train, new RunPlan(train, line, from, to));
    }

    public static RunPlan plan(String train) {
        return PLANS.get(train);
    }

    public static int lines() {
        return LINES.size();
    }

    public static int plans() {
        return PLANS.size();
    }

    public static void clear() {
        LINES.clear();
        PLANS.clear();
    }
}
