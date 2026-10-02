SELECT deployment_unit_id,
       server_profile_id,
       systemd_unit_name,
       release_root_path,
       ownership_manifest_sha256,
       current_release_sha256,
       runtime_payload,
       runtime_state,
       autostart_state,
       is_ownership_verified,
       observation_evidence,
       has_display_override,
       display_name,
       application_category,
       access_url,
       current_release_published_at,
       observed_at
FROM deployment_unit
WHERE 1 = 0;
SELECT application_id, server_profile_id, health_component_id, health_check_payload, updated_at, graph_ready
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
       runtime_state,
       can_start,
       can_stop,
       detected_name,
       has_display_override,
       display_name,
       application_category,
       access_url,
       adopted_at,
       observed_at
FROM external_application
WHERE 1 = 0;
SELECT deployment_unit_id, configuration_revision, configuration_sha256, schema_version, created_at
FROM deployment_unit_configuration_snapshot
WHERE 1 = 0;
SELECT deployment_unit_id, configuration_revision, configuration_key, value_type, value_text, configuration_scope
FROM deployment_unit_configuration_entry
WHERE 1 = 0;
SELECT deployment_unit_id, release_sha256, configuration_revision, configuration_sha256, is_secret_binding_complete
FROM deployment_unit_release_binding
WHERE 1 = 0;
SELECT secret_id, secret_revision, credential_id, created_at, purpose
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
       platform_task_id,
       project_id,
       application_id,
       server_profile_id,
       mode,
       phase,
       outcome,
       reviewed_revision,
       request_payload,
       recovery_payload,
       result_payload,
       business_committed,
       cleanup_complete,
       created_at,
       updated_at
FROM deployment_execution
WHERE 1 = 0;
SELECT application_id,
       release_id,
       release_sha256,
       execution_id,
       state,
       runtime_payload,
       health_payload,
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
       state,
       maintenance_token,
       candidate_id,
       request_payload,
       recovery_payload,
       result_payload,
       updated_at
FROM deployment_handoff
WHERE 1 = 0;
SELECT preference_key, preference_value
FROM preference
WHERE 1 = 0;
SELECT log_id,
       recorded_at,
       owner_id,
       category,
       level,
       event_code,
       message,
       details,
       subject_mod_id,
       mod_version,
       task_id,
       operation_id,
       outcome,
       truncated
FROM deployment_log
WHERE 1 = 0;
