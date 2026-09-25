CREATE TABLE security_profile (
 security_profile_id INTEGER PRIMARY KEY CHECK(security_profile_id=1),
 is_encryption_enabled INTEGER NOT NULL DEFAULT 1 CHECK(is_encryption_enabled IN (0,1)),
 password_verifier TEXT NOT NULL,
 wrapped_data_key BLOB NOT NULL CHECK(length(wrapped_data_key)=48),
 key_wrap_algorithm TEXT NOT NULL CHECK(key_wrap_algorithm='AES256_GCM_V1'),
 key_wrap_nonce BLOB NOT NULL CHECK(length(key_wrap_nonce)=12),
 key_derivation_algorithm TEXT NOT NULL CHECK(key_derivation_algorithm='ARGON2ID_V1'),
 key_derivation_salt BLOB NOT NULL CHECK(length(key_derivation_salt)=16),
 key_derivation_parameters TEXT NOT NULL,
 format_version INTEGER NOT NULL CHECK(format_version=1)
) STRICT;
CREATE TABLE credential (
 credential_id TEXT NOT NULL PRIMARY KEY,
 security_profile_id INTEGER NOT NULL DEFAULT 1 REFERENCES security_profile(security_profile_id) CHECK(security_profile_id=1),
 storage_format TEXT NOT NULL CHECK(storage_format IN ('PLAIN_V1','AES256_GCM_V1')),
 payload BLOB NOT NULL,
 nonce BLOB,
 UNIQUE(security_profile_id,nonce),
 CHECK((storage_format='PLAIN_V1' AND nonce IS NULL AND length(payload)>0) OR
       (storage_format='AES256_GCM_V1' AND nonce IS NOT NULL AND length(nonce)=12 AND length(payload)>16))
) STRICT;
CREATE TABLE server_profile (
 server_profile_id TEXT NOT NULL PRIMARY KEY,
 credential_id TEXT NOT NULL REFERENCES credential(credential_id),
 host TEXT NOT NULL,
 ssh_port INTEGER NOT NULL CHECK(ssh_port BETWEEN 1 AND 65535),
 username TEXT NOT NULL,
 host_key_sha256 TEXT,
 host_key_format TEXT CHECK(host_key_format='SSH_WIRE'),
 trusted_host TEXT,
 trusted_ssh_port INTEGER CHECK(trusted_ssh_port BETWEEN 1 AND 65535),
 display_name TEXT NOT NULL,
 last_connection_succeeded INTEGER CHECK(last_connection_succeeded IN (0,1)),
 last_operating_system TEXT,
 last_checked_at INTEGER,
 CHECK((host_key_sha256 IS NULL AND host_key_format IS NULL AND trusted_host IS NULL AND trusted_ssh_port IS NULL) OR
       (host_key_sha256 IS NOT NULL AND host_key_format IS NOT NULL AND trusted_host IS NOT NULL AND trusted_ssh_port IS NOT NULL))
) STRICT;
CREATE TABLE deployment_unit (
 deployment_unit_id TEXT NOT NULL PRIMARY KEY,
 server_profile_id TEXT NOT NULL REFERENCES server_profile(server_profile_id),
 systemd_unit_name TEXT NOT NULL,
 release_root_path TEXT NOT NULL,
 ownership_manifest_sha256 TEXT NOT NULL,
 current_release_sha256 TEXT,
 runtime_payload BLOB,
 runtime_state TEXT,
 autostart_state TEXT,
 is_ownership_verified INTEGER CHECK(is_ownership_verified IN (0,1)),
 observation_evidence TEXT,
 has_display_override INTEGER NOT NULL DEFAULT 0 CHECK(has_display_override IN (0,1)),
 display_name TEXT,
 application_category TEXT CHECK(application_category IN ('WEBSITE','APP')),
 access_url TEXT,
 current_release_published_at INTEGER,
 observed_at INTEGER,
 CHECK((current_release_sha256 IS NULL)=(current_release_published_at IS NULL)),
 CHECK((runtime_state IS NULL AND autostart_state IS NULL AND is_ownership_verified IS NULL AND observation_evidence IS NULL AND observed_at IS NULL) OR
       (runtime_state IS NOT NULL AND autostart_state IS NOT NULL AND is_ownership_verified IS NOT NULL AND observation_evidence IS NOT NULL AND observed_at IS NOT NULL)),
 CHECK((has_display_override=0 AND display_name IS NULL AND application_category IS NULL AND access_url IS NULL) OR
       (has_display_override=1 AND display_name IS NOT NULL AND application_category IS NOT NULL))
) STRICT;
CREATE TABLE managed_application (
 application_id TEXT NOT NULL PRIMARY KEY,
 server_profile_id TEXT NOT NULL REFERENCES server_profile(server_profile_id),
 health_component_id TEXT NOT NULL,
 health_check_payload BLOB,
 updated_at INTEGER NOT NULL,
 FOREIGN KEY(application_id,health_component_id) REFERENCES managed_application_component(application_id,component_id) DEFERRABLE INITIALLY DEFERRED
) STRICT;
CREATE TABLE managed_application_component (
 application_id TEXT NOT NULL REFERENCES managed_application(application_id),
 component_id TEXT NOT NULL,
 deployment_unit_id TEXT NOT NULL UNIQUE REFERENCES deployment_unit(deployment_unit_id),
 reviewed_runtime_payload BLOB,
 reviewed_data_paths_payload BLOB,
 reviewed_resource_bindings_payload BLOB,
 PRIMARY KEY(application_id,component_id)
) STRICT;
CREATE TABLE managed_application_dependency (
 application_id TEXT NOT NULL,
 component_id TEXT NOT NULL,
 dependency_component_id TEXT NOT NULL,
 PRIMARY KEY(application_id,component_id,dependency_component_id),
 FOREIGN KEY(application_id,component_id) REFERENCES managed_application_component(application_id,component_id),
 FOREIGN KEY(application_id,dependency_component_id) REFERENCES managed_application_component(application_id,component_id),
 CHECK(component_id<>dependency_component_id)
) STRICT;
CREATE TABLE external_application (
 external_application_id TEXT NOT NULL PRIMARY KEY,
 server_profile_id TEXT NOT NULL REFERENCES server_profile(server_profile_id),
 runtime_type TEXT NOT NULL CHECK(runtime_type IN ('SYSTEMD','DOCKER')),
 runtime_identity TEXT NOT NULL,
 identity_fingerprint TEXT NOT NULL,
 bound_host TEXT NOT NULL,
 bound_ssh_port INTEGER NOT NULL CHECK(bound_ssh_port BETWEEN 1 AND 65535),
 bound_username TEXT NOT NULL,
 runtime_state TEXT NOT NULL,
 can_start INTEGER NOT NULL CHECK(can_start IN (0,1)),
 can_stop INTEGER NOT NULL CHECK(can_stop IN (0,1)),
 detected_name TEXT NOT NULL,
 has_display_override INTEGER NOT NULL DEFAULT 0 CHECK(has_display_override IN (0,1)),
 display_name TEXT,
 application_category TEXT CHECK(application_category IN ('WEBSITE','APP')),
 access_url TEXT,
 adopted_at INTEGER NOT NULL,
 observed_at INTEGER NOT NULL,
 UNIQUE(server_profile_id,runtime_type,runtime_identity),
 CHECK((has_display_override=0 AND display_name IS NULL AND application_category IS NULL AND access_url IS NULL) OR
       (has_display_override=1 AND display_name IS NOT NULL AND application_category IS NOT NULL))
) STRICT;
CREATE TABLE deployment_unit_configuration_snapshot (
 deployment_unit_id TEXT NOT NULL,
 configuration_revision INTEGER NOT NULL CHECK(configuration_revision>0),
 configuration_sha256 TEXT NOT NULL,
 schema_version TEXT NOT NULL,
 created_at INTEGER NOT NULL,
 PRIMARY KEY(deployment_unit_id,configuration_revision)
) STRICT;
CREATE TABLE deployment_unit_configuration_entry (
 deployment_unit_id TEXT NOT NULL,
 configuration_revision INTEGER NOT NULL,
 configuration_key TEXT NOT NULL,
 value_type TEXT NOT NULL CHECK(value_type IN ('TEXT','NUMBER','FLAG')),
 value_text TEXT NOT NULL,
 configuration_scope TEXT NOT NULL,
 PRIMARY KEY(deployment_unit_id,configuration_revision,configuration_key),
 FOREIGN KEY(deployment_unit_id,configuration_revision) REFERENCES deployment_unit_configuration_snapshot(deployment_unit_id,configuration_revision)
) STRICT;
CREATE TABLE deployment_unit_release_binding (
 deployment_unit_id TEXT NOT NULL,
 release_sha256 TEXT NOT NULL,
 configuration_revision INTEGER,
 configuration_sha256 TEXT,
 is_secret_binding_complete INTEGER NOT NULL DEFAULT 0 CHECK(is_secret_binding_complete IN (0,1)),
 PRIMARY KEY(deployment_unit_id,release_sha256),
 FOREIGN KEY(deployment_unit_id,configuration_revision) REFERENCES deployment_unit_configuration_snapshot(deployment_unit_id,configuration_revision),
 CHECK((configuration_revision IS NULL)=(configuration_sha256 IS NULL))
) STRICT;
CREATE TABLE secret_revision (
 secret_id TEXT NOT NULL,
 secret_revision INTEGER NOT NULL CHECK(secret_revision>0),
 credential_id TEXT NOT NULL REFERENCES credential(credential_id),
 created_at INTEGER NOT NULL,
 PRIMARY KEY(secret_id,secret_revision)
) STRICT;
CREATE TABLE deployment_unit_release_secret_reference (
 deployment_unit_id TEXT NOT NULL,
 release_sha256 TEXT NOT NULL,
 secret_id TEXT NOT NULL,
 secret_revision INTEGER NOT NULL,
 PRIMARY KEY(deployment_unit_id,release_sha256,secret_id,secret_revision),
 FOREIGN KEY(deployment_unit_id,release_sha256) REFERENCES deployment_unit_release_binding(deployment_unit_id,release_sha256),
 FOREIGN KEY(secret_id,secret_revision) REFERENCES secret_revision(secret_id,secret_revision)
) STRICT;
CREATE INDEX release_secret_revision_index ON deployment_unit_release_secret_reference(secret_id,secret_revision);
CREATE TABLE ai_model (
 model_id TEXT NOT NULL PRIMARY KEY,
 credential_id TEXT NOT NULL REFERENCES credential(credential_id),
 endpoint_url TEXT NOT NULL,
 model_name TEXT NOT NULL,
 configuration_revision INTEGER NOT NULL DEFAULT 1 CHECK(configuration_revision>0),
 text_verified_revision INTEGER CHECK(text_verified_revision>0),
 text_verified_at INTEGER,
 vision_verified_revision INTEGER CHECK(vision_verified_revision>0),
 vision_verified_at INTEGER,
 is_language_enabled INTEGER NOT NULL DEFAULT 0 CHECK(is_language_enabled IN (0,1)),
 is_approval_enabled INTEGER NOT NULL DEFAULT 0 CHECK(is_approval_enabled IN (0,1)),
 is_vision_enabled INTEGER NOT NULL DEFAULT 0 CHECK(is_vision_enabled IN (0,1)),
 display_name TEXT NOT NULL,
 display_order INTEGER NOT NULL UNIQUE CHECK(display_order>=0),
 language_call_order INTEGER UNIQUE CHECK(language_call_order>=0),
 approval_call_order INTEGER UNIQUE CHECK(approval_call_order>=0),
 vision_call_order INTEGER UNIQUE CHECK(vision_call_order>=0),
 CHECK((text_verified_revision IS NULL)=(text_verified_at IS NULL)),
 CHECK((vision_verified_revision IS NULL)=(vision_verified_at IS NULL)),
 CHECK(is_language_enabled=0 OR language_call_order IS NOT NULL),
 CHECK(is_approval_enabled=0 OR approval_call_order IS NOT NULL),
 CHECK(is_vision_enabled=0 OR vision_call_order IS NOT NULL)
) STRICT;
CREATE TABLE browser_recovery_task (
 task_id TEXT NOT NULL PRIMARY KEY,
 server_profile_id TEXT NOT NULL,
 operation_id TEXT NOT NULL,
 task_state TEXT NOT NULL,
 last_action_digest TEXT,
 last_event_code TEXT NOT NULL,
 updated_at INTEGER NOT NULL
) STRICT;
CREATE TABLE browser_recovery_event (
 event_id INTEGER PRIMARY KEY AUTOINCREMENT,
 task_id TEXT NOT NULL REFERENCES browser_recovery_task(task_id),
 event_code TEXT NOT NULL,
 task_state TEXT NOT NULL,
 action_digest TEXT,
 created_at INTEGER NOT NULL
) STRICT;
CREATE INDEX browser_recovery_event_task_index ON browser_recovery_event(task_id,event_id);
CREATE TABLE deployment_agent_task (
 task_id TEXT NOT NULL PRIMARY KEY,
 server_profile_id TEXT NOT NULL,
 target_digest TEXT NOT NULL,
 configuration_digest TEXT NOT NULL,
 automation_mode TEXT NOT NULL CHECK(automation_mode IN ('STATIC','ASSISTED','AGENT')),
 approval_mode TEXT NOT NULL CHECK(approval_mode IN ('MANUAL_REVIEW','AUTOMATIC','FULL_CONTROL')),
 deployment_skill_id TEXT NOT NULL,
 approval_skill_id TEXT NOT NULL,
 execution_semantics_version INTEGER NOT NULL CHECK(execution_semantics_version>0),
 task_state TEXT NOT NULL,
 started_at INTEGER NOT NULL,
 updated_at INTEGER NOT NULL
) STRICT;
CREATE TABLE deployment_agent_event (
 event_id INTEGER PRIMARY KEY AUTOINCREMENT,
 task_id TEXT NOT NULL REFERENCES deployment_agent_task(task_id),
 action_id TEXT,
 event_type TEXT NOT NULL,
 event_detail TEXT NOT NULL,
 created_at INTEGER NOT NULL
) STRICT;
CREATE INDEX deployment_agent_event_task_index ON deployment_agent_event(task_id,event_id);
CREATE INDEX deployment_agent_event_action_index ON deployment_agent_event(task_id,action_id,event_type,event_id);
CREATE TABLE desktop_preference (
 preference_key TEXT NOT NULL PRIMARY KEY,
 preference_value TEXT NOT NULL
) STRICT;
