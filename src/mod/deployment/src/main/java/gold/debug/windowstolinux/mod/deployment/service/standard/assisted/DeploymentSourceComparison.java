package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import gold.debug.windowstolinux.mod.deployment.ai.skill.DeploymentSkillResources;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.json.DeploymentJsonFields;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.ApplicationSpec;

/** Independent bounded AI scan followed by an explicit static-result comparison. / 有界独立 AI 来源扫描及与确定性结果的显式比较。 */
public final class DeploymentSourceComparison {

    /** Read-only component ownership comparison instruction. / 只读组件归属比较指令。 */
    private static final String COMPONENT_POLICY = DeploymentSkillResources.text(DeploymentSourceComparison.class, "component-ownership.md");

    /** Read-only independent source tool instruction. / 只读独立来源工具指令。 */
    private static final String POLICY = DeploymentSkillResources.text(DeploymentSourceComparison.class, "source-identification.md") + COMPONENT_POLICY;

    /** Bundled fixed flow instruction. / 随包固定流程说明。 */
    private static final String CALIBRATION = DeploymentSkillResources.text(DeploymentSourceComparison.class, "source-calibration.md") + COMPONENT_POLICY;

    /** Binds only the original inputs and capabilities owned by this adapter. / 仅绑定当前适配所属的原输入及能力。 */
    private DeploymentSourceComparison() {
    }

    /** Compares independently observed source units with deterministic discovery. / 比较独立观测的来源单元与确定性发现。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param server exact original target server / 精确原目标服务器
     * @param source original frozen source evidence / 原冻结来源依据
     * @param recognized deterministically recognized source components / 确定性识别来源组件
     * @param task original admitted task / 原已准入任务
     * @return components selected from the verified source comparison / 从已核实来源比较选择的组件
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public static List<DiscoveredProjectPart> compare(ModContext context, ServerId server,
                                                      DeploymentSourceBrowser source, List<DiscoveredProjectPart> recognized, TaskContext task) throws Exception {
        var revision = context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
        var selection = new gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentNativeSelection(context);
        var observations = new ArrayList<Map<String, Object>>();
        var read = new HashSet<String>();
        var queries = new HashSet<String>();
        var types = Arrays.stream(DeploymentProjectKind.values()).filter(DeploymentProjectKind::deployable)
            .map(Enum::name).toList();
        for (int turn = 0; turn < 12; turn++) {
            if (task.cancelToken().requested()) throw new java.util.concurrent.CancellationException();
            source.verifyBoundaries();
            gold.debug.windowstolinux.appapi.ai.model.AiToolCall call;
            try {
                call = selection.select(POLICY, Map.of("sourceRevision", source.revision(),
                    "projectTypes", types, "observations", observations, "readPaths", read.stream().sorted().toList(), "remaining", 12 - turn), revision, task, false);
            } catch (
                gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentNativeSelection.RejectedBatchException rejected) {
                observations.add(Map.of("feedback", "INVALID_BATCH", "message", rejected.getMessage()));
                continue;
            }
            var args = DeploymentAiJson.arguments(call);
            String tool = call.name();
            if (tool.equals("identify")) {
                var observed = identify(args, read, observations, tool, types, source);
                if (observed.isEmpty()) continue;
                var identified = observed.orElseThrow();
                source.verify();
                boolean matches = matches(recognized, identified);
                task.progress("AI_SOURCE_COMPARISON " + (matches ? "MATCH" : "MISMATCH") + " static="
                    + recognized + " ai=" + identified, Optional.empty());
                if (matches) return recognized;
                var calibrated = calibrate(context, source, recognized, List.copyOf(identified), observations, read, revision, task);
                if (calibrated.isPresent()) return calibrated.orElseThrow();
                var answer = context.interaction().requestText(
                    gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.text(context, "source.comparisonTitle"),
                    gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.text(context, "source.comparisonPrompt", recognized, identified), false, task);
                if (answer.outcome() != TaskResult.SUCCEEDED)
                    throw new DeploymentAiJson.CapabilityOutcomeException(answer.outcome(), answer.problem());
                return switch (answer.value().orElseThrow().strip().toUpperCase(Locale.ROOT)) {
                    case "STATIC" -> recognized;
                    case "AI" -> List.copyOf(identified);
                    default -> throw new IllegalArgumentException("Identification choice must be STATIC or AI");
                };
            }
            if (tool.equals("unable")) throw new IOException("AI source identification unavailable");
            if (!Set.of("list", "read", "search").contains(tool))
                throw new SecurityException("Unsupported identification tool");
            if (!validQueryArguments(args, tool, observations)) continue;
            String path = DeploymentJsonFields.text(args, tool.equals("search") ? "query" : "path", 500);
            int offset;
            int limit;
            try {
                offset = DeploymentJsonFields.number(args, "offset", 0, 1000000);
                limit = DeploymentJsonFields.number(args, "limit", 1, 100);
            } catch (IllegalArgumentException invalidPage) {
                observations.add(invalidPageFeedback(tool));
                continue;
            }
            if (!queries.add(tool + ":" + path + ":" + offset + ":" + limit)) {
                observations.add(Map.of("tool", tool, "feedback", "DUPLICATE_QUERY"));
                continue;
            }
            var queried = query(source, tool, path, offset, limit, observations);
            if (queried.isEmpty()) continue;
            var lines = queried.orElseThrow();
            if (tool.equals("read") && lines.stream().anyMatch(s -> !s.contains("[credential content excluded]")))
                read.add(path);
            observations.add(Map.of("tool", tool, "path", path, "output", lines));
            if (observations.toString().length() > 48000)
                throw new IOException("Identification evidence budget exhausted");
        }
        throw new IOException("AI identification decision budget exhausted");
    }

    /** Checks exact normalized source ownership and project classification. / 核对精确规范化来源归属及项目分类。
     * @param recognized deterministically recognized source components / 确定性识别来源组件
     * @param identified independently identified source components / 独立识别来源组件
     * @return whether normalized component roots and types agree / 规范化组件根及类型是否一致 */
    static boolean matches(List<DiscoveredProjectPart> recognized, List<DiscoveredProjectPart> identified) {
        if (recognized.size() != identified.size()) return false;
        return identified.stream().allMatch(ai -> recognized.stream().anyMatch(st ->
            st.relativeRoot().normalize().equals(ai.relativeRoot().normalize())
                && new HashSet<>(st.types()).equals(new HashSet<>(ai.types()))));
    }

