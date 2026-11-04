CREATE TABLE food_nutrient (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    carbs_100g REAL NOT NULL,
    fiber_100g REAL NOT NULL,
    protein_100g REAL NOT NULL,
    fat_100g REAL NOT NULL,
    kcal_100g REAL NOT NULL,
    source TEXT NOT NULL
);

CREATE TABLE gi_reference (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    gi REAL,
    food_type TEXT,
    source TEXT NOT NULL
);

CREATE TABLE food_alias (
    alias TEXT PRIMARY KEY,
    nutrient_id INTEGER NOT NULL REFERENCES food_nutrient(id),
    gi_id INTEGER REFERENCES gi_reference(id),
    typical_serving_g REAL NOT NULL
);
