"""Exercise patient APIs against a disposable loopback classroom backend only."""

import argparse
import json
import secrets
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True, help="Loopback /api/v1 URL printed by run_local_miniapp_e2e.py")
    args = parser.parse_args()
    parsed = urllib.parse.urlparse(args.url)
    if parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or parsed.path.rstrip("/") != "/api/v1":
        parser.error("This smoke test only accepts a loopback http://127.0.0.1:<port>/api/v1 URL")
    base = args.url.rstrip("/")

    def request(method: str, path: str, body: dict | None = None,
                token: str | None = None, key: str | None = None):
        headers = {"Accept": "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        if key is not None:
            headers["Idempotency-Key"] = key
        payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(base + path, data=payload, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raw = error.read()
            detail = json.loads(raw) if raw else None
            raise RuntimeError(f"{method} {path} returned HTTP {error.code}: "
                               f"{detail.get('code') if isinstance(detail, dict) else 'unparsed'}") from None

    status, public = request("GET", "/public/branches")
    assert status == 200 and "教学虚拟" in json.dumps(public, ensure_ascii=False), "Not the isolated classroom fixture"
    status, login = request("POST", "/auth/wechat", {"code": "mock-code-unbound"})
    assert status == 200 and login["data"]["kind"] == "registration"
    registration_token = login["data"]["registration_token"]
    phone = "199" + "".join(str(secrets.randbelow(10)) for _ in range(8))
    status, registered = request("POST", "/auth/patient-register", {
        "registration_token": registration_token,
        "name": "教学虚拟小程序联调",
        "phone": phone,
        "allergy_status": "无已知",
    })
    assert status == 200
    token = registered["data"]["session_token"]
    patient_id = registered["data"]["patient_id"]
    print("Local mock WeChat exchange and patient registration: PASS")

    status, me = request("GET", "/auth/me", token=token)
    assert status == 200 and me["data"]["identity_type"] == "patient"
    status, overview = request("GET", "/me/booking", token=token)
    assert status == 200
    data = overview["data"]
    options = [(entry["schedule_id"], entry["item_id"], slot)
               for entry in data["availability"] for slot in entry["slots"]]
    assert len(options) >= 2, "Classroom fixture has fewer than two bookable slots"
    schedule_id, item_id, slot = options[0]
    status, created = request("POST", "/me/appointments", {
        "schedule_id": schedule_id, "item_id": item_id, "starts_at": slot,
        "visit_type": "初诊", "purpose": "教学虚拟联调",
    }, token=token, key=str(uuid.uuid4()))
    assert status == 201
    appointment_id = created["data"]["appointment_id"]
    status, refreshed = request("GET", "/me/booking", token=token)
    assert status == 200 and any(str(row["appointment_id"]) == str(appointment_id)
                                 for row in refreshed["data"]["appointments"])
    print("Authenticated booking create and read-back: PASS")

    for path in ("/me/profile", "/me/records", "/me/billing", "/me/changes"):
        status, _ = request("GET", path, token=token)
        assert status == 200, path
    status, relogin = request("POST", "/auth/wechat", {"code": "mock-code-unbound"})
    assert status == 200 and relogin["data"]["kind"] == "session"
    print("Patient profile, records, billing, changes and repeat mock login: PASS")
    print("Synthetic patient ID:", patient_id, "appointment ID:", appointment_id)
    print("No real WeChat identity, payment, invoice issuance, or cloud deployment was exercised.")


if __name__ == "__main__":
    main()