    /** Requires an explicit comparison of conflicting independently observed source evidence. / 要求对冲突的独立来源依据进行显式比较。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param source original frozen source evidence / 原冻结来源依据
     * @param recognized deterministically recognized source components / 确定性识别来源组件
     * @param identified independently identified source components / 独立识别来源组件
     * @param observations bounded verified source tool history / 有界已核实来源工具历史
     * @param read source paths actually read by this task / 本任务实际读取的来源路径
     * @param revision captured original model revision / 已捕获原模型修订
     * @param task original admitted task / 原已准入任务
     * @return evidence-resolved component choice or unresolved result / 依据解决的组件选择或未解决结果
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    private static Optional<List<DiscoveredProjectPart>> calibrate(ModContext context, DeploymentSourceBrowser source,
                                                                   List<DiscoveredProjectPart> recognized, List<DiscoveredProjectPart> identified,
                                                                   List<Map<String, Object>> observations, Set<String> read, String revision, TaskContext task) throws Exception {
        var selection = new gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentNativeSelection(context);
        String policy = CALIBRATION;
        var history = new ArrayList<>(observations);
        var declarations = source.containsFile(ApplicationSpec.FILE) ? List.of(ApplicationSpec.FILE) : List.<String>of();
        for (int turn = 0; turn < 6; turn++) {
            if (task.cancelToken().requested()) throw new java.util.concurrent.CancellationException();
            source.verifyBoundaries();
            var unreviewed = declarations.stream().filter(path -> !read.contains(path)).toList();
            gold.debug.windowstolinux.appapi.ai.model.AiToolCall call;
            try {
                call = selection.select(policy, Map.of("static", componentFacts(recognized),
                    "independentAi", componentFacts(identified), "unreviewedDeclarations", unreviewed,
                    "observations", history, "readPaths", read.stream().sorted().toList(), "remaining", 6 - turn), revision, task, true);
            } catch (
                gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentNativeSelection.RejectedBatchException rejected) {
                history.add(Map.of("feedback", "INVALID_BATCH", "message", rejected.getMessage()));
                continue;
            }
            String tool = call.name();
            var args = DeploymentAiJson.arguments(call);
            if (tool.equals("conclude")) {
                DeploymentJsonFields.exact(args, Set.of("decision", "reason", "evidence"));
                String decision = DeploymentJsonFields.text(args, "decision", 16);
                String reason = DeploymentJsonFields.text(args, "reason", 2000);
                if (!Set.of("STATIC", "AI", "UNRESOLVED").contains(decision))
                    throw new IllegalArgumentException("Invalid identification calibration decision");
                if (!validEvidence(args.get("evidence"), read, tool, history, !decision.equals("UNRESOLVED")))
                    continue;
                if (!decision.equals("UNRESOLVED") && !unreviewed.isEmpty()) {
                    history.add(Map.of("tool", tool, "feedback", "CALIBRATION_EVIDENCE_INCOMPLETE",
                        "requiredReadPaths", unreviewed, "message", "Determination discarded: existing application ownership metadata has not been read. Read these paths and reassess both results, or conclude UNRESOLVED; the original six-turn budget is unchanged."));
                    continue;
                }
                source.verify();
                task.progress("AI_SOURCE_CALIBRATION " + decision + " " + reason, Optional.empty());
                return switch (decision) {
                    case "STATIC" -> Optional.of(recognized);
                    case "AI" -> Optional.of(identified);
                    default -> Optional.empty();
                };
            }
            if (!Set.of("list", "read", "search").contains(tool))
                throw new SecurityException("Unsupported calibration tool");
            if (!validQueryArguments(args, tool, history)) continue;
            String path = DeploymentJsonFields.text(args, tool.equals("search") ? "query" : "path", 500);
            int offset;
            int limit;
            try {
                offset = DeploymentJsonFields.number(args, "offset", 0, 1000000);
                limit = DeploymentJsonFields.number(args, "limit", 1, 100);
            } catch (IllegalArgumentException invalidPage) {
                history.add(invalidPageFeedback(tool));
                continue;
            }
            var queried = query(source, tool, path, offset, limit, history);
            if (queried.isEmpty()) continue;
            var lines = queried.orElseThrow();
            if (tool.equals("read") && lines.stream().anyMatch(s -> !s.contains("[credential content excluded]")))
                read.add(path);
            history.add(Map.of("tool", tool, "path", path, "output", lines));
            if (history.toString().length() > 48000) return Optional.empty();
        }
        source.verify();
        task.progress("AI_SOURCE_CALIBRATION UNRESOLVED budget exhausted", Optional.empty());
        return Optional.empty();
    }

    /** Reads bounded protocol evidence for the original operation. / 读取原操作的有界协议依据。
     * @param source original frozen source evidence / 原冻结来源依据
     * @param tool offered source or publication tool name / 可用来源或发布工具名称
     * @param path original path bound to this operation / 当前操作绑定的原path值
     * @param offset original offset bound to this operation / 当前操作绑定的原offset值
     * @param limit original limit bound to this operation / 当前操作绑定的原limit值
     * @param observations bounded verified source tool history / 有界已核实来源工具历史
     * @return bounded original operation protocol response / 有界原操作协议响应
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    private static Optional<List<String>> query(DeploymentSourceBrowser source, String tool, String path,
                                                int offset, int limit, List<Map<String, Object>> observations) throws Exception {
        try {
            return Optional.of(switch (tool) {
                case "list" -> source.list(path.equals(".") ? "" : path, offset, limit);
                case "read" -> source.read(path, offset, limit);
                default -> source.search(path, offset, limit);
            });
        } catch (DeploymentSourceBrowser.MissingMemberException missing) {
            observations.add(Map.of("tool", tool, "path", path, "feedback", "FILE_NOT_FOUND", "message", missing.getMessage()));
            return Optional.empty();
        }
    }

    /** Builds bounded source-component facts for independent comparison. / 为独立比较建立有界来源组件事实。
     * @param parts original parts bound to this operation / 当前操作绑定的原parts值
     * @return bounded normalized component observations / 有界规范化组件观测 */
    private static List<Map<String, Object>> componentFacts(List<DiscoveredProjectPart> parts) {
        return parts.stream().map(part -> {
            String root = part.relativeRoot().normalize().toString().replace('\\', '/');
            return Map.<String, Object>of("id", part.id(), "root", root.isEmpty() ? "." : root,
                "projectTypes", part.types().stream().map(Enum::name).toList());
        }).toList();
    }

