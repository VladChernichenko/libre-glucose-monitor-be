-- Fix permissions for existing database and user
-- Run this as a superuser (postgres)

-- Connect to the glucose_monitor database
\c glucose_monitor;

-- Grant all necessary permissions
GRANT ALL ON SCHEMA public TO glucose_monitor_user;
GRANT CREATE ON SCHEMA public TO glucose_monitor_user;
GRANT USAGE ON SCHEMA public TO glucose_monitor_user;

-- Grant table creation privileges
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO glucose_monitor_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO glucose_monitor_user;

-- Grant all privileges on all existing tables (if any)
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO glucose_monitor_user;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO glucose_monitor_user;

-- Make sure the user can create tables
GRANT CREATE ON DATABASE glucose_monitor TO glucose_monitor_user;
