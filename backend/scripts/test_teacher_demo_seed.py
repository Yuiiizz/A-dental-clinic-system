"""Verify the explicit classroom fixture against a disposable PostgreSQL cluster."""
import argparse
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--pg-bin', required=True)
args = parser.parse_args()
backend = Path(__file__).resolve().parents[1]
repo = backend.parent
run = backend / 'target' / ('teacher-demo-test-' + uuid.uuid4().hex)
run.mkdir(parents=True, exist_ok=False)
pg = Path(args.pg_bin).resolve()
exe = '.exe' if os.name == 'nt' else ''
password = secrets.token_hex(32)
password_file = run / 'init-password'
password_file.write_text(password, encoding='utf-8')
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    port = sock.getsockname()[1]
database = 'a_dental_teacher_demo_test_' + uuid.uuid4().hex[:8]
env = {key: value for key, value in os.environ.items() if not key.startswith('PG')}
env.update(PGHOST='127.0.0.1', PGPORT=str(port), PGUSER='teacher_test',
           PGPASSWORD=password, PGCONNECT_TIMEOUT='5')
flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0

def execute(*parts, capture=False, check=True, quiet=False):
    return subprocess.run([str(x) for x in parts], cwd=backend, env=env, check=check,
                          text=True, capture_output=capture,
                          stdout=subprocess.DEVNULL if quiet else None,
                          stderr=subprocess.DEVNULL if quiet else None,
                          creationflags=flags)

def tool(name):
    return pg / (name + exe)

