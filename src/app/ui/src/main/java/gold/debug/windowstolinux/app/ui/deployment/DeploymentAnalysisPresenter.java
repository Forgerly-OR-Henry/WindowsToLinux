package gold.debug.windowstolinux.app.ui.deployment;

import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.app.ui.shell.PageMessages;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Formats localized language, evidence, conflict, missing-input, and rejection summaries. / 格式化本地化语言、证据、冲突、缺失输入和拒绝摘要。 */
final class DeploymentAnalysisPresenter {
    private final PageMessages messages;

    DeploymentAnalysisPresenter(PageMessages messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    String facts(ReviewedSourcePreparation preparation) {
        var facts = preparation.assessment().facts().orElseThrow();
        var language = facts.languageFacts();
        String languages = messages.text("source.languageSummary", Map.of(
                "ecosystems", language.ecosystems().stream().map(item -> messages.text("language.ecosystem."
                        + item.name().toLowerCase(Locale.ROOT))).sorted().reduce((a, b) -> a + ", " + b).orElse("-"),
                "sources", language.sourceLanguages().stream().map(item -> messages.text("language.source."
                        + item.name().toLowerCase(Locale.ROOT))).sorted().reduce((a, b) -> a + ", " + b).orElse("-")));
        String evidence = facts.evidence().isEmpty() ? "" : messages.text("source.facts", Map.of("items", facts.evidence().stream()
                .map(item -> messages.text("source.factItem", Map.of("subject", messages.catalog().text(item.subject()),
                        "conclusion", messages.catalog().text(item.conclusion()), "source", item.source(), "confidence",
                        messages.text("analysis.confidence." + item.confidence().name().toLowerCase(Locale.ROOT)))) + "\n")
                .reduce("", String::concat)));
        String conflicts = facts.conflicts().isEmpty() ? "" : messages.text("source.conflicts", Map.of("items",
                facts.conflicts().stream().map(messages.catalog()::text).map(item -> "- " + item + "\n").reduce("", String::concat)));
        String missing = facts.missingInformation().isEmpty() ? "" : messages.text("source.missing", Map.of("items",
                facts.missingInformation().stream().map(messages.catalog()::text).map(item -> "- " + item + "\n")
                        .reduce("", String::concat)));
        String runtime = preparation.assessment().runtimeSuggestion().map(suggestion ->
                (suggestion.evidence().isEmpty() ? "" : messages.text("source.runtimeInferred", Map.of("items",
                        suggestion.evidence().stream().map(item -> "- " + messages.catalog().text(item.subject()) + " ("
                                + item.source() + ")\n").reduce("", String::concat))))
                + (suggestion.requiredUserInput().isEmpty() ? "" : messages.text("source.runtimeRequired", Map.of("items",
                        suggestion.requiredUserInput().stream().map(messages.catalog()::text).map(item -> "- " + item + "\n")
                                .reduce("", String::concat))))).orElse("");
        return languages + evidence + conflicts + missing + runtime;
    }

    String rejections(ReviewedSourcePreparation preparation) {
        if (!preparation.assessment().rejections().isEmpty()) {
            return preparation.assessment().rejections().stream().map(RejectionReason::message)
                    .map(messages.catalog()::text).map(item -> "- " + item + "\n").reduce("", String::concat);
        }
        return preparation.assessment().facts().map(ignored -> facts(preparation))
                .orElse(messages.text("diagnostic.unknown"));
    }
}
