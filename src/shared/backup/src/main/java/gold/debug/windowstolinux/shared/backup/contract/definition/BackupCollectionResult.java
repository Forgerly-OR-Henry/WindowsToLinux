package gold.debug.windowstolinux.shared.backup.contract.definition;

import java.nio.file.Path;
import java.util.*;

import gold.debug.windowstolinux.shared.backup.manifest.*;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;

/**
 * Validated material returned only after runtime recovery and cleanup. / 仅在运行恢复及清理完成后返回的已验证素材。
 *
 * @param materials materials / 素材集合
 * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
 * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
 * @param originalStates authoritative component observations captured before this maintenance window / 本次维护窗口前采集的权威组件观测
 */
public record BackupCollectionResult(Map<BackupMember, Path> materials, BackupDatabase database, BackupRuntime runtime,
        Map<String, LifecycleObservation> originalStates) {
    /**
     * Validates and binds the inputs required by backup collection result.
     * <p>校验并绑定备份采集结果所需输入。
     *
     * @param materials materials / 素材集合
     * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param originalStates authoritative component observations captured before this maintenance window / 本次维护窗口前采集的权威组件观测
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupCollectionResult {
        materials = Collections.unmodifiableMap(new LinkedHashMap<>(materials));
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(runtime, "runtime");
        originalStates = Map.copyOf(originalStates);
    }
}
