package gold.debug.windowstolinux.mod.deployment.service.agent.protocol;

import java.util.*;

/** Actual observations and independent findings outlive rolling transcripts. / 实际观察和独立发现独立于滚动会话。
 * This state is not an approval or an execution receipt. / 此状态不是审批或执行回执。 */
public final class DeploymentAgentEvidence {
    private final int maxObservations;

    public DeploymentAgentEvidence() {
        this(gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().execution().maxObservations());
    }

    public DeploymentAgentEvidence(int maxObservations) {
        if (maxObservations < 1 || maxObservations > 999999)
            throw new IllegalArgumentException("Observation capacity exceeds the original ID bound");
        this.maxObservations = maxObservations;
    }

    /** Identity metadata for one actual observation / 一项实际观察的身份元数据。
     * @param component Selected component identity / 选定组件身份
     * @param revision Exact current revision / 精确当前修订
     * @param plan Current plan revision / 当前计划修订
     * @param kind Actual observation kind / 实际观察类别
     * @param path Source-relative member / 来源相对成员
     */
    private record Observation(String component, String revision, long plan, String kind, String path) {
    }

    /** Actual identity-bound observations / 实际身份绑定观察。
     */
    private final Map<String, Observation> observations = new LinkedHashMap<>();

    /** Independent current-source findings / 独立当前来源发现。
     */
    private final Map<String, DeploymentAgentIdentification> findings = new TreeMap<>();

    /** Source identities of independent findings / 独立发现的来源身份。
     */
    private final Map<String, String> findingRevisions = new HashMap<>();

    /** Latest public decisions by action / 各动作最近公开判断。
     */
    private final Map<String, DeploymentAgentAssessment> decisions = new TreeMap<>();

    /** Observed results requiring evaluation / 须评估的已观察结果。
     */
    private final Set<String> pending = new LinkedHashSet<>();

    /** Retained redacted feedback / 留存脱敏反馈。
     */
    private final Map<String, String> outputs = new LinkedHashMap<>();

    private record Page(String path, int offset, int limit, boolean truncated) {
    }

    private final Map<String, Page> pages = new HashMap<>();

    private record LinePage(int offset, int totalLines, int nextLine, boolean hasMore) {
    }

    private final Map<String, LinePage> linePages = new HashMap<>();

    private final Map<String, Integer> lengths = new HashMap<>();

    /** Task-local observation sequence / 任务内观察序号。
     */
    private int sequence;

    /** Assigns an ID only after an actual observation. / 只在真实观察后分配标识。
     * @param component component or empty original source / 组件或空原来源
     * @param revision exact source revision / 精确来源修订
     * @param plan current plan revision / 当前计划修订
     * @param kind observed capability / 已观察能力
     * @param path actually read member or empty / 实际读取成员或空
     * @param critical result requiring evaluation / 须评估的结果
     * @return stable task-local ID / 稳定任务内标识 */
    public String observe(String component, String revision, long plan, String kind, String path, boolean critical) {
        if (observations.size() >= maxObservations)
            throw new IllegalStateException("Agent observation capacity exhausted");
        String id = "o" + ++sequence;
        observations.put(id, new Observation(component, revision, plan, kind, path));
        if (critical) pending.add(id);
        return id;
    }

    /** Keeps only independent current-source findings. / 只保留当前来源的独立判断。
     * @param values model findings / 模型发现
     * @param revisions current source revisions / 当前来源修订
     * @param original original snapshot identity / 原快照身份 */
    public void identify(List<DeploymentAgentIdentification> values, Map<String, String> revisions, String original) {
        if (values.toString().length() > 16000)
            throw new IllegalArgumentException("Independent identification summary exceeds budget");
        var combined = new TreeMap<>(findings);
        values.forEach(v -> combined.put(v.component(), v));
        if (combined.size() > 16 || combined.values().toString().length() > 16000)
            throw new IllegalArgumentException("Current independent findings exceed budget");
        var ids = new HashSet<String>();
        for (var value : values) {
            if (!ids.add(value.component())) throw new IllegalArgumentException("Duplicate identified component");
            String revision = revisions.getOrDefault(value.component(), original);
            requireReads(value.component(), value.root(), value.evidence(), revision);
        }
        values.forEach(value -> {
            findings.put(value.component(), value);
            findingRevisions.put(value.component(), revisions.getOrDefault(value.component(), original));
        });
    }

    /** Requires current identification and actual reads for a plan or tool. / 计划或工具须具有当前识别及实际读取。
     * @param component selected component / 选定组件
     * @param root selected root / 选定根
     * @param revision current revision / 当前修订 */
    public void requireIdentified(String component, String root, String revision) {
        var found = findings.get(component);
        if (found == null || !found.root().equals(root) || !revision.equals(findingRevisions.get(component)))
            throw new IllegalArgumentException("Independent current-source identification required for " + component);
    }

