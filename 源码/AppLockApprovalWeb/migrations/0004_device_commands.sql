CREATE TABLE IF NOT EXISTS device_commands (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  command_type TEXT NOT NULL,
  target_package TEXT,
  payload_text TEXT,
  status TEXT NOT NULL DEFAULT 'pending',
  created_at INTEGER NOT NULL,
  applied_at INTEGER,
  FOREIGN KEY (device_id) REFERENCES app_devices(id)
);

CREATE INDEX IF NOT EXISTS idx_device_commands_device_status_created
ON device_commands(device_id, status, created_at DESC);
