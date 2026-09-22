package gold.debug.windowstolinux.shared.model.project.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared reviewed execution, exposure and delivery contract. / 共享的经审阅执行、对外服务与交付契约。
 *
 * @param mode selected operating or storage mode / 所选运行或存储模式
 * @param reviewed reviewed / 已审阅
 * @param command fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
 * @param workingDirectory working directory / 工作目录
 * @param endpoints endpoints / 端点集合
 * @param verification verification / 验证
 * @param expectedOutput expected output / 预期输出
 * @param client client / 客户端
 * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
 * @param companions companions / 配套单元集合
 * @param buildDirectory build directory / 构建目录
 * @param workers workers / 工作线程集合
 */
public record ApplicationWorkload(ExecutionMode mode, boolean reviewed, ApplicationCommand command,
        String workingDirectory, List<ApplicationEndpoint> endpoints, Optional<ApplicationCommand> verification,
        String expectedOutput, Optional<ApplicationCommand> client, List<ApplicationInput> inputs,
        List<ApplicationCompanion> companions, String buildDirectory, List<ApplicationWorker> workers) {
    /**
     * Initializes application workload through its shared constructor contract.
     * <p>通过共享构造契约初始化应用工作负载。
     *
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param reviewed reviewed / 已审阅
     * @param command fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
     * @param workingDirectory working directory / 工作目录
     * @param endpoints endpoints / 端点集合
     * @param verification verification / 验证
     * @param expectedOutput expected output / 预期输出
     * @param client client / 客户端
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @param companions companions / 配套单元集合
     * @param buildDirectory build directory / 构建目录
     */
    public ApplicationWorkload(ExecutionMode mode, boolean reviewed, ApplicationCommand command,
            String workingDirectory, List<ApplicationEndpoint> endpoints, Optional<ApplicationCommand> verification,
            String expectedOutput, Optional<ApplicationCommand> client, List<ApplicationInput> inputs,
            List<ApplicationCompanion> companions, String buildDirectory) {
        this(mode, reviewed, command, workingDirectory, endpoints, verification, expectedOutput, client, inputs,
                companions, buildDirectory, List.of());
    }

    /**
     * Initializes application workload through its shared constructor contract.
     * <p>通过共享构造契约初始化应用工作负载。
     *
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param reviewed reviewed / 已审阅
     * @param command fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
     * @param workingDirectory working directory / 工作目录
     * @param endpoints endpoints / 端点集合
     * @param verification verification / 验证
     * @param expectedOutput expected output / 预期输出
     * @param client client / 客户端
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @param companions companions / 配套单元集合
     */
    public ApplicationWorkload(ExecutionMode mode, boolean reviewed, ApplicationCommand command,
            String workingDirectory, List<ApplicationEndpoint> endpoints, Optional<ApplicationCommand> verification,
            String expectedOutput, Optional<ApplicationCommand> client, List<ApplicationInput> inputs,
            List<ApplicationCompanion> companions) {
        this(mode, reviewed, command, workingDirectory, endpoints, verification, expectedOutput, client, inputs,
                companions, "");
    }
    /**
     * Distinguishes long-running services from bounded application jobs.
     * <p>区分常驻服务及有界应用任务。
     */
    public enum ExecutionMode {
        /**
         * DAEMON classification within execution mode.
         * <p>执行模式中的守护线程分类。
         */
        DAEMON,
        /**
         * ON DEMAND classification within execution mode.
         * <p>执行模式中的对应按需分类。
         */
        ON_DEMAND
    }
    /**
     * Classifies the reviewed application workload for deployment and presentation.
     * <p>对已审阅应用工作负载进行部署及展示分类。
     */
    public enum CategoryType {
        /**
         * WEBSITE classification within category type.
         * <p>类别类型中的网站分类。
         */
        WEBSITE,
        /**
         * APP classification within category type.
         * <p>类别类型中的应用分类。
         */
        APP
    }

    /**
     * Validates and binds the inputs required by application workload.
     * <p>校验并绑定应用工作负载所需输入。
     *
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param reviewed reviewed / 已审阅
     * @param command fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
     * @param workingDirectory working directory / 工作目录
     * @param endpoints endpoints / 端点集合
     * @param verification verification / 验证
     * @param expectedOutput expected output / 预期输出
     * @param client client / 客户端
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @param companions companions / 配套单元集合
     * @param buildDirectory build directory / 构建目录
     * @param workers workers / 工作线程集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ApplicationWorkload {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(command);
        buildDirectory = ApplicationCommand.relative(buildDirectory, true);
        workingDirectory = ApplicationCommand.relative(workingDirectory, true);
        endpoints = List.copyOf(endpoints);
        inputs = List.copyOf(inputs);
        companions = List.copyOf(companions);
        workers = List.copyOf(workers);
        if (workers.size() > 8 || workers.stream().map(ApplicationWorker::id).distinct().count() != workers.size()
                || !workers.isEmpty() && mode != ExecutionMode.DAEMON)
            throw new IllegalArgumentException("workers require a daemon and at most eight unique identities");
        Objects.requireNonNull(verification);
        Objects.requireNonNull(client);
        Objects.requireNonNull(expectedOutput);
        if (endpoints.size() > 32 || inputs.size() > 32 || companions.size() > 32 || expectedOutput.length() > 4096
                || expectedOutput.indexOf('\0') >= 0 || expectedOutput.indexOf('\n') >= 0
                || expectedOutput.indexOf('\r') >= 0)
            throw new IllegalArgumentException("application declaration exceeds its bounds");
        if (mode == ExecutionMode.ON_DEMAND && verification.isEmpty())
            throw new IllegalArgumentException("on-demand applications require installation verification");
        if (mode == ExecutionMode.ON_DEMAND && !endpoints.isEmpty())
            throw new IllegalArgumentException("on-demand applications cannot publish persistent service endpoints");
        if (endpoints.stream().map(ApplicationEndpoint::id).distinct().count() != endpoints.size()
                || endpoints.stream().map(ApplicationEndpoint::portKey).distinct().count() != endpoints.size()
                || inputs.stream().map(ApplicationInput::id).distinct().count() != inputs.size()
                || companions.stream().map(ApplicationCompanion::id).distinct().count() != companions.size()
                || companions.stream().map(ApplicationCompanion::environment).distinct().count() != companions.size())
            throw new IllegalArgumentException("application resource identifiers or port bindings conflict");
        for (var input : inputs)
            for (var other : inputs) {
                if (input != other && (input.accessPath().equals(other.accessPath())
                        || input.accessPath().startsWith(other.accessPath() + "/")))
                    throw new IllegalArgumentException("external input mappings overlap");
            }
    }

    /**
     * Returns category.
     * <p>返回类别。
     *
     * @return category / 类别
     */
    public CategoryType category() {
        return endpoints.stream().anyMatch(value -> value.exposure() == ApplicationEndpoint.ExposureType.EXTERNAL)
                ? CategoryType.WEBSITE
                : CategoryType.APP;
    }

    /**
     * Reports whether the lifecycle condition holds for this contract.
     * <p>判断当前契约是否满足生命周期条件。
     *
     * @return true when lifecycle condition holds for this contract, false otherwise / 当前契约是否满足生命周期条件时为 true，否则为 false
     */
    public boolean supportsLifecycle() {
        return reviewed && mode == ExecutionMode.DAEMON;
    }

    /**
     * Builds application workload from the supplied unspecified inputs.
     * <p>根据所提供未指定输入构建应用工作负载。
     *
     * @return application workload from the supplied unspecified inputs / 根据所提供未指定输入构建应用工作负载
     */
    public static ApplicationWorkload unspecified() {
        return new ApplicationWorkload(ExecutionMode.DAEMON, false, ApplicationCommand.primary(), "", List.of(),
                Optional.empty(), "", Optional.empty(), List.of(), List.of());
    }
}
