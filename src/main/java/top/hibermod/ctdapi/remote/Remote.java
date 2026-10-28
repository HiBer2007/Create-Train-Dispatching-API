package top.hibermod.ctdapi.remote;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceLocation;

import top.hibermod.ctdapi.Command;
import top.hibermod.ctdapi.DispatchFrame;
import top.hibermod.ctdapi.DispatchKernel;
import top.hibermod.ctdapi.FrameContext;
import top.hibermod.ctdapi.KernelEnv;
import top.hibermod.ctdapi.RailwayEvent;
import top.hibermod.ctdapi.hud.Hud;
import top.hibermod.ctdapi.pis.Pis;

/**
 * **进程外调度引擎**的接入层。
 *
 * <p>时刻表与实时调度需要大量计算（全路网寻路、时刻优化、换乘接续、延误传播），
 * 这些完全可以跑在游戏进程之外——甚至跑在另一台机器上。本包就是那条边界。
 *
 * <h2>三段分离</h2>
 * <pre>
 *   DispatchKernel（接口）  ←  RemoteKernel（本包）  ←  KernelTransport（管道）+ FrameCodec（编解码）
 * </pre>
 * <ul>
 *   <li>{@link KernelTransport} 只管「把字节送出去、把字节取回来」，可以是 HTTP、
 *       WebSocket、本地 socket、甚至 stdio 管道；</li>
 *   <li>{@link FrameCodec} 只管「状态怎么序列化、响应怎么解析」，可以用 JSON、Protobuf、NBT；</li>
 *   <li>本包的 {@link RemoteKernel} 把二者粘起来，并负责**延迟与失效**的处理。</li>
 * </ul>
 *
 * <h2>⚠ 进程外的核心问题：延迟</h2>
 * 一次网络往返必然跨帧。这意味着引擎看到的永远是**上一帧或更早**的世界。
 * 本实现因此做了两件事：
 * <ol>
 *   <li><b>不阻塞</b>：请求异步发出，收到的响应在**下一帧**应用；</li>
 *   <li><b>授权衰减</b>：旧响应不能永远有效。每个 tick 按 {@link RemotePolicy#authorityDecay()}
 *       削减授权，超过 {@link RemotePolicy#maxStaleTicks()} 直接按 {@link RemotePolicy#holdOnStale()} 处理。</li>
 * </ol>
 *
 * <p>第二条是关键：如果远端说「可以走 500 格」然后断线了，列车不该傻跑 500 格。
 * 授权必须随时间自然收紧——这与真实 ETCS 的「通信超时降级」是同一个思路。
 */
public final class Remote {

    private Remote() {}

    /** 传输管道。实现方负责连接管理、重连与线程安全。 */
    public interface KernelTransport extends AutoCloseable {
        /**
         * 发送一帧状态并等待响应。
         *
         * <p><b>不要抛异常</b>——失败请返回一个异常完成的 future。宿主会把失败当作「本帧无响应」。
         *
         * @param request       编码后的状态
         * @param timeoutMillis 建议超时
         */
        CompletableFuture<byte[]> exchange(byte[] request, long timeoutMillis);

        /** 当前是否可用。为 false 时宿主跳过发送。 */
        default boolean isConnected() {
            return true;
        }

        @Override
        default void close() {}
    }

    /** 编解码。两端必须用同一份实现。 */
    public interface FrameCodec {
        /** 把本帧状态编码成字节。 */
        byte[] encode(FrameContext in, long revision);

        /** 解析响应。解析失败请返回 {@code null} 而不是抛异常。 */
        @Nullable
        KernelResponse decode(byte[] response);
    }