    /** Checks read observations, never bare paths or static-tool claims. / 检查读取观察而非路径或静态工具声明。
     * @param component component / 组件
     * @param root relative root / 相对根
     * @param ids observation IDs / 观察标识
     * @param revision current revision / 当前修订 */
    public void requireReads(String component, String root, List<String> ids, String revision) {
        for (String id : ids) {
            var value = observations.get(id);
            if (value == null || !value.kind().equals("READ") || !value.revision().equals(revision)
                || !(value.component().isEmpty() || value.component().equals(component))
                || !(root.equals(".") || value.path().startsWith(root + "/")))
                throw new IllegalArgumentException("Unread, stale or unrelated source evidence: " + id
                    + " (" + (value == null ? "unknown ID" : "kind=" + value.kind() + ", path=" + value.path())
                    + "; expected current original READ inside " + root + ")");
        }
    }

    /** Checks explicit declaration ownership without widening identification or plan read scopes. / 核验明确声明归属，不扩大识别或计划读取范围。
     * @param component selected component / 选定组件
     * @param root original component root / 原组件根
     * @param selection model-selected declaration and evidence / 模型选定声明及依据
     * @param revision exact current source revision / 精确当前来源修订
     * @throws IllegalArgumentException missing, stale or unrelated original reads / 原读取缺失、过时或无关时 */
    public void requireDatabaseSource(String component, String root, DeploymentAgentDatabaseSourceSelection selection, String revision) {
        requireReads(component, root, selection.consumptionEvidence(), revision);
        requireReads(component, ".", selection.declarationEvidence(), revision);
        boolean manifest = false;
        for (String id : selection.declarationEvidence()) {
            String path = observations.get(id).path();
            if (path.equals(selection.declarationPath())) manifest = true;
            else if (!path.endsWith(".sql") || !(selection.declarationRoot().equals(".") || path.startsWith(selection.declarationRoot() + "/")))
                throw new IllegalArgumentException("Database declaration evidence must read its exact manifest or declaration SQL members");
        }
        if (!manifest)
            throw new IllegalArgumentException("Read the exact selected database manifest before assigning it to a component");
    }

    /** Requires the next critical decision to assess actual pending results. / 下一关键决策须评估真实待处理结果。
     * @param action selected action / 选定动作
     * @param assessment model public summary / 模型公开摘要
     * @param revisions current component revisions / 当前组件修订
     * @param original original source / 原来源
     * @param plan current plan / 当前计划 */
    public void assess(String action, DeploymentAgentAssessment assessment, Map<String, String> revisions, String original, long plan) {
        if (assessment.resolution() == DeploymentAgentAssessment.ResolutionType.UNRESOLVED)
            throw new IllegalArgumentException("MODEL_ASSESSMENT_UNRESOLVED: action=" + action
                + " field=assessment.resolution submitted=UNRESOLVED; investigate the reported blocking difference for this proposed action before resubmitting");
        if (!assessment.basis().containsAll(pending))
            throw new IllegalArgumentException("Decision must evaluate pending observations " + pending);
        for (String id : assessment.basis()) {
            var value = observations.get(id);
            if (value == null || !value.revision().equals(revisions.getOrDefault(value.component(), original))
                || !value.kind().equals("READ") && value.plan() != plan)
                throw new IllegalArgumentException("Stale or unknown decision observation " + id);
        }
        decisions.put(action, assessment);
        pending.clear();
    }

    /** Invalidates stale conclusions and pending observations without deleting receipts. / 使旧结论及待处理观察失效，不删除回执。
     * @param component patched component or empty plan-only change / 补丁组件或空计划变更 */
    public void invalidate(String component) {
        pending.clear();
        decisions.clear();
        if (!component.isEmpty()) {
            findings.remove(component);
            findingRevisions.remove(component);
        }
    }

    /** Provides bounded conclusions instead of replaying tool bodies. / 提供有界结论而不重放工具正文。
     * @return current public state / 当前公开状态 */
    public Map<String, Object> state() {
        var summaries = new TreeMap<String, Object>();
        decisions.forEach((name, value) -> summaries.put(name, Map.of("basis", value.basis(),
            "suitability", brief(value.suitability()), "differences", brief(value.differences()),
            "resolution", value.resolution().name(), "decision", brief(value.decision()))));
        var facts = new TreeMap<String, Object>();
        pending.forEach(id -> {
            var value = observations.get(id);
            String body = outputs.getOrDefault(id, "");
            String summary = brief(body);
            facts.put(id, Map.of("component", value.component(), "sourceRevision", value.revision(),
                "planRevision", value.plan(), "kind", value.kind(), "summary", summary,
                "summaryTruncated", body.length() > summary.length(), "retainedCharacters", body.length()));
        });
        return Map.of("identification", List.copyOf(findings.values()), "decisions", summaries,
            "pending", List.copyOf(pending), "pendingFacts", facts);
    }

