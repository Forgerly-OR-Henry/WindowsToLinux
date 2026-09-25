package gold.debug.windowstolinux.api.db;

import java.util.List;
import java.util.Optional;

/**
 * Author evidence for accepting or rejecting a schema. / 作者对数据结构作出接受或拒绝判断的证据。
 * @param valid whether the author validation accepted the data / 作者验证是否接受数据
 * @param violations copied validation failures, empty for accepted data / 验证失败列表副本，接受数据时为空
 * @param businessDigest optional author validation digest / 可选的作者验证摘要
 */
public record ValidationReport(boolean valid, List<String> violations, Optional<String> businessDigest) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param valid whether the author validation accepted the data / 作者验证是否接受数据
     * @param violations copied validation failures, empty for accepted data / 验证失败列表副本，接受数据时为空
     * @param businessDigest optional author validation digest / 可选的作者验证摘要
     */
    public ValidationReport {
        violations = List.copyOf(violations);
        java.util.Objects.requireNonNull(businessDigest, "businessDigest");
        if (valid && !violations.isEmpty())
            throw new IllegalArgumentException("Valid report contains violations");
    }
}
