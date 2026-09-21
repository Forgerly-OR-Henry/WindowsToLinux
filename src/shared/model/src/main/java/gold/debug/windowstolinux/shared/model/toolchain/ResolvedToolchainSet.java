package gold.debug.windowstolinux.shared.model.toolchain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable per-release tool identities, never reselected by a later catalog revision. / 发布级不可变工具身份，不随目录修订重新选择。
 *
 * @param catalogRevision catalog revision / 目录修订
 * @param selections selections / 选择集合
 */
public record ResolvedToolchainSet(String catalogRevision, List<Selection> selections) {
    /**
     * Records whether a resolved toolchain comes from the host or managed installation.
     * <p>记录已解析工具链来自宿主机还是受管安装。
     */
    public enum OriginType {
    /**
     * SYSTEM classification within origin type.
     * <p>源类型中的系统分类。
     */
     SYSTEM,
    /**
     * MANAGED classification within origin type.
     * <p>源类型中的受管分类。
     */
     MANAGED }
    /**
     * Binds one required toolchain to an exact executable and version selection.
     * <p>将一个所需工具链绑定到精确的可执行文件及版本选择。
     *
     * @param requirement requirement / 要求
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
     * @param origin origin / 源
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param sha256 lower-case hexadecimal SHA-256 digest / 小写十六进制 SHA-256 摘要
     */
    public record Selection(ToolchainRequirement requirement, ToolchainVersion version, String directory,
                            OriginType origin, String source, String sha256) {
        /**
         * Validates and binds the inputs required by selection.
         * <p>校验并绑定选择所需输入。
         *
         * @param requirement requirement / 要求
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
         * @param origin origin / 源
         * @param source source identity or content read by the operation / 操作读取的源身份或内容
         * @param sha256 lower-case hexadecimal SHA-256 digest / 小写十六进制 SHA-256 摘要
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public Selection {
            Objects.requireNonNull(requirement); Objects.requireNonNull(version); Objects.requireNonNull(origin);
            if (requirement.ecosystem() != version.ecosystem() || version.preview())
                throw new IllegalArgumentException("selected tool must be a matching stable ecosystem");
            if (directory == null || !directory.matches("/(?:[A-Za-z0-9_+.-]+/)*[A-Za-z0-9_+.-]+")
                    || directory.contains("/../") || directory.contains("/./") || directory.endsWith("/.."))
                throw new IllegalArgumentException("invalid toolchain directory");
            if (source == null || source.isBlank() || source.length() > 2048 || source.chars().anyMatch(c -> c < 32))
                throw new IllegalArgumentException("invalid toolchain source evidence");
            if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("toolchain digest required");
        }
    }
    /**
     * Validates and binds the inputs required by resolved toolchain set.
     * <p>校验并绑定已解析工具链集合所需输入。
     *
     * @param catalogRevision catalog revision / 目录修订
     * @param selections selections / 选择集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public ResolvedToolchainSet {
        if (catalogRevision == null || !catalogRevision.matches("[A-Za-z0-9._-]{1,96}"))
            throw new IllegalArgumentException("invalid catalog revision");
        selections = List.copyOf(selections);
        if (selections.size() > 32 || selections.stream().map(s -> s.requirement().ecosystem() + ":" + s.requirement().purpose())
                .distinct().count() != selections.size()) throw new IllegalArgumentException("duplicate toolchain role");
    }
    /**
     * Finds optional.
     * <p>查找可选。
     *
     * @param ecosystem ecosystem / 生态
     * @param purpose purpose / 用途
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    public Optional<Selection> find(ToolchainEcosystemType ecosystem, ToolchainRequirement.PurposeType purpose) {
        return selections.stream().filter(s -> s.requirement().ecosystem() == ecosystem && s.requirement().purpose() == purpose).findFirst();
    }
}
