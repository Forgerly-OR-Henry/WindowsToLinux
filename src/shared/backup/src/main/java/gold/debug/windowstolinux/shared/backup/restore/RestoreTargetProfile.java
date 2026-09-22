package gold.debug.windowstolinux.shared.backup.restore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;

/**
 * Verified target facts required before any restore mutation. / 任何恢复修改前所需的已验证目标事实。
 *
 * @param serverId persisted server identifier / 持久化服务器标识
 * @param distroId distro id / 发行版标识
 * @param distroVersion distro version / 发行版版本
 * @param architecture observed machine architecture / 观测到的机器架构
 * @param runtimeKind runtime kind / 运行时种类
 * @param runtimeVersion runtime version / 运行时版本
 * @param databaseType database type / 数据库类型
 * @param databaseEngineVersion database engine version / 数据库引擎版本
 * @param availableBytes available bytes / 可用字节
 * @param managedRootWritable managed root writable / 受管根目录可写
 * @param requiredPortsAvailable required ports available / 必需端口集合可用
 * @param foreignApplicationConflict foreign application conflict / 外部应用冲突
 * @param sourceRebuildSupported source rebuild supported / 源码重建受支持
 * @param databaseCompatibilityVerified database compatibility verified / 数据库兼容性已验证
 * @param binaryExperimentApproved binary experiment approved / 二进制Experiment已批准
 * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
 */
public record RestoreTargetProfile(String serverId, String distroId, String distroVersion, String architecture,
        String runtimeKind, String runtimeVersion, BackupDatabaseType databaseType, String databaseEngineVersion,
        long availableBytes, boolean managedRootWritable, boolean requiredPortsAvailable,
        boolean foreignApplicationConflict, boolean sourceRebuildSupported, boolean databaseCompatibilityVerified,
        boolean binaryExperimentApproved, List<String> evidence) {
    /**
     * Validates bounded non-secret target evidence. / 校验有界无秘密目标证据。
     *
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param distroId distro id / 发行版标识
     * @param distroVersion distro version / 发行版版本
     * @param architecture observed machine architecture / 观测到的机器架构
     * @param runtimeKind runtime kind / 运行时种类
     * @param runtimeVersion runtime version / 运行时版本
     * @param databaseType database type / 数据库类型
     * @param databaseEngineVersion database engine version / 数据库引擎版本
     * @param availableBytes available bytes / 可用字节
     * @param managedRootWritable managed root writable / 受管根目录可写
     * @param requiredPortsAvailable required ports available / 必需端口集合可用
     * @param foreignApplicationConflict foreign application conflict / 外部应用冲突
     * @param sourceRebuildSupported source rebuild supported / 源码重建受支持
     * @param databaseCompatibilityVerified database compatibility verified / 数据库兼容性已验证
     * @param binaryExperimentApproved binary experiment approved / 二进制Experiment已批准
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public RestoreTargetProfile {
        serverId = identifier(serverId, "serverId");
        distroId = identifier(distroId, "distroId");
        distroVersion = text(distroVersion, "distroVersion", 128);
        architecture = identifier(architecture, "architecture");
        runtimeKind = identifier(runtimeKind, "runtimeKind");
        runtimeVersion = text(runtimeVersion, "runtimeVersion", 128);
        databaseType = Objects.requireNonNull(databaseType, "databaseType");
        databaseEngineVersion = text(databaseEngineVersion, "databaseEngineVersion", 128);
        if (availableBytes < 0)
            throw new IllegalArgumentException("availableBytes must not be negative");
        evidence = evidence(evidence);
    }

    /**
     * Validates an identifier against the bounded syntax of the owning contract.
     * <p>按所属契约的有界语法验证标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return identifier text / 标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String identifier(String value, String field) {
        value = Objects.requireNonNull(value, field).trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9._-]{0,127}"))
            throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    /**
     * Validates textual content against the declared length and character constraints.
     * <p>按声明的长度及字符约束校验文本内容。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @param maximumLength maximum length / 最大长度
     * @return text text / 文本文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String text(String value, String field, int maximumLength) {
        value = Objects.requireNonNull(value, field).trim();
        if (value.isEmpty() || value.length() > maximumLength || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must contain bounded printable text");
        }
        return value;
    }

    /**
     * Validates a nonempty bounded set of distinct target compatibility evidence strings.
     * <p>校验非空、有界且内容不重复的目标兼容性证据字符串集合。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static List<String> evidence(List<String> values) {
        Objects.requireNonNull(values, "evidence");
        if (values.isEmpty() || values.size() > 64)
            throw new IllegalArgumentException("target evidence is incomplete");
        List<String> result = new ArrayList<>(values.size());
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            String item = text(value, "evidence", 512);
            if (!unique.add(item))
                throw new IllegalArgumentException("target evidence contains duplicates");
            result.add(item);
        }
        return List.copyOf(result);
    }
}
