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
