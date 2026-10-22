package gold.debug.windowstolinux.mod.deployment.ai.agent;

import java.util.*;

import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;

import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.json.DeploymentJsonFields;

/** Bounded model-selected tool loop with verified capability feedback. / 带已核实能力反馈的有界模型工具循环。
 * @param <T> task result value type / 任务结果值类型 */
public final class DeploymentModelToolLoop<T> {
    /** Checks the owning protocol's allowance immediately before dispatch. / 发送前检查所属协议额度。 */
    @FunctionalInterface
    public interface BeforeModel {
        void check() throws Exception;
    }

    /** Projects tool output under the owning protocol's data boundary. / 按所属协议数据边界投影工具输出。 */
    @FunctionalInterface
    public interface FeedbackProjection {
        String project(String name, String output) throws Exception;
    }

    /** Optional protocol-specific decision review; it grants no execution authority. / 可选协议决策审查，不授予执行权限。 */
    public interface SelectionReview {
        /** Whether the action requires a bounded assessment. / 动作是否须有界评估。
         * @param name action / 动作
         * @return required summary / 须有摘要 */
        boolean required(String name);

        /** Validates the selected action's actual evidence. / 验证选定动作的真实依据。
         * @param name action / 动作
         * @param summary public summary / 公开摘要
         * @throws Exception invalid evidence / 无效依据 */
        void assess(String name, com.fasterxml.jackson.databind.JsonNode summary) throws Exception;

        /** Registers actual feedback after the tool has run. / 工具运行后登记真实反馈。
         * @param name action / 动作
         * @param output measured result / 实测结果
         * @return observation ID / 观察标识 */
        String observed(String name, String output);

        /** Returns current bounded evidence state. / 返回当前有界证据状态。
         * @return current state / 当前状态 */
        Map<String, Object> state();

        /** Reads retained feedback without executing its action. / 读取留存反馈，不执行原动作。 */
        String observation(String id, int offset, int limit);

        /** Pages actual observation metadata. / 分页查询真实观察元数据。 */
        Map<String, Object> evidenceIndex(int offset, int limit);
    }

    /** Task-bound execution of one prerequisite-checked tool. / 一项已核对前置条件工具的任务绑定执行。
     * @param <T> task result value type / 任务结果值类型 */
    @FunctionalInterface
    public interface Action<T> {
        /** Advances the original bounded tool session using verified feedback. / 使用已核实反馈推进原有界工具会话。
         * @param arguments validated command arguments / 已校验命令参数
         * @return verified terminal tool-loop result / 已核实终态工具循环结果
         * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
        Feedback<T> run(JsonNode arguments) throws Exception;
    }

    /** Offered tool description and its controlled task action. / 可用工具说明及其受控任务动作。
     * @param description offered tool capability and argument contract / 可用工具能力及参数合同
     * @param action controlled task action for the offered tool / 可用工具的受控任务动作
     * @param <T> task result value type / 任务结果值类型 */
    public record Tool<T>(String description, String schema, DeploymentToolScheduler.Execution execution,
                          Action<T> action) {
        public Tool(String description, Action<T> action) {
            this(description, DeploymentToolSchemas.object().toString(), DeploymentToolScheduler.Execution.EXCLUSIVE, action);
        }

        public Tool(String description, String schema, Action<T> action) {
            this(description, schema, DeploymentToolScheduler.Execution.EXCLUSIVE, action);
        }
    }

    /** Verified observation or final classification from one tool action. / 一项工具动作的已核实观测或最终分类。
     * @param observation verified feedback from the original tool / 原工具的已核实反馈
     * @param terminal known original operation terminal classification / 已知原操作终态分类
     * @param <T> task result value type / 任务结果值类型 */
    public record Feedback<T>(String observation, Optional<OperationResult<T>> terminal) {
        /** Returns nonterminal verified feedback to the original tool loop. / 向原工具循环返回非终态已核实反馈。
         * @param text canonical archived publication document / 规范归档发布文档
         * @param <T> task result value type / 任务结果值类型
         * @return verified nonterminal tool feedback / 已核实非终态工具反馈 */
        public static <T> Feedback<T> observed(String text) {
            return new Feedback<>(text, Optional.empty());
        }