    /**
     * 引擎的一帧输出。
     *
     * <p>所有列表都可以为空——空表示「本帧不干预这一部分」。
     */
    public record KernelResponse(
        long revision,
        List<Command.TrainCommand> trains,
        List<Command.AspectCommand> aspects,
        List<Command.SwitchCommand> switches,
        List<Command.DeviceCommand> devices,
        List<Hud.HudPanel> hud,
        List<Pis.BoardModel> boards,
        List<RailwayEvent> events
    ) {
        public KernelResponse {
            trains = trains == null ? List.of() : List.copyOf(trains);
            aspects = aspects == null ? List.of() : List.copyOf(aspects);
            switches = switches == null ? List.of() : List.copyOf(switches);
            devices = devices == null ? List.of() : List.copyOf(devices);
            hud = hud == null ? List.of() : List.copyOf(hud);
            boards = boards == null ? List.of() : List.copyOf(boards);
            events = events == null ? List.of() : List.copyOf(events);
        }

        public static KernelResponse empty(long revision) {
            return new KernelResponse(revision, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    /**
     * 进程外引擎的失效策略。
     *
     * @param timeoutMillis  单次往返超时
     * @param maxStaleTicks  响应超过多少 tick 视为失效
     * @param authorityDecay 每 tick 削减的授权（格）。0 = 不衰减（不建议）
     * @param holdOnStale    失效时是否强制停车（true = 安全优先）
     */
    public record RemotePolicy(
        long timeoutMillis,
        int maxStaleTicks,
        double authorityDecay,
        boolean holdOnStale
    ) {
        public static final RemotePolicy SAFE = new RemotePolicy(250L, 3, 8.0, true);

        public static final RemotePolicy PERMISSIVE = new RemotePolicy(500L, 10, 2.0, false);
    }

    /**
     * 把 {@link KernelTransport} + {@link FrameCodec} 包装成一个 {@link DispatchKernel}。
     *
     * <p>注册方式与普通内核完全一致：
     * <pre>
     * DispatchKernels.register(id, () -> new RemoteKernel(id, transport, codec, RemotePolicy.SAFE));
     * </pre>
     */
    public static final class RemoteKernel implements DispatchKernel {

        private final ResourceLocation id;
        private final KernelTransport transport;
        private final FrameCodec codec;
        private final RemotePolicy policy;

        private final AtomicReference<KernelResponse> latest = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<byte[]>> inflight = new AtomicReference<>();

        private long revision;
        private long lastResponseTick = Long.MIN_VALUE;
        private boolean degraded;

        public RemoteKernel(ResourceLocation id, KernelTransport transport, FrameCodec codec, RemotePolicy policy) {
            this.id = id;
            this.transport = transport;
            this.codec = codec;
            this.policy = policy;
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public void onAttach(KernelEnv env) {
            env.log("info", "Remote dispatch kernel '{}' attached (transport={}, codec={})",
                id, transport.getClass().getSimpleName(), codec.getClass().getSimpleName());
        }

        @Override
        public void onFrame(FrameContext in, DispatchFrame out) {
            long now = in.gameTime();

            // 1) 收：把已完成的响应取回来（非阻塞）
            CompletableFuture<byte[]> f = inflight.get();
            if (f != null && f.isDone()) {
                inflight.set(null);
                if (!f.isCompletedExceptionally()) {
                    KernelResponse response = codec.decode(f.getNow(null));
                    if (response != null) {
                        latest.set(response);
                        lastResponseTick = now;
                        degraded = false;
                    }
                } else {
                    degrade(out, now, "transport failure");
                }
            }

            // 2) 发：没有在途请求就发一帧（非阻塞）
            if (inflight.get() == null && transport.isConnected()) {
                revision++;
                byte[] payload = codec.encode(in, revision);
                try {
                    inflight.set(transport.exchange(payload, policy.timeoutMillis()));
                } catch (Throwable t) {
                    degrade(out, now, "encode/send failed: " + t.getMessage());
                }
            }

            // 3) 用：应用最近一次响应，并按陈旧度收紧授权
            KernelResponse response = latest.get();
            if (response == null) {
                if (policy.holdOnStale()) holdAll(out, in);
                return;
            }

            long age = now - lastResponseTick;
            if (age > policy.maxStaleTicks()) {
                degrade(out, now, "stale response (" + age + " ticks)");
                if (policy.holdOnStale()) holdAll(out, in);
                return;
            }

            double decay = policy.authorityDecay() * age;

            for (Command.TrainCommand c : response.trains()) {
                out.command(c.movementAuthority() <= 0
                    ? c
                    : new Command.TrainCommand(
                        c.trainId(), c.direction(),
                        Math.max(0, c.movementAuthority() - decay),
                        c.speedCap(), c.hold(), c.route()));
            }
            for (Command.AspectCommand c : response.aspects()) out.aspect(c);
            for (Command.SwitchCommand c : response.switches()) out.switchLock(c);
            for (Command.DeviceCommand c : response.devices()) out.device(c);
            for (Hud.HudPanel p : response.hud()) out.hud(p);
            for (Pis.BoardModel b : response.boards()) out.board(b);
            for (RailwayEvent e : response.events()) out.event(e);
        }

        private void degrade(DispatchFrame out, long now, String why) {
            if (degraded) return;
            degraded = true;
            out.event(new RailwayEvent.KernelFault(now, id.toString(), "remote engine: " + why));
        }

        private void holdAll(DispatchFrame out, FrameContext in) {
            List<Command.TrainCommand> holds = new ArrayList<>();
            in.telemetry().trains().forEach(t -> holds.add(Command.TrainCommand.hold(t.id())));
            holds.forEach(out::command);
        }
    }
}
