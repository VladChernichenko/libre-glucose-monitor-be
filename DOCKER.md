# Docker Setup for Glucose Monitor Backend

This document explains how to run the Glucose Monitor Backend application using Docker.

## Prerequisites

- Docker Desktop installed and running
- Docker Compose (usually comes with Docker Desktop)

## Quick Start

### Option 1: Using the provided script
```bash
./docker-run.sh
```

### Option 2: Manual commands
```bash
# Build and start all services
docker-compose up --build

# Or run in detached mode
docker-compose up --build -d
```

## Services

The Docker setup includes two services:

1. **PostgreSQL Database** (port 5433)
   - Database: `glucose_monitor`
   - Username: `glucose_monitor_user`
   - Password: `glucose`

2. **Spring Boot Application** (port 8080)
   - Main application server
   - Health check endpoint: `http://localhost:8080/actuator/health`

## Useful Commands

### View logs
```bash
# All services
docker-compose logs -f

# Specific service
docker-compose logs -f app
docker-compose logs -f postgres
```

### Stop services
```bash
# Stop and remove containers
docker-compose down

# Stop and remove containers, networks, and volumes
docker-compose down -v
```

### Rebuild application
```bash
# Rebuild only the app service
docker-compose up --build app

# Rebuild all services
docker-compose up --build
```

### Access database
```bash
# Connect to PostgreSQL container
docker-compose exec postgres psql -U glucose_monitor_user -d glucose_monitor
```



## Environment Variables

The application uses the following environment variables:

- `SPRING_PROFILES_ACTIVE=docker` - Activates Docker-specific configuration
- `DB_PASSWORD=glucose` - Database password (can be overridden)

## Health Checks

All services include health checks:

- **PostgreSQL**: Checks if database is ready to accept connections
- **Application**: Checks if the Spring Boot actuator health endpoint is responding

## Troubleshooting

### Application won't start
1. Check if all services are healthy: `docker-compose ps`
2. Check application logs: `docker-compose logs app`
3. Ensure database is ready before application starts (health checks handle this)

### Database connection issues
1. Verify PostgreSQL is running: `docker-compose logs postgres`
2. Check if database was initialized properly
3. Try connecting manually: `docker-compose exec postgres psql -U glucose_monitor_user -d glucose_monitor`

### Port conflicts
If you have services running on ports 5433 or 8080, you can modify the port mappings in `docker-compose.yml`:

```yaml
ports:
  - "5434:5432"  # Map to different host port
```

## Development

For development, you can:

1. Mount your source code as a volume for live reloading
2. Use Docker for databases only and run the app locally
3. Use the provided `application-docker.yml` configuration

## Production Considerations

- Change default passwords in `docker-compose.yml`
- Use environment variables for sensitive data
- Consider using Docker secrets for production
- Set up proper logging and monitoring
- Use a reverse proxy (nginx) for production
