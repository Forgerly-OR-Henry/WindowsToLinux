package gold.debug.windowstolinux.app.service.ai;

import gold.debug.windowstolinux.app.db.persistence.repository.AiProfileRepository;
import gold.debug.windowstolinux.app.service.server.DesktopSecretStoreService;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationStatus;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiProviderAttempt;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Shared ordered invocation policy; each enabled provider is attempted once per immutable configuration snapshot. / 共用有序调用策略，每个启用提供者在配置快照中仅尝试一次。 */
final class AiProviderChain {
    private final AiProfileRepository profiles;
    private final DesktopSecretStoreService secrets;
    AiProviderChain(AiProfileRepository profiles, DesktopSecretStoreService secrets) { this.profiles = profiles; this.secrets = secrets; }

    <T> Outcome<T> invoke(char[] master, Invocation<T> invocation) throws java.sql.SQLException {
        List<AiProviderAttempt> attempts = new ArrayList<>(); Optional<T> last = Optional.empty();
        try {
            checkCancelled();
            var snapshot = profiles.listConfigured().stream().filter(value -> value.enabled()).map(value -> AiProviderProfile.fromStored(value.profile())).toList();
            for (AiProviderProfile profile : snapshot) {
                checkCancelled(); char[] key = null; Attempt<T> result = null;
                try (var store = secrets.open(profile.credentialMode(), master)) {
                    key = store.read(profile.credentialKey()).orElseGet(() -> new char[0]);
                    if (key.length == 0) throw new IllegalStateException("credential-unavailable");
                    checkCancelled(); result = invocation.call(profile, key); checkCancelled();
                } catch (CancellationException cancelled) { throw cancelled; }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException("AI invocation cancelled"); }
                catch (Exception failure) {
                    checkCancelled(); result = null;
                } finally { if (key != null) Arrays.fill(key, '\0'); }
                attempts.add(new AiProviderAttempt(profile.id(), profile.model(), result == null ? AiInvocationStatus.UNAVAILABLE : result.status(),
                        result == null ? "credential-or-provider-unavailable" : result.detail(), Instant.now()));
                if (result != null) {
                    last = Optional.of(result.value());
                    if (result.status() == AiInvocationStatus.VALIDATED) return new Outcome<>(last, List.copyOf(attempts), true, snapshot);
                }
            }
            return new Outcome<>(last, List.copyOf(attempts), false, snapshot);
        } finally { if (master != null) Arrays.fill(master, '\0'); }
    }
    static void checkCancelled() { if (Thread.currentThread().isInterrupted()) throw new CancellationException("AI invocation cancelled"); }
    @FunctionalInterface interface Invocation<T> { Attempt<T> call(AiProviderProfile profile, char[] key) throws Exception; }
    record Attempt<T>(T value, AiInvocationStatus status, String detail) { }
    record Outcome<T>(Optional<T> value, List<AiProviderAttempt> attempts, boolean valid, List<AiProviderProfile> snapshot) { }
}