    /** Returns bounded correction guidance for unread source page arguments. / 为尚未读取的来源分页参数返回有界纠正反馈。
     * @param tool offered source or publication tool name / 可用来源或发布工具名称
     * @return correction feedback without a source read / 不含来源读取的纠正反馈 */
    private static Map<String, Object> invalidPageFeedback(String tool) {
        return Map.of("tool", tool, "feedback", "INVALID_PAGE", "message",
            "Request discarded without reading source. offset must be an integer in 0..1000000 and limit in 1..100. Use successive pages for larger reads.");
    }

    /** Checks that every cited source file was actually read in this task. / 核对所有引用来源文件确由本任务读取。
     * @param evidence original evidence bound to this operation / 当前操作绑定的原evidence值
     * @param read source paths actually read by this task / 本任务实际读取的来源路径
     * @param tool offered source or publication tool name / 可用来源或发布工具名称
     * @param observations bounded verified source tool history / 有界已核实来源工具历史
     * @param required original required bound to this operation / 当前操作绑定的原required值
     * @return whether every cited source was actually read / 所有引用来源是否确已读取 */
    private static boolean validEvidence(com.fasterxml.jackson.databind.JsonNode evidence, Set<String> read,
                                         String tool, List<Map<String, Object>> observations, boolean required) {
        if (evidence == null || !evidence.isArray() || evidence.size() > 8 || required && evidence.isEmpty())
            throw new IllegalArgumentException("Observed source evidence required");
        boolean unread = false;
        for (var cite : evidence) {
            if (!cite.isTextual()) throw new SecurityException("Source evidence must name an observed file");
            if (!read.contains(cite.textValue())) unread = true;
        }
        if (!unread) return true;
        observations.add(Map.of("tool", tool, "feedback", "UNREAD_EVIDENCE", "readPaths", read.stream().sorted().toList(),
            "message", "Entire conclusion discarded without accepting evidence or reading source. Read the cited files successfully and resubmit, or cite only readPaths. Correction consumes remaining turns."));
        return false;
    }

