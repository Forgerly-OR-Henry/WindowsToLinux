package gold.debug.windowstolinux.mod.deployment.api;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Nonsecret usage copied only from an exact complete active publication. / 仅从精确完整活动发布复制的无秘密用法。
 * @param application exact published identity / 精确发布身份
 * @param revisionSha256 original complete release / 原完整发布
 * @param ownershipSha256 complete ownership identity / 完整归属身份
 * @param state last verified runtime observation / 最后核实运行观察
 * @param autostart last verified automatic startup / 最后核实自动启动
 * @param components individually qualified usage / 逐项带身份的组件用法
 * @param actions applicable reviewed daemon actions / 适用的已审阅守护进程动作 */
public record DeploymentApplicationUsage(DeploymentApplicationId application, String revisionSha256,
                                         String ownershipSha256, ApplicationRunState state,
                                         ApplicationAutostartState autostart, List<Component> components,
                                         Set<DeploymentLifecycleAction> actions) {
    /** Copies only bounded complete identities and public facts. / 仅复制有界完整身份与公开事实。
     * @param application exact published identity / 精确发布身份
     * @param revisionSha256 original complete release / 原完整发布
     * @param ownershipSha256 complete ownership identity / 完整归属身份
     * @param state last verified runtime observation / 最后核实运行观察
     * @param autostart last verified automatic startup / 最后核实自动启动
     * @param components individually qualified usage / 逐项带身份的组件用法
     * @param actions applicable reviewed daemon actions / 适用的已审阅守护进程动作 */
    public DeploymentApplicationUsage {
        Objects.requireNonNull(application);
        for (String digest : List.of(revisionSha256, ownershipSha256))
            if (!digest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Complete usage identity required");
        Objects.requireNonNull(state);
        Objects.requireNonNull(autostart);
        components = List.copyOf(components);
        actions = Set.copyOf(actions);
        if (components.isEmpty() || components.size() > 64)
            throw new IllegalArgumentException("Bounded complete component usage required");
    }

    /** Reviewed execution classification; old declarations stay unverified. / 已审阅执行分类；旧声明保持未验证。 */
    public enum Mode {
        /** Reviewed daemon. / 已审阅守护进程。 */ DAEMON,
        /** Reviewed bounded job. / 已审阅有界任务。 */ ON_DEMAND,
        /** Declaration needs a fresh review. / 声明需要重新审阅。 */ UNVERIFIED
    }

    /** One component's fixed commands and legal external endpoints. / 一个组件的固定命令与合法外部端点。
     * @param id original component / 原组件
     * @param unitId original managed unit / 原受管单元
     * @param mode reviewed mode or explicit uncertainty / 已审阅模式或明确未知
     * @param state last component runtime observation / 最后组件运行观察
     * @param autostart last component automatic startup / 最后组件自动启动
     * @param command fixed helper command or absence / 固定helper命令或无值
     * @param endpoints reviewed external endpoints / 已审阅外部端点
     * @param workers original worker identities without arguments / 无参数的原工作线程身份 */
    public record Component(String id, String unitId, Mode mode, ApplicationRunState state,
                            ApplicationAutostartState autostart, Optional<String> command,
                            List<Endpoint> endpoints, List<String> workers) {
        /** Copies only public component usage. / 仅复制公开组件用法。
         * @param id original component / 原组件
         * @param unitId original managed unit / 原受管单元
         * @param mode reviewed mode / 已审阅模式
         * @param state last runtime observation / 最后运行观察
         * @param autostart last automatic startup / 最后自动启动
         * @param command fixed helper command / 固定helper命令
         * @param endpoints external endpoints / 外部端点
         * @param workers original worker identities / 原工作线程身份 */
        public Component {
            Objects.requireNonNull(id);
            Objects.requireNonNull(unitId);
            Objects.requireNonNull(mode);
            Objects.requireNonNull(state);
            Objects.requireNonNull(autostart);
            Objects.requireNonNull(command);
            endpoints = List.copyOf(endpoints);
            workers = List.copyOf(workers);
            if (mode == Mode.UNVERIFIED && (command.isPresent() || !endpoints.isEmpty() || !workers.isEmpty()))
                throw new IllegalArgumentException("Unreviewed usage cannot expose commands or endpoints");
        }
    }

    /** One credential-free endpoint; only HTTP(S) has a browser target. / 一项无凭据端点；仅HTTP(S)有浏览器目标。
     * @param id original endpoint identity / 原端点身份
     * @param protocol reviewed transport / 已审阅协议
     * @param value exact copyable endpoint / 精确可复制端点
     * @param browser independently validated HTTP(S) target / 独立校验的HTTP(S)目标 */
    public record Endpoint(String id, String protocol, String value, Optional<URI> browser) {
        /** Retains copied public endpoint values. / 保留复制的公开端点值。
         * @param id original endpoint identity / 原端点身份
         * @param protocol reviewed transport / 已审阅协议
         * @param value copyable endpoint / 可复制端点
         * @param browser validated browser target / 已校验浏览器目标 */
        public Endpoint {
            Objects.requireNonNull(id);
            Objects.requireNonNull(protocol);
            Objects.requireNonNull(value);
            Objects.requireNonNull(browser);
            if (!Set.of("http", "https", "tcp", "udp").contains(protocol) || value.length() > 4096)
                throw new IllegalArgumentException("Bounded reviewed endpoint required");
            if (browser.isPresent()) {
                var target = browser.orElseThrow();
                if (target.getScheme() == null || !Set.of("http", "https").contains(target.getScheme().toLowerCase(java.util.Locale.ROOT))
                    || target.getHost() == null || target.getUserInfo() != null || target.getFragment() != null
                    || target.getPort() == 0 || target.getPort() > 65535 || !target.toString().equals(value)
                    || !Set.of("http", "https").contains(protocol))
                    throw new IllegalArgumentException("Only legal credential-free HTTP(S) browser targets are allowed");
            }
        }
    }
}
