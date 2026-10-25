"""Export one acceptance run's persisted report, Kafka/ClickHouse spans and sanitized I/O.

Run on the deployment host. Uses the dedicated acceptance account's current Redis
session, without logging in again or printing credentials. Output is private.
"""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--run-id', required=True, type=uuid.UUID)
parser.add_argument('--output', required=True, type=Path)
parser.add_argument('--allow-failed', action='store_true')
args = parser.parse_args()
run_id = str(args.run_id)
out = args.output
out.mkdir(mode=0o700, parents=True, exist_ok=True)
os.chmod(str(out), 0o700)

def mysql(sql):
    command = ['docker', 'exec', '-i', 'tianmu-site-business-mysql-1', 'sh', '-c',
        'export MYSQL_PWD="$(cat /run/secrets/site-mysql-root-password)"; exec mysql -uroot --batch --raw --skip-column-names tianmu_site_agent']
    return subprocess.check_output(command, input=sql.encode()).decode().splitlines()

snapshot = json.loads(mysql("SELECT snapshot_json FROM agent_run WHERE run_id='" + run_id + "';")[0])
if snapshot['status'] != 'SUCCEEDED' and not args.allow_failed:
    print(json.dumps({k: snapshot.get(k) for k in ('runId', 'status', 'errorCode', 'steps', 'traceId')}))
    raise SystemExit(1)

command = ['docker', 'exec', '-i', 'tianmu-staging-clickhouse-1', 'sh', '-c',
    'exec clickhouse-client --user trace_admin --password "$(cat /run/secrets/clickhouse-password)" --format JSONEachRow']
query = "SELECT envelope_json FROM tianmu_trace.trace_spans_current WHERE trace_id='" + snapshot['traceId'] + "' ORDER BY start_time;"
spans = [json.loads(json.loads(line)['envelope_json']) for line in subprocess.check_output(command, input=query.encode()).decode().splitlines()]
if not any(span['name'] == 'agent.run' for span in spans):
    raise SystemExit('Run root has not reached ClickHouse yet; retry after the exporter flushes')

def write(name, data):
    target = out / name
    target.write_bytes(data)
    os.chmod(str(target), 0o600)

write('spans.jsonl', ''.join(json.dumps(span, ensure_ascii=False) + '\n' for span in spans).encode())
token = subprocess.check_output(['docker', 'exec', 'tianmu-staging-redis-1', 'sh', '-c',
    'export REDISCLI_AUTH="$(cat /run/secrets/redis-password)"; exec redis-cli -n 1 --raw GET 10001']).decode().strip()
if not token or token == '(nil)':
    raise SystemExit('The dedicated acceptance account must be logged in first')

def fetch(path):
    request = urllib.request.Request('http://127.0.0.1:18202' + path, headers={'Authorization': 'Bearer ' + token})
    with urllib.request.urlopen(request, timeout=15) as response:
        return response.read()

artifacts = [json.loads(line) for line in mysql("SELECT JSON_OBJECT('artifactId',artifact_id,'status',status,'sizeBytes',size_bytes,'sha256',content_sha256) FROM agent_artifact WHERE run_id='" + run_id + "' AND media_type='text/html';")]
for artifact in artifacts:
    html = fetch('/api/agent/runs/' + run_id + '/reports/' + artifact['artifactId'])
    if len(html) != artifact['sizeBytes'] or hashlib.sha256(html).hexdigest() != artifact['sha256']:
        raise SystemExit('Persisted report integrity mismatch')
    write('report.html', html)

details = out / 'details'
details.mkdir(mode=0o700, exist_ok=True)
refs = set()
failures = 0
for span in spans:
    attrs = span.get('attributes', {})
    for name, ref in attrs.items():
        if name.startswith('detail.') and name.endswith('_state') and ref == 'CAPTURE_FAILED': failures += 1
        if not name.startswith('detail.') or not name.endswith('_ref') or ref == 'UNAVAILABLE' or ref in refs: continue
        span_id, direction, size, digest = ref.split('/')
        body = fetch('/api/agent/runs/' + run_id + '/trace-payloads/' + ref)
        if len(body) != int(size) or hashlib.sha256(body).hexdigest() != digest: raise SystemExit('Trace detail integrity mismatch')
        key = hashlib.sha256((run_id + '/' + span_id + '/' + direction + '/' + digest).encode()).hexdigest()
        write('details/td_' + key + '.json', body)
        refs.add(ref)

root = next(span for span in spans if span['name'] == 'agent.run')
attrs = root.get('attributes', {})
summary = {'runId': run_id, 'traceId': snapshot['traceId'], 'status': snapshot['status'],
    'steps': snapshot['steps'], 'modelCalls': snapshot['totalModelCalls'], 'durationMs': round(root['durationNanos'] / 1000000, 2),
    'spanCount': len(spans), 'nodeTypes': dict(Counter(span.get('attributes', {}).get('node.type', 'UNKNOWN') for span in spans)),
    'tools': sorted(set(span['attributes']['tool.name'] for span in spans if span.get('attributes', {}).get('tool.name'))),
    'inputTokens': attrs.get('usage.input_tokens'), 'outputTokens': attrs.get('usage.output_tokens'),
    'detailSnapshots': len(refs), 'captureFailures': failures, 'persistedReports': artifacts, 'credentialsPrinted': False}
write('summary.json', json.dumps(summary, ensure_ascii=False, indent=2).encode())
print(json.dumps(summary, ensure_ascii=False))
if failures: raise SystemExit(1)
