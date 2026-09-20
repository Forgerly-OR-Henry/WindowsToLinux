CREATE TABLE users (
 id TEXT PRIMARY KEY, display_name TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('INTERNAL','ACTIVE','DISABLED')),
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE TABLE workspaces (
 id TEXT PRIMARY KEY, name TEXT NOT NULL, owner_user_id TEXT NOT NULL REFERENCES users(id),
 status TEXT NOT NULL CHECK(status IN ('INTERNAL','ACTIVE','DISABLED')),
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE TABLE workspace_members (
 workspace_id TEXT NOT NULL REFERENCES workspaces(id), user_id TEXT NOT NULL REFERENCES users(id),
 status TEXT NOT NULL CHECK(status IN ('ACTIVE','DISABLED')), joined_at TEXT NOT NULL,
 PRIMARY KEY(workspace_id,user_id)
);
CREATE TABLE roles (
 workspace_id TEXT NOT NULL REFERENCES workspaces(id), id TEXT NOT NULL, name TEXT NOT NULL,
 PRIMARY KEY(workspace_id,id), UNIQUE(workspace_id,name)
);
CREATE TABLE permissions (id TEXT PRIMARY KEY, description TEXT NOT NULL);
CREATE TABLE role_permissions (
 workspace_id TEXT NOT NULL, role_id TEXT NOT NULL, permission_id TEXT NOT NULL REFERENCES permissions(id),
 PRIMARY KEY(workspace_id,role_id,permission_id),
 FOREIGN KEY(workspace_id,role_id) REFERENCES roles(workspace_id,id)
);
CREATE TABLE member_roles (
 workspace_id TEXT NOT NULL, user_id TEXT NOT NULL, role_id TEXT NOT NULL,
 PRIMARY KEY(workspace_id,user_id,role_id),
 FOREIGN KEY(workspace_id,user_id) REFERENCES workspace_members(workspace_id,user_id),
 FOREIGN KEY(workspace_id,role_id) REFERENCES roles(workspace_id,id)
);
CREATE TABLE user_credentials (
 user_id TEXT PRIMARY KEY REFERENCES users(id), login_name TEXT NOT NULL UNIQUE COLLATE NOCASE,
 password_hash TEXT NOT NULL, password_changed_at TEXT NOT NULL, failed_attempts INTEGER NOT NULL DEFAULT 0,
 locked_until TEXT, version INTEGER NOT NULL DEFAULT 1 CHECK(version>0)
);
CREATE TABLE auth_sessions (
 id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), token_hash TEXT NOT NULL UNIQUE,
 created_at TEXT NOT NULL, expires_at TEXT NOT NULL, last_seen_at TEXT NOT NULL, revoked_at TEXT
);
CREATE INDEX auth_sessions_user_expiry ON auth_sessions(user_id,expires_at);
CREATE TABLE secrets (
 workspace_id TEXT NOT NULL, id TEXT NOT NULL, version INTEGER NOT NULL CHECK(version>0),
 created_by TEXT NOT NULL, purpose TEXT NOT NULL, ciphertext BLOB NOT NULL,
 created_at TEXT NOT NULL, PRIMARY KEY(workspace_id,id,version),
 FOREIGN KEY(workspace_id,created_by) REFERENCES workspace_members(workspace_id,user_id)
);
CREATE TABLE servers (
 workspace_id TEXT NOT NULL, id TEXT NOT NULL, created_by TEXT NOT NULL,
 name TEXT NOT NULL, host TEXT NOT NULL, port INTEGER NOT NULL CHECK(port BETWEEN 1 AND 65535),
 username TEXT NOT NULL, fingerprint TEXT, secret_id TEXT, secret_version INTEGER,
 document TEXT NOT NULL CHECK(json_valid(document)), version INTEGER NOT NULL DEFAULT 1 CHECK(version>0),
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY(workspace_id,id),
 UNIQUE(workspace_id,host,port,username),
 FOREIGN KEY(workspace_id,created_by) REFERENCES workspace_members(workspace_id,user_id),
 FOREIGN KEY(workspace_id,secret_id,secret_version) REFERENCES secrets(workspace_id,id,version),
 CHECK((secret_id IS NULL)=(secret_version IS NULL))
);
CREATE TABLE ai_profiles (
 workspace_id TEXT NOT NULL, id TEXT NOT NULL, created_by TEXT NOT NULL,
 name TEXT NOT NULL, priority INTEGER NOT NULL CHECK(priority>=0), enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),
 secret_id TEXT, secret_version INTEGER, document TEXT NOT NULL CHECK(json_valid(document)),
 version INTEGER NOT NULL DEFAULT 1 CHECK(version>0), created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
 PRIMARY KEY(workspace_id,id),
 FOREIGN KEY(workspace_id,created_by) REFERENCES workspace_members(workspace_id,user_id),
 FOREIGN KEY(workspace_id,secret_id,secret_version) REFERENCES secrets(workspace_id,id,version),
 CHECK((secret_id IS NULL)=(secret_version IS NULL))
);
CREATE TABLE sources (
 workspace_id TEXT NOT NULL, id TEXT NOT NULL, created_by TEXT NOT NULL, name TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('UPLOADING','READY','FAILED')),
 kind TEXT NOT NULL CHECK(kind IN ('UPLOAD','GIT')), digest TEXT, byte_count INTEGER NOT NULL CHECK(byte_count>=0),
 document TEXT NOT NULL CHECK(json_valid(document)), version INTEGER NOT NULL DEFAULT 1 CHECK(version>0),
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY(workspace_id,id),
 FOREIGN KEY(workspace_id,created_by) REFERENCES workspace_members(workspace_id,user_id)
);
CREATE TABLE applications (
 workspace_id TEXT NOT NULL, id TEXT NOT NULL, created_by TEXT NOT NULL, name TEXT NOT NULL,
 server_id TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('MANAGED','EXTERNAL')),
 remote_identity TEXT NOT NULL, document TEXT NOT NULL CHECK(json_valid(document)),
 version INTEGER NOT NULL DEFAULT 1 CHECK(version>0), created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
 PRIMARY KEY(workspace_id,id), UNIQUE(workspace_id,server_id,remote_identity),
 FOREIGN KEY(workspace_id,created_by) REFERENCES workspace_members(workspace_id,user_id),
 FOREIGN KEY(workspace_id,server_id) REFERENCES servers(workspace_id,id)
);
CREATE TABLE configuration_revisions (
 workspace_id TEXT NOT NULL, application_id TEXT NOT NULL, id TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision>0),
 created_by TEXT NOT NULL, document TEXT NOT NULL CHECK(json_valid(document)), digest TEXT NOT NULL, created_at TEXT NOT NULL,
 PRIMARY KEY(workspace_id,id,revision),
 FOREIGN KEY(workspace_id,application_id) REFERENCES applications(workspace_id,id),
 FOREIGN KEY(workspace_id,created_by) REFERENCES workspace_members(workspace_id,user_id)
);
CREATE TABLE backups (
 workspace_id TEXT NOT NULL, id TEXT NOT NULL, created_by TEXT NOT NULL, application_id TEXT,
 name TEXT NOT NULL, digest TEXT NOT NULL, byte_count INTEGER NOT NULL CHECK(byte_count>=0),
 document TEXT NOT NULL CHECK(json_valid(document)), version INTEGER NOT NULL DEFAULT 1 CHECK(version>0),
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY(workspace_id,id),
 FOREIGN KEY(workspace_id,application_id) REFERENCES applications(workspace_id,id),
 FOREIGN KEY(workspace_id,created_by) REFERENCES workspace_members(workspace_id,user_id)
);
CREATE TABLE preferences (
 workspace_id TEXT NOT NULL, user_id TEXT NOT NULL, name TEXT NOT NULL, value TEXT NOT NULL,
 updated_at TEXT NOT NULL, PRIMARY KEY(workspace_id,user_id,name),
 FOREIGN KEY(workspace_id,user_id) REFERENCES workspace_members(workspace_id,user_id)
);
CREATE TABLE tasks (
 workspace_id TEXT NOT NULL, id TEXT NOT NULL, created_by TEXT NOT NULL, kind TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('QUEUED','ANALYZING','WAITING_DECISION','RUNNING','CANCELLING','CANCELLED','SUCCEEDED','FAILED','INTERRUPTED','REVALIDATION_REQUIRED')),
 request_json TEXT NOT NULL CHECK(json_valid(request_json)), result_json TEXT CHECK(result_json IS NULL OR json_valid(result_json)),
 source_id TEXT, application_id TEXT, backup_id TEXT,
 error_code TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, finished_at TEXT,
 PRIMARY KEY(workspace_id,id),
 FOREIGN KEY(workspace_id,created_by) REFERENCES workspace_members(workspace_id,user_id),
 FOREIGN KEY(workspace_id,source_id) REFERENCES sources(workspace_id,id),
 FOREIGN KEY(workspace_id,application_id) REFERENCES applications(workspace_id,id),
 FOREIGN KEY(workspace_id,backup_id) REFERENCES backups(workspace_id,id)
);
CREATE INDEX tasks_owner_created ON tasks(workspace_id,created_by,created_at);
CREATE INDEX tasks_state ON tasks(state,created_at);
CREATE TABLE task_targets (
 workspace_id TEXT NOT NULL, task_id TEXT NOT NULL, server_id TEXT NOT NULL, mutating INTEGER NOT NULL CHECK(mutating IN (0,1)),
 PRIMARY KEY(workspace_id,task_id,server_id),
 FOREIGN KEY(workspace_id,task_id) REFERENCES tasks(workspace_id,id),
 FOREIGN KEY(workspace_id,server_id) REFERENCES servers(workspace_id,id)
);
CREATE TABLE task_events (
 workspace_id TEXT NOT NULL, task_id TEXT NOT NULL, sequence INTEGER NOT NULL CHECK(sequence>0),
 kind TEXT NOT NULL, message TEXT NOT NULL, detail_json TEXT NOT NULL CHECK(json_valid(detail_json)), created_at TEXT NOT NULL,
 PRIMARY KEY(workspace_id,task_id,sequence), FOREIGN KEY(workspace_id,task_id) REFERENCES tasks(workspace_id,id)
);
CREATE TABLE task_steps (
 workspace_id TEXT NOT NULL, task_id TEXT NOT NULL, ordinal INTEGER NOT NULL CHECK(ordinal>=0),
 name TEXT NOT NULL, state TEXT NOT NULL, input_digest TEXT, result_digest TEXT, remote_version TEXT,
 recovery_json TEXT CHECK(recovery_json IS NULL OR json_valid(recovery_json)), updated_at TEXT NOT NULL,
 PRIMARY KEY(workspace_id,task_id,ordinal), FOREIGN KEY(workspace_id,task_id) REFERENCES tasks(workspace_id,id)
);
CREATE TABLE task_decisions (
 workspace_id TEXT NOT NULL, task_id TEXT NOT NULL, id TEXT NOT NULL, kind TEXT NOT NULL,
 prompt_json TEXT NOT NULL CHECK(json_valid(prompt_json)), answer_json TEXT CHECK(answer_json IS NULL OR json_valid(answer_json)),
 expires_at TEXT NOT NULL, answered_by TEXT, answered_at TEXT,
 PRIMARY KEY(workspace_id,task_id,id), FOREIGN KEY(workspace_id,task_id) REFERENCES tasks(workspace_id,id),
 FOREIGN KEY(workspace_id,answered_by) REFERENCES workspace_members(workspace_id,user_id)
);
CREATE TABLE audit_events (
 id TEXT PRIMARY KEY, workspace_id TEXT, actor_user_id TEXT REFERENCES users(id), action TEXT NOT NULL,
 resource_type TEXT, resource_id TEXT, outcome TEXT NOT NULL, correlation_id TEXT NOT NULL,
 detail_json TEXT NOT NULL CHECK(json_valid(detail_json)), created_at TEXT NOT NULL,
 FOREIGN KEY(workspace_id) REFERENCES workspaces(id)
);
CREATE INDEX audit_workspace_time ON audit_events(workspace_id,created_at);
CREATE TABLE release_channels (id TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE);
CREATE TABLE releases (
 id TEXT PRIMARY KEY, channel_id TEXT NOT NULL REFERENCES release_channels(id), version TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('DRAFT','PUBLISHED','REVOKED')), created_by TEXT NOT NULL REFERENCES users(id),
 created_at TEXT NOT NULL, published_at TEXT, manifest_digest TEXT, signature TEXT,
 UNIQUE(channel_id,version)
);
CREATE TABLE release_artifacts (
 id TEXT PRIMARY KEY, release_id TEXT NOT NULL REFERENCES releases(id), platform TEXT NOT NULL, architecture TEXT NOT NULL,
 storage_key TEXT NOT NULL UNIQUE, digest TEXT NOT NULL, byte_count INTEGER NOT NULL CHECK(byte_count>0),
 UNIQUE(release_id,platform,architecture)
);
