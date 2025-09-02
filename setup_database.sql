-- Create database for glucose monitoring application
CREATE DATABASE glucose_monitor;

-- Connect to the new database
\c glucose_monitor;

-- Create user for the application
CREATE USER glucose_monitor_user WITH PASSWORD 'glucose_monitor_password';

-- Grant privileges to the user
GRANT ALL PRIVILEGES ON DATABASE glucose_monitor TO glucose_monitor_user;

-- Grant schema privileges
GRANT ALL ON SCHEMA public TO glucose_monitor_user;

-- Grant schema privileges (this is crucial for table creation)
GRANT ALL ON SCHEMA public TO glucose_monitor_user;
GRANT CREATE ON SCHEMA public TO glucose_monitor_user;

-- Grant table creation privileges
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO glucose_monitor_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO glucose_monitor_user;

-- Create extensions if needed
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Grant usage on the database
GRANT CONNECT ON DATABASE glucose_monitor TO glucose_monitor_user;

-- Create users table
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    username VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    account_non_expired BOOLEAN NOT NULL DEFAULT TRUE,
    credentials_non_expired BOOLEAN NOT NULL DEFAULT TRUE,
    account_non_locked BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
