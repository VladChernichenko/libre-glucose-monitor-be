ALTER TABLE notes
    ALTER COLUMN nutrition_profile TYPE JSON USING nutrition_profile::json;
