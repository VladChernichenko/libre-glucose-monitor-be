# Get started with Neon (PostgreSQL)

[Neon](https://neon.tech) is serverless Postgres. This app already uses PostgreSQL with Flyway migrations, so you only need a connection string and environment variables.

## 1. Create a database

1. Sign up at [https://console.neon.tech](https://console.neon.tech).
2. **Create project** and pick a region close to your app.
3. Neon creates a default branch and database (often `neondb`).

## 2. Connect locally (terminal or IntelliJ)

You do **not** need a local Postgres process if the app uses Neon.

1. In [Neon console](https://console.neon.tech) open the project ? **Connect** ? copy the **JDBC** URL (`jdbc:postgresql://...`, usually with `sslmode=require` and `user` / `password` query params).

2. **Option A - `prod` profile** (matches deployment; recommended if you already run with `prod`):

   **IntelliJ:** Run Configuration ? *Modify options* ? **Environment variables**:

   - `SPRING_PROFILES_ACTIVE` = `prod`
   - `DATABASE_URL` = *(paste JDBC string from Neon)*
   - `JWT_SECRET` = *(any long random string, 32+ chars)*

   **Terminal:**

   ```bash
   cd glucose-monitor-be
   export SPRING_PROFILES_ACTIVE=prod
   export DATABASE_URL='jdbc:postgresql://...'
   export JWT_SECRET='your-local-dev-secret-at-least-32-chars'
   ./gradlew bootRun
   ```

3. **Option B - default (non-prod) profile** with Neon as the DB only:

   Set `DATABASE_URL` to the same JDBC string. Clear inherited DB user/password so Neon's URL credentials are used:

   - `DB_USERNAME` = *(empty)*
   - `DB_PASSWORD` = *(empty)*

   In IntelliJ, add those empty vars or leave the value blank if the field allows it; from a shell use `export DB_USERNAME=` and `export DB_PASSWORD=`.

Do not put the JDBC string in `application.yml` or commit it.

## 3. Connection string for Spring Boot

In the Neon console, open your project ? **Dashboard** ? **Connect**.

1. Choose **JDBC** (not the generic URI only, unless you convert it yourself).
2. Copy the URL. It must start with `jdbc:postgresql://` and include SSL, for example:
   - `?sslmode=require` (or `verify-full` if Neon shows it).

If the console only shows `postgresql://...`, convert it:

- Prefix: `jdbc:postgresql://`
- Keep host, path, and query string (`?sslmode=require`, etc.).
- Put username/password in the URL as Neon documents (often as query parameters like `user=` and `password=` in the JDBC example).

Do **not** commit credentials; use environment variables only.

## 4. Environment variables (deployed / prod-style)

Use the **prod** profile (same pattern as Render): migrations run via Flyway on startup.

| Variable | Required | Notes |
|----------|----------|--------|
| `SPRING_PROFILES_ACTIVE` | yes | `prod` |
| `DATABASE_URL` | yes | Full JDBC URL from Neon (see above). |
| `JWT_SECRET` | yes | Long random string (32+ characters). |
| `DB_USERNAME` | no | Leave unset so credentials from `DATABASE_URL` are used. Only set if your URL has no user/password. |
| `DB_PASSWORD` | no | Same as `DB_USERNAME`. |
| `CORS_ALLOWED_ORIGINS` | recommended | Your frontend origin(s), comma-separated. |

Example (local shell, do not paste real secrets into project files):

```bash
export SPRING_PROFILES_ACTIVE=prod
export DATABASE_URL='jdbc:postgresql://ep-xxxx.region.aws.neon.tech/neondb?sslmode=require&user=...&password=...'
export JWT_SECRET='your-long-random-secret'
./gradlew bootRun
```

Or run the jar:

```bash
java -jar build/libs/glucose-monitor-be-*.jar
```

## 5. Pooling and branches

- Neon offers a **pooled** host (often `-pooler` in the hostname) for many short-lived connections; use the connection string Neon labels for your stack (e.g. serverless / pooled).
- You can use **preview branches** for staging; point `DATABASE_URL` at that branch's connection string.

## 6. Verify

After startup:

- `GET http://localhost:8080/actuator/health` (or your deployed host)
- `GET /api/auth/test`

If the app fails to connect, check:

- URL uses `jdbc:postgresql://` and includes `sslmode=require` (or stricter) as Neon instructs.
- Password special characters: use the exact JDBC string from Neon (it is usually already encoded).
- `JWT_SECRET` is set when using `prod`.

## 7. Production hosting

Point your host (Railway, Fly.io, Render, Kubernetes, etc.) at the same variables. You do **not** need a bundled Postgres container if `DATABASE_URL` points at Neon.

See also [RENDER_DEPLOYMENT.md](./RENDER_DEPLOYMENT.md) for a similar `DATABASE_URL` + `prod` profile layout.
