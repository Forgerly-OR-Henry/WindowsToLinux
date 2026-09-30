CREATE TABLE deployment_unit (
 deployment_unit_id CHARACTER VARYING NOT NULL PRIMARY KEY,
 server_profile_id CHARACTER VARYING NOT NULL,
 systemd_unit_name CHARACTER VARYING NOT NULL,
 release_root_path CHARACTER VARYING NOT NULL,
 ownership_manifest_sha256 CHARACTER VARYING NOT NULL,
 current_release_sha256 CHARACTER VARYING,
 runtime_payload BINARY LARGE OBJECT,
 runtime_state CHARACTER VARYING,
 autostart_state CHARACTER VARYING,
 is_ownership_verified INTEGER CHECK(is_ownership_verified IN (0,1)),
 observation_evidence CHARACTER VARYING,
 has_display_override INTEGER NOT NULL DEFAULT 0 CHECK(has_display_override IN (0,1)),
 display_name CHARACTER VARYING,
 application_category CHARACTER VARYING CHECK(application_category IN ('WEBSITE','APP')),
 access_url CHARACTER VARYING,
 current_release_published_at BIGINT,
 observed_at BIGINT,
 CHECK((current_release_sha256 IS NULL)=(current_release_published_at IS NULL)),
 CHECK((runtime_state IS NULL AND autostart_state IS NULL AND is_ownership_verified IS NULL AND observation_evidence IS NULL AND observed_at IS NULL) OR
       (runtime_state IS NOT NULL AND autostart_state IS NOT NULL AND is_ownership_verified IS NOT NULL AND observation_evidence IS NOT NULL AND observed_at IS NOT NULL)),
 CHECK((has_display_override=0 AND display_name IS NULL AND application_category IS NULL AND access_url IS NULL) OR
       (has_display_override=1 AND display_name IS NOT NULL AND application_category IS NOT NULL))
);
CREATE TABLE managed_application (
 application_id CHARACTER VARYING NOT NULL PRIMARY KEY,
 server_profile_id CHARACTER VARYING NOT NULL,
 health_component_id CHARACTER VARYING,
 health_check_payload BINARY LARGE OBJECT,
 updated_at BIGINT NOT NULL,
 graph_ready BOOLEAN NOT NULL DEFAULT FALSE,
 CHECK(NOT graph_ready OR health_component_id IS NOT NULL)
);
CREATE TABLE managed_application_component (
 application_id CHARACTER VARYING NOT NULL REFERENCES managed_application(application_id),
 component_id CHARACTER VARYING NOT NULL,
 deployment_unit_id CHARACTER VARYING NOT NULL UNIQUE REFERENCES deployment_unit(deployment_unit_id),
 reviewed_runtime_payload BINARY LARGE OBJECT,
 reviewed_data_paths_payload BINARY LARGE OBJECT,
 reviewed_resource_bindings_payload BINARY LARGE OBJECT,
 PRIMARY KEY(application_id,component_id)
);
CREATE TABLE managed_application_dependency (
 application_id CHARACTER VARYING NOT NULL,
 component_id CHARACTER VARYING NOT NULL,
 dependency_component_id CHARACTER VARYING NOT NULL,
 PRIMARY KEY(application_id,component_id,dependency_component_id),
 FOREIGN KEY(application_id,component_id) REFERENCES managed_application_component(application_id,component_id),
 FOREIGN KEY(application_id,dependency_component_id) REFERENCES managed_application_component(application_id,component_id),
 CHECK(component_id<>dependency_component_id)
);
CREATE TABLE external_application (
 external_application_id CHARACTER VARYING NOT NULL PRIMARY KEY,
 server_profile_id CHARACTER VARYING NOT NULL,
 runtime_type CHARACTER VARYING NOT NULL CHECK(runtime_type IN ('SYSTEMD','DOCKER')),
 runtime_identity CHARACTER VARYING NOT NULL,
 identity_fingerprint CHARACTER VARYING NOT NULL,
 bound_host CHARACTER VARYING NOT NULL,
 bound_ssh_port INTEGER NOT NULL CHECK(bound_ssh_port BETWEEN 1 AND 65535),
 bound_username CHARACTER VARYING NOT NULL,
 runtime_state CHARACTER VARYING NOT NULL,
 can_start INTEGER NOT NULL CHECK(can_start IN (0,1)),
 can_stop INTEGER NOT NULL CHECK(can_stop IN (0,1)),
 detected_name CHARACTER VARYING NOT NULL,
 has_display_override INTEGER NOT NULL DEFAULT 0 CHECK(has_display_override IN (0,1)),
 display_name CHARACTER VARYING,
 application_category CHARACTER VARYING CHECK(application_category IN ('WEBSITE','APP')),
 access_url CHARACTER VARYING,
 adopted_at BIGINT NOT NULL,
 observed_at BIGINT NOT NULL,
 UNIQUE(server_profile_id,runtime_type,runtime_identity),
 CHECK((has_display_override=0 AND display_name IS NULL AND application_category IS NULL AND access_url IS NULL) OR
       (has_display_override=1 AND display_name IS NOT NULL AND application_category IS NOT NULL))
);
CREATE TABLE deployment_unit_configuration_snapshot (
 deployment_unit_id CHARACTER VARYING NOT NULL,
 configuration_revision INTEGER NOT NULL CHECK(configuration_revision>0),
 configuration_sha256 CHARACTER VARYING NOT NULL,
 schema_version CHARACTER VARYING NOT NULL,
 created_at BIGINT NOT NULL,
 PRIMARY KEY(deployment_unit_id,configuration_revision)
);
CREATE TABLE deployment_unit_configuration_entry (
 deployment_unit_id CHARACTER VARYING NOT NULL,
 configuration_revision INTEGER NOT NULL,
 configuration_key CHARACTER VARYING NOT NULL,
 value_type CHARACTER VARYING NOT NULL CHECK(value_type IN ('TEXT','NUMBER','FLAG')),
 value_text CHARACTER VARYING NOT NULL,
 configuration_scope CHARACTER VARYING NOT NULL,
 PRIMARY KEY(deployment_unit_id,configuration_revision,configuration_key),
 FOREIGN KEY(deployment_unit_id,configuration_revision) REFERENCES deployment_unit_configuration_snapshot(deployment_unit_id,configuration_revision)
);
CREATE TABLE deployment_unit_release_binding (
 deployment_unit_id CHARACTER VARYING NOT NULL,
 release_sha256 CHARACTER VARYING NOT NULL,
 configuration_revision INTEGER,
 configuration_sha256 CHARACTER VARYING,
 is_secret_binding_complete INTEGER NOT NULL DEFAULT 0 CHECK(is_secret_binding_complete IN (0,1)),
 PRIMARY KEY(deployment_unit_id,release_sha256),
 FOREIGN KEY(deployment_unit_id,configuration_revision) REFERENCES deployment_unit_configuration_snapshot(deployment_unit_id,configuration_revision),
 CHECK((configuration_revision IS NULL)=(configuration_sha256 IS NULL))
);
CREATE TABLE secret_revision (
 secret_id CHARACTER VARYING NOT NULL,
 secret_revision INTEGER NOT NULL CHECK(secret_revision>0),
 credential_id CHARACTER VARYING NOT NULL,
 created_at BIGINT NOT NULL,
 purpose CHARACTER VARYING CHECK(purpose IS NULL OR LENGTH(purpose) BETWEEN 1 AND 128),
 PRIMARY KEY(secret_id,secret_revision)
);
CREATE TABLE deployment_unit_release_secret_reference (
 deployment_unit_id CHARACTER VARYING NOT NULL,
 release_sha256 CHARACTER VARYING NOT NULL,
 secret_id CHARACTER VARYING NOT NULL,
 secret_revision INTEGER NOT NULL,
 PRIMARY KEY(deployment_unit_id,release_sha256,secret_id,secret_revision),
 FOREIGN KEY(deployment_unit_id,release_sha256) REFERENCES deployment_unit_release_binding(deployment_unit_id,release_sha256),
 FOREIGN KEY(secret_id,secret_revision) REFERENCES secret_revision(secret_id,secret_revision)
);
CREATE INDEX release_secret_revision_index ON deployment_unit_release_secret_reference(secret_id,secret_revision);
CREATE TABLE deployment_agent_task (
 task_id CHARACTER VARYING NOT NULL PRIMARY KEY,
 server_profile_id CHARACTER VARYING NOT NULL,
 target_digest CHARACTER VARYING NOT NULL,
 configuration_digest CHARACTER VARYING NOT NULL,
 automation_mode CHARACTER VARYING NOT NULL CHECK(automation_mode IN ('STATIC','ASSISTED','AGENT')),
 approval_mode CHARACTER VARYING NOT NULL CHECK(approval_mode IN ('MANUAL_REVIEW','AUTOMATIC','FULL_CONTROL')),
 deployment_skill_id CHARACTER VARYING NOT NULL,
 approval_skill_id CHARACTER VARYING NOT NULL,
 execution_semantics_version INTEGER NOT NULL CHECK(execution_semantics_version>0),
 task_state CHARACTER VARYING NOT NULL,
 started_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL
);
CREATE TABLE deployment_agent_event (
 event_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
 task_id CHARACTER VARYING NOT NULL REFERENCES deployment_agent_task(task_id),
 action_id CHARACTER VARYING,
 event_type CHARACTER VARYING NOT NULL,
 event_detail CHARACTER VARYING NOT NULL,
 created_at BIGINT NOT NULL
);
CREATE INDEX deployment_agent_event_task_index ON deployment_agent_event(task_id,event_id);
CREATE INDEX deployment_agent_event_action_index ON deployment_agent_event(task_id,action_id,event_type,event_id);
ALTER TABLE managed_application ADD CONSTRAINT managed_health_component_fk
 FOREIGN KEY(application_id,health_component_id) REFERENCES managed_application_component(application_id,component_id);

