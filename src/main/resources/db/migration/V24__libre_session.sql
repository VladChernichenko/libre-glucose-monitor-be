-- LibreLinkUp session per user, so a backend restart reuses the token instead of logging every
-- Libre user in again (LLU rate-limits logins). The token is AES-GCM encrypted by the app
-- (EncryptedStringConverter, same as user_data_source_config.libre_password).

CREATE TABLE IF NOT EXISTS libre_session (
    user_id     UUID         PRIMARY KEY,
    token       TEXT         NOT NULL,            -- encrypted bearer token
    base_url    VARCHAR(255),                     -- regional host the token was issued on
    account_id  VARCHAR(128),                     -- sha256 hex of the LLU user id (account-id header)
    locale      VARCHAR(20),                      -- Accept-Language used at login
    expires_at  TIMESTAMPTZ,                      -- authTicket.expires; NULL = unknown
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_libre_session_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