    /** Validates read-only source tool arguments before any source read. / 在读取来源前校验只读来源工具参数。
     * @param args strict tool argument object / 严格工具参数对象
     * @param tool offered source or publication tool name / 可用来源或发布工具名称
     * @param observations bounded verified source tool history / 有界已核实来源工具历史
     * @return whether exact tool arguments are valid / 精确工具参数是否有效 */
    private static boolean validQueryArguments(com.fasterxml.jackson.databind.JsonNode args, String tool,
                                               List<Map<String, Object>> observations) {
        var keys = Set.of(tool.equals("search") ? "query" : "path", "offset", "limit");
        try {
            DeploymentJsonFields.exact(args, keys);
            return true;
        } catch (IllegalArgumentException invalidArguments) {
            observations.add(Map.of("tool", tool, "feedback", "INVALID_ARGUMENTS", "requiredKeys", keys,
                "message", "Request discarded without reading source. Resubmit exactly these argument keys; offset 0..1000000, limit 1..100."));
            return false;
        }
    }

    /** Validates independently identified components against actually read source evidence. / 按实际读取来源依据校验独立识别组件。
     * @param args original identification fields / 原识别字段
     * @param read actually read source paths / 实际读取来源路径
     * @param observations original bounded evidence history / 原有界依据历史
     * @param tool original identification tool / 原识别工具
     * @param types supported deployment types / 支持的部署类型
     * @param source original frozen source browser / 原冻结来源浏览器
     * @return complete identification or unread-evidence correction / 完整识别或未读依据纠正
     * @throws Exception if original source or component validation fails / 原来源或组件校验失败时 */
    private static Optional<List<DiscoveredProjectPart>> identify(com.fasterxml.jackson.databind.JsonNode args,
                                                                  Set<String> read, List<Map<String, Object>> observations, String tool, List<String> types,
                                                                  DeploymentSourceBrowser source) throws Exception {
        DeploymentJsonFields.exact(args, Set.of("components"));
        var components = args.get("components");
        if (components == null || !components.isArray() || components.isEmpty() || components.size() > 16)
            throw new IllegalArgumentException("AI identification requires 1..16 components");
        boolean evidenceValid = true;
        for (var item : components) {
            DeploymentJsonFields.exact(item, Set.of("id", "root", "type", "evidence"));
            if (!validEvidence(item.get("evidence"), read, tool, observations, true)) {
                evidenceValid = false;
                break;
            }
        }
        if (!evidenceValid) return Optional.empty();
        var identified = new ArrayList<DiscoveredProjectPart>();
        var ids = new HashSet<String>();
        var roots = new HashSet<String>();
        for (var item : components) {
            String id = DeploymentJsonFields.text(item, "id", 63);
            String root = DeploymentJsonFields.text(item, "root", 500);
            String type = DeploymentJsonFields.text(item, "type", 64);
            if (!id.matches("[a-z0-9][a-z0-9-]{0,62}") || !ids.add(id) || !roots.add(root)
                || !types.contains(type)) throw new IllegalArgumentException("Invalid AI component");
            source.list(root.equals(".") ? "" : root, 0, 1);
            identified.add(new DiscoveredProjectPart(id, Path.of(root.equals(".") ? "" : root),
                List.of(DeploymentProjectKind.valueOf(type))));
        }
        return Optional.of(List.copyOf(identified));
    }
}
