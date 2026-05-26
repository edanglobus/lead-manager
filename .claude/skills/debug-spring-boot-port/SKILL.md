---
name: debug-spring-boot-port
description: Use when the Spring Boot app fails to start with "Port 8080 was already in use" on Windows, or when `./mvnw spring-boot:run` mysteriously appears to do nothing while the previous run seemed to have ended. Specific to the orphaned-JVM gotcha when Maven's wrapper is killed but its forked JVM child survives. Includes the recovery steps.
---

# Recovering from "port 8080 already in use" on Windows

## The gotcha

`./mvnw spring-boot:run` (and the Maven wrapper in general) **forks a child JVM** to run the Spring Boot app. When something kills the wrapper process — `Ctrl+C` on Windows, `TaskStop` from Claude, a terminal disconnect — the child JVM does NOT always receive the signal. The wrapper exits; the JVM keeps running; the JVM keeps holding port 8080.

The next `./mvnw spring-boot:run` then fails with:

```
APPLICATION FAILED TO START
Description:
Web server failed to start. Port 8080 was already in use.
```

This is **not** a code problem. It's an orphaned process.

## Recovery

### Find what's on port 8080

```bash
netstat -ano | grep ':8080' | grep LISTENING
```

Output looks like:

```
TCP    0.0.0.0:8080           0.0.0.0:0              LISTENING       39280
```

The last column is the PID.

### Kill it

```bash
taskkill //PID 39280 //F
```

Note the `//` prefix — that's git-bash on Windows escaping. Plain PowerShell would use `taskkill /PID 39280 /F`.

### One-liner that does both

```bash
netstat -ano | grep ':8080' | grep LISTENING | awk '{print $5}' | head -1 | xargs -I {} taskkill //PID {} //F
```

If nothing is on the port (clean state), `xargs` simply does nothing and exits 0.

## Now start the app again

```bash
cd "<project root>"
set -a && . ./.env && set +a && ./mvnw -B spring-boot:run
```

For background use in Claude Code, `run_in_background=true` on the Bash tool. Then poll for readiness with an `until` loop:

```bash
until curl -sf http://localhost:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; do sleep 2; done && echo "READY"
```

## Prevention (medium-term)

- **Don't** rely on `Ctrl+C` for clean shutdown of `spring-boot:run` on Windows; assume an orphan and use the recovery above.
- If multiple sessions are likely, consider running on a non-default port: `SERVER_PORT=8081 ./mvnw spring-boot:run`.
- For Claude sessions: after `TaskStop` of any `spring-boot:run` task, the next thing you do should be the port-clear one-liner above before starting a new run.

## Why this skill exists

This was discovered the hard way during sub-step 1.3 of slice 1. The bug took longer to diagnose than to fix because the symptom (500 from a curl) didn't match the cause (curl was hitting an OLD instance of the JVM that still had the bug, not the newly-started fixed instance).

## Related

- Maven Spring Boot plugin docs: [`fork` parameter](https://docs.spring.io/spring-boot/maven-plugin/run.html#run.process-forking) (defaults to `true`).
- Linux/Mac equivalent: `lsof -i :8080 | grep LISTEN | awk '{print $2}' | xargs kill -9`.
