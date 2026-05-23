-- Add locale (Accept-Language / region tag) to LibreLinkUp data source configs.
-- Used by the background LibreLinkUp sync scheduler to authenticate against the
-- correct regional endpoint (e.g. "fr-FR" → api-fr.libreview.io).
ALTER TABLE user_data_source_config
    ADD COLUMN IF NOT EXISTS libre_locale VARCHAR(20);
