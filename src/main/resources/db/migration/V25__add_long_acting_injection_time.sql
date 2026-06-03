-- Optional daily time-of-day at which the user takes their long-acting (basal) insulin.
-- Used by the client to enable the "Log long-acting insulin" action only around that time.
-- Nullable: when unset, the client leaves the action always available.
ALTER TABLE user_insulin_preferences ADD COLUMN long_acting_injection_time TIME;