started = False
try:
    execute(tool('initdb'), '-D', run / 'data', '-U', 'teacher_test',
            '--auth=scram-sha-256', '--pwfile', password_file,
            '--encoding=UTF8', '--locale=C', capture=True)
    password_file.unlink()
    execute(tool('pg_ctl'), '-D', run / 'data', '-l', run / 'postgres.log',
            '-o', f'-p {port} -h 127.0.0.1', '-w', '-t', '30', 'start', quiet=True)
    started = True
    execute(tool('createdb'), database, capture=True)
    migrations = backend / 'src/main/resources/db/migration'
    for migration in sorted(migrations.glob('V*.sql'), key=lambda file: int(file.name.split('_')[0][1:])):
        execute(tool('psql'), '-X', '-v', 'ON_ERROR_STOP=1', '-d', database, '-f', migration, capture=True)
    seed = repo / 'deploy/teacher-demo-seed.sql'
    execute(tool('psql'), '-X', '-v', 'ON_ERROR_STOP=1', '-d', database, '-f', seed, capture=True)
    repeated = execute(tool('psql'), '-X', '-v', 'ON_ERROR_STOP=1', '-d', database,
                       '-f', seed, capture=True, check=False)
    assert repeated.returncode != 0, 'repeat import unexpectedly succeeded'
    consumable_hex = '耗材'.encode('utf-8').hex()
    posted_hex = '已过账'.encode('utf-8').hex()
    inbound_hex = '采购入库'.encode('utf-8').hex()
    # Keep the Windows psql -c argument ASCII; the seed file itself remains UTF-8.
    query = f"""SELECT (SELECT count(*) FROM staff WHERE staff_id BETWEEN 911 AND 918),
        (SELECT count(*) FROM branch WHERE branch_id IN (901,902)),
        (SELECT count(*) FROM schedule WHERE dentist_id BETWEEN 911 AND 918),
        (SELECT count(*) FROM patient WHERE patient_id BETWEEN 931 AND 938),
        (SELECT count(*) FROM appointment WHERE appointment_id BETWEEN 941 AND 948),
        (SELECT count(*) FROM resource_booking rb JOIN appointment a ON a.appointment_id=rb.appointment_id WHERE a.appointment_id BETWEEN 941 AND 948),
        (SELECT count(*) FROM internal_staff_message WHERE branch_id=901),
        (SELECT count(*) FROM supplier WHERE supplier_id BETWEEN 901 AND 903 AND credit_code LIKE 'DEMO-SUP-%'),
        (SELECT count(*) FROM stock_item WHERE stock_item_id BETWEEN 901 AND 904 AND encode(convert_to(kind,'UTF8'),'hex')='{consumable_hex}'),
        (SELECT count(*) FROM warehouse WHERE warehouse_id BETWEEN 901 AND 903),
        (SELECT count(*) FROM purchase_receipt WHERE receipt_id BETWEEN 901 AND 903 AND encode(convert_to(state,'UTF8'),'hex')='{posted_hex}'),
        (SELECT count(*) FROM purchase_receipt_line WHERE receipt_line_id BETWEEN 901 AND 905),
        (SELECT count(*) FROM stock_movement WHERE receipt_line_id BETWEEN 901 AND 905 AND encode(convert_to(kind,'UTF8'),'hex')='{inbound_hex}'),
        (SELECT count(*) FROM account_role ar JOIN role r ON r.role_id=ar.role_id WHERE ar.account_id=903 AND r.code='inventory'),
        (SELECT count(*) FROM schedule WHERE dentist_id BETWEEN 911 AND 918 AND starts_at <= now()),
        (SELECT count(*) FROM account WHERE account_id BETWEEN 901 AND 918 AND password_hash IS NOT NULL),
        (SELECT count(*) FROM wechat_identity)"""
    result = execute(tool('psql'), '-X', '-At', '-d', database, '-c', query, capture=True)
    values = tuple(map(int, result.stdout.strip().split('|')))
    assert values == (8, 2, 112, 8, 8, 8, 4, 3, 4, 3, 3, 5, 5, 1, 0, 0, 0), values
    print('PASS: 8 synthetic doctors, 2 branches, 112 future schedules, 8 patients, 8 appointments, 3 suppliers, 4 stock items, 3 warehouses, 3 posted receipts, 5 matched inbound movements; no passwords or WeChat bindings')
    expansion = repo / 'deploy/operations/2026-11-23-inventory-samples.sql'
    first_expansion = execute(tool('psql'), '-X', '-v', 'ON_ERROR_STOP=1', '-d', database,
                              '-f', expansion, capture=True, check=False)
    assert first_expansion.returncode == 0, first_expansion.stderr
    repeated_expansion = execute(tool('psql'), '-X', '-v', 'ON_ERROR_STOP=1', '-d', database,
                                 '-f', expansion, capture=True, check=False)
    assert repeated_expansion.returncode != 0, 'repeat inventory expansion unexpectedly succeeded'
    audit_operation_hex = '导入虚构库存样例'.encode('utf-8').hex()
    expanded_query = f"""SELECT
        (SELECT count(*) FROM stock_item WHERE code LIKE 'DEMO-CONS-%'),
        (SELECT count(*) FROM purchase_receipt WHERE document_no LIKE 'DEMO-INV-20261123-%' AND encode(convert_to(state,'UTF8'),'hex')='{posted_hex}'),
        (SELECT count(*) FROM purchase_receipt_line l JOIN purchase_receipt r ON r.receipt_id=l.receipt_id WHERE r.document_no LIKE 'DEMO-INV-20261123-%'),
        (SELECT count(*) FROM stock_movement m JOIN purchase_receipt_line l ON l.receipt_line_id=m.receipt_line_id JOIN purchase_receipt r ON r.receipt_id=l.receipt_id WHERE r.document_no LIKE 'DEMO-INV-20261123-%' AND m.quantity_delta=l.quantity),
        (SELECT count(DISTINCT b.stock_item_id) FROM purchase_receipt_line l JOIN purchase_receipt r ON r.receipt_id=l.receipt_id JOIN stock_batch b ON b.batch_id=l.batch_id WHERE r.branch_id=901 AND r.document_no LIKE 'DEMO-INV-20261123-%'),
        (SELECT count(DISTINCT b.stock_item_id) FROM purchase_receipt_line l JOIN purchase_receipt r ON r.receipt_id=l.receipt_id JOIN stock_batch b ON b.batch_id=l.batch_id WHERE r.branch_id=902 AND r.document_no LIKE 'DEMO-INV-20261123-%'),
        (SELECT count(DISTINCT b.stock_item_id) FROM purchase_receipt_line l JOIN purchase_receipt r ON r.receipt_id=l.receipt_id JOIN stock_batch b ON b.batch_id=l.batch_id WHERE r.branch_id=901 AND encode(convert_to(r.state,'UTF8'),'hex')='{posted_hex}'),
        (SELECT count(DISTINCT b.stock_item_id) FROM purchase_receipt_line l JOIN purchase_receipt r ON r.receipt_id=l.receipt_id JOIN stock_batch b ON b.batch_id=l.batch_id WHERE r.branch_id=902 AND encode(convert_to(r.state,'UTF8'),'hex')='{posted_hex}'),
        (SELECT count(*) FROM audit_log WHERE encode(convert_to(operation,'UTF8'),'hex')='{audit_operation_hex}')"""
    expanded = execute(tool('psql'), '-X', '-At', '-d', database, '-c', expanded_query, capture=True)
    expanded_values = tuple(map(int, expanded.stdout.strip().split('|')))
    assert expanded_values == (18, 6, 28, 28, 14, 14, 17, 15, 6), expanded_values
    print('PASS: inventory expansion adds 14 items, 6 posted receipts and 28 matched movements across both branches; repeat import refused')
    directory_expansion = repo / 'deploy/teacher-demo-expand.sql'
    first_directory = execute(tool('psql'), '-X', '-v', 'ON_ERROR_STOP=1', '-d', database,
                              '-f', directory_expansion, capture=True, check=False)
    assert first_directory.returncode == 0, first_directory.stderr
    repeated_directory = execute(tool('psql'), '-X', '-v', 'ON_ERROR_STOP=1', '-d', database,
                                 '-f', directory_expansion, capture=True, check=False)
    assert repeated_directory.returncode != 0, 'repeat directory expansion unexpectedly succeeded'
    directory_query = """SELECT
        (SELECT count(*) FROM branch WHERE branch_id IN (901,902,903,904)),
        (SELECT count(*) FROM staff WHERE staff_id BETWEEN 911 AND 918 OR staff_id BETWEEN 921 AND 924),
        (SELECT count(*) FROM branch WHERE branch_id IN (901,902,903,904) AND public_intro IS NOT NULL),
        (SELECT count(*) FROM staff WHERE (staff_id BETWEEN 911 AND 918 OR staff_id BETWEEN 921 AND 924) AND public_intro IS NOT NULL),
        (SELECT count(*) FROM schedule WHERE dentist_id BETWEEN 921 AND 924),
        (SELECT count(*) FROM branch_opening_interval WHERE branch_id IN (903,904)),
        (SELECT nextval(pg_get_serial_sequence('branch','branch_id')) > max(branch_id) FROM branch),
        (SELECT nextval(pg_get_serial_sequence('staff','staff_id')) > max(staff_id) FROM staff),
        (SELECT nextval(pg_get_serial_sequence('account','account_id')) > max(account_id) FROM account),
        (SELECT nextval(pg_get_serial_sequence('resource','resource_id')) > max(resource_id) FROM resource),
        (SELECT nextval(pg_get_serial_sequence('booking_policy','policy_id')) > max(policy_id) FROM booking_policy)"""
    directory_result = execute(tool('psql'), '-X', '-At', '-d', database, '-c', directory_query, capture=True)
    directory_values = directory_result.stdout.strip().split('|')
    assert directory_values == ['4', '12', '4', '12', '56', '56', 't', 't', 't', 't', 't'], directory_values
    print('PASS: directory expansion adds 2 branches, 4 doctors, public introductions and 56 schedules; IDs advance and repeat import refused')
    linked_before = execute(tool('psql'), '-X', '-At', '-d', database, '-c',
                            "SELECT string_agg(a.appointment_id::text||':'||s.starts_at::text||':'||encode(convert_to(s.state,'UTF8'),'hex'),',' ORDER BY a.appointment_id) FROM appointment a JOIN schedule s USING(schedule_id)", capture=True).stdout.strip()
    roster = repo / 'deploy/operations/2026-11-23-rotating-shifts-assistants.sql'
    first_roster = execute(tool('psql'), '-X', '-v', 'ON_ERROR_STOP=1', '-d', database,
                           '-f', roster, capture=True, check=False)
    assert first_roster.returncode == 0, first_roster.stderr
    repeated_roster = execute(tool('psql'), '-X', '-v', 'ON_ERROR_STOP=1', '-d', database,
                              '-f', roster, capture=True, check=False)
    assert repeated_roster.returncode != 0, 'repeat roster import unexpectedly succeeded'
    linked_after = execute(tool('psql'), '-X', '-At', '-d', database, '-c',
                           "SELECT string_agg(a.appointment_id::text||':'||s.starts_at::text||':'||encode(convert_to(s.state,'UTF8'),'hex'),',' ORDER BY a.appointment_id) FROM appointment a JOIN schedule s USING(schedule_id)", capture=True).stdout.strip()
    assert linked_before == linked_after, 'an appointment-linked schedule changed'
    roster_query = """SELECT (SELECT count(*) FROM staff WHERE staff_id BETWEEN 951 AND 962),
       (SELECT count(*) FROM assistant_shift),
       (SELECT count(*) FROM schedule WHERE branch_id IN (901,902) AND dentist_id BETWEEN 911 AND 918 AND (starts_at AT TIME ZONE 'Asia/Shanghai')::time=TIME '13:00'),
       (SELECT count(*) FROM schedule WHERE branch_id IN (901,902) AND dentist_id BETWEEN 911 AND 918 AND version>1)"""
    roster_values = tuple(map(int, execute(tool('psql'), '-X', '-At', '-d', database,
                                           '-c', roster_query, capture=True).stdout.strip().split('|')))
    assert roster_values[0] == 12 and all(value > 0 for value in roster_values[1:]), roster_values
    print(f'PASS: rotating roster adds {roster_values[1]} assistant shifts, afternoon dentist shifts and off-duty days; linked appointments unchanged; repeat refused')
    # Emulate a patient signing in after the earlier directory expansion. This
    # occupies a new account ID in the reserved-looking 925-936 range, so new
    # staff accounts must use generated IDs instead of assuming account=staff.
    unreported_hex = '未申报'.encode('utf-8').hex()
    patient_account = f"""WITH new_patient AS (
      INSERT INTO patient(name,phone,allergy_status)
      VALUES ('synthetic-extra-patient','00000000999',convert_from(decode('{unreported_hex}','hex'),'UTF8'))
      RETURNING patient_id
    ) INSERT INTO account(patient_id,enabled,must_change_password,created_at)
      SELECT patient_id,true,false,now() FROM new_patient RETURNING account_id"""
    occupied_account = execute(tool('psql'), '-X', '-At', '-d', database,
                               '-c', patient_account, capture=True).stdout.strip().splitlines()[0]
    assert 925 <= int(occupied_account) <= 936, occupied_account
    expanded_roster = repo / 'deploy/operations/2026-11-23-parttime-doctors.sql'
    first_parttime = execute(tool('psql'), '-X', '-v', 'ON_ERROR_STOP=1', '-d', database,
                             '-f', expanded_roster, capture=True, check=False)
    assert first_parttime.returncode == 0, first_parttime.stderr
    repeated_parttime = execute(tool('psql'), '-X', '-v', 'ON_ERROR_STOP=1', '-d', database,
                                '-f', expanded_roster, capture=True, check=False)
    assert repeated_parttime.returncode != 0, 'repeat part-time expansion unexpectedly succeeded'
    linked_after_parttime = execute(tool('psql'), '-X', '-At', '-d', database, '-c',
                           "SELECT string_agg(a.appointment_id::text||':'||s.starts_at::text||':'||encode(convert_to(s.state,'UTF8'),'hex'),',' ORDER BY a.appointment_id) FROM appointment a JOIN schedule s USING(schedule_id)", capture=True).stdout.strip()
    assert linked_before == linked_after_parttime, 'part-time expansion changed an appointment-linked schedule'
    dentist_hex = '牙医'.encode('utf-8').hex()
    published_hex = '已发布'.encode('utf-8').hex()
    verified_hex = 'verified'.encode('utf-8').hex()
    parttime_query = f"""SELECT
      (SELECT count(*) FROM staff WHERE staff_id BETWEEN 925 AND 936),
      (SELECT count(*) FROM account WHERE staff_id BETWEEN 925 AND 936 AND NOT enabled AND password_hash IS NULL),
      (SELECT count(*) FROM credential WHERE staff_id BETWEEN 925 AND 936 AND encode(convert_to(verification_state,'UTF8'),'hex')='{verified_hex}'),
      (SELECT count(*) FROM schedule WHERE dentist_id BETWEEN 925 AND 936 AND encode(convert_to(state,'UTF8'),'hex')='{published_hex}'),
      (SELECT count(*) FROM (SELECT sb.branch_id FROM staff_branch sb JOIN staff s ON s.staff_id=sb.staff_id
          WHERE sb.branch_id BETWEEN 901 AND 904 AND encode(convert_to(s.staff_type,'UTF8'),'hex')='{dentist_hex}' GROUP BY sb.branch_id HAVING count(*)=6) x)"""
    parttime_values = tuple(map(int, execute(tool('psql'), '-X', '-At', '-d', database,
                                '-c', parttime_query, capture=True).stdout.strip().split('|')))
    assert parttime_values[:3] == (12,12,12) and parttime_values[3] > 0 and parttime_values[4] == 4, parttime_values
    weekly_query = f"""WITH days AS (
      SELECT (now() AT TIME ZONE 'Asia/Shanghai')::date+n AS work_day FROM generate_series(1,14) n
    ), full_weeks AS (
      SELECT date_trunc('week',work_day::timestamp)::date AS week_start FROM days
      GROUP BY 1 HAVING count(*)=7
    ), doctor_weeks AS (
      SELECT sb.staff_id,w.week_start,count(sc.schedule_id) AS shifts
      FROM staff_branch sb CROSS JOIN full_weeks w
      LEFT JOIN schedule sc ON sc.dentist_id=sb.staff_id AND sc.branch_id=sb.branch_id
        AND sc.state=convert_from(decode('{published_hex}','hex'),'UTF8')
        AND (sc.starts_at AT TIME ZONE 'Asia/Shanghai')::date BETWEEN w.week_start AND w.week_start+6
      WHERE sb.branch_id BETWEEN 901 AND 904 AND
        (sb.staff_id BETWEEN 911 AND 918 OR sb.staff_id BETWEEN 921 AND 936)
      GROUP BY sb.staff_id,w.week_start
    ) SELECT count(*),min(shifts),max(shifts) FROM doctor_weeks"""
    weekly = execute(tool('psql'), '-X', '-At', '-d', database, '-c', weekly_query, capture=True).stdout.strip().split('|')
    weekly_count, weekly_min, weekly_max = map(int, weekly)
    assert weekly_count >= 24 and weekly_min >= 2 and weekly_max <= 3, weekly
    print(f'PASS: 24 fictional doctors across four branches, {parttime_values[3]} new published shifts; full-week doctors work {weekly_min}-{weekly_max} shifts; appointment-linked schedules unchanged; repeat refused')
    topup = repo / 'deploy/operations/2026-11-24-three-week-roster.sql'
    before_topup = execute(tool('psql'), '-X', '-At', '-d', database, '-c',
                           "SELECT (SELECT count(*) FROM schedule),(SELECT count(*) FROM assistant_shift)",
                           capture=True).stdout.strip()
    first_topup = execute(tool('psql'), '-X', '-v', 'ON_ERROR_STOP=1', '-d', database,
                          '-f', topup, capture=True, check=False)
    assert first_topup.returncode == 0, first_topup.stderr
    after_topup = execute(tool('psql'), '-X', '-At', '-d', database, '-c',
                          "SELECT (SELECT count(*) FROM schedule),(SELECT count(*) FROM assistant_shift)",
                          capture=True).stdout.strip()
    assert after_topup != before_topup, 'third-week top-up added no shifts'
    second_topup = execute(tool('psql'), '-X', '-v', 'ON_ERROR_STOP=1', '-d', database,
                           '-f', topup, capture=True, check=False)
    assert second_topup.returncode == 0, second_topup.stderr
    repeated_counts = execute(tool('psql'), '-X', '-At', '-d', database, '-c',
                               "SELECT (SELECT count(*) FROM schedule),(SELECT count(*) FROM assistant_shift)",
                               capture=True).stdout.strip()
    assert after_topup == repeated_counts, 'repeat top-up changed shift counts'
    linked_after_topup = execute(tool('psql'), '-X', '-At', '-d', database, '-c',
                           "SELECT string_agg(a.appointment_id::text||':'||s.starts_at::text||':'||encode(convert_to(s.state,'UTF8'),'hex'),',' ORDER BY a.appointment_id) FROM appointment a JOIN schedule s USING(schedule_id)", capture=True).stdout.strip()
    assert linked_before == linked_after_topup, 'top-up changed an appointment-linked schedule'
    assistant_planned_hex = '已排班'.encode('utf-8').hex()
    third_week_query = f"""WITH days AS (
      SELECT (now() AT TIME ZONE 'Asia/Shanghai')::date+n AS work_day FROM generate_series(15,21) n
    ), all_days AS (
      SELECT b.branch_id,d.work_day FROM branch b CROSS JOIN days d WHERE b.branch_id BETWEEN 901 AND 904
    ), tallies AS (
      SELECT ad.branch_id,ad.work_day,
        (SELECT count(*) FROM schedule s WHERE s.branch_id=ad.branch_id AND s.state=convert_from(decode('{published_hex}','hex'),'UTF8')
           AND (s.starts_at AT TIME ZONE 'Asia/Shanghai')::date=ad.work_day
           AND (s.starts_at AT TIME ZONE 'Asia/Shanghai')::time=TIME '09:00') AS am,
        (SELECT count(*) FROM schedule s WHERE s.branch_id=ad.branch_id AND s.state=convert_from(decode('{published_hex}','hex'),'UTF8')
           AND (s.starts_at AT TIME ZONE 'Asia/Shanghai')::date=ad.work_day
           AND (s.starts_at AT TIME ZONE 'Asia/Shanghai')::time=TIME '13:00') AS pm,
        (SELECT count(*) FROM assistant_shift a WHERE a.branch_id=ad.branch_id AND a.state=convert_from(decode('{assistant_planned_hex}','hex'),'UTF8')
           AND (a.starts_at AT TIME ZONE 'Asia/Shanghai')::date=ad.work_day) AS helpers
      FROM all_days ad
    ) SELECT count(*),min(am),max(am),min(pm),max(pm),min(helpers),max(helpers) FROM tallies"""
    third_week = tuple(map(int, execute(tool('psql'), '-X', '-At', '-d', database,
                                       '-c', third_week_query, capture=True).stdout.strip().split('|')))
    assert third_week == (28,1,2,1,1,2,2), third_week
    print('PASS: third-week roster covers 4 branches x 7 dates, morning/afternoon and assistants; repeat top-up has no effect; booked shifts unchanged')
finally:
    if started:
        execute(tool('pg_ctl'), '-D', run / 'data', '-m', 'fast', '-w', '-t', '30', 'stop', quiet=True)
    if password_file.exists():
        password_file.unlink()
    print('Stopped-cluster artifacts:', run)
