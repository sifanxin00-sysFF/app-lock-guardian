CREATE TABLE IF NOT EXISTS student_mock_state (
  id TEXT PRIMARY KEY,
  student_name TEXT NOT NULL,
  device_name TEXT NOT NULL,
  device_model TEXT NOT NULL,
  device_id TEXT NOT NULL,
  binding_status TEXT NOT NULL,
  binding_code TEXT NOT NULL,
  binding_expires_at INTEGER NOT NULL,
  guardian_name TEXT,
  guardian_relation TEXT,
  guard_status TEXT NOT NULL,
  guard_mode TEXT NOT NULL,
  guard_mode_name TEXT NOT NULL,
  guard_start_at INTEGER,
  guard_end_at INTEGER,
  permissions_json TEXT NOT NULL,
  apps_json TEXT NOT NULL,
  whitelist_json TEXT NOT NULL,
  last_sync_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS student_mock_release_requests (
  id TEXT PRIMARY KEY,
  app_id TEXT NOT NULL,
  app_name TEXT NOT NULL,
  app_category TEXT NOT NULL,
  duration_minutes INTEGER NOT NULL,
  reason TEXT NOT NULL,
  status TEXT NOT NULL,
  submitted_at INTEGER NOT NULL,
  decided_at INTEGER,
  reject_reason TEXT,
  temporary_access_expire_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_student_mock_release_status_created
ON student_mock_release_requests(status, submitted_at DESC);

CREATE TABLE IF NOT EXISTS student_mock_events (
  id TEXT PRIMARY KEY,
  event_type TEXT NOT NULL,
  message TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_student_mock_events_created
ON student_mock_events(created_at DESC);
