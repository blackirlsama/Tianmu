import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
import contextlib
import io
from unittest.mock import patch

from collect_evaluation import (AgentClient, EvaluationError, capture, clean, collect, dataset,
                                join_reviews, make_row, offline_details, plan_summary, primary_spans, read_plan, valid_base_url)


RUN = '11111111-2222-4333-8444-555555555555'
SPAN = '1234567890abcdef'


def response(mode='PROVIDER'):
    return {'modelMode': mode, 'businessMode': 'DEMO', 'run': {'runId': RUN, 'status': 'SUCCEEDED', 'traceId': 'a' * 32},
            'spans': [{'spanId': SPAN, 'traceId': 'a' * 32, 'parentSpanId': 'abcdef1234567890', 'name': 'agent.model',
                       'startTimeUnixNano': 1, 'endTimeUnixNano': 2, 'durationNanos': 1, 'status': 'UNSET',
                       'attributes': {'run.id': RUN, 'span.type': 'llm_inference', 'node.type': 'MODEL',
                                      'model.name': 'model-a', 'model.input_tokens': 100, 'model.output_tokens': 25}},
                      {'spanId': 'abcdef1234567890', 'traceId': 'a' * 32, 'parentSpanId': '0' * 16, 'name': 'agent.run',
                       'startTimeUnixNano': 0, 'endTimeUnixNano': 3, 'durationNanos': 3, 'status': 'UNSET',
                       'attributes': {'run.id': RUN, 'run.status': 'SUCCEEDED', 'usage.scope': 'RUN', 'usage.complete': True,
                                      'usage.input_tokens': 100, 'usage.output_tokens': 25}}]}


META = {'runId': RUN, 'taskId': 'q1', 'taskName': '计数', 'expectedModel': 'model-a',
        'modelVersion': 'test-only', 'conditionId': 'same-input', 'modelMode': 'PROVIDER'}


