CREATE TABLE IF NOT EXISTS user_glucose_sync_state (
    user_id                          UUID PRIMARY KEY,
    last_checked_at                  TIMESTAMP,
    last_new_data_at                 TIMESTAMP,
    last_seen_entry_timestamp        BIGINT,
    next_poll_at                     TIMESTAMP,
    consecutive_no_change_count      INTEGER NOT NULL DEFAULT 0,
    last_status                      VARCHAR(32),
    updated_at                       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_glucose_sync_state_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_user_glucose_sync_state_next_poll
    ON user_glucose_sync_state (next_poll_at);
