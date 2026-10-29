package top.hibermod.ctdapi.net;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：**真实列车状态**的定期推送。
 *
 * <h2>为什么必须有这一层</h2>
 * Create 只在**有人正在操控**时给客户端发速度
 * （{@code CarriageContraptionEntity#control} 里每 5 刻一次 {@code TrainHUDUpdatePacket}）。
 * 一旦松手或者交给自动驾驶，客户端那份 {@code train.speed} 就**不再更新**，
 * 而列车本身还在动——于是仪表显示的是一张过期的照片。
 *
 * <p>实测证据：松手后客户端日志里 {@code train.speed} 恒定 {@code 0.7275}，
 * 同一段时间列车在画面上持续减速。
 *
 * <h2>为什么放在契约层</h2>
 * 数据在服务端（宿主能读到真的 {@code train.speed}），消费方是脚本运行时/订阅者。
 * 两者都依赖 API，让宿主直接依赖脚本运行时会反过来绑死装载顺序。
 *
 * <p>顺带：调度算法本来也需要一份统一的车速快照，这个包对它是同一份数据。
 *
 * @param trains 本帧的快照；空列表表示「服务端没有列车」
 */
public record TrainFeedPayload(List<Entry> trains) implements CustomPacketPayload {

    /**
     * 一列车的状态。
     *
     * @param trainId     列车 id
     * @param speed       真实速度（格/刻，带符号，车体参考系）
     * @param targetSpeed 目标速度（格/刻）
     * @param autopilot   是否处于自动驾驶（无人操控）状态
     * @param gameTime    采样时刻
     */
    public record Entry(UUID trainId, double speed, double targetSpeed,
                        boolean autopilot, long gameTime,
                        double authoritySpeed, double authorityDistance) {

        /** 内核没有下发过授权时的允许速度。 */
        public static final double NO_AUTHORITY = -1.0;

        /** 内核给过允许速度吗。 */
        public boolean hasAuthority() {
            return authoritySpeed >= 0;
        }
    }

    public static final CustomPacketPayload.Type<TrainFeedPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath("ctdapi", "train_feed"));

    public static final StreamCodec<FriendlyByteBuf, TrainFeedPayload> STREAM_CODEC =
        CustomPacketPayload.codec(TrainFeedPayload::write, TrainFeedPayload::read);

    public TrainFeedPayload {
        if (trains == null) trains = List.of();
    }

    @Override
    public CustomPacketPayload.Type<TrainFeedPayload> type() {
        return TYPE;
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(trains.size());
        for (Entry e : trains) {
            buf.writeUUID(e.trainId());
            buf.writeDouble(e.speed());
            buf.writeDouble(e.targetSpeed());
            buf.writeBoolean(e.autopilot());
            buf.writeVarLong(e.gameTime());
            buf.writeDouble(e.authoritySpeed());
            buf.writeDouble(e.authorityDistance());
        }
    }

    private static TrainFeedPayload read(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Entry> list = new ArrayList<>(Math.min(n, 256));
        for (int i = 0; i < n; i++) {
            list.add(new Entry(buf.readUUID(), buf.readDouble(), buf.readDouble(),
                buf.readBoolean(), buf.readVarLong(), buf.readDouble(), buf.readDouble()));
        }
        return new TrainFeedPayload(list);
    }

    /** 取某列车；没有返回 {@code null}。 */
    public Entry find(UUID trainId) {
        for (Entry e : trains) {
            if (e.trainId().equals(trainId)) return e;
        }
        return null;
    }
}
