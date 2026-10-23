Model host commands run from / with a scrubbed environment, fixed system PATH, LC_ALL=C and HOME=/dev/null.
The body receives probe_command(name), which returns an absolute executable only after root ownership,
non-writability and ancestor checks. Validate host executables with this helper and invoke its returned path.
Use trusted installed package managers with their signature checks intact, or checksum-bound official artifacts;
do not disable integrity checks. Explain the observed prerequisite or publication need in reason.
The product adds its own task/operation/body-bound receipt wrapper; the model body must not alter receipts.
The complete wrapper and body are independently reviewed. A rationale or operation name is not rememberAuthorization.