CREATE TABLE deployment_project (
 project_id UUID PRIMARY KEY,
 display_name VARCHAR(256) NOT NULL,
 source_kind VARCHAR(16) NOT NULL CHECK(source_kind IN ('LOCAL','GIT')),
 source_location CHARACTER VARYING NOT NULL,
 git_ref CHARACTER VARYING,
 git_commit CHAR(40),
 source_sha256 CHAR(64),
 created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL,
 CHECK(source_kind<>'GIT' OR git_ref IS NOT NULL)
);
CREATE TABLE deployment_execution (
 execution_id UUID PRIMARY KEY,
 platform_task_id UUID NOT NULL UNIQUE,
 project_id UUID REFERENCES deployment_project(project_id),
 application_id CHARACTER VARYING,
 server_profile_id CHARACTER VARYING NOT NULL,
 mode VARCHAR(16) NOT NULL CHECK(mode IN ('STATIC','ASSISTED','AGENT')),
 phase VARCHAR(40) NOT NULL,
 outcome VARCHAR(16) CHECK(outcome IN ('SUCCEEDED','FAILED','CANCELLED','UNKNOWN')),
 reviewed_revision CHAR(64) NOT NULL,
 request_payload BINARY LARGE OBJECT NOT NULL,
 recovery_payload BINARY LARGE OBJECT NOT NULL,
 result_payload BINARY LARGE OBJECT,
 business_committed BOOLEAN NOT NULL DEFAULT FALSE,
 cleanup_complete BOOLEAN NOT NULL DEFAULT FALSE,
 created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL,
 CHECK(NOT business_committed OR result_payload IS NOT NULL)
);
CREATE INDEX deployment_execution_application ON deployment_execution(application_id,created_at);
CREATE TABLE deployment_release (
 application_id CHARACTER VARYING NOT NULL REFERENCES managed_application(application_id),
 release_id UUID NOT NULL,
 release_sha256 CHAR(64) NOT NULL,
 execution_id UUID NOT NULL REFERENCES deployment_execution(execution_id),
 state VARCHAR(20) NOT NULL CHECK(state IN ('CANDIDATE','ACTIVE','RETIRED','FAILED','UNKNOWN')),
 runtime_payload BINARY LARGE OBJECT NOT NULL,
 health_payload BINARY LARGE OBJECT NOT NULL,
 created_at BIGINT NOT NULL,
 PRIMARY KEY(application_id,release_id),
 UNIQUE(application_id,release_sha256)
);
CREATE TABLE deployment_handoff (
 handoff_id UUID PRIMARY KEY,
 owner_backup_mod VARCHAR(512) NOT NULL,
 platform_task_id UUID NOT NULL,
 application_id CHARACTER VARYING NOT NULL,
 source_server_id CHARACTER VARYING NOT NULL,
 target_server_id CHARACTER VARYING,
 revision_sha256 CHAR(64) NOT NULL,
 state VARCHAR(24) NOT NULL CHECK(state IN
  ('REVIEWED','MAINTENANCE','COLLECTED','PREPARED','ACTIVATING','ACTIVE','COMPENSATING','FINISHED','UNKNOWN')),
 maintenance_token CHARACTER VARYING,
 candidate_id CHARACTER VARYING,
 request_payload BINARY LARGE OBJECT NOT NULL,
 recovery_payload BINARY LARGE OBJECT NOT NULL,
 result_payload BINARY LARGE OBJECT,
 updated_at BIGINT NOT NULL
);
CREATE INDEX deployment_handoff_task ON deployment_handoff(platform_task_id,state);
CREATE TABLE preference (
 preference_key VARCHAR(128) PRIMARY KEY,
 preference_value CHARACTER VARYING NOT NULL
);

