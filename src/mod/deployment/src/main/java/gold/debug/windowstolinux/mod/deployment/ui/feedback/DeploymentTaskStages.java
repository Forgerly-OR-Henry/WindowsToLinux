package gold.debug.windowstolinux.mod.deployment.ui.feedback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

/** Projects known workflow messages and retains arbitrary output in details. / 投影已知流程文案，任意输出保留在详情中。 */
final class DeploymentTaskStages {
    /** One fixed workflow stage and its optional estimate. / 固定流程阶段及其可选估算。
     * @param key original message identity / 原文案标识
     * @param message plain-language presentation identity / 通俗展示标识
     * @param estimate phase percentage or negative unknown / 阶段百分比或负数未知值 */
    record Stage(String key, String message, int estimate) {
    }

    /** Explicit workflow catalog rather than arbitrary task text. / 使用明确流程目录而非任意任务文本。 */
    private static final Map<String, Stage> STAGES = stages();

    /** Templates from the actual workflow language bundles. / 来自实际流程语言资源的模板。 */
    private static final List<Map.Entry<Pattern, Stage>> PATTERNS = patterns();

    /** Prevents instances of the pure stage mapper. / 禁止创建纯阶段映射器实例。 */
    private DeploymentTaskStages() {
    }

    /** Identifies fixed keys and localized templates without displaying raw suffixes. / 识别固定键及本地化模板，不展示原始后缀。
     * @param text actual reported progress / 实际报告进度
     * @return known stage or empty / 已知阶段或空值 */
    static Optional<Stage> identify(String text) {
        if (STAGES.containsKey(text)) return Optional.of(STAGES.get(text));
        return PATTERNS.stream().filter(entry -> entry.getKey().matcher(text).matches())
            .map(Map.Entry::getValue).findFirst();
    }

    /** Enumerates known workflow phases. / 枚举已知流程阶段。
     * @return immutable key catalog / 不可变键目录 */
    private static Map<String, Stage> stages() {
        var stages = new LinkedHashMap<String, Stage>();
        add(stages, "source", 8, "stage.freezeSource", "agent.stage.identify", "agent.stage.patch");
        add(stages, "revision", 30, "stage.checkSourceRevision");
        add(stages, "review", 18, "stage.reviewGraph", "agent.stage.plan", "agent.stage.analyze");
        add(stages, "archive", 25, "stage.archiveComponent", "stage.archiveComponents");
        add(stages, "prepare", 30, "stage.prepareComponent", "agent.stage.tools", "environment.stage",
            "environment.command.packages", "environment.command.prepare", "environment.command.enforce", "environment.command.commit");
        add(stages, "build", 45, "stage.buildCandidate", "stage.executeCandidateBuild", "stage.buildGraph", "stage.buildComponent", "agent.stage.build");
        add(stages, "configure", 55, "agent.stage.database", "agent.stage.reference", "stage.configureGraphComponent");
        add(stages, "publish", 70, "stage.publishCandidate", "stage.publishGraphComponent", "stage.snapshotGraphComponent", "stage.stopGraphComponent");
        add(stages, "verify", 85, "stage.candidateHealth", "stage.graphHealth", "stage.healthGraphComponent", "agent.stage.seal");
        // These operations have no stable position in an autonomous or repeated workflow.
        add(stages, "observe", -1, "agent.stage.observe");
        add(stages, "prepared", 35, "stage.closePreparation");
        add(stages, "saved", -1, "stage.projectSaved");
        return Collections.unmodifiableMap(stages);
    }

    /** Associates original keys with one public phase. / 将原键关联到一个公开阶段。
     * @param stages catalog under construction / 构建中的目录
     * @param message public message suffix / 公开文案后缀
     * @param estimate phase percentage or negative unknown / 阶段百分比或负数未知值
     * @param keys fixed original workflow keys / 固定原流程键 */
    private static void add(Map<String, Stage> stages, String message, int estimate, String... keys) {
        for (String key : keys) stages.put(key, new Stage(key, "stage." + message, estimate));
    }

    /** Compiles fixed templates without trusting arbitrary output. / 编译固定模板，不信任任意输出。
     * @return fixed template recognizers / 固定模板识别器 */
    private static List<Map.Entry<Pattern, Stage>> patterns() {
        var patterns = new ArrayList<Map.Entry<Pattern, Stage>>();
        for (Locale locale : List.of(Locale.ROOT, Locale.CHINESE)) {
            var messages = ResourceBundle.getBundle(
                "gold.debug.windowstolinux.mod.deployment.service.feedback.workflow-messages", locale,
                DeploymentTaskStages.class.getClassLoader(),
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
            STAGES.forEach((key, stage) -> {
                String template = messages.getString(key);
                var matcher = Pattern.compile("\\{[0-9]+}").matcher(template);
                var regex = new StringBuilder();
                int end = 0;
                while (matcher.find()) {
                    regex.append(Pattern.quote(template.substring(end, matcher.start()))).append("[^\\r\\n]*");
                    end = matcher.end();
                }
                regex.append(Pattern.quote(template.substring(end)));
                patterns.add(Map.entry(Pattern.compile(regex.toString()), stage));
            });
        }
        return List.copyOf(patterns);
    }
}
