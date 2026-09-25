CREATE TABLE backup_archive (
 archive_id UUID PRIMARY KEY,
 application_id CHARACTER VARYING NOT NULL,
 source_server_id CHARACTER VARYING NOT NULL,
 source_revision CHAR(64) NOT NULL,
 archive_path CHARACTER VARYING NOT NULL,
 archive_sha256 CHAR(64) NOT NULL,
 archive_size BIGINT NOT NULL CHECK(archive_size>=0),
 format_version INTEGER NOT NULL CHECK(format_version>0),
 encrypted BOOLEAN NOT NULL,
 manifest_payload BINARY LARGE OBJECT NOT NULL,
 state VARCHAR(16) NOT NULL CHECK(state IN ('READY','MISSING','INVALID','UNKNOWN')),
 created_at BIGINT NOT NULL,
 verified_at BIGINT,
 UNIQUE(archive_path,archive_sha256)
);
CREATE TABLE backup_operation (
 operation_id UUID PRIMARY KEY,
 platform_task_id UUID NOT NULL UNIQUE,
 archive_id UUID REFERENCES backup_archive(archive_id),
 operation_type VARCHAR(16) NOT NULL CHECK(operation_type IN ('BACKUP','INSPECT','RESTORE','MIGRATE')),
 source_server_id CHARACTER VARYING,
 target_server_id CHARACTER VARYING,
 deployment_handoff_id UUID,
 phase VARCHAR(40) NOT NULL,
 outcome VARCHAR(16) CHECK(outcome IN ('SUCCEEDED','FAILED','CANCELLED','UNKNOWN')),
 request_payload BINARY LARGE OBJECT NOT NULL,
 recovery_payload BINARY LARGE OBJECT NOT NULL,
 result_payload BINARY LARGE OBJECT,
 business_committed BOOLEAN NOT NULL DEFAULT FALSE,
 cleanup_complete BOOLEAN NOT NULL DEFAULT FALSE,
 created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL,
 CHECK(NOT business_committed OR result_payload IS NOT NULL)
);
CREATE INDEX backup_operation_servers ON backup_operation(source_server_id,target_server_id,phase);
CREATE TABLE backup_item (
 operation_id UUID NOT NULL REFERENCES backup_operation(operation_id),
 item_id VARCHAR(256) NOT NULL,
 item_kind VARCHAR(20) NOT NULL CHECK(item_kind IN ('CONFIG','DATA','DATABASE','RUNTIME','SECRET_REFERENCE')),
 component_id VARCHAR(256) NOT NULL,
 binding_id VARCHAR(256) NOT NULL,
 relative_path CHARACTER VARYING NOT NULL,
 content_sha256 CHAR(64),
 byte_count BIGINT CHECK(byte_count>=0),
 state VARCHAR(16) NOT NULL CHECK(state IN ('PENDING','COLLECTED','VERIFIED','FAILED','UNKNOWN')),
 recovery_payload BINARY LARGE OBJECT NOT NULL,
 PRIMARY KEY(operation_id,item_id)
);
CREATE TABLE restore_candidate (
 operation_id UUID PRIMARY KEY REFERENCES backup_operation(operation_id),
 deployment_handoff_id UUID NOT NULL,
 target_server_id CHARACTER VARYING NOT NULL,
 candidate_id CHARACTER VARYING,
 expected_archive_sha256 CHAR(64) NOT NULL,
 state VARCHAR(20) NOT NULL CHECK(state IN ('PREPARING','PREPARED','ACTIVATING','ACTIVE','DISCARDED','UNKNOWN')),
 recovery_payload BINARY LARGE OBJECT NOT NULL,
 updated_at BIGINT NOT NULL
);
CREATE TABLE preference (
 preference_key VARCHAR(128) PRIMARY KEY,
 preference_value CHARACTER VARYING NOT NULL
);

CREATE TABLE backup_log (
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
CREATE INDEX backup_log_order ON backup_log(owner_id,category,recorded_at DESC,log_id DESC);
