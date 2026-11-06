#!/usr/bin/env python3
"""Run V025 HTTP checks against a short-lived server and isolated test database."""
import argparse
import csv
import http.cookiejar
import io
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile


class RegressionError(Exception):
    pass


def check(condition, message):
    if not condition:
        raise RegressionError(message)


class Response:
    def __init__(self, status, headers, body):
        self.status, self.headers, self.body = status, headers, body
        self.data = None
        if body and "application/json" in headers.get("Content-Type", ""):
            self.data = json.loads(body.decode("utf-8"))


class Client:
    def __init__(self, base):
        self.base = base
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.csrf = None

    def request(self, method, path, data=None, raw=None, csrf=True):
        headers = {}
        if raw is not None:
            body = raw
            headers["Content-Type"] = "application/octet-stream"
        elif data is not None:
            body = json.dumps(data, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=utf-8"
        elif method in ("POST", "PUT", "PATCH"):
            body = b""
        else:
            body = None
        if csrf and method not in ("GET", "HEAD") and self.csrf:
            headers["X-CSRF-Token"] = self.csrf
        request = urllib.request.Request(self.base + "/api/v1/" + path, data=body, method=method, headers=headers)
        try:
            with self.opener.open(request, timeout=30) as response:
                return Response(response.status, response.headers, response.read())
        except urllib.error.HTTPError as error:
            return Response(error.code, error.headers, error.read())

    def expect(self, method, path, status=200, **kwargs):
        response = self.request(method, path, **kwargs)
        code = response.data.get("code", "") if isinstance(response.data, dict) else ""
        check(response.status == status, f"{method} {path.split('?')[0]} expected {status}, got {response.status} ({code})")
        return response

    def login(self, username, password):
        response = self.expect("POST", "auth/login", data={"username": username, "password": password})
        self.csrf = response.data["csrfToken"]
        return response.data

    def upload(self, path, filename, contents, csrf=True):
        boundary = "ipam-test-" + secrets.token_hex(12)
        payload = ("--" + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="' + filename +
                   '"\r\nContent-Type: application/octet-stream\r\n\r\n').encode("utf-8")
        payload += contents + ("\r\n--" + boundary + "--\r\n").encode("ascii")
        headers = {"Content-Type": "multipart/form-data; boundary=" + boundary, "Origin": self.base}
        if csrf and self.csrf:
            headers["X-CSRF-Token"] = self.csrf
        request = urllib.request.Request(self.base + "/api/v1/" + path, data=payload, headers=headers, method="POST")
        try:
            with self.opener.open(request, timeout=30) as response:
                return Response(response.status, response.headers, response.read())
        except urllib.error.HTTPError as error:
            return Response(error.code, error.headers, error.read())

    def change_password(self, current, new):
        response = self.expect("POST", "auth/change-password", data={"currentPassword": current, "newPassword": new})
        self.csrf = response.data["csrfToken"]
        check(not response.data["mustChangePassword"], "changed password still requires change")


def password():
    # Generated test-only credentials are neither persisted in source nor printed.
    return "T" + secrets.token_hex(16) + "a9"


def pool(client, outlet, state=None):
    query = {"outletId": outlet, "rangeType": "OFFICE"}
    if state:
        query["state"] = state
    response = client.expect("GET", "admin/ip-pool-v2?" + urllib.parse.urlencode(query)).data
    check(response["total"] <= response["pageSize"], "HTTP fixture unexpectedly spans pool pages")
    return response["items"]


def find_row(rows, pool_id):
    matches = [row for row in rows if row["id"] == pool_id]
    check(len(matches) == 1, "pool result did not contain exactly the expected IP")
    return matches[0]


def assert_private_fields_absent(value):
    forbidden = {"switchIp", "switchPort", "macAddress", "serialNumber", "detailJson", "passwordHash", "password_hash"}
    if isinstance(value, dict):
        check(not (forbidden & value.keys()), "technology administrator received management-only data")
        for item in value.values():
            assert_private_fields_absent(item)
    elif isinstance(value, list):
        for item in value:
            assert_private_fields_absent(item)


def upload_preview(client, archive):
    return client.expect("POST", "admin/backups/restore-preview?fileName=" + urllib.parse.quote("HTTP系统备份.zip"), raw=archive).data


def download(client):
    response = client.expect("POST", "admin/backups/download")
    check("application/zip" in response.headers.get("Content-Type", ""), "backup did not return ZIP")
    check("attachment" in response.headers.get("Content-Disposition", ""), "backup download header missing")
    with zipfile.ZipFile(io.BytesIO(response.body)) as archive:
        check(set(archive.namelist()) == {"manifest.json", "ipam.db"}, "backup ZIP contents")
        manifest = json.loads(archive.read("manifest.json"))
        check(manifest["schemaVersion"] == 24, "downloaded backup schema")
    return response.body


def await_restore(public, job_id):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        status = public.expect("GET", "backup-restore-status/" + job_id).data
        check(set(status) <= {"jobId", "status", "message", "requiresRelogin"}, "public restore status exposed data")
        if status["status"] in ("SUCCEEDED", "FAILED"):
            check(status["status"] == "SUCCEEDED", "HTTP restore failed")
            check(status["requiresRelogin"], "completed restore did not require login")
            while time.monotonic() < deadline:
                ready = public.request("GET", "health")
                if ready.status == 200:
                    return
                check(ready.status == 503, "unexpected health result after restore")
                time.sleep(0.02)
            raise RegressionError("business APIs did not reopen after restore")
        time.sleep(0.05)
    raise RegressionError("HTTP restore timed out")


def restored_names(client, branch_id):
    return [row["name"] for row in client.expect("GET", "admin/branches").data["items"] if row["id"] == branch_id]


def exercise_assignment_imports(primary, secondary, tech, public, outlet_id, manual_ip):
    endpoint = "admin/assignment-imports"
    for client, status in ((public, 401), (tech, 403)):
        client.expect("GET", endpoint + "/recent", status=status)
        client.expect("GET", endpoint + "/template.xlsx", status=status)
        check(client.upload(endpoint, "test.csv", b"invalid").status == status, "assignment upload permission")
    check(primary.upload(endpoint, "test.csv", b"invalid", csrf=False).status == 403, "assignment upload CSRF")
    for ext, mime in (("csv", "text/csv"), ("xlsx", "spreadsheetml")):
        template = primary.expect("GET", endpoint + "/template." + ext)
        check(mime in template.headers.get("Content-Type", ""), "assignment template type")
    asset_name = "HTTP批量补录资产"
    asset_id = primary.expect("POST", "admin/assets", status=201,
        data={"assetType": "TECH", "name": asset_name, "requiresUser": 0, "requiresResourceCode": 0,
              "recommendedRangeType": "OFFICE", "active": 1}).data["id"]
    headers = ["IP地址", "资产类型", "资产名称", "支行名称（核对用）", "网点名称（核对用）", "资源编码",
               "使用人姓名", "统一认证号", "负责科技管理员账号", "交换机IP", "交换机端口", "MAC地址", "设备序列号", "补录说明"]
    def content(rows):
        stream = io.StringIO(newline="")
        writer = csv.writer(stream)
        writer.writerow(headers)
        for ip, note in rows:
            writer.writerow([ip, "科技资产", asset_name, "", "", "", "", "", "", "", "", "", "", note])
        return stream.getvalue().encode("utf-8")
    def preview(rows):
        response = primary.upload(endpoint, "存量设备台账.csv", content(rows))
        check(response.status == 201, "assignment upload did not return preview")
        check(response.data["fileName"] == "存量设备台账.csv", "UTF-8 upload filename was corrupted")
        return response.data
    def confirm(job, expected=200, partial=True):
        return primary.expect("POST", endpoint + f"/{job['id']}/confirm", status=expected,
            data={"previewVersion": job["previewVersion"], "acceptPartial": partial}).data
    available = pool(primary, outlet_id, "AVAILABLE")
    check(len(available) >= 5, "assignment import fixture too small")
    gateway = [row for row in pool(primary, outlet_id) if row["state"] == "SYSTEM_RESERVED"][0]["ipAddress"]
    first = available[0]
    mixed = [(first["ipAddress"], "HTTP混合导入"), (manual_ip, "已分配冲突"),
             (gateway, "网关不可补录"), ("108.63.255.1", "未入池")]
    job = preview(mixed)
    check(job["readyRows"] == 1 and job["errorRows"] == 3, "mixed assignment preview counts")
    check(find_row(pool(primary, outlet_id), first["id"])["state"] == "AVAILABLE", "preview wrote assignment")
    for suffix, method, kwargs in (("", "GET", {}), ("/not-imported.csv", "GET", {}),
                                   ("/confirm", "POST", {"data": {"previewVersion": job["previewVersion"], "acceptPartial": True}}),
                                   ("", "DELETE", {})):
        response = secondary.request(method, endpoint + f"/{job['id']}" + suffix, **kwargs)
        check(response.status in (403, 404), "another administrator accessed import task")
    confirm(job, expected=400, partial=False)
    primary.expect("POST", endpoint + f"/{job['id']}/confirm", status=403, csrf=False,
        data={"previewVersion": job["previewVersion"], "acceptPartial": True})
    result = confirm(job)
    check(result["status"] == "COMPLETED" and result["importedRows"] == 1 and result["notImportedRows"] == 3,
          "partial assignment confirmation counts")
    check(find_row(pool(primary, outlet_id), first["id"])["state"] == "ASSIGNED", "passing import row not allocated")
    check(confirm(job)["importedRows"] == 1, "completed task retry changed result")
    report = primary.expect("GET", endpoint + f"/{job['id']}/not-imported.csv")
    check("text/csv" in report.headers.get("Content-Type", "") and "attachment" in report.headers.get("Content-Disposition", ""),
          "not-imported download headers")
    check(len(list(csv.reader(io.StringIO(report.body.decode("utf-8-sig"))))) == 4, "not-imported report rows")
    repeated = preview(mixed)
    check(repeated["readyRows"] == 0 and repeated["duplicateRows"] == 1 and repeated["errorRows"] == 3,
          "repeated file did not skip matching allocation")
    confirm(repeated, expected=409)
    primary.expect("DELETE", endpoint + f"/{repeated['id']}", status=204)
    canceled = primary.expect("GET", endpoint + f"/{repeated['id']}").data
    check(canceled["status"] == "CANCELLED", "cancel did not persist")
    confirm(repeated, expected=409)
    change_a, change_b = available[1:3]
    stale = preview([(change_a["ipAddress"], ""), (change_b["ipAddress"], "")])
    secondary.expect("POST", f"admin/ip-pool/{change_a['id']}/manual-assignment", status=201, data={"assetId": asset_id})
    refreshed = confirm(stale)
    check(refreshed.get("requiresReview") and refreshed["previewVersion"] > stale["previewVersion"], "stale preview was not refreshed")
    check(find_row(pool(primary, outlet_id), change_b["id"])["state"] == "AVAILABLE", "stale confirmation imported another row")
    check(confirm(stale).get("requiresReview"), "old version permitted stale confirmation")
    current = confirm(refreshed)
    check(current["status"] == "COMPLETED" and current["importedRows"] == 1, "reviewed preview did not import remaining row")
    pending = preview([(available[3]["ipAddress"], "restore must expire this preview")])
    check(pending["readyRows"] == 1, "pending restore preview missing")
    check(any(row["id"] == job["id"] for row in primary.expect("GET", endpoint + "/recent").data["items"]), "recent import missing")
    print("PASS|HTTP_V026|multipart,templates,permissions,csrf,ownership,partial,duplicates,csv-report,cancel,retry,stale-reconfirm")
    return pending, job


def exercise_pool_asset_filters(primary, tech, public, branch_id, outlet_id, asset_id):
    endpoint = "admin/ip-pool-v2?"
    def filtered(**fields):
        return primary.expect("GET", endpoint + urllib.parse.urlencode(fields)).data
    public.expect("GET", endpoint + "assetType=TECH", status=401)
    tech.expect("GET", endpoint + "assetType=TECH", status=403)
    public.expect("GET", "admin/ip-pool-asset-options", status=401)
    tech.expect("GET", "admin/ip-pool-asset-options", status=403)
    for query in ("assetType=UNKNOWN", "assetId=0", "assetId=-1", "assetId=text"):
        primary.expect("GET", endpoint + query, status=400)
    current = filtered(branchId=branch_id, outletId=outlet_id, rangeType="OFFICE", state="ASSIGNED",
                       assetType="TECH", assetId=asset_id)
    check(current["total"] == 2 and len(current["items"]) == 2, "HTTP asset ID did not select approval and manual rows")
    check({row["assignmentSource"] for row in current["items"]} == {"APPLICATION", "MANUAL_REGISTRATION"},
          "HTTP type filter missed an allocation source")
    check(filtered(assetId=asset_id)["total"] == 2, "HTTP minor category must work without a major category")
    check(filtered(assetType="NON_TECH", assetId=asset_id)["total"] == 0, "HTTP category mismatch was ignored")
    check(filtered(assetType="TECH", state="AVAILABLE")["total"] == 0, "HTTP unassigned pool acquired an asset category")
    all_tech = filtered(assetType="TECH", outletId=outlet_id)
    check(all_tech["total"] >= 5, "HTTP type filter missed batch-imported allocations")
    exact = filtered(assetId=asset_id, q=current["items"][0]["ipAddress"])
    check(exact["total"] == 1, "HTTP asset and exact-IP filters did not compose")
    check(filtered(assetId=asset_id, page=2)["total"] == 2 and not filtered(assetId=asset_id, page=2)["items"],
          "HTTP page count ignored the asset filter")
    same_name = primary.expect("POST", "admin/assets", status=201,
        data={"assetType": "NON_TECH", "name": "HTTP资源编码资产", "requiresUser": 0, "requiresResourceCode": 0,
              "recommendedRangeType": "OFFICE", "active": 1}).data["id"]
    selected = pool(primary, outlet_id, "AVAILABLE")[-1]
    allocated = primary.expect("POST", f"admin/ip-pool/{selected['id']}/manual-assignment", status=201,
                               data={"assetId": same_name}).data
    primary.expect("PUT", f"admin/assets/{same_name}", status=204,
        data={"assetType": "NON_TECH", "name": "HTTP资源编码资产", "requiresUser": 0, "requiresResourceCode": 0,
              "recommendedRangeType": "OFFICE", "active": 0})
    check(filtered(assetId=same_name)["total"] == 1 and filtered(assetId=asset_id)["total"] == 2,
          "HTTP same-name or disabled assets were conflated")
    choices = primary.expect("GET", "admin/ip-pool-asset-options").data["items"]
    check(any(row["id"] == same_name and row["active"] == 0 for row in choices), "disabled filter option missing")
    primary.expect("POST", f"admin/assignments/{allocated['id']}/release", status=200,
                   data={"reason": "资产筛选释放核对"})
    check(filtered(assetId=same_name)["total"] == 0, "released asset remained in HTTP filter results")
    check(filtered(q=selected["ipAddress"])["total"] == 1, "release made the unfiltered IP disappear")
    print("PASS|HTTP_V027|major,minor,combined,sources,batch,same-name,inactive,release,pagination,validation,permissions")


def exercise(base, bootstrap_password):
    public = Client(base)
    primary, secondary, tech = Client(base), Client(base), Client(base)
    primary_password, secondary_initial, secondary_password, tech_initial, tech_password = (password() for _ in range(5))
    primary.login("http_initial", bootstrap_password)
    primary.expect("GET", "admin/backups/status", status=403)
    primary.change_password(bootstrap_password, primary_password)
    check(primary.expect("GET", "admin/backups/status").data["reminderDue"], "first global backup reminder")

    secondary_id = primary.expect("POST", "admin/users", status=201,
        data={"username": "http_other", "realName": "HTTP其他管理员", "role": "ADMIN", "initialPassword": secondary_initial}).data["id"]
    tech_id = primary.expect("POST", "admin/users", status=201,
        data={"username": "http_tech", "realName": "HTTP科技管理员", "role": "TECH_ADMIN", "initialPassword": tech_initial}).data["id"]
    check(secondary_id != tech_id, "new account IDs reused")
    secondary.login("http_other", secondary_initial)
    secondary.change_password(secondary_initial, secondary_password)
    tech.login("http_tech", tech_initial)
    tech.change_password(tech_initial, tech_password)

    for endpoint, method in (("admin/backups/status", "GET"), ("admin/backups/download", "POST"),
                             ("admin/backups/restore-preview", "POST"), ("admin/backups/restore-confirm", "POST")):
        kwargs = {"raw": b"not a backup"} if "preview" in endpoint else {"data": {"uploadId": "a" * 32}} if "confirm" in endpoint else {}
        public.expect(method, endpoint, status=401, **kwargs)
        tech.expect(method, endpoint, status=403, **kwargs)
    for method, endpoint, kwargs in (("POST", "admin/backups/download", {}),
                                    ("POST", "admin/backups/restore-preview", {"raw": b"invalid"}),
                                    ("POST", "admin/backups/restore-confirm", {"data": {"uploadId": "a" * 32}})):
        response = secondary.expect(method, endpoint, status=403, csrf=False, **kwargs)
        check(response.data["code"] == "CSRF_INVALID", "CSRF failure code")

    branch_id = primary.expect("POST", "admin/branches", status=201, data={"code": "HTTPB25", "name": "HTTP原支行"}).data["id"]
    outlet_id = primary.expect("POST", "admin/outlets", status=201, data={"branchId": branch_id, "code": "HTTPO25", "name": "HTTP网点"}).data["id"]
    plan = primary.expect("POST", "admin/outlet-plans", status=201,
        data={"branchId": branch_id, "outletId": outlet_id, "productionCidr": "108.56.32.0/27"}).data
    check(len(plan["segments"]) == 6, "six-network generation")
    asset_id = primary.expect("POST", "admin/assets", status=201,
        data={"assetType": "TECH", "name": "HTTP资源编码资产", "requiresUser": 0, "requiresResourceCode": 1,
              "recommendedRangeType": "OFFICE", "active": 1}).data["id"]
    available = pool(primary, outlet_id, "AVAILABLE")
    check(len(available) >= 5, "HTTP test address pool too small")
    occupied_ids = [available[0]["id"], available[1]["id"]]
    count = secondary.expect("PUT", "admin/ip-pool/status", data={"ids": occupied_ids, "state": "OCCUPIED", "reservationReason": "HTTP存量待补录"}).data["updated"]
    check(count == 2 and len(pool(primary, outlet_id, "OCCUPIED")) == 2, "batch occupied state")
    manual_pool = available[2]
    manual = secondary.expect("POST", f"admin/ip-pool/{manual_pool['id']}/manual-assignment", status=201,
        data={"assetId": asset_id, "assetCode": "HTTP-M001", "responsibleTechUserId": tech_id,
              "switchIp": "108.56.35.28", "switchPort": "GE0/0/1", "macAddress": "自由MAC标记 / 待核实",
              "serialNumber": "SN-HTTP-001"}).data
    manual_id = manual["id"]
    original = find_row(pool(primary, outlet_id), manual_pool["id"])
    check(original["state"] == "ASSIGNED" and original["serialNumber"] == "SN-HTTP-001", "manual assignment fields")
    check(original["switchIp"] == "108.56.35.28" and original["macAddress"] == "自由MAC标记 / 待核实", "separate switch/free-form MAC")

    app_id = tech.expect("POST", "applications-v2", status=201,
        data={"branchId": branch_id, "outletId": outlet_id, "assetId": asset_id, "assetCode": "HTTP-A001"}).data["id"]
    candidates = primary.expect("GET", f"admin/applications/{app_id}/candidates?rangeType=OFFICE").data["items"]
    check(not (set(occupied_ids + [manual_pool["id"]]) & {row["id"] for row in candidates}), "occupied/assigned IP offered for approval")
    primary.expect("POST", f"admin/applications/{app_id}/approve", status=204,
        data={"poolId": candidates[0]["id"], "finalRangeType": "OFFICE", "switchIp": "108.56.35.27",
              "switchPort": "GE0/0/3", "macAddress": "审批MAC待录", "serialNumber": "SN-HTTP-APP"})
    change = {"poolId": manual_pool["id"], "expectedVersion": original["assignmentVersion"],
              "resourceCode": "HTTP-M002", "switchIp": "108.56.35.29", "switchPort": "GE0/0/10",
              "macAddress": "自由MAC标记-已核实", "serialNumber": "SN-HTTP-002"}
    updated = secondary.expect("PUT", f"admin/assignments/{manual_id}/information", data=change).data
    check(updated["changed"] and updated["assignmentVersion"] == original["assignmentVersion"] + 1, "central edit version")
    secondary.expect("PUT", f"admin/assignments/{manual_id}/information", status=409, data=change)
    current = find_row(pool(primary, outlet_id), manual_pool["id"])
    check(current["resourceCode"] == "HTTP-M002" and current["serialNumber"] == "SN-HTTP-002", "central edit did not persist")
    duplicate = {"poolId": manual_pool["id"], "expectedVersion": current["assignmentVersion"], "resourceCode": "HTTP-A001"}
    secondary.expect("PUT", f"admin/assignments/{manual_id}/information", status=409, data=duplicate)
    tech.expect("PUT", f"admin/assignments/{manual_id}/information", status=403, data=change)
    for endpoint in ("my-assignments", "applications", "notifications"):
        assert_private_fields_absent(tech.expect("GET", endpoint).data)
    audits = primary.expect("GET", "admin/audit").data["items"]
    matches = [row for row in audits if row["action"] == "UPDATE_ASSIGNMENT_INFORMATION"]
    check(matches and "HTTP-M001" in matches[0]["changeBefore"] and "HTTP-M002" in matches[0]["changeAfter"], "admin before/after audit")
    assert_private_fields_absent(tech.expect("GET", "my-assignments").data)
    check(all("detailJson" not in row for row in audits), "raw audit detail exposed")
    tech.expect("GET", "admin/audit", status=403)
    public.expect("GET", "admin/setup", status=401)
    tech.expect("GET", "admin/setup", status=403)
    tech.expect("POST", "admin/setup/complete", status=403, data={})
    primary.expect("POST", "admin/setup/complete", status=403, data={}, csrf=False)
    setup_status = primary.expect("GET", "admin/setup").data
    check(setup_status["plannedOutletCount"] >= 1, "setup did not see live planned outlet")
    primary.expect("POST", "admin/setup/complete", status=400, data={})
    print("PASS|HTTP_V029|setup-auth,csrf,live-status,unreviewed-rules-blocked")
    print("PASS|HTTP_V025|permissions,csrf,batch-occupied,manual,approval,central-edit,privacy,audit")

    pending_import, completed_import = exercise_assignment_imports(primary, secondary, tech, public, outlet_id, manual_pool["ipAddress"])
    exercise_pool_asset_filters(primary, tech, public, branch_id, outlet_id, asset_id)

    initial_archive = download(primary)
    initial_preview = upload_preview(primary, initial_archive)
    secondary.expect("POST", "admin/backups/restore-confirm", status=409, data={"uploadId": initial_preview["uploadId"]})
    primary.expect("DELETE", "admin/backups/restore-preview/" + initial_preview["uploadId"], status=204)
    primary.expect("POST", "admin/backups/restore-confirm", status=409, data={"uploadId": initial_preview["uploadId"]})
    archive = download(secondary)
    backup_status = primary.expect("GET", "admin/backups/status").data
    check(backup_status["lastBackupBy"] == "http_other" and not backup_status["reminderDue"], "global backup time/operator")
    public.expect("GET", "backup-restore-status/" + "a" * 32, status=404)

    # Both administrator types perform a complete restore, with new sessions each time.
    for restoring_admin in (secondary, primary):
        restoring_admin.expect("PUT", f"admin/branches/{branch_id}", status=204,
            data={"code": "HTTPB25", "name": "HTTP备份后修改", "active": True})
        check(restored_names(restoring_admin, branch_id) == ["HTTP备份后修改"], "pre-restore mutation")
        preview = upload_preview(restoring_admin, archive)
        check(preview["sourceSchema"] == 24 and preview["requiresRelogin"], "HTTP preview result")
        confirm = restoring_admin.expect("POST", "admin/backups/restore-confirm", status=202,
            data={"uploadId": preview["uploadId"]}).data
        await_restore(public, confirm["jobId"])
        # The completion endpoint is readable without a cookie even after session revocation.
        for client in (primary, secondary, tech):
            client.expect("GET", "auth/me", status=401)
        primary.login("http_initial", primary_password)
        secondary.login("http_other", secondary_password)
        tech.login("http_tech", tech_password)
        check(restored_names(primary, branch_id) == ["HTTP原支行"], "restore did not roll business data back")
        restored = find_row(pool(primary, outlet_id), manual_pool["id"])
        check(restored["resourceCode"] == "HTTP-M002" and restored["macAddress"] == "自由MAC标记-已核实", "restore lost central fields")
        check(restored["switchIp"] == "108.56.35.29" and restored["serialNumber"] == "SN-HTTP-002", "restore lost separate management fields")
        check(len(pool(primary, outlet_id, "OCCUPIED")) == 2, "restore lost occupied state")
        current_status = secondary.expect("GET", "admin/backups/status").data
        check(current_status["lastBackupAt"] == backup_status["lastBackupAt"] and current_status["lastBackupBy"] == "http_other",
              "restore rewound global recent manual-backup tracking")
        check(not current_status["reminderDue"], "restore lost recent backup reminder state")
        assert_private_fields_absent(tech.expect("GET", "my-assignments").data)
        expired = primary.expect("GET", f"admin/assignment-imports/{pending_import['id']}").data
        check(expired["status"] == "EXPIRED", "restore revived pending assignment import")
        primary.expect("POST", f"admin/assignment-imports/{pending_import['id']}/confirm", status=409,
            data={"previewVersion": pending_import["previewVersion"], "acceptPartial": True})
        check(primary.expect("GET", f"admin/assignment-imports/{completed_import['id']}").data["status"] == "COMPLETED",
              "restore lost completed assignment import history")
    print("PASS|HTTP_V025|both-admin-backup-preview-restore,public-job-status,old-sessions-revoked,relogin,data-rollback,global-reminder")


def run(args):
    repository = Path(__file__).resolve().parents[1]
    classes = Path(args.classes).resolve()
    check((classes / "com/icbc/changzhou/ipam/IpamApplication.class").is_file(), "compiled application classes missing")
    java = Path(args.java_home).resolve() / "bin/java"
    check(java.is_file(), "Java executable missing")
    work = Path(tempfile.mkdtemp(prefix="ipam-http-v025-"))
    data = work / "data"
    data.mkdir(mode=0o700)
    bootstrap_password = password()
    password_file = data / ".bootstrap-password"
    password_file.write_text(bootstrap_password, encoding="utf-8")
    password_file.chmod(0o600)
    with socket.socket() as port_probe:
        port_probe.bind(("127.0.0.1", 0))
        port = port_probe.getsockname()[1]
    env = os.environ.copy()
    env.pop("IPAM_ADMIN_PASSWORD", None)
    env.update({"IPAM_DATA_DIR": str(data), "IPAM_STATIC_DIR": str(repository / "frontend"),
                "IPAM_BIND": "127.0.0.1", "IPAM_PORT": str(port), "IPAM_ADMIN_USERNAME": "http_initial",
                "IPAM_ADMIN_PASSWORD_FILE": str(password_file), "IPAM_SECURE_COOKIE": "false"})
    success = False
    process = None
    try:
        with (work / "server.log").open("w", encoding="utf-8") as log:
            process = subprocess.Popen([str(java), "-cp", str(classes) + os.pathsep + str(repository / "backend/lib/*"),
                                        "com.icbc.changzhou.ipam.IpamApplication"], cwd=repository, env=env,
                                       stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
            base = f"http://127.0.0.1:{port}"
            deadline = time.monotonic() + 20
            while True:
                check(process.poll() is None, "temporary Java service exited during startup")
                try:
                    if Client(base).request("GET", "health").status == 200:
                        break
                except (urllib.error.URLError, ConnectionError, TimeoutError):
                    pass
                check(time.monotonic() < deadline, "temporary Java service startup timed out")
                time.sleep(0.1)
            check(not password_file.exists(), "one-time bootstrap password file was not removed")
            exercise(base, bootstrap_password)
            success = True
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        if process is not None:
            check(process.poll() is not None, "temporary Java PID still running")
        if success:
            shutil.rmtree(work)
            print("PASS|HTTP_V025|temporary-java-pid-stopped,isolated-data-cleaned")
        else:
            # Logs never contain request credentials; keep only failed isolated fixtures for diagnosis.
            print("HTTP regression diagnostic directory: " + str(work), file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", required=True)
    parser.add_argument("--classes", default="build/classes")
    args = parser.parse_args()
    try:
        run(args)
    except (RegressionError, OSError, ValueError) as error:
        print("FAIL|HTTP_V025|" + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
