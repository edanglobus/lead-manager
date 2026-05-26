---
name: start-lead-manager-backend
description: Use when starting the Lead Manager backend locally on Windows + PowerShell (the supported dev environment). Covers the correct boot order (Postgres → env into process → mvnw), the bash-vs-PowerShell trap (`&&` and `set -a` don't work in PS), how to actually load `.env` into the JVM child process, and the stale-postgres-volume gotcha when `DB_PASSWORD` changes. Trigger when the user says "start the app", "boot the backend", "run the API locally", or hits `password authentication failed for user "leadmanager"` / `The token '&&' is not a valid statement separator`.
---

# Starting the Lead Manager backend locally

## TL;DR (Windows + PowerShell)

```powershell
# 1. Bring up Postgres — pass --env-file explicitly; auto-discovery is flaky on Windows.
docker compose --env-file .\.env up -d postgres

# 2. Load .env into the PowerShell PROCESS so the forked JVM inherits it.
Get-Content .\.env | ForEach-Object {
  if ($_ -match '^\s*([^#=][^=]*)=(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2], 'Process')
  }
}

# 3. Run the app.
.\mvnw spring-boot:run
```

You're up when the log shows `Started LeadManagerApplication in N seconds`. API is on `http://localhost:8080`.

## Pitfall #1 — the README is bash, you're in PowerShell

The README shows:

```bash
set -a && . ./.env && set +a && ./mvnw spring-boot:run
```

That's bash. In PowerShell you get:

```
The token '&&' is not a valid statement separator in this version.
```

Three reasons it fails:
- `&&` chains commands in bash; PowerShell 5.1 (Windows default) uses `;` and has no `&&`.
- `set -a` is a bash builtin to auto-export — no PowerShell equivalent.
- `. ./.env` dot-sources a **PowerShell script**. `.env` is plain `KEY=VALUE` pairs, not PS, so dot-sourcing it does nothing useful.

Use the `ForEach-Object` loop from the TL;DR — that's the PowerShell equivalent of `set -a; source .env; set +a`.

## Pitfall #2 — env vars must reach the child JVM

`./mvnw spring-boot:run` **forks a child JVM** (see [`debug-spring-boot-port`](../debug-spring-boot-port/SKILL.md)). The child inherits the parent's process environment. So:

- `[Environment]::SetEnvironmentVariable($k, $v, 'Process')` → JVM sees it.
- `$env:DB_PASSWORD = 'x'` → also fine (also sets Process scope).
- Setting it at User/System scope without restarting the shell → won't propagate.
- Setting it in a different PowerShell window → obviously won't propagate.

If `DB_PASSWORD` doesn't make it into the JVM, Spring resolves `${DB_PASSWORD}` to empty and you get `FATAL: password authentication failed for user "leadmanager"` — which looks like a DB problem but is really an env-var problem.

## Pitfall #3 — Postgres `initdb` only runs once per volume

The Postgres image creates `POSTGRES_USER` with `POSTGRES_PASSWORD` **only on the first boot of an empty volume**. Change `DB_PASSWORD` in `.env` after that and the running container's user keeps the OLD password forever. Symptom: identical auth-failed error as Pitfall #2.

To check which one you're hitting, the connection test below tells you whether the *current* container actually accepts the *current* `.env` password:

```powershell
$env:PGPASSWORD = (Select-String -Path .\.env -Pattern '^DB_PASSWORD=(.+)').Matches[0].Groups[1].Value
docker exec -e PGPASSWORD=$env:PGPASSWORD lead-manager-postgres psql -U leadmanager -d leadmanager -c 'SELECT 1;'
```

If that fails → it's Pitfall #3. Fix without losing data:

```powershell
docker exec lead-manager-postgres psql -U leadmanager -d leadmanager `
  -c "ALTER USER leadmanager WITH PASSWORD '<value from .env>';"
```

Or wipe and recreate (DESTROYS local users / refresh tokens / any data in the volume):

```powershell
docker compose down --volumes
docker compose --env-file .\.env up -d postgres
```

## Verifying it actually came up

```powershell
# Health (db component must be UP):
curl http://localhost:8080/actuator/health

# Slice 1 login sanity check:
curl -X POST http://localhost:8080/api/v1/auth/login `
  -H 'Content-Type: application/json' `
  -d '{"email":"sixth@example.com","password":"correcthorsebatterystaple"}'
# expect 200 with accessToken + refreshToken
```

## Shutting down

```powershell
# Stop the app: Ctrl+C in the mvnw window.
#   If the JVM gets orphaned (port 8080 stays held), see debug-spring-boot-port.
docker compose down            # stop Postgres, keep volume
docker compose down --volumes  # stop Postgres AND wipe data
```

## Related

- [`debug-spring-boot-port`](../debug-spring-boot-port/SKILL.md) — orphan JVM holding port 8080 after a failed shutdown.
- [`lead-manager-vertical-slice`](../lead-manager-vertical-slice/SKILL.md) — every slice's last sub-step is "boot the app and curl the new endpoint"; this skill is *how* to do that boot.
- [`add-flyway-migration`](../add-flyway-migration/SKILL.md) — Flyway runs at app startup; a bad migration here makes Spring Boot fail to start.
