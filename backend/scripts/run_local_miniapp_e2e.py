"""Run the backend against a disposable, loopback-only classroom database.

This is a local integration harness, not a deployment entry point. Stop it with
Ctrl+C; it stops PostgreSQL and the backend while retaining ignored logs under
backend/target for diagnosis.
"""

import argparse
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pg-bin", required=True, type=Path)
    parser.add_argument("--jar", type=Path)
    args = parser.parse_args()

    backend = Path(__file__).resolve().parents[1]
    repo = backend.parent
    jar = (args.jar or backend / "target/a-dental-clinic-backend-0.1.0-SNAPSHOT.jar").resolve()
    pg_bin = args.pg_bin.resolve()
    if not jar.is_file():
        parser.error(f"Build the backend jar first: {jar}")
    for name in ("initdb", "pg_ctl", "createdb", "psql"):
        if not (pg_bin / (name + (".exe" if os.name == "nt" else ""))).is_file():
            parser.error(f"PostgreSQL tool is missing: {name}")

    run = backend / "target" / ("local-miniapp-e2e-" + uuid.uuid4().hex)
    run.mkdir(parents=True, exist_ok=False)
    database = "a_dental_teacher_demo_e2e_" + uuid.uuid4().hex[:8]
    db_port = free_port()
    api_port = free_port()
    password = secrets.token_hex(32)
    password_file = run / "init-password"
    password_file.write_text(password, encoding="utf-8")
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    env = {key: value for key, value in os.environ.items()
           if not key.startswith("PG") and key not in ("DB_URL", "DB_USERNAME", "DB_PASSWORD")}
    env.update(PGHOST="127.0.0.1", PGPORT=str(db_port), PGUSER="miniapp_e2e",
               PGPASSWORD=password, PGCONNECT_TIMEOUT="5", PGCLIENTENCODING="UTF8")

    def tool(name: str) -> Path:
        return pg_bin / (name + (".exe" if os.name == "nt" else ""))

    def run_command(parts: list[object], *, quiet: bool = False) -> None:
        subprocess.run([str(part) for part in parts], cwd=backend, env=env,
                       check=True, creationflags=flags,
                       stdout=subprocess.DEVNULL if quiet else None)

    pg_started = False
    app: subprocess.Popen[bytes] | None = None
    log = None
    try:
        run_command([tool("initdb"), "-D", run / "data", "-U", "miniapp_e2e",
                     "--auth=scram-sha-256", "--pwfile", password_file,
                     "--encoding=UTF8", "--locale=C"], quiet=True)
        password_file.unlink()
        run_command([tool("pg_ctl"), "-D", run / "data", "-l", run / "postgres.log",
                     "-o", f"-p {db_port} -h 127.0.0.1", "-w", "-t", "30", "start"], quiet=True)
        pg_started = True
        run_command([tool("createdb"), database], quiet=True)

        app_env = dict(env)
        app_env.update(SPRING_PROFILES_ACTIVE="local",
                       DB_URL=f"jdbc:postgresql://127.0.0.1:{db_port}/{database}",
                       DB_USERNAME="miniapp_e2e", DB_PASSWORD=password,
                       SERVER_PORT=str(api_port), APP_SEED_ENABLED="false",
                       MOCK_WECHAT_ENABLED="true", SESSION_COOKIE_SECURE="false")
        log = (run / "backend.log").open("wb")
        app = subprocess.Popen(["java", "-jar", str(jar), "--server.address=127.0.0.1"],
                               cwd=backend, env=app_env, stdout=log, stderr=subprocess.STDOUT,
                               creationflags=flags)
        url = f"http://127.0.0.1:{api_port}/api/v1"
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if app.poll() is not None:
                raise RuntimeError(f"Backend exited; inspect {run / 'backend.log'}")
            try:
                with urllib.request.urlopen(url + "/public/branches", timeout=2) as response:
                    if response.status == 200:
                        break
            except (urllib.error.URLError, TimeoutError):
                pass
            time.sleep(1)
        else:
            raise RuntimeError(f"Backend did not become ready; inspect {run / 'backend.log'}")

        # Keep psql diagnostics visible: a failed seed must not look like a successful import.
        run_command([tool("psql"), "-X", "-v", "ON_ERROR_STOP=1", "-d", database,
                     "-f", repo / "deploy/teacher-demo-seed.sql"])
        with urllib.request.urlopen(url + "/public/branches", timeout=5) as response:
            if response.status != 200 or "教学虚拟" not in response.read().decode("utf-8"):
                raise RuntimeError("The public branch API did not return classroom data")

        print("Local classroom API is ready:", url, flush=True)
        print("Isolated run directory:", run, flush=True)
        print("WeChat identity uses local mock only; no real WeChat credentials are configured.", flush=True)
        print("Press Ctrl+C to stop both processes.", flush=True)
        while app.poll() is None:
            time.sleep(1)
        raise RuntimeError(f"Backend exited; inspect {run / 'backend.log'}")
    except KeyboardInterrupt:
        print("Stopping local classroom services.", flush=True)
    finally:
        if app is not None and app.poll() is None:
            app.terminate()
            try:
                app.wait(timeout=15)
            except subprocess.TimeoutExpired:
                app.kill()
                app.wait(timeout=5)
        if log is not None:
            log.close()
        # Stop even when startup was interrupted before pg_started was set; never hide a running cluster.
        if pg_started or (run / "data").is_dir():
            status = subprocess.run(
                [str(tool("pg_ctl")), "-D", str(run / "data"), "status"],
                cwd=backend, env=env, creationflags=flags,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            if status.returncode == 0:
                try:
                    run_command([tool("pg_ctl"), "-D", run / "data", "-m", "fast", "-w", "-t", "120", "stop"],
                                quiet=True)
                except subprocess.CalledProcessError:
                    print("Warning: PostgreSQL did not stop cleanly; inspect:", run, file=sys.stderr)
                    raise
                after = subprocess.run(
                    [str(tool("pg_ctl")), "-D", str(run / "data"), "status"],
                    cwd=backend, env=env, creationflags=flags,
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
                if after.returncode == 0:
                    print("Warning: PostgreSQL is still running after stop; inspect:", run, file=sys.stderr)
            elif (run / "data" / "postmaster.pid").exists():
                print("Warning: stale postmaster.pid remains after stopped cluster:", run, file=sys.stderr)
        if password_file.exists():
            password_file.unlink()
        print("Stopped-cluster logs:", run, flush=True)


if __name__ == "__main__":
    main()
