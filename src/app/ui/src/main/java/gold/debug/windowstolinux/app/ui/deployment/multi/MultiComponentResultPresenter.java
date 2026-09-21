package gold.debug.windowstolinux.app.ui.deployment.multi;

import gold.debug.windowstolinux.app.service.deployment.multi.ReviewedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.source.PreparedMultiComponentSource;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.MultiComponentDeploymentResult;
import gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle.MultiComponentLifecycleResult;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Formats bounded multi-component review and result evidence for the desktop output area. / 为桌面输出区格式化有界多组件审阅与结果证据。
 */
final class MultiComponentResultPresenter {
    /**
     * Bound page message presenter collaborator for localized message resolver.
     * <p>处理本地化消息解析器的页面消息展示器协作对象。
     */
    private final PageMessagePresenter messages;

    /**
     * Creates the localized presenter. / 创建本地化呈现器。
     *
     * @param messages localized message resolver / 本地化消息解析器
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    MultiComponentResultPresenter(PageMessagePresenter messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /**
     * Formats the final pre-mutation review. / 格式化修改前最终审阅。
     *
     * @param candidate candidate / 候选
     * @return the final pre-mutation review / 修改前最终审阅
     */
    String review(ReviewedMultiComponentApplication candidate) {
        String waves = candidate.plan().buildWaves().stream().map(wave -> String.join(" + ", wave))
                .reduce((left, right) -> left + " -> " + right).orElse("");
        String supports = candidate.components().stream().map(component -> component.componentId() + " = "
                        + messages.text("support.level." + component.request().facts().support().level().name()
                        .toLowerCase(Locale.ROOT)))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        String databases = candidate.components().stream().map(component -> component.componentId() + " = "
                        + databaseReview(component.request().databaseBindings()))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return messages.text("component.review.body", Map.of("application", candidate.plan().applicationId(),
                "waves", waves, "stop", String.join(" -> ", candidate.plan().stopOrder()),
                "start", String.join(" -> ", candidate.plan().startOrder()), "supports", supports,
                "databases", databases));
    }

    /**
     * Distinguishes unreviewed, reviewed-empty and explicitly bound database resources in the result text.
     * <p>在结果文本中区分未审阅、已审阅为空及显式绑定的数据库资源。
     *
     * @param bindings bindings / 绑定集合
     * @return database review text / 数据库审阅文本
     */
    private String databaseReview(Optional<java.util.List<ManagedDatabaseBinding>> bindings) {
        if (bindings.isEmpty()) return messages.text("database.review.unreviewed");
        if (bindings.orElseThrow().isEmpty()) return messages.text("database.review.none");
        return bindings.orElseThrow().stream().map(this::databaseBindingReview)
                .reduce((left, right) -> left + ", " + right).orElseThrow();
    }

    /**
     * Formats the SQLite file or managed server database binding without secret material.
     * <p>格式化 SQLite 文件或受管服务器数据库绑定，不包含秘密素材。
     *
     * @param binding binding / 绑定
     * @return the SQLite file or managed server database binding without secret material /  SQLite 文件或受管服务器数据库绑定，不包含秘密素材
     */
    private String databaseBindingReview(ManagedDatabaseBinding binding) {
        if (binding.connection() instanceof ManagedDatabaseConnection.Sqlite sqlite) {
            return messages.text("database.review.sqlite", Map.of("id", binding.databaseId(),
                    "file", sqlite.fileName()));
        }
        ManagedDatabaseConnection.Server connection = (ManagedDatabaseConnection.Server) binding.connection();
        return messages.text("database.review.server", Map.of("engine", messages.text("db.engine." + connection.engine().name().toLowerCase(Locale.ROOT)),
                "id", binding.databaseId(), "host", connection.host(), "port", connection.port(),
                "database", connection.database()));
    }