        /** Returns the final classified result of the original tool loop. / 返回原工具循环的最终分类结果。
         * @param result original classified capability result / 原分类能力结果
         * @param <T> task result value type / 任务结果值类型
         * @return original terminal tool feedback / 原终态工具反馈 */
        public static <T> Feedback<T> ended(OperationResult<T> result) {
            return new Feedback<>(result.outcome() + " " + result.problem().map(p -> p.eventCode()).orElse(""), Optional.of(result));
        }
    }

    /** Original task-bound Mod capabilities. / 原任务绑定 Mod 能力。 */
    private final ModContext context;

    /** Original admitted task. / 原已准入任务。 */
    private final TaskContext task;

    /** Captured original model routing revision. / 已捕获原模型路由修订。 */
    private final String revision;

    /** Fixed tool instruction without executable authority. / 不具执行权限的固定工具指令。 */
    private final String policy;

    private final Supplier<String> selectedInstructions;

    /** Maximum remaining model decisions for the original loop. / 原循环剩余模型决策上限。 */
    private final int budget;

    /** Optional same-response decision review / 可选同响应决策审阅。
     */
    private final SelectionReview review;

    private final BeforeModel beforeModel;

    private final FeedbackProjection feedback;

    private int decisionsStarted;

    private int observedAttempts;

    /** Reports consumed decisions and attempts without changing their limits. / 只读报告已用决策及尝试，不改变原上限。 */
    public Map<String, Integer> budgetState() {
        return Map.of("maximum", budget, "decisionsStarted", decisionsStarted,
            "remainingDecisions", Math.max(0, budget - decisionsStarted), "observedAttempts", observedAttempts,
            "remainingAttempts", Math.max(0, budget - observedAttempts),
            "sharedRemainingAttempts", DeploymentToolBudget.remaining(), "maximumCallsPerResponse", 4);
    }

    /** Binds only the original inputs and capabilities owned by this adapter. / 仅绑定当前适配所属的原输入及能力。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param task original admitted task / 原已准入任务
     * @param revision captured original model revision / 已捕获原模型修订
     * @param policy fixed instruction without tool execution authority / 不具工具执行权限的固定指令
     * @param budget maximum model decisions in this original loop / 本原循环模型决策上限
     * @param beforeModel owning protocol allowance check / 所属协议额度检查
     * @param feedback owning protocol feedback projection / 所属协议反馈投影 */
    public DeploymentModelToolLoop(ModContext context, TaskContext task, String revision, String policy, int budget,
                                   BeforeModel beforeModel, FeedbackProjection feedback) {
        this(context, task, revision, policy, budget, null, () -> "", beforeModel, feedback);
    }

    /** Binds a protocol-specific assessment without another model call. / 绑定协议评估而不增加模型调用。
     * @param context original context / 原上下文
     * @param task original task / 原任务
     * @param revision model route / 模型路由
     * @param policy tool policy / 工具策略
     * @param budget remaining decisions / 剩余决策
     * @param review protocol evidence review / 协议证据审查
     * @param selectedInstructions selected current protocol instructions / 当前已选协议说明
     * @param beforeModel owning protocol allowance check / 所属协议额度检查
     * @param feedback owning protocol feedback projection / 所属协议反馈投影 */
    public DeploymentModelToolLoop(ModContext context, TaskContext task, String revision, String policy, int budget,
                                   SelectionReview review, Supplier<String> selectedInstructions,
                                   BeforeModel beforeModel, FeedbackProjection feedback) {
        this.context = Objects.requireNonNull(context);
        this.task = Objects.requireNonNull(task);
        this.revision = Objects.requireNonNull(revision);
        this.policy = Objects.requireNonNull(policy);
        this.selectedInstructions = Objects.requireNonNull(selectedInstructions);
        this.beforeModel = Objects.requireNonNull(beforeModel);
        this.feedback = Objects.requireNonNull(feedback);
        if (budget < 1 || budget > gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().execution().localToolTurns())
            throw new IllegalArgumentException("Invalid model tool budget");
        this.budget = budget;
        this.review = review;
    }

