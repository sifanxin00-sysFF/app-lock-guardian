CREATE TABLE IF NOT EXISTS guardian_account (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  username TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  password_salt TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS guardian_sessions (
  id TEXT PRIMARY KEY,
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS app_devices (
  id TEXT PRIMARY KEY,
  device_secret TEXT NOT NULL,
  device_name TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS unlock_requests (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  request_type TEXT NOT NULL,
  target_package TEXT,
  target_label TEXT,
  requested_minutes INTEGER,
  status TEXT NOT NULL,
  approved_minutes INTEGER,
  created_at INTEGER NOT NULL,
  decided_at INTEGER,
  FOREIGN KEY (device_id) REFERENCES app_devices(id)
);

CREATE INDEX IF NOT EXISTS idx_unlock_requests_status_created
ON unlock_requests(status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_unlock_requests_device_created
ON unlock_requests(device_id, created_at DESC);
