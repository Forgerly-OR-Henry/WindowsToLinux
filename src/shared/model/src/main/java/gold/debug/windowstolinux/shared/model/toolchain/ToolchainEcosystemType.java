package gold.debug.windowstolinux.shared.model.toolchain;

/**
 * Version identity and release-line precision, independent of supported releases. / 与支持清单无关的生态版本身份和分支精度。
 */
public enum ToolchainEcosystemType {
    /**
     * JAVA classification within toolchain ecosystem type.
     * <p>工具链生态类型中的Java分类。
     */
    JAVA(1),
    /**
     * NODE classification within toolchain ecosystem type.
     * <p>工具链生态类型中的节点分类。
     */
     NODE(1),
    /**
     * PYTHON classification within toolchain ecosystem type.
     * <p>工具链生态类型中的PYTHON分类。
     */
     PYTHON(2),
    /**
     * DOTNET classification within toolchain ecosystem type.
     * <p>工具链生态类型中的DOTNET分类。
     */
     DOTNET(1),
    /**
     * KOTLIN classification within toolchain ecosystem type.
     * <p>工具链生态类型中的KOTLIN分类。
     */
     KOTLIN(2),
    /**
     * GO classification within toolchain ecosystem type.
     * <p>工具链生态类型中的GO分类。
     */
     GO(2),
    /**
     * RUST classification within toolchain ecosystem type.
     * <p>工具链生态类型中的RUST分类。
     */
     RUST(2),
    /**
     * PHP classification within toolchain ecosystem type.
     * <p>工具链生态类型中的PHP分类。
     */
     PHP(2),
    /**
     * RUBY classification within toolchain ecosystem type.
     * <p>工具链生态类型中的Ruby分类。
     */
     RUBY(2),
    /**
     * C classification within toolchain ecosystem type.
     * <p>工具链生态类型中的C分类。
     */
    C(1),
    /**
     * CPP classification within toolchain ecosystem type.
     * <p>工具链生态类型中的CPP分类。
     */
     CPP(1);

    /**
     * Branch segments.
     * <p>分支Segments。
     */
    private final int branchSegments;
    /**
     * Binds the supplied dependencies and state for toolchain ecosystem type.
     * <p>为工具链生态类型绑定传入的依赖及状态。
     *
     * @param branchSegments branch segments / 分支Segments
     */
    ToolchainEcosystemType(int branchSegments) { this.branchSegments = branchSegments; }
    /**
     * Returns branch segments.
     * <p>返回分支Segments。
     *
     * @return branch segments / 分支Segments
     */
    public int branchSegments() { return branchSegments; }
}
