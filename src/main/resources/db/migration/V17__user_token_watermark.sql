-- Logout-all watermark: every token for `username` issued before `tokens_valid_after` is rejected.
-- Replaces the deletable logout-all sentinel row in token_blacklist, which the next password login
-- removed - reviving every token issued before the logout-all (stolen refresh tokens included).
CREATE TABLE IF NOT EXISTS user_token_watermark (
    username            VARCHAR(255) PRIMARY KEY,
    tokens_valid_after  TIMESTAMPTZ  NOT NULL
);

-- Carry over users who are logged out everywhere right now (sentinel still active), so the upgrade
-- does not sign their old tokens back in. Sentinel key = sha256('LOGOUT_ALL_DEVICES:' || username).
INSERT INTO user_token_watermark (username, tokens_valid_after)
SELECT u.username, now()
FROM users u
JOIN token_blacklist b
  ON b.token_hash = encode(sha256(convert_to('LOGOUT_ALL_DEVICES:' || btrim(u.username), 'UTF8')), 'hex')
WHERE b.expires_at > now()
ON CONFLICT (username) DO NOTHING;
