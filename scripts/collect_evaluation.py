"""Collect authorized Agent runs into the model-comparison import format.

Python 3.10+ standard library. `plan`, `export`, and `review` never create model
requests. `collect` requires --execute and a private, explicitly configured plan.
Credentials are read from environment variables; never put them in the plan.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

TERMINAL = {"SUCCEEDED", "FAILED", "NEEDS_INPUT", "CANCELLED"}
UUID_RE = re.compile(r"^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$", re.I)
REF_RE = re.compile(r"^([a-f0-9]{16})/(input|output)/([1-9][0-9]{0,5})/([a-f0-9]{64})$")
REDACTED_KEYS = {"password", "passwd", "apikey", "secret", "secretkey", "authorization", "authorizationref",
                 "cookie", "credential", "accesstoken", "refreshtoken", "reasoningcontent"}


class EvaluationError(Exception):
    pass


def load_json(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8-sig"))
    except (OSError, ValueError) as error:
        raise EvaluationError("Cannot read the specified JSON file") from error


def write_json(path, data):
    path = Path(path)
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w", encoding="utf-8") as output:
        json.dump(data, output, ensure_ascii=False, indent=2)
        output.flush()
        os.fsync(output.fileno())
    try:
        temporary.chmod(0o600)
    except OSError:
        pass
    os.replace(temporary, path)


def clean(value):
    """Additional defense for exported snapshots; not a guarantee for arbitrary PII."""
    if isinstance(value, dict):
        result = {}
        opaque_keys = {"runid", "traceid", "spanid", "parentspanid", "eventid", "run.id", "run.trace_id", "session.id", "taskinputsha256", "sourcerevision"}
        for key, item in value.items():
            if re.sub(r"[^a-z]", "", key.lower()) in REDACTED_KEYS:
                result[key] = "[REDACTED]"
            elif isinstance(item, str) and ((key.lower() in opaque_keys and re.fullmatch(r"[a-fA-F0-9-]{7,64}", item))
                                           or (key.startswith("detail.") and key.endswith("_ref") and REF_RE.fullmatch(item))):
                result[key] = item  # Identifiers and digests are not phone-number text.
            else:
                result[key] = clean(item)
        return result
    if isinstance(value, list):
        return [clean(item) for item in value]
    if isinstance(value, str):
        stripped = value.strip()
        if stripped.startswith(("{", "[")):
            try:
                parsed = json.loads(stripped)
                sanitized = clean(parsed)
                if sanitized != parsed:
                    return json.dumps(sanitized, ensure_ascii=False)
            except ValueError:
                pass
        text = re.sub(r"(?i)Bearer\s+[a-z0-9._~+/-]+=*", "Bearer [REDACTED]", value)
        text = re.sub(r"(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}", "[email]", text)
        text = re.sub(r"(?<!\d)1[3-9]\d{9}(?!\d)", "[phone]", text)
        text = re.sub(r"(?i)(api[_-]?key|password|secret[_-]?key|authorization)([\s\"':=]+)[^\s\"',;}]+", r"\1\2[REDACTED]", text)
        return text
    return value


def valid_base_url(base):
    parsed = urllib.parse.urlsplit(base)
    if parsed.username or parsed.password or parsed.query or parsed.fragment or not parsed.hostname:
        raise EvaluationError("Agent URL must not contain credentials, query parameters or fragments")
    if parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "localhost", "::1"}):
        raise EvaluationError("Use verified HTTPS or a loopback HTTP SSH tunnel for Agent access")
    if parsed.path.rstrip("/") != "/api/agent":
        raise EvaluationError("Agent baseUrl must end with /api/agent")
    return base.rstrip("/")


def read_plan(path):
    plan = load_json(path)
    if not isinstance(plan, dict) or not isinstance(plan.get("targets"), list) or not isinstance(plan.get("tasks"), list):
        raise EvaluationError("Plan requires targets and tasks arrays")
    if not 2 <= len(plan["targets"]) <= 10 or not 2 <= len(plan["tasks"]) <= 20:
        raise EvaluationError("Configure 2-10 model targets and 2-20 common tasks")
    repetitions = plan.get("repetitions", 3)
    if type(repetitions) is not int or not 1 <= repetitions <= 20:
        raise EvaluationError("repetitions must be between 1 and 20")
    if not isinstance(plan.get("conditionId"), str) or not plan["conditionId"].strip():
        raise EvaluationError("Specify conditionId for the shared prompt, data, tools and budgets")
    target_ids, task_ids = set(), set()
    for target in plan["targets"]:
        if not isinstance(target, dict) or not target.get("id") or not target.get("expectedModel") or not target.get("modelVersion"):
            raise EvaluationError("Each target needs id, expectedModel and modelVersion")
        if target["id"] in target_ids:
            raise EvaluationError("Duplicate target ID")
        target_ids.add(target["id"])
        valid_base_url(target.get("baseUrl", ""))
        if not re.fullmatch(r"[A-Z][A-Z0-9_]{2,100}", target.get("tokenEnv", "")):
            raise EvaluationError("Each target must name an environment variable using tokenEnv")
        if any(re.sub(r"[^a-z]", "", key.lower()) in REDACTED_KEYS for key in target):
            raise EvaluationError("Do not put credentials in the plan; use tokenEnv")
    for task in plan["tasks"]:
        if not isinstance(task, dict) or not task.get("id") or not task.get("name") or not isinstance(task.get("message"), str) or not 1 <= len(task["message"]) <= 2000:
            raise EvaluationError("Each task needs id, name and a 1-2000 character message")
        if task["id"] in task_ids:
            raise EvaluationError("Duplicate task ID")
        task_ids.add(task["id"])
    return plan


def plan_summary(plan):
    jobs = len(plan["targets"]) * len(plan["tasks"]) * plan.get("repetitions", 3)
    ready = all(not str(target["expectedModel"]).startswith("REPLACE_") and not str(target["modelVersion"]).startswith("REPLACE_")
                and bool(os.environ.get(target["tokenEnv"])) for target in plan["targets"])
    ready = ready and all("REPLACE_" not in task["message"] for task in plan["tasks"])
    ready = ready and isinstance(plan.get("sourceRevision"), str) and not plan["sourceRevision"].startswith("REPLACE_")
    cap = plan.get("maximumRuns", 100)
    if type(cap) is not int or cap < 1 or jobs > cap:
        raise EvaluationError("Planned runs exceed maximumRuns")
    return {"models": len(plan["targets"]), "tasks": len(plan["tasks"]), "repetitions": plan.get("repetitions", 3),
            "plannedRuns": jobs, "ready": ready, "conditionId": plan["conditionId"],
            "callsCreated": 0, "note": "Each run may make multiple paid model calls; this is not a price quote or a course sample-count guarantee."}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


class AgentClient:
    def __init__(self, target):
        self.base = valid_base_url(target["baseUrl"])
        self.token = os.environ.get(target["tokenEnv"], "").strip()
        if not self.token:
            raise EvaluationError("The target authentication environment variable is not set")
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, method, path, body=None, key=None):
        headers = {"Authorization": self.token if self.token.startswith("Bearer ") else "Bearer " + self.token,
                   "Accept": "application/json", "Content-Type": "application/json"}
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request(self.base + path, method=method, headers=headers,
                                         data=None if body is None else json.dumps(body, ensure_ascii=False).encode())
        try:
            with self.opener.open(request, timeout=20) as response:
                raw = response.read(12 * 1024 * 1024 + 1)
                if len(raw) > 12 * 1024 * 1024:
                    raise EvaluationError("Agent response exceeds the collection size limit")
                result = json.loads(raw)
                if not isinstance(result, dict):
                    raise EvaluationError("Agent returned an invalid JSON envelope")
                return result
        except urllib.error.HTTPError as error:
            # Never print server response bodies or credentials.
            raise EvaluationError("Agent HTTP " + str(error.code)) from None
        except (urllib.error.URLError, TimeoutError, ValueError):
            raise EvaluationError("Agent request failed; inspect service availability, TLS and authentication") from None

    def trace(self, run_id):
        if not UUID_RE.fullmatch(run_id):
            raise EvaluationError("Run ID must be an Agent UUID")
        return self.request("GET", "/runs/" + run_id + "/trace")

    def payload(self, run_id, span_id, direction, reference):
        match = REF_RE.fullmatch(reference or "")
        if not match or match[1] != span_id or match[2] != direction or int(match[3]) > 524288:
            raise EvaluationError("Invalid node detail reference")
        result = self.request("GET", "/runs/" + run_id + "/trace-payloads/" + reference)
        return result


def primary_spans(spans, run_id, trace_id=None):
    candidates = [span for span in spans if span.get("attributes", {}).get("run.id") == run_id]
    if not trace_id:
        root = next((span for span in candidates if span.get("name") == "agent.run"), None)
        trace_id = root.get("traceId") if root else None
    candidates = [span for span in candidates if not trace_id or span.get("traceId") == trace_id]
    nodes = {span["spanId"]: span for span in candidates if span.get("spanId")}
    if len(nodes) > 2000:
        raise EvaluationError("Run exceeds the 2,000-node frontend limit")
    return sorted(nodes.values(), key=lambda span: int(span.get("startTimeUnixNano", 0)))


def make_row(metadata, response, details, allow_demo=False):
    snapshot = response.get("run", {})
    run_id = snapshot.get("runId") or metadata.get("runId")
    if not run_id:
        raise EvaluationError("No runId in captured run")
    spans = primary_spans(response.get("spans", []), run_id, snapshot.get("traceId"))
    if not spans:
        raise EvaluationError("No owned Trace spans available for this run")
    mode = response.get("modelMode", metadata.get("modelMode", "UNVERIFIED"))
    is_demo = mode == "DEMO"
    if mode != "PROVIDER" and not (allow_demo and is_demo):
        raise EvaluationError("Real-provider provenance is not confirmed; do not relabel scripted or unknown traces as real experiments")
    models = sorted({span["attributes"]["model.name"] for span in spans if span.get("attributes", {}).get("span.type") == "llm_inference"
                     and span["attributes"].get("model.name")})
    expected = metadata.get("expectedModel") or metadata.get("model")
    if not is_demo and (not expected or models != [expected]):
        raise EvaluationError("Observed main-loop model does not match the configured comparison model")
    if not is_demo and not any(span.get("attributes", {}).get("span.type") == "llm_inference" for span in spans):
        raise EvaluationError("No main-loop model call was observed; FAQ shortcuts cannot be counted as model performance")
    root = next((span.get("attributes", {}) for span in spans if span.get("name") == "agent.run"), {})
    finish = next((span.get("attributes", {}) for span in spans if span.get("attributes", {}).get("span.type") == "finish"), {})
    status = snapshot.get("status")
    if not status or status == "UNKNOWN":
        status = finish.get("run.status") or root.get("run.status") or "UNKNOWN"
    if status not in TERMINAL or not any(span.get("name") == "agent.run" for span in spans):
        raise EvaluationError("Run has not reached a terminal state or its root Trace is not yet available")
    return {"runId": run_id, "taskId": metadata.get("taskId", "未标注任务"), "taskName": metadata.get("taskName", metadata.get("taskId", "未标注任务")),
            "model": " + ".join(models) or ("模拟模型" if is_demo else "未提供"), "modelVersion": metadata.get("modelVersion", ""),
            "conditionId": metadata.get("conditionId", "条件未记录"), "status": status,
            "modelMode": mode, "businessMode": response.get("businessMode", metadata.get("businessMode", "UNVERIFIED")),
            "demo": is_demo, "evaluation": {"correct": None, "basis": "待人工评分或按已确认口径进行 SQL 对账"},
            "collection": {"taskInputSha256": metadata.get("taskInputSha256"), "observedModels": models,
                           "sourceRevision": metadata.get("sourceRevision"), "capturedAt": datetime.now(timezone.utc).isoformat()},
            "trace": {"spans": clean(spans), "details": clean(details)}}


def dataset(rows, title):
    if not rows:
        raise EvaluationError("No completed captures to export")
    if len({row["demo"] for row in rows}) != 1:
        raise EvaluationError("Cannot combine synthetic and provider experiments")
    if len({row["runId"] for row in rows}) != len(rows):
        raise EvaluationError("Duplicate run IDs in the capture index")
    result = {"schemaVersion": "tianmu-evaluation/1", "title": title, "demo": rows[0]["demo"], "runs": rows}
    if len(json.dumps(result, ensure_ascii=False).encode()) > 30 * 1024 * 1024:
        raise EvaluationError("Combined dataset exceeds 30 MB; import individual capture files or split the index")
    return result


def join_reviews(data, review_document):
    reviews = review_document.get("reviews", [])
    if not isinstance(reviews, list):
        raise EvaluationError("Review file requires a reviews array")
    by_id = {row["runId"]: row for row in data["runs"]}
    seen = set()
    for review in reviews:
        run_id = review.get("runId")
        if run_id not in by_id or run_id in seen:
            raise EvaluationError("Review refers to an unknown or duplicate run ID")
        seen.add(run_id)
        if type(review.get("correct")) is not bool or not all(isinstance(review.get(key), str) and review[key].strip() for key in ["basis", "reviewerRef", "reviewedAt"]):
            raise EvaluationError("A review needs boolean correct, basis, reviewerRef and reviewedAt")
        try:
            timestamp = datetime.fromisoformat(review["reviewedAt"].replace("Z", "+00:00"))
            if timestamp.tzinfo is None:
                raise ValueError("Missing timezone")
        except ValueError:
            raise EvaluationError("reviewedAt must be an ISO timestamp with timezone") from None
        by_id[run_id]["evaluation"] = clean({key: review[key] for key in ["correct", "basis", "reviewerRef", "reviewedAt"]})
    return data


def offline_details(directory, run_id, spans):
    details = {}
    if directory is None:
        return details
    root = Path(directory).resolve()
    for span in spans:
        entry = {}
        for direction in ["input", "output"]:
            reference = span.get("attributes", {}).get("detail." + direction + "_ref", "")
            match = REF_RE.fullmatch(reference)
            if not match or match[1] != span["spanId"] or match[2] != direction or int(match[3]) > 524288:
                continue
            sha = match[4]
            key = hashlib.sha256(f"{run_id}/{span['spanId']}/{direction}/{sha}".encode()).hexdigest()
            path = root / ("td_" + key + ".json")
            if not path.is_file() or path.is_symlink():
                continue
            raw = path.read_bytes()
            if len(raw) != int(match[3]) or hashlib.sha256(raw).hexdigest() != sha:
                raise EvaluationError("Private snapshot failed byte/hash verification")
            payload = json.loads(raw)
            entry[direction] = payload.get("preview") if payload.get("state") == "TRUNCATED" else payload.get("value")
        if entry:
            details[span["spanId"]] = entry
    return details


def capture(client, run_id, metadata, allow_demo=False):
    response = client.trace(run_id)
    if response.get("run", {}).get("runId") != run_id or response.get("truncated"):
        raise EvaluationError("Run identity mismatch or truncated Trace response")
    # Validate model provenance before downloading payloads.
    make_row(metadata, response, {}, allow_demo)
    spans = primary_spans(response.get("spans", []), run_id, response.get("run", {}).get("traceId"))
    details = {}
    for span in spans:
        for direction in ["input", "output"]:
            ref = span.get("attributes", {}).get("detail." + direction + "_ref")
            if ref and ref != "UNAVAILABLE":
                payload = client.payload(run_id, span["spanId"], direction, ref)
                details.setdefault(span["spanId"], {})[direction] = payload.get("preview") if payload.get("state") == "TRUNCATED" else payload.get("value")
    return make_row(metadata, response, details, allow_demo)


def collect(plan, output, execute=False, allow_demo=False):
    summary = plan_summary(plan)
    if not execute:
        raise EvaluationError("Collection can invoke paid models. Review `plan` and supply --execute explicitly")
    if not summary["ready"]:
        raise EvaluationError("Replace model/task placeholders and set target authentication variables before collection")
    output = Path(output)
    if output.exists():
        raise EvaluationError("Use a new output directory; existing captures must not be overwritten")
    output.mkdir(parents=True, mode=0o700)
    (output / "captures").mkdir(mode=0o700)
    rows, index = [], []
    jobs = [(target, task, repeat) for repeat in range(plan.get("repetitions", 3)) for task in plan["tasks"] for target in plan["targets"]]
    (output / "plan.sha256").write_text(hashlib.sha256(json.dumps(plan, sort_keys=True, ensure_ascii=False).encode()).hexdigest() + "\n")
    for slot, (target, task, repeat) in enumerate(jobs):
        metadata = {"targetId": target["id"], "expectedModel": target["expectedModel"], "modelVersion": target["modelVersion"],
                    "taskId": task["id"], "taskName": task["name"], "conditionId": plan["conditionId"],
                    "taskInputSha256": hashlib.sha256(json.dumps({"message": task["message"], "context": task.get("context")}, sort_keys=True, ensure_ascii=False).encode()).hexdigest(),
                    "repeat": repeat + 1, "slot": slot + 1, "idempotencyKey": str(uuid.uuid4()), "sourceRevision": plan.get("sourceRevision")}
        index.append(metadata)
        write_json(output / "run-index.json", {"runs": index})
        try:
            client = AgentClient(target)
            body = {"message": task["message"]}
            if task.get("context"):
                body["context"] = task["context"]
            snapshot = client.request("POST", "/runs", body, metadata["idempotencyKey"])
            run_id = snapshot.get("runId", "")
            if not UUID_RE.fullmatch(run_id):
                raise EvaluationError("Agent did not return a valid run ID")
            metadata["runId"] = run_id
            write_json(output / "run-index.json", {"runs": index})
            deadline = time.monotonic() + min(max(plan.get("runTimeoutSeconds", 180), 10), 600)
            while snapshot.get("status") not in TERMINAL:
                if time.monotonic() >= deadline:
                    raise EvaluationError("Collection wait expired; run may still be executing. Do not blindly resubmit")
                time.sleep(1)
                snapshot = client.request("GET", "/runs/" + run_id)
            metadata["traceId"] = snapshot.get("traceId")
            metadata["status"] = snapshot.get("status")
            # Finish is not proof of all spans being present. Allow a short cleanup window.
            time.sleep(1.5)
            row = capture(client, run_id, metadata, allow_demo)
            rows.append(row)
            metadata["capture"] = "CAPTURED"
            write_json(output / "captures" / (run_id + ".json"), dataset([row], "独立运行采集"))
            write_json(output / "dataset.json", dataset(rows, plan.get("title", "真实多模型实验")))
        except EvaluationError as error:
            metadata["capture"] = "INCOMPLETE"
            metadata["failure"] = str(error)
            # Halt, preserve a recoverable index, and never substitute synthetic results.
            write_json(output / "run-index.json", {"runs": index})
            print(json.dumps({"capturedRuns": len(rows), "stoppedAtSlot": slot + 1, "runId": metadata.get("runId"), "reason": str(error)}, ensure_ascii=False))
            raise
        write_json(output / "run-index.json", {"runs": index})
        print(json.dumps({"completedSlot": slot + 1, "plannedRuns": len(jobs), "runId": run_id, "correctness": "NOT_REVIEWED"}), flush=True)
    return {"capturedRuns": len(rows), "dataset": str(output / "dataset.json"), "paidCalls": "provider-dependent, inspect usage"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    plan_command = commands.add_parser("plan", help="Validate the private plan without making requests")
    plan_command.add_argument("--config", type=Path, required=True)
    collection = commands.add_parser("collect")
    collection.add_argument("--config", type=Path, required=True)
    collection.add_argument("--output", type=Path, required=True)
    collection.add_argument("--execute", action="store_true")
    collection.add_argument("--allow-demo", action="store_true", help="Explicit test-only mode; output remains marked demo")
    export = commands.add_parser("export", help="Pack existing private traces without making requests")
    export.add_argument("--index", type=Path, required=True)
    export.add_argument("--trace", type=Path, required=True)
    export.add_argument("--details", type=Path)
    export.add_argument("--reviews", type=Path)
    export.add_argument("--output", type=Path, required=True)
    export.add_argument("--allow-demo", action="store_true")
    review = commands.add_parser("review", help="Join explicit human/SQL review labels; no model calls")
    review.add_argument("--dataset", type=Path, required=True)
    review.add_argument("--reviews", type=Path, required=True)
    review.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "plan":
            result = plan_summary(read_plan(args.config))
        elif args.command == "collect":
            result = collect(read_plan(args.config), args.output, args.execute, args.allow_demo)
        else:
            if args.output.exists():
                raise EvaluationError("Output already exists; preserve the previous dataset and select a new path")
            if args.command == "review":
                data = join_reviews(load_json(args.dataset), load_json(args.reviews))
            else:
                if args.trace.stat().st_size > 64 * 1024 * 1024:
                    raise EvaluationError("Trace file is larger than 64 MB; split the archive")
                spans = [json.loads(line) for line in args.trace.read_text(encoding="utf-8").splitlines() if line.strip()]
                rows = []
                for metadata in load_json(args.index).get("runs", []):
                    selected = primary_spans(spans, metadata["runId"], metadata.get("traceId"))
                    details = offline_details(args.details, metadata["runId"], selected)
                    response = {"run": {"runId": metadata["runId"], "status": metadata.get("status", "UNKNOWN"), "traceId": metadata.get("traceId")},
                                "spans": selected, "modelMode": metadata.get("modelMode", "UNVERIFIED"), "businessMode": metadata.get("businessMode", "UNVERIFIED")}
                    rows.append(make_row(metadata, response, details, args.allow_demo))
                data = dataset(rows, "已有运行日志导出")
                if args.reviews:
                    data = join_reviews(data, load_json(args.reviews))
            args.output.parent.mkdir(parents=True, exist_ok=True)
            write_json(args.output, clean(data))
            result = {"dataset": str(args.output), "runs": len(data["runs"]), "modelRequestsCreated": 0}
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except (EvaluationError, OSError, ValueError, KeyError, TypeError) as error:
        message = str(error) if isinstance(error, EvaluationError) else "Invalid or unavailable evaluation input; original files were preserved"
        print(json.dumps({"error": message}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