CREATE TABLE deployment_log (
 log_id CHAR(36) PRIMARY KEY,
 recorded_at BIGINT NOT NULL,
 owner_id VARCHAR(512) NOT NULL,
 category VARCHAR(64) NOT NULL CHECK(REGEXP_LIKE(category,'^[a-z0-9_]{1,64}$')),
 level VARCHAR(8) NOT NULL CHECK(level IN ('DEBUG','INFO','WARN','ERROR')),
 event_code VARCHAR(128) NOT NULL,
 message CHARACTER VARYING NOT NULL,
 details CHARACTER LARGE OBJECT,
 subject_mod_id VARCHAR(512),
 mod_version VARCHAR(512),
 task_id UUID,
 operation_id UUID,
 outcome VARCHAR(16) CHECK(outcome IN ('SUCCEEDED','FAILED','CANCELLED')),
 truncated BOOLEAN NOT NULL,
 CHECK(OCTET_LENGTH(message)<=2048)
);
CREATE INDEX deployment_log_order ON deployment_log(owner_id,category,recorded_at DESC,log_id DESC);
CREATE TABLE deployment_management_operation (
 operation_id UUID PRIMARY KEY,
 platform_task_id UUID NOT NULL UNIQUE,
 owner_mod_id VARCHAR(512) NOT NULL,
 owner_mod_version VARCHAR(512) NOT NULL,
 application_id CHARACTER VARYING NOT NULL,
 server_profile_id CHARACTER VARYING NOT NULL,
 action VARCHAR(40) NOT NULL CHECK(action IN
  ('LIFECYCLE_START','LIFECYCLE_STOP','LIFECYCLE_RESTART','EXTERNAL_ADOPT',
   'EXTERNAL_START','EXTERNAL_STOP','EXTERNAL_RESTART','HISTORY_SWITCH')),
 current_release_sha256 CHAR(64) NOT NULL CHECK(REGEXP_LIKE(current_release_sha256,'^[0-9a-f]{64}$')),
 target_release_sha256 CHAR(64) CHECK(target_release_sha256 IS NULL OR REGEXP_LIKE(target_release_sha256,'^[0-9a-f]{64}$')),
 phase VARCHAR(24) NOT NULL CHECK(phase IN
  ('REVIEWED','RESERVED','INSERTING','REGISTERED','SNAPSHOTTING','ACTIVATING',
   'EFFECTING','COMPENSATING','FINISHED','UNKNOWN')),
 component_state BINARY LARGE OBJECT NOT NULL,
 updated_at BIGINT NOT NULL
);
CREATE INDEX deployment_management_application ON deployment_management_operation(application_id,updated_at DESC);
