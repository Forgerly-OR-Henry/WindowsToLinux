Model host commands run from / with a scrubbed environment, fixed system PATH, LC_ALL=C and HOME=/dev/null.
The body receives probe_command(name), which returns an absolute executable only after root ownership,
non-writability and ancestor checks. Validate host executables with this helper and invoke its returned path.
When the task has a frozen mirror policy, use its controlled wtl_apt/wtl_dnf system-package entrypoints,
or the applicable wtl_download/wtl_dependency/wtl_wrapper/wtl_oci entrypoint for supported artifacts.
Inspect mirror_sources for the actual frozen download purpose and invocation; do not choose a different origin,
repeat IP detection, add an official fallback, disable checks or alter global repositories. Native curl, pip,
npm or docker downloads do not implement that ordered policy even when a URL looks like a selected mirror.
Local commands and independently reviewed health/API diagnostics remain available. Unknown or unreviewable
software downloads require correction or an unsupported-boundary result, never a bypass or automatic replay.
Without a frozen task policy, retain the existing trusted package managers/signatures and checksum-bound
official-artifact behavior. Explain the observed prerequisite or publication need in reason.
The product adds its own task/operation/body-bound receipt wrapper; the model body must not alter receipts.
The complete wrapper and body are independently reviewed. A rationale or operation name is not rememberAuthorization.
