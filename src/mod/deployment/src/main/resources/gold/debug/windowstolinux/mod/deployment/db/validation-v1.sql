SELECT deployment_unit_id,
       server_profile_id,
       systemd_unit_name,
       release_root_path,
       ownership_manifest_sha256,
       has_display_override,
       display_name,
       application_category,
       access_url,
       current_release_sha256,
       runtime_payload,
       current_release_published_at,
       runtime_state,
       autostart_state,
       is_ownership_verified,
       observation_evidence,
       observed_at
FROM deployment_unit
WHERE 1 = 0;
SELECT application_id, health_component_id, health_check_payload, server_profile_id, graph_ready, updated_at
FROM managed_application
WHERE 1 = 0;
SELECT application_id,
       component_id,
       deployment_unit_id,
       reviewed_runtime_payload,
       reviewed_data_paths_payload,
       reviewed_resource_bindings_payload
FROM managed_application_component
WHERE 1 = 0;
SELECT application_id, component_id, dependency_component_id
FROM managed_application_dependency
WHERE 1 = 0;
SELECT external_application_id,
       server_profile_id,
       runtime_type,
       runtime_identity,
       identity_fingerprint,
       bound_host,
       bound_ssh_port,
       bound_username,
       detected_name,
       has_display_override,
       display_name,
       application_category,
       access_url,
       can_start,
       can_stop,
       runtime_state,
       adopted_at,
       observed_at
FROM external_application
WHERE 1 = 0;
SELECT deployment_unit_id, configuration_revision, configuration_sha256, schema_version, created_at
FROM deployment_unit_configuration_snapshot
WHERE 1 = 0;
SELECT deployment_unit_id, configuration_revision, configuration_key, configuration_scope, value_type, value_text
FROM deployment_unit_configuration_entry
WHERE 1 = 0;
SELECT deployment_unit_id, release_sha256, configuration_revision, configuration_sha256, is_secret_binding_complete
FROM deployment_unit_release_binding
WHERE 1 = 0;
SELECT secret_id, secret_revision, credential_id, purpose, created_at
FROM secret_revision
WHERE 1 = 0;
SELECT deployment_unit_id, release_sha256, secret_id, secret_revision
FROM deployment_unit_release_secret_reference
WHERE 1 = 0;
SELECT task_id,
       server_profile_id,
       target_digest,
       configuration_digest,
       automation_mode,
       approval_mode,
       deployment_skill_id,
       approval_skill_id,
       execution_semantics_version,
       task_state,
       started_at,
       updated_at
FROM deployment_agent_task
WHERE 1 = 0;
SELECT event_id, task_id, action_id, event_type, event_detail, created_at
FROM deployment_agent_event
WHERE 1 = 0;
SELECT project_id,
       display_name,
       source_kind,
       source_location,
       git_ref,
       git_commit,
       source_sha256,
       created_at,
       updated_at
FROM deployment_project
WHERE 1 = 0;
SELECT execution_id,
       project_id,
       platform_task_id,
       application_id,
       server_profile_id,
       mode,
       reviewed_revision,
       request_payload,
       recovery_payload,
       phase,
       outcome,
       result_payload,
       business_committed,
       cleanup_complete,
       created_at,
       updated_at
FROM deployment_execution
WHERE 1 = 0;
SELECT application_id,
       release_id,
       execution_id,
       origin_payload,
       release_sha256,
       runtime_payload,
       health_payload,
       state,
       created_at
FROM deployment_release
WHERE 1 = 0;
SELECT handoff_id,
       owner_backup_mod,
       platform_task_id,
       application_id,
       source_server_id,
       target_server_id,
       revision_sha256,
       maintenance_token,
       candidate_id,
       request_payload,
       recovery_payload,
       state,
       result_payload,
       updated_at
FROM deployment_handoff
WHERE 1 = 0;
SELECT preference_key, preference_value
FROM preference
WHERE 1 = 0;
SELECT log_id,
       owner_id,
       category,
       subject_mod_id,
       mod_version,
       task_id,
       operation_id,
       level,
       event_code,
       message,
       details,
       outcome,
       truncated,
       recorded_at
FROM deployment_log
WHERE 1 = 0;
SELECT operation_id,
       platform_task_id,
       owner_mod_id,
       owner_mod_version,
       application_id,
       server_profile_id, action, current_release_sha256, target_release_sha256, component_state, phase, updated_at
FROM deployment_management_operation
WHERE 1 = 0;

SELECT operation_id,
       platform_task_id,
       server_profile_id,
       mode,
       outcome,
       application_id,
       release_id,
       release_sha256,
       settlement_payload,
       settled_at
FROM deployment_operation_receipt
WHERE 1 = 0;