class CollectionTest(unittest.TestCase):
    def test_success_does_not_create_a_correctness_label_and_unknown_models_are_not_relabelled(self):
        row = make_row(META, response(), {})
        self.assertIsNone(row['evaluation']['correct'])
        self.assertEqual('model-a', row['model'])
        with self.assertRaises(EvaluationError):
            make_row({**META, 'expectedModel': 'model-b'}, response(), {})
        with self.assertRaises(EvaluationError):
            make_row(META, response('DEMO'), {})
        self.assertTrue(make_row(META, response('DEMO'), {}, allow_demo=True)['demo'])
        with self.assertRaises(EvaluationError):
            make_row(META, response('UNVERIFIED'), {})

    def test_owned_trace_filter_and_replay_dedup(self):
        spans = response()['spans']
        other = {**spans[0], 'spanId': 'f' * 16, 'attributes': {'run.id': 'another'}}
        sse = {**spans[0], 'traceId': 'b' * 32}
        self.assertEqual(2, len(primary_spans([*spans, *spans, other, sse], RUN, 'a' * 32)))

    def test_reviews_require_explicit_grounding_and_timezone(self):
        data = dataset([make_row(META, response(), {})], 'test')
        reviewed = {'runId': RUN, 'correct': False, 'basis': '人工对照标注 v1', 'reviewerRef': 'reviewer-1', 'reviewedAt': '2026-10-31T16:00:00+08:00'}
        self.assertFalse(join_reviews(copy.deepcopy(data), {'reviews': [reviewed]})['runs'][0]['evaluation']['correct'])
        for invalid in [{**reviewed, 'basis': ''}, {**reviewed, 'runId': 'another'}, {**reviewed, 'correct': 'true'}, {**reviewed, 'reviewedAt': '2026-10-31T16:00:00'}]:
            with self.assertRaises(EvaluationError):
                join_reviews(copy.deepcopy(data), {'reviews': [invalid]})

    def test_plan_is_read_only_and_requires_filled_configuration(self):
        path = Path(__file__).resolve().parents[1] / 'tianmu-agent/eval/collection-plan.example.json'
        plan = read_plan(path)
        report = plan_summary(plan)
        self.assertEqual(18, report['plannedRuns'])
        self.assertEqual(0, report['callsCreated'])
        self.assertFalse(report['ready'])
        with tempfile.TemporaryDirectory() as directory, patch('collect_evaluation.AgentClient') as client:
            with self.assertRaises(EvaluationError):
                collect(plan, Path(directory) / 'new', execute=False)
            client.assert_not_called()
            self.assertFalse((Path(directory) / 'new').exists())

    def test_http_transport_validates_destinations_and_snapshot_references(self):
        for base in ['http://remote.example/api/agent', 'https://alice:secret@example.com/api/agent', 'https://example.com/api/agent?token=secret']:
            with self.assertRaises(EvaluationError):
                valid_base_url(base)
        self.assertEqual('http://127.0.0.1:8201/api/agent', valid_base_url('http://127.0.0.1:8201/api/agent/'))
        with patch.dict(os.environ, {'TIANMU_TEST_AUTH': 'test-only-credential'}):
            client = AgentClient({'baseUrl': 'http://127.0.0.1:8201/api/agent', 'tokenEnv': 'TIANMU_TEST_AUTH'})
            with self.assertRaises(EvaluationError):
                client.payload(RUN, SPAN, 'input', '../private')

    def test_private_snapshots_verify_digest_before_sanitizing(self):
        with tempfile.TemporaryDirectory() as directory:
            raw = json.dumps({'state': 'COMPLETE', 'value': {'apiKey': 'not-a-real-secret', 'messages': [{'content': 'hello'}]}}).encode()
            sha = hashlib.sha256(raw).hexdigest()
            key = hashlib.sha256(f'{RUN}/{SPAN}/input/{sha}'.encode()).hexdigest()
            path = Path(directory) / ('td_' + key + '.json')
            path.write_bytes(raw)
            spans = response()['spans']
            spans[0]['attributes']['detail.input_ref'] = f'{SPAN}/input/{len(raw)}/{sha}'
            details = offline_details(directory, RUN, spans)
            self.assertEqual('[REDACTED]', clean(details)[SPAN]['input']['apiKey'])
            path.write_bytes(raw + b'bad')
            with self.assertRaises(EvaluationError):
                offline_details(directory, RUN, spans)

    def test_failed_capture_is_not_substituted_with_synthetic_data(self):
        class Client:
            def trace(self, run_id):
                return {**response(), 'truncated': True}
        with self.assertRaises(EvaluationError):
            capture(Client(), RUN, META)

    def test_explicit_collection_writes_importable_dataset_with_actual_model_ids(self):
        class Client:
            sequence = 0
            def __init__(self, target):
                self.model = target['expectedModel']
            def request(self, method, path, body=None, key=None):
                if method == 'POST':
                    self.__class__.sequence += 1
                    self.run_id = f'11111111-2222-4333-8444-{self.sequence:012d}'
                return {'runId': self.run_id, 'status': 'SUCCEEDED', 'traceId': 'a' * 32}
            def trace(self, run_id):
                data = response()
                data['run']['runId'] = run_id
                for span in data['spans']:
                    span['attributes']['run.id'] = run_id
                data['spans'][0]['attributes']['model.name'] = self.model
                return data
        plan = {'targets': [
            {'id': 'a', 'baseUrl': 'http://127.0.0.1:8201/api/agent', 'tokenEnv': 'EVAL_AUTH_A', 'expectedModel': 'model-a', 'modelVersion': 'v1'},
            {'id': 'b', 'baseUrl': 'http://127.0.0.1:8202/api/agent', 'tokenEnv': 'EVAL_AUTH_B', 'expectedModel': 'model-b', 'modelVersion': 'v1'}],
            'tasks': [{'id': 'q1', 'name': 'test1', 'message': 'question1'}, {'id': 'q2', 'name': 'test2', 'message': 'question2'}],
            'conditionId': 'same-fixture', 'repetitions': 1, 'maximumRuns': 4, 'sourceRevision': 'test-only'}
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {'EVAL_AUTH_A': 'test-only', 'EVAL_AUTH_B': 'test-only'}), \
                patch('collect_evaluation.AgentClient', Client), patch('collect_evaluation.time.sleep'), contextlib.redirect_stdout(io.StringIO()):
            output = Path(directory) / 'new'
            collect(plan, output, execute=True)
            data = json.loads((output / 'dataset.json').read_text(encoding='utf-8'))
            self.assertEqual(4, len(data['runs']))
            self.assertEqual({'model-a', 'model-b'}, {row['model'] for row in data['runs']})
            self.assertTrue(all(row['evaluation']['correct'] is None for row in data['runs']))
            with self.assertRaises(EvaluationError):
                collect(plan, output, execute=True)

    def test_nested_credentials_and_reasoning_are_redacted(self):
        value = {'authorizationRef': 'secret', 'reasoning_content': 'hidden', 'message': '{"apiKey":"secret"}',
                 'text': 'Bearer nonproduction-token someone@example.com'}
        result = clean(value)
        self.assertEqual('[REDACTED]', result['authorizationRef'])
        self.assertEqual('[REDACTED]', result['reasoning_content'])
        self.assertNotIn('someone@example.com', result['text'])
        self.assertEqual('[REDACTED]', json.loads(result['message'])['apiKey'])
        opaque = 'abc13800138000de'
        self.assertEqual(opaque, clean({'spanId': opaque})['spanId'])


if __name__ == '__main__':
    unittest.main()
