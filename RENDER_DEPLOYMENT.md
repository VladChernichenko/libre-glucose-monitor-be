# Render Deployment Guide

This guide will help you deploy your Spring Boot application to Render with a PostgreSQL database.

## Prerequisites

1. A Render account (free tier available)
2. Your code pushed to a Git repository (GitHub, GitLab, or Bitbucket)

## Step 1: Create a PostgreSQL Database

1. Go to your Render dashboard
2. Click "New +" → "PostgreSQL"
3. Configure your database:
   - **Name**: `glucose-monitor-db`
   - **Database**: `glucose_monitor` (must be lowercase with underscores only)
   - **User**: `glucose_monitor_user`
   - **Plan**: Free (or paid if you need more resources)

### ⚠️ Important: Render Database Naming Requirements

Render PostgreSQL databases must follow these naming rules:
- **Database name**: Must match pattern `/(^[a-z_][a-z0-9_]*$)|(^$)/`
- **Allowed characters**: lowercase letters (a-z), numbers (0-9), underscores (_)
- **Must start with**: lowercase letter or underscore
- **Examples**: ✅ `glucose_monitor`, ✅ `my_app_db`, ❌ `glucose-monitor`, ❌ `MyApp`

4. Click "Create Database"
5. **Important**: Save the connection details, especially the `DATABASE_URL`

## Step 2: Deploy Your Web Service

1. In your Render dashboard, click "New +" → "Web Service"
2. Connect your Git repository
3. Configure your service:
   - **Name**: `glucose-monitor-be`
   - **Environment**: `Java`
   - **Build Command**: `./gradlew build`
   - **Start Command**: `java -Dspring.profiles.active=prod -jar build/libs/glucose-monitor-be-*.jar`
   - **Plan**: Free (or paid if you need more resources)

## Step 3: Configure Environment Variables

In your web service settings, add these environment variables:

### Required Variables:
- `SPRING_PROFILES_ACTIVE` = `prod`
- `DATABASE_URL` = (Copy from your PostgreSQL service - Render provides this automatically)
- `JWT_SECRET` = (Generate a strong secret key, at least 32 characters)

### Optional Variables:
- `CORS_ALLOWED_ORIGINS` = `https://your-frontend-domain.onrender.com` (replace with your actual frontend URL)
- `PORT` = `8080` (Render sets this automatically, but you can override if needed)

## Step 4: Database Connection

Render automatically provides the `DATABASE_URL` environment variable in the format:
```
postgresql://username:password@host:port/database
```

Your application is configured to use this automatically.

## Step 5: Deploy

1. Click "Create Web Service"
2. Render will build and deploy your application
3. Monitor the build logs for any issues
4. Once deployed, your API will be available at: `https://your-service-name.onrender.com`

## Step 6: Test Your Deployment

1. **Health Check**: `GET https://your-service-name.onrender.com/actuator/health`
2. **Test Endpoint**: `GET https://your-service-name.onrender.com/api/auth/test`
3. **Register User**: `POST https://your-service-name.onrender.com/api/auth/register`

## Troubleshooting

### Database Connection Issues
- Ensure your PostgreSQL service is running
- Check that `DATABASE_URL` is correctly set
- Verify the database name, username, and password match
- **Important**: Database name must be lowercase with underscores only (e.g., `glucose_monitor`, not `glucose-monitor`)

### Build Failures
- Check the build logs in Render dashboard
- Ensure all dependencies are correctly specified in `build.gradle`
- Verify Java version compatibility (your app uses Java 21)

### CORS Issues
- Update `CORS_ALLOWED_ORIGINS` environment variable with your frontend URL
- Ensure your frontend is making requests to the correct Render URL

### JWT Issues
- Ensure `JWT_SECRET` is set and is at least 32 characters long
- Use a strong, random secret key

## Environment-Specific Configuration

### Development (Local)
- Uses `application.yml` with local PostgreSQL
- CORS allows `localhost:3000`
- SQL logging enabled

### Production (Render)
- Uses `application-prod.yml` with Render PostgreSQL
- CORS configurable via environment variables
- SQL logging disabled
- Flyway migrations enabled

## Database Migrations

The application uses Flyway for database migrations:
- Migration files are in `src/main/resources/db/migration/`
- Migrations run automatically on startup
- `baseline-on-migrate: true` allows existing databases to be migrated

## Security Considerations

1. **JWT Secret**: Use a strong, random secret key in production
2. **CORS**: Only allow your frontend domain(s)
3. **Database**: Use strong passwords and limit access
4. **HTTPS**: Render provides HTTPS automatically

## Monitoring

- Use Render's built-in monitoring
- Check application logs in the Render dashboard
- Monitor database performance and usage

## Scaling

- Free tier has limitations (sleeps after inactivity)
- Consider paid plans for production use
- Monitor resource usage and upgrade as needed

## Support

- Check Render documentation: https://render.com/docs
- Spring Boot deployment guide: https://spring.io/guides/gs/spring-boot-for-azure/
- PostgreSQL on Render: https://render.com/docs/databases/postgresql
