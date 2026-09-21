package gold.debug.windowstolinux.shared.analyze.ecosystem.rust.cargo;

import java.util.List;

/**
 * Fixed Cargo metadata used by Rust service analysis. / Rust 服务分析使用的固定 Cargo 元数据。
 *
 * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
 * @param crateName crate name / crate名称
 * @param missingFiles missing files / 缺失文件集合
 */
record RustCargoFacts(String version, String crateName, List<String> missingFiles) {
    /**
     * Binds the supplied dependencies and state for rust cargo facts.
     * <p>为RustCargo事实绑定传入的依赖及状态。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param crateName crate name / crate名称
     * @param missingFiles missing files / 缺失文件集合
     */
    RustCargoFacts {
        missingFiles = List.copyOf(missingFiles);
    }
}