    /** Advances the original bounded tool session using verified feedback. / 使用已核实反馈推进原有界工具会话。
     * @param available original available bound to this operation / 当前操作绑定的原available值
     * @param state original state bound to this operation / 当前操作绑定的原state值
     * @return verified terminal tool-loop result / 已核实终态工具循环结果
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public OperationResult<T> run(Supplier<Map<String, Tool<T>>> available,
                                  Supplier<Map<String, Object>> state) throws Exception {
        try (var scope = DeploymentToolBudget.openStandaloneIfAbsent()) {
            return runScoped(available, state);
        }
    }

    private OperationResult<T> runScoped(Supplier<Map<String, Tool<T>>> available,
                                         Supplier<Map<String, Object>> state) throws Exception {
        var ai = new DeploymentAiJson(context);
        var history = new DeploymentNativeHistory();
        int attempts = 0;
        var callIds = new HashSet<String>();
        var terminal = new java.util.concurrent.atomic.AtomicReference<OperationResult<T>>();
        for (int turn = 0; turn < budget && attempts < budget && DeploymentToolBudget.remaining() > 0; turn++) {
            checkpoint();
            decisionsStarted = turn + 1;
            var offered = offered(available);
            var definitions = offered.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry ->
                new gold.debug.windowstolinux.appapi.ai.model.AiToolDefinition(entry.getKey(), entry.getValue().description(),
                    review != null && review.required(entry.getKey()) ? DeploymentToolSchemas.withAssessment(entry.getValue().schema()) : entry.getValue().schema())).toList();
            var current = new HashMap<String, Object>(state.get());
            if (review != null) current.put("judgement", review.state());
            String input = DeploymentAiJson.facts(Map.of("state", current, "remaining", budget - turn - 1),
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().history().characters());
            beforeModel.check();
            var response = ai.call(AiPurpose.LANGUAGE, history.messages(policy + selectedInstructions.get(), input), definitions, revision, task, false);
            if (response.toolCalls().isEmpty())
                throw new IllegalStateException("Native tool action required; text cannot finish deployment");
            var calls = response.toolCalls();
            attempts += calls.size();
            observedAttempts = attempts;
            boolean withinBudget = DeploymentToolBudget.consume(calls);
            List<gold.debug.windowstolinux.appapi.ai.model.AiToolResult> results;
            boolean duplicate = calls.stream().anyMatch(call -> !callIds.add(call.id()));
            if (calls.size() > 4 || attempts > budget || !withinBudget || duplicate || !response.finishReason().equals("tool_calls")) {
                results = calls.stream().map(call -> new gold.debug.windowstolinux.appapi.ai.model.AiToolResult(call.id(), "BATCH_REJECTED: call count, finish status or original attempt budget", true)).toList();
            } else {
                results = DeploymentToolScheduler.execute(calls, new DeploymentToolScheduler.Adapter() {
                    public void checkpoint() throws Exception {
                        DeploymentModelToolLoop.this.checkpoint();
                    }

                    public boolean stopped() {
                        return terminal.get() != null;
                    }

                    public DeploymentToolScheduler.Execution execution(gold.debug.windowstolinux.appapi.ai.model.AiToolCall call) {
                        var tool = offered.get(call.name());
                        return tool == null || !Set.of("source", "list", "read", "search", "observation", "plan_state", "evidence_index").contains(call.name())
                            ? DeploymentToolScheduler.Execution.EXCLUSIVE : tool.execution();
                    }

                    public DeploymentToolScheduler.Prepared prepare(gold.debug.windowstolinux.appapi.ai.model.AiToolCall call) throws Exception {
                        var tool = offered(available).get(call.name());
                        if (tool == null || !offered.containsKey(call.name()))
                            throw new IllegalArgumentException("Tool is currently unavailable");
                        if (tool.execution() != offered.get(call.name()).execution() || !tool.schema().equals(offered.get(call.name()).schema()))
                            throw new IllegalArgumentException("Tool registration changed before dispatch");
                        var args = DeploymentAiJson.arguments(call);
                        String schema = review != null && review.required(call.name()) ? DeploymentToolSchemas.withAssessment(tool.schema()) : tool.schema();
                        DeploymentToolSchemas.validate(new com.fasterxml.jackson.databind.ObjectMapper().readTree(schema), args);
                        if (review != null && review.required(call.name())) {
                            try {
                                review.assess(call.name(), args.get("assessment"));
                            } catch (java.io.IOException invalid) {
                                throw new IllegalArgumentException("Invalid assessment parameters");
                            }
                        }
                        var actionArgs = (com.fasterxml.jackson.databind.node.ObjectNode) args.deepCopy();
                        actionArgs.remove("assessment");
                        return new DeploymentToolScheduler.Prepared() {
                            public Object run() throws Exception {
                                return tool.action().run(actionArgs);
                            }

                            public gold.debug.windowstolinux.appapi.ai.model.AiToolResult commit(gold.debug.windowstolinux.appapi.ai.model.AiToolCall original, Object value) throws Exception {
                                var result = (Feedback<T>) value;
                                if (result.terminal().isPresent()) terminal.set(result.terminal().orElseThrow());
                                String output = feedback.project(original.name(), result.observation());
                                if (review != null) output = review.observed(original.name(), output) + "\n" + output;
                                task.progress("AI_TOOL_RESULT " + original.name(), Optional.empty());
                                return new gold.debug.windowstolinux.appapi.ai.model.AiToolResult(original.id(), output.substring(0, Math.min(output.length(), 6000)), false);
                            }
                        };
                    }
                });
                if (terminal.get() != null) return terminal.get();
            }
            history.retain(input, response, results);
        }
        throw new IllegalStateException("Model tool decision or attempt budget exhausted");
    }

    private void checkpoint() throws Exception {
        if (task.cancelToken().requested()) throw new java.util.concurrent.CancellationException();
        if (!revision.equals(context.ai().configurationRevision(AiPurpose.LANGUAGE, task)))
            throw new SecurityException("Native model route changed");
    }

    private Map<String, Tool<T>> offered(Supplier<Map<String, Tool<T>>> available) {
        var tools = new LinkedHashMap<>(available.get());
        if (review != null) {
            tools.put("observation", new Tool<>("Read retained observation body", DeploymentToolSchemas.object("id", "offset:integer", "limit:integer").toString(),
                DeploymentToolScheduler.Execution.LOCAL_READ, args -> Feedback.observed(review.observation(
                DeploymentJsonFields.text(args, "id", 8), DeploymentJsonFields.number(args, "offset", 0, 12000), DeploymentJsonFields.number(args, "limit", 1, 6000)))));
            var indexSchema = DeploymentToolSchemas.object("offset:integer", "limit:integer");
            ((com.fasterxml.jackson.databind.node.ObjectNode) indexSchema.at("/properties/offset")).put("maximum", gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().execution().maxObservations());
            ((com.fasterxml.jackson.databind.node.ObjectNode) indexSchema.at("/properties/limit")).put("maximum", 50);
            tools.put("evidence_index", new Tool<>("Page original observation metadata", indexSchema.toString(),
                DeploymentToolScheduler.Execution.LOCAL_READ, args -> Feedback.observed(DeploymentAiJson.facts(review.evidenceIndex(
                    DeploymentJsonFields.number(args, "offset", 0, gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().execution().maxObservations()),
                    DeploymentJsonFields.number(args, "limit", 1, 50)),
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().history().characters()))));
        }
        return Map.copyOf(tools);
    }
}
