package gold.debug.windowstolinux.shared.analyze.ecosystem.rust.cargo;

import java.util.List;

/** Fixed Cargo metadata used by Rust service analysis. / Rust 服务分析使用的固定 Cargo 元数据。 */
record RustCargoFacts(String version, String crateName, List<String> missingFiles) {
    RustCargoFacts {
        missingFiles = List.copyOf(missingFiles);
    }
}
