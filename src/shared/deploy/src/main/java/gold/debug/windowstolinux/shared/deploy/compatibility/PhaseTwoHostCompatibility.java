package gold.debug.windowstolinux.shared.deploy.compatibility;

import gold.debug.windowstolinux.shared.deploy.plan.PhaseTwoRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.server.PhaseTwoLinuxCapabilities;
import gold.debug.windowstolinux.shared.model.server.PhaseTwoLinuxDistro;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Evaluates only collected host facts; it does not connect, install packages, or claim runtime acceptance.
 *
 * <p>仅评估已采集的主机事实；不连接、不安装软件包，也不声明运行环境验收。
 */
public final class PhaseTwoHostCompatibility {
    private PhaseTwoHostCompatibility() {
    }

    /**
     * Evaluates the selected runtime against live host facts.
     *
     * <p>根据实时主机事实评估所选运行时。
     *
     * @param capabilities collected host facts / 已采集的主机事实
     * @param runtime selected typed runtime / 所选类型化运行时
     * @return conservative compatibility outcome / 保守兼容性结果
     */
    public static Result evaluate(PhaseTwoLinuxCapabilities capabilities, PhaseTwoRuntimeSpecification runtime) {
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
        runtime = Objects.requireNonNull(runtime, "runtime");
        List<String> evidence = new ArrayList<>();
        if (!capabilities.x86_64()) {
            return result(PhaseTwoHostSupport.UNSUPPORTED, "architecture must be x86_64", evidence);
        }
        if (!capabilities.systemdAvailable()) {
            return result(PhaseTwoHostSupport.UNSUPPORTED, "systemd is required for managed lifecycle", evidence);
        }
        PhaseTwoHostSupport base = baseSupport(capabilities, evidence);
        if (base != PhaseTwoHostSupport.READY_FOR_RUNTIME_VALIDATION) {
            return new Result(base, List.copyOf(evidence));
        }
        if (runtime instanceof PhaseTwoRuntimeSpecification.Container container) {
            if (container.engine() == PhaseTwoRuntimeSpecification.ContainerEngine.DOCKER && !capabilities.dockerAvailable()) {
                return result(PhaseTwoHostSupport.UNSUPPORTED, "Docker client is not available", evidence);
            }
            if (container.engine() == PhaseTwoRuntimeSpecification.ContainerEngine.PODMAN
                    && (!capabilities.podmanAvailable() || !capabilities.podmanQuadletAvailable())) {
                return result(PhaseTwoHostSupport.UNSUPPORTED, "Podman Quadlet is required for managed Podman autostart", evidence);
            }
        }
        evidence.add("runtime matrix matches collected host facts only");
        return new Result(PhaseTwoHostSupport.READY_FOR_RUNTIME_VALIDATION, List.copyOf(evidence));
    }

    private static PhaseTwoHostSupport baseSupport(PhaseTwoLinuxCapabilities capabilities, List<String> evidence) {
        return switch (capabilities.distro()) {
            case UBUNTU -> {
                if (!"apt".equals(capabilities.packageManager())
                        || !("22.04".equals(capabilities.version()) || "24.04".equals(capabilities.version()))) {
                    yield result(PhaseTwoHostSupport.UNSUPPORTED, "Ubuntu must be 22.04 or 24.04 with apt", evidence).support();
                }
                yield PhaseTwoHostSupport.READY_FOR_RUNTIME_VALIDATION;
            }
            case CENTOS_STREAM -> {
                if (!"dnf".equals(capabilities.packageManager())
                        || !("9".equals(capabilities.version()) || "10".equals(capabilities.version()))) {
                    yield result(PhaseTwoHostSupport.UNSUPPORTED, "CentOS Stream must be 9 or 10 with dnf", evidence).support();
                }
                if ("10".equals(capabilities.version()) && capabilities.cpuFlags().isEmpty()) {
                    evidence.add("CentOS Stream 10 CPU flags were not collected");
                    yield PhaseTwoHostSupport.REQUIRES_CPU_REVIEW;
                }
                if ("10".equals(capabilities.version())) {
                    evidence.add("CentOS Stream 10 CPU flags are host-specific and require review before execution");
                    yield PhaseTwoHostSupport.REQUIRES_CPU_REVIEW;
                }
                yield PhaseTwoHostSupport.READY_FOR_RUNTIME_VALIDATION;
            }
            case LEGACY_CENTOS -> {
                evidence.add("discontinued CentOS requires explicit maintenance and repository-risk acknowledgement");
                yield PhaseTwoHostSupport.LEGACY_RISK_CONFIRMATION_REQUIRED;
            }
            case OTHER -> result(PhaseTwoHostSupport.UNSUPPORTED, "distribution is outside the Phase Two matrix", evidence).support();
        };
    }

    private static Result result(PhaseTwoHostSupport support, String detail, List<String> evidence) {
        evidence.add(detail);
        return new Result(support, List.copyOf(evidence));
    }

    /**
     * A structured decision with no live-state assertion.
     *
     * <p>不包含实时状态断言的结构化决策。
     *
     * @param support conservative matrix status / 保守矩阵状态
     * @param evidence decision evidence / 决策证据
     */
    public record Result(PhaseTwoHostSupport support, List<String> evidence) {
        /** Creates a {@code Result} instance. / 创建 {@code Result} 实例。 */
        public Result {
            support = Objects.requireNonNull(support, "support");
            evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        }
    }
}