    /** Retains bounded redacted actual feedback for explicit reads. / 保留有界脱敏真实反馈以供显式读取。
     * @param id actual observation / 实际观察
     * @param output already redacted feedback / 已脱敏反馈 */
    public void output(String id, String output) {
        if (!observations.containsKey(id)) throw new IllegalArgumentException("Unknown observation");
        outputs.put(id, output.substring(0, Math.min(output.length(), 12000)));
        lengths.put(id, output.length());
    }

    public void page(String id, String path, int offset, int limit, boolean truncated) {
        if (!observations.containsKey(id)) throw new IllegalArgumentException("Unknown observation");
        pages.put(id, new Page(path, offset, limit, truncated));
    }

    /** Retains actual READ line continuation independently of retained-body character truncation. / 将真实READ行续读与留存正文字符截断分开记录。 */
    public void readPage(String id, int offset, int totalLines, int nextLine, boolean hasMore) {
        var observation = observations.get(id);
        if (observation == null || !observation.kind().equals("READ") || offset < 0 || totalLines < offset
            || nextLine < offset || nextLine > totalLines || hasMore != (nextLine < totalLines))
            throw new IllegalArgumentException("Invalid source READ line page");
        linePages.put(id, new LinePage(offset, totalLines, nextLine, hasMore));
    }

    public Map<String, Object> index(int offset, int limit) {
        if (offset < 0 || offset > maxObservations || limit < 1 || limit > 50)
            throw new IllegalArgumentException("Invalid evidence index page");
        var entries = new ArrayList<Map<String, Object>>();
        for (var entry : observations.entrySet()) {
            var id = entry.getKey();
            var value = entry.getValue();
            var page = pages.getOrDefault(id, new Page(value.path(), 0, 0, false));
            var entryFacts = new LinkedHashMap<String, Object>(Map.ofEntries(Map.entry("id", id), Map.entry("kind", value.kind()), Map.entry("component", value.component()),
                Map.entry("sourceRevision", value.revision()), Map.entry("planRevision", value.plan()), Map.entry("path", page.path()),
                Map.entry("offset", page.offset()), Map.entry("limit", page.limit()), Map.entry("retainedCharacters", outputs.getOrDefault(id, "").length()),
                Map.entry("totalCharacters", lengths.getOrDefault(id, 0)), Map.entry("truncated", page.truncated() || lengths.getOrDefault(id, 0) > 12000)));
            var linePage = linePages.get(id);
            if (linePage != null) {
                entryFacts.put("lineOffset", linePage.offset());
                entryFacts.put("totalLines", linePage.totalLines());
                entryFacts.put("nextLine", linePage.nextLine());
                entryFacts.put("hasMore", linePage.hasMore());
            }
            entries.add(Collections.unmodifiableMap(entryFacts));
        }
        int start = Math.min(offset, entries.size()), end = Math.min(start + limit, entries.size());
        return Map.of("total", entries.size(), "offset", start, "nextOffset", end, "hasMore", end < entries.size(), "entries", List.copyOf(entries.subList(start, end)));
    }

    public Map<String, Object> latestIndex() {
        return index(Math.max(0, observations.size() - 50), 50);
    }

    /** Reads original feedback without replaying its action. / 不重放动作而读取原反馈。
     * @param id actual ID / 实际标识
     * @param offset first character / 起始字符
     * @param limit page size / 页大小
     * @return identity-bound actual page / 身份绑定的真实页 */
    public String read(String id, int offset, int limit) {
        var value = observations.get(id);
        if (value == null || offset < 0 || limit < 1 || limit > 6000)
            throw new IllegalArgumentException("Invalid observation page");
        String text = outputs.getOrDefault(id, "");
        int start = Math.min(offset, text.length()), end = Math.min(start + limit, text.length());
        var linePage = linePages.get(id);
        String sourcePage = linePage == null ? "" : " lineOffset=" + linePage.offset() + " totalLines=" + linePage.totalLines()
            + " nextLine=" + linePage.nextLine() + " hasMore=" + linePage.hasMore();
        String continuation = linePage == null ? "" : "Observation offsets page characters of this retained READ body only. Only if hasMore=true, continue unread source with read or source kind=read at line offset nextLine; this query never reads the file tail.\n";
        return id + " component=" + value.component() + " source=" + value.revision() + " plan=" + value.plan()
            + " kind=" + value.kind() + " total=" + text.length() + " next=" + end + sourcePage + "\n" + continuation + text.substring(start, end);
    }

    /** Bounds public summaries without replacing retained evidence / 限制公开摘要而不取代留存依据。
     * @param value Current typed input / 当前类型化输入
     * @return checked current result / 已校验当前结果
     */
    private static String brief(String value) {
        return value.substring(0, Math.min(value.length(), 200));
    }
}
