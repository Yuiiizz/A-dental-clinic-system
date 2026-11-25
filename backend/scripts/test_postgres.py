"""Start a disposable loopback-only PostgreSQL cluster; never use existing databases."""
import sys
sys.stdout.reconfigure(errors="replace")
import argparse
import os
from pathlib import Path
import secrets
import socket
import subprocess
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--pg-bin', required=True)
parser.add_argument('--maven', required=True)
parser.add_argument('--settings')
args = parser.parse_args()
backend = Path(__file__).resolve().parents[1]
run_id = uuid.uuid4().hex
run = backend / 'target' / ('test-postgres-' + run_id)
run.mkdir(parents=True, exist_ok=False)
pg = Path(args.pg_bin).resolve()
exe = '.exe' if os.name == 'nt' else ''
env = dict(os.environ)
# Do not inherit options/credentials that could redirect libpq or tests.
for key in list(env):
    if key.startswith(('PG', 'TEST_DB_', 'DENTAL_TEST_')) or key in ('DB_URL','DB_USERNAME','DB_PASSWORD'):
        env.pop(key)
password = secrets.token_hex(32)
token = secrets.token_hex(32)
password_file = run / 'init-password'
password_file.write_text(password, encoding='utf-8')
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    port = sock.getsockname()[1]
database = 'dental_test_' + run_id
env.update(PGHOST='127.0.0.1', PGPORT=str(port), PGUSER='dental_test_runner', PGPASSWORD=password, PGCONNECT_TIMEOUT='5')
flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0

def execute(command, **kwargs):
    return subprocess.run([str(x) for x in command], cwd=backend, env=env, check=True,
                          creationflags=flags, **kwargs)

def tool(name):
    return pg / (name + exe)

started = False
try:
    execute([tool('initdb'), '-D', run/'data', '-U', 'dental_test_runner', '--auth=scram-sha-256',
             '--pwfile', password_file, '--encoding=UTF8', '--locale=C'], stdout=subprocess.DEVNULL)
    password_file.unlink()
    execute([tool('pg_ctl'), '-D', run/'data', '-l', run/'postgres.log', '-o',
             f'-p {port} -h 127.0.0.1', '-w', '-t', '30', 'start'], stdout=subprocess.DEVNULL)
    started = True
    execute([tool('createdb'), database])
    execute([tool('psql'), '-X', '-v', 'ON_ERROR_STOP=1', '-d', database],
            input=f"CREATE SCHEMA test_harness; CREATE TABLE test_harness.identity(token text NOT NULL); INSERT INTO test_harness.identity VALUES ('{token}');", text=True, stdout=subprocess.DEVNULL)
    env.update(TEST_DB_URL=f'jdbc:postgresql://127.0.0.1:{port}/{database}',
               TEST_DB_USERNAME='dental_test_runner', TEST_DB_PASSWORD=password, DB_PASSWORD=password,
               DENTAL_TEST_DATABASE=database, DENTAL_TEST_PORT=str(port), DENTAL_TEST_TOKEN=token)
    command = [args.maven]
    if args.settings:
        command += ['-s', args.settings]
    command += ['test', '-q']
    with (run / "maven.log").open("w", encoding="utf-8") as log:
        try:
            execute(command, stdout=log, stderr=subprocess.STDOUT)
        except subprocess.CalledProcessError:
            log.flush()
            print((run / "maven.log").read_text(encoding="utf-8", errors="replace")[-10000:])
            raise
    print('PASS: isolated PostgreSQL tests completed; cluster will be stopped.')
finally:
    if started:
        # Windows filesystem sync can outlast the 30-second default even after all tests pass.
        execute([tool('pg_ctl'), '-D', run/'data', '-m', 'fast', '-w', '-t', '120', 'stop'], stdout=subprocess.DEVNULL)
    if password_file.exists():
        password_file.unlink()
    # Keep stopped test artifacts under ignored target for diagnostics; no recursive deletion.
    print('Stopped-cluster artifacts:', run)
