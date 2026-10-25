-- Report persistence emits a replayable delivery notification before the final answer.
-- Keep the existing DONE uniqueness and event sequencing constraints unchanged.
ALTER TABLE agent_run_event
    DROP CHECK ck_event_type,
    ADD CONSTRAINT ck_event_type CHECK (
        event_type IN ('PROGRESS', 'ARTIFACT_READY', 'ANSWER', 'CLARIFICATION', 'ERROR', 'ABORT', 'DONE')
    );
