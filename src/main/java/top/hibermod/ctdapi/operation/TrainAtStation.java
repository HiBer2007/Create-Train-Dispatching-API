package top.hibermod.ctdapi.operation;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * 列车与车站之间的一次运营事件。
 *
 * <h2>为什么在 API 层</h2>
 * 「车快进站了」这件事只有**宿主**看得到（它每帧在采样列车），
 * 而「该说什么话」归脚本。两边都得知道这个事件，所以它必须住在契约层，
 * 不能让宿主直接依赖脚本运行时，也不能让脚本自己去翻列车对象。
 *
 * <h2>三种相位</h2>
 * <ul>
 *   <li>{@link Phase#APPROACHING} —— 已进入接近区段；</li>
 *   <li>{@link Phase#STOPPED} —— 停稳；</li>
 *   <li>{@link Phase#DEPARTING} —— 已起步离开。</li>
 * </ul>
 * 只报**相位变化**，不每帧刷：广播是按站播的，不是按 tick 播的。
 *
 * @param train       列车 id
 * @param trainName   列车名（可能为空串）
 * @param station     车站 id
 * @param stationName 车站名（可能为空串）
 * @param phase       相位
 * @param gameTime    发生时刻
 * @param delaySeconds 晚点秒数；0 表示没有晚点信息，负值表示早点
 */
public record TrainAtStation(
    UUID train,
    String trainName,
    UUID station,
    String stationName,
    Phase phase,
    long gameTime,
    int delaySeconds
) {

    /** 相位。 */
    public enum Phase {
        APPROACHING,
        STOPPED,
        DEPARTING
    }

    public TrainAtStation {
        if (train == null) throw new IllegalArgumentException("train is required");
        if (station == null) throw new IllegalArgumentException("station is required");
        if (phase == null) throw new IllegalArgumentException("phase is required");
        if (trainName == null) trainName = "";
        if (stationName == null) stationName = "";
    }

    /** 一行摘要，用于日志。 */
    public String summary() {
        return phase + " " + (trainName.isEmpty() ? train.toString() : trainName)
            + " @ " + (stationName.isEmpty() ? station.toString() : stationName)
            + (delaySeconds == 0 ? "" : (delaySeconds > 0 ? " +" : " ") + delaySeconds + "s");
    }

    /** 便于测试构造。 */
    public static TrainAtStation of(UUID train, String trainName, UUID station, String stationName,
                                    Phase phase, long gameTime) {
        return new TrainAtStation(train, trainName, station, stationName, phase, gameTime, 0);
    }

    @Nullable
    public static TrainAtStation orNull(@Nullable TrainAtStation value) {
        return value;
    }
}
