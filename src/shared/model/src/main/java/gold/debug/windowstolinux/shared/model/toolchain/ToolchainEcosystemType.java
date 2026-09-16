package gold.debug.windowstolinux.shared.model.toolchain;

/** Version identity and release-line precision, independent of supported releases. / 与支持清单无关的生态版本身份和分支精度。 */
public enum ToolchainEcosystemType {
    JAVA(1), NODE(1), PYTHON(2), DOTNET(1), KOTLIN(2), GO(2), RUST(2), PHP(2), RUBY(2),
    C(1), CPP(1);

    private final int branchSegments;
    ToolchainEcosystemType(int branchSegments) { this.branchSegments = branchSegments; }
    public int branchSegments() { return branchSegments; }
}
