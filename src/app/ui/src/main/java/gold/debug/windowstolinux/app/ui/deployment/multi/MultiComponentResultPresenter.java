package gold.debug.windowstolinux.app.ui.deployment.multi;

import gold.debug.windowstolinux.app.service.deployment.multi.ReviewedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.source.PreparedMultiComponentSource;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.MultiComponentDeploymentResult;
import gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle.MultiComponentLifecycleResult;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Formats bounded multi-component review and result evidence for the desktop output area. / 为桌面输出区格式化有界多组件审阅与结果证据。 */
final class MultiComponentResultPresenter {
    private final PageMessagePresenter messages;

    /** Creates the localized presenter. / 创建本地化呈现器。 */
    MultiComponentResultPresenter(PageMessagePresenter messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /** Formats the final pre-mutation review. / 格式化修改前最终审阅。 */
    String review(ReviewedMultiComponentApplication candidate) {
        String waves = candidate.plan().buildWaves().stream().map(wave -> String.join(" + ", wave))
                .reduce((left, right) -> left + " -> " + right).orElse("");
        String supports = candidate.components().stream().map(component -> component.componentId() + " = "
                        + messages.text("support.level." + component.request().facts().support().level().name()
                        .toLowerCase(Locale.ROOT)))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return messages.text("component.review.body", Map.of("application", candidate.plan().applicationId(),
                "waves", waves, "stop", String.join(" -> ", candidate.plan().stopOrder()),
                "start", String.join(" -> ", candidate.plan().startOrder()), "supports", supports));
    }

    /** Formats static support scopes and component issues. / 格式化静态支持范围与组件问题。 */
    String analysis(PreparedMultiComponentSource prepared) {
        String components = prepared.assessment().components().stream().map(component -> "- "
                + component.componentId() + " | " + component.facts().projectType().name() + " | "
                + messages.text("support.level." + component.facts().support().level().name().toLowerCase(Locale.ROOT))
                + " | " + component.facts().support().validatedTargets().stream()
                .map(target -> target.distro() + " " + target.version() + " " + target.architecture())
                .reduce((left, right) -> left + ", " + right).orElse(messages.text("analysis.validation.none")))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        String issues = prepared.assessment().issues().stream().map(issue -> "- " + issue.code() + " ["
                        + String.join(",", issue.componentIds()) + "]")
                .reduce((left, right) -> left + "\n" + right).orElse(messages.text("component.analysis.noIssues"));
        return messages.text("component.analysis.result", Map.of("admission", prepared.assessment().admission().name(),
                "components", components, "issues", issues));
    }

    /** Formats every component deployment result without collapsing failures. / 格式化每个组件部署结果且不折叠失败。 */
    String deployment(MultiComponentDeploymentResult result) {
        String components = result.componentResults().stream().map(component -> "- " + component.componentId()
                        + " = " + component.state().name() + "\n" + component.events().stream()
                        .map(event -> "  " + event.step().code() + "=" + event.succeeded()
                                + event.failure().map(failure -> " [" + failure.code() + "]").orElse(""))
                        .reduce((left, right) -> left + "\n" + right).orElse(""))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        String warnings = result.nonFatalFailures().stream().map(failure -> messages.text("failure.warning.summary",
                        Map.of("code", failure.code(), "message", messages.catalog().text(failure.userMessage()),
                                "recovery", messages.text("failure.recovery."
                                        + failure.recoveryDisposition().name().toLowerCase(Locale.ROOT)))))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return messages.text("component.deployment.result", Map.of("status", result.status().name(),
                "identity", result.applicationReleaseIdentity().orElse("-"), "components", components))
                + "\n\n" + messages.text("failure.operation.summary",
                Map.of("operationId", result.operationIdentity().toString()))
                + (warnings.isBlank() ? "" : "\n" + warnings);
    }

    /** Formats aggregate and per-component authoritative lifecycle states. / 格式化汇总及逐组件权威生命周期状态。 */
    String lifecycle(MultiComponentLifecycleResult result) {
        String components = result.componentResults().stream().map(component -> "- " + component.componentId()
                        + " = " + component.observation().map(messages::lifecycle).orElse("unobserved"))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        String warnings = result.nonFatalFailures().stream().map(failure -> messages.text("failure.warning.summary",
                        Map.of("code", failure.code(), "message", messages.catalog().text(failure.userMessage()),
                                "recovery", messages.text("failure.recovery."
                                        + failure.recoveryDisposition().name().toLowerCase(Locale.ROOT)))))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return messages.text("component.lifecycle.result", Map.of("accepted", Boolean.toString(result.accepted()),
                "runtime", result.runtimeState().name(), "autostart", result.autostartState().name(),
                "components", components)) + "\n\n" + messages.text("failure.operation.summary",
                Map.of("operationId", result.operationIdentity().toString()))
                + result.failure().map(failure -> "\n" + failure.code() + ": "
                + messages.catalog().text(failure.userMessage())).orElse("")
                + (warnings.isBlank() ? "" : "\n" + warnings);
    }
}