    /**
     * Formats static support scopes and component issues. / 格式化静态支持范围与组件问题。
     *
     * @param prepared prepared / 已准备
     * @return static support scopes and component issues / 静态支持范围与组件问题
     */
    String analysis(PreparedMultiComponentSource prepared) {
        String components = prepared.assessment().components().stream().map(component -> "- "
                + component.componentId() + " | " + messages.text("project.type." + component.facts().projectType().name().toLowerCase(Locale.ROOT)) + " | "
                + messages.text("support.level." + component.facts().support().level().name().toLowerCase(Locale.ROOT))
                + " | " + component.facts().support().validatedTargets().stream()
                .map(target -> target.distro() + " " + target.version() + " " + target.architecture())
                .reduce((left, right) -> left + ", " + right).orElse(messages.text("analysis.validation.none")))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        String issues = prepared.assessment().issues().stream().map(issue -> "- " + issue.code() + " ["
                        + String.join(",", issue.componentIds()) + "]")
                .reduce((left, right) -> left + "\n" + right).orElse(messages.text("component.analysis.noIssues"));
        return messages.text("component.analysis.result", Map.of("admission", messages.text("analysis.admission." + prepared.assessment().admission().name().toLowerCase(Locale.ROOT)),
                "components", components, "issues", issues));
    }

    /**
     * Formats every component deployment result without collapsing failures. / 格式化每个组件部署结果且不折叠失败。
     *
     * @param result typed outcome produced by the delegated operation / 被委派操作产生的类型化结果
     * @return every component deployment result without collapsing failures / 每个组件部署结果且不折叠失败
     */
    String deployment(MultiComponentDeploymentResult result) {
        String components = result.componentResults().stream().map(component -> "- " + component.componentId()
                        + " = " + messages.text("component.state." + component.state().name().toLowerCase(Locale.ROOT)) + "\n" + component.events().stream()
                        .map(event -> "  " + messages.catalog().text(event.message()) + " = " + messages.text(event.succeeded() ? "value.success" : "value.failed")
                                + event.failure().map(failure -> " [" + failure.code() + "]").orElse(""))
                        .reduce((left, right) -> left + "\n" + right).orElse(""))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        String warnings = result.nonFatalFailures().stream().map(failure -> messages.text("failure.warning.summary",
                        Map.of("code", failure.code(), "message", messages.catalog().text(failure.userMessage()),
                                "recovery", messages.text("failure.recovery."
                                        + failure.recoveryDisposition().name().toLowerCase(Locale.ROOT)))))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return messages.text("component.deployment.result", Map.of("status", messages.text("deployment.status." + result.status().name().toLowerCase(Locale.ROOT)),
                "identity", result.applicationReleaseIdentity().orElse("-"), "components", components))
                + "\n\n" + messages.text("failure.operation.summary",
                Map.of("operationId", result.operationIdentity().toString()))
                + (warnings.isBlank() ? "" : "\n" + warnings);
    }

    /**
     * Formats aggregate and per-component authoritative lifecycle states. / 格式化汇总及逐组件权威生命周期状态。
     *
     * @param result typed outcome produced by the delegated operation / 被委派操作产生的类型化结果
     * @return aggregate and per-component authoritative lifecycle states / 汇总及逐组件权威生命周期状态
     */
    String lifecycle(MultiComponentLifecycleResult result) {
        String components = result.componentResults().stream().map(component -> "- " + component.componentId()
                        + " = " + component.observation().map(messages::lifecycle).orElse(messages.text("lifecycle.noObservation")))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        String warnings = result.nonFatalFailures().stream().map(failure -> messages.text("failure.warning.summary",
                        Map.of("code", failure.code(), "message", messages.catalog().text(failure.userMessage()),
                                "recovery", messages.text("failure.recovery."
                                        + failure.recoveryDisposition().name().toLowerCase(Locale.ROOT)))))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return messages.text("component.lifecycle.result", Map.of("accepted", messages.text(result.accepted() ? "value.yes" : "value.no"),
                "runtime", messages.text("runtime.state." + result.runtimeState().name().toLowerCase(Locale.ROOT)), "autostart", messages.text("autostart.state." + result.autostartState().name().toLowerCase(Locale.ROOT)),
                "components", components)) + "\n\n" + messages.text("failure.operation.summary",
                Map.of("operationId", result.operationIdentity().toString()))
                + result.failure().map(failure -> "\n" + failure.code() + ": "
                + messages.catalog().text(failure.userMessage())).orElse("")
                + (warnings.isBlank() ? "" : "\n" + warnings);
    }
}
