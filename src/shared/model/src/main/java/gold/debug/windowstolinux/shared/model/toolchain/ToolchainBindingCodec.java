package gold.debug.windowstolinux.shared.model.toolchain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Versioned, deterministic release binding without a configuration parser dependency. / 无配置解析依赖的确定性版本化发布绑定。
 */
public final class ToolchainBindingCodec {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ToolchainBindingCodec() {
    }

    /**
     * Encodes toolchain binding.
     * <p>编码工具链绑定。
     *
     * @param set set / 集合
     * @return toolchain binding / 工具链绑定
     */
    public static String encode(ResolvedToolchainSet set) {
        StringBuilder out = new StringBuilder("WTL-TOOLS-1\t" + set.catalogRevision() + "\n");
        for (var s : set.selections()) {
            var r = s.requirement();
            out.append(r.ecosystem()).append('\t').append(r.purpose()).append('\t').append(base64(r.declaration()))
                    .append('\t').append(base64(r.source())).append('\t').append(r.constraint()).append('\t')
                    .append(r.version().map(ToolchainVersion::text).orElse("-")).append('\t').append(s.version().text())
                    .append('\t').append(s.directory()).append('\t').append(s.origin()).append('\t')
                    .append(base64(s.source())).append('\t').append(s.sha256()).append('\n');
        }
        return out.toString();
    }

    /**
     * Decodes bounded toolchain bindings with exact versions and provenance, rejecting malformed or trailing data.
     * <p>解码带精确版本及来源证据的有界工具链绑定，并拒绝格式无效或尾随数据。
     *
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @return bounded toolchain bindings with exact versions and provenance, rejecting malformed or trailing data / 带精确版本及来源证据的有界工具链绑定，并拒绝格式无效或尾随数据
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public static ResolvedToolchainSet decode(String text) {
        if (text.length() > 65536 || !text.endsWith("\n"))
            throw new IllegalArgumentException("invalid binding size or framing");
        String[] lines = text.split("\n");
        String[] header = lines[0].split("\t", -1);
        if (header.length != 2 || !header[0].equals("WTL-TOOLS-1"))
            throw new IllegalArgumentException("unknown binding format");
        var selections = new ArrayList<ResolvedToolchainSet.Selection>();
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split("\t", -1);
            if (f.length != 11)
                throw new IllegalArgumentException("invalid binding field count");
            var eco = ToolchainEcosystemType.valueOf(f[0]);
            var requirement = new ToolchainRequirement(eco, unbase64(f[2]), unbase64(f[3]),
                    ToolchainRequirement.PurposeType.valueOf(f[1]), ToolchainRequirement.ConstraintType.valueOf(f[4]),
                    f[5].equals("-") ? java.util.Optional.empty() : ToolchainVersion.parse(eco, f[5]));
            selections.add(
                    new ResolvedToolchainSet.Selection(requirement, ToolchainVersion.parse(eco, f[6]).orElseThrow(),
                            f[7], ResolvedToolchainSet.OriginType.valueOf(f[8]), unbase64(f[9]), f[10]));
        }
        return new ResolvedToolchainSet(header[1], selections);
    }

    /**
     * Computes the SHA-256 identity of the stable UTF-8 toolchain binding encoding.
     * <p>计算稳定 UTF-8 工具链绑定编码的 SHA-256 身份。
     *
     * @param set set / 集合
     * @return the SHA-256 identity of the stable UTF-8 toolchain binding encoding / 稳定 UTF-8 工具链绑定编码的 SHA-256 身份
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public static String identity(ResolvedToolchainSet set) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(encode(set).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Encodes UTF-8 text as Base64.
     * <p>将 UTF-8 文本编码为 Base64。
     *
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @return uTF-8 text as Base64 / 将 UTF-8 文本编码为 Base64
     */
    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes Base64 content into UTF-8 text.
     * <p>将 Base64 内容解码为 UTF-8 文本。
     *
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @return base64 content into UTF-8 text / 将 Base64 内容解码为 UTF-8 文本
     */
    private static String unbase64(String text) {
        return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
    }
}
