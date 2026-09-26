package gold.debug.windowstolinux.app.db.task;

import java.time.Instant;
import java.util.*;

/** Exact process creation checkpoint, separate from its business outcome. / 独立于业务结果的精确进程创建检查点。
 * @param boot physical creator's application boot / 物理创建方应用启动
 * @param process durable process UUID / 持久进程 UUID
 * @param pid observed OS identity, absent before creation / 已观察系统身份，创建前为空
 * @param started observed OS start instant, paired with PID / 已观察系统启动时间，与 PID 成对 */
public record ProcessRecovery(UUID boot, UUID process, OptionalLong pid, Optional<Instant> started) {
    /** Validates inseparable process identity facts. / 校验不可分离的进程身份事实。
     * @param boot physical boot / 物理启动
     * @param process stable process UUID / 稳定进程 UUID
     * @param pid observed PID / 已观察 PID
     * @param started OS start time / 系统启动时间 */
    public ProcessRecovery {
        Objects.requireNonNull(boot);
        Objects.requireNonNull(process);
        Objects.requireNonNull(pid);
        Objects.requireNonNull(started);
        if (pid.isPresent() != started.isPresent() || pid.isPresent() && pid.getAsLong() <= 0)
            throw new IllegalArgumentException("Invalid process checkpoint");
    }

    /** Encodes only non-secret identity facts, never arguments or environment. / 仅编码非秘密身份事实，不编码参数或环境。
     * @return strict existing resource envelope / 严格既有资源封套 */
    public ResourceRecovery encode() {
        var fields = new TreeMap<String, String>();
        fields.put("bootId", boot.toString());
        fields.put("processId", process.toString());
        fields.put("phase", pid.isPresent() ? "STARTED" : "STARTING");
        if (pid.isPresent()) {
            fields.put("pid", Long.toString(pid.getAsLong()));
            fields.put("startedAt", started.orElseThrow().toString());
        }
        return new ResourceRecovery("process.v1", fields);
    }

    /** Decodes exact supported phases without guessing missing PID facts. / 解码精确受支持阶段，不猜测缺失 PID 事实。
     * @param recovery original metadata / 原元数据
     * @return exact checkpoint / 精确检查点 */
    public static ProcessRecovery decode(ResourceRecovery recovery) {
        var f = recovery.fields();
        boolean running = "STARTED".equals(f.get("phase"));
        if (!recovery.protocol().equals("process.v1") || !running && !"STARTING".equals(f.get("phase"))
                || !f.keySet()
                        .equals(running
                                ? Set.of("bootId", "processId", "phase", "pid", "startedAt")
                                : Set.of("bootId", "processId", "phase")))
            throw new IllegalArgumentException("Unrecognized process checkpoint");
        var value = new ProcessRecovery(UUID.fromString(f.get("bootId")), UUID.fromString(f.get("processId")),
                running ? OptionalLong.of(Long.parseLong(f.get("pid"))) : OptionalLong.empty(),
                running ? Optional.of(Instant.parse(f.get("startedAt"))) : Optional.empty());
        if (!value.encode().equals(recovery))
            throw new IllegalArgumentException("Noncanonical process checkpoint");
        return value;
    }
}
