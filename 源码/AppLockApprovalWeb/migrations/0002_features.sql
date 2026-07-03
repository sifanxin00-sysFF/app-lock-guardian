ALTER TABLE unlock_requests ADD COLUMN request_reason TEXT;
ALTER TABLE unlock_requests ADD COLUMN guardian_note TEXT;

CREATE INDEX IF NOT EXISTS idx_unlock_requests_type_status_created
ON unlock_requests(request_type, status, created_at DESC);
