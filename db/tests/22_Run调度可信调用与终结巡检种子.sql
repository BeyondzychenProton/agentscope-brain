-- 巡检种子：基线全零后注入跨行异常；全程由执行器包在事务中并回滚。
SET @n = UTC_TIMESTAMP(6);
SET @h = UNHEX(REPEAT('ab', 32));
SET @h2 = UNHEX(REPEAT('cd', 32));
START TRANSACTION;

INSERT INTO platform_user (id, display_name, status_changed_at, gmt_create, gmt_modified)
VALUES (941001, '巡检合成用户941001', @n, @n, @n);
INSERT INTO business_session (id, user_id, employee_id, employee_release_id, state_session_key, last_activity_at, gmt_create, gmt_modified)
VALUES (941001, 941001, 1, 1, REPEAT('b', 32), @n, @n, @n);
INSERT INTO business_message (id, session_id, user_id, employee_id, message_no, input_sequence, sender_kind, submission_key, submission_sha256, accepted_at, gmt_create, gmt_modified)
VALUES (941001, 941001, 941001, 1, 1, 1, 'USER', UNHEX(REPEAT('c1', 16)), @h, @n, @n, @n);
INSERT INTO business_run (id, session_id, input_message_id, actor_user_id, employee_release_id, memory_generation_at_start, memory_revision_at_start, memory_policy_version, run_request_key, request_sha256, status, row_version, gmt_create, gmt_modified)
VALUES (941001, 941001, 941001, 941001, 1, 1, 1, 'p1', UNHEX(REPEAT('c2', 16)), @h, 'WAITING_INPUT', 1, @n, @n);
UPDATE business_message SET initial_run_id = 941001 WHERE id = 941001;

INSERT INTO runtime_capacity (id, singleton_key, foreground_limit, normal_waiting_limit, occupied_count, normal_waiting_count, boot_epoch, gmt_create, gmt_modified)
VALUES (1, 1, 2, 16, 1, 1, 3, @n, @n);

INSERT INTO runtime_dispatch (
    id, dispatch_key, user_id, employee_id, session_id, run_id, employee_release_id,
    dispatch_kind, work_kind, source_kind, source_id, source_version, request_key, request_sha256,
    status, permit_state, counts_toward_waiting, boot_epoch, claim_token, claimed_at, claim_fence,
    gmt_create, gmt_modified
) VALUES (
    941001, UNHEX(REPEAT('d1', 16)), 941001, 1, 941001, 941001, 1,
    'ORDINARY', 'ORIGINAL_RUN', 'ORDINARY_SUBMISSION', 4101, 1, UNHEX(REPEAT('d2', 16)), @h,
    'CLAIMED', 'HELD', 0, 3, UNHEX(REPEAT('d3', 16)), @n, 1, @n, @n
);
INSERT INTO runtime_dispatch (
    id, dispatch_key, user_id, employee_id, session_id, run_id, employee_release_id,
    dispatch_kind, work_kind, source_kind, source_id, source_version, request_key, request_sha256,
    status, permit_state, counts_toward_waiting, boot_epoch, gmt_create, gmt_modified
) VALUES (
    941002, UNHEX(REPEAT('d4', 16)), 941001, 1, 941001, 941001, 1,
    'ORDINARY', 'ORIGINAL_RUN_WAIT', 'ORDINARY_SUBMISSION', 4102, 1, UNHEX(REPEAT('d5', 16)), @h2,
    'WAITING', 'NONE', 1, 3, @n, @n
);

INSERT INTO runtime_sdk_call (
    id, sdk_call_key, run_id, session_id, dispatch_id, call_no, user_id, employee_id, employee_release_id,
    state_session_key, state_generation, sdk_version, boot_epoch, claim_fence, executor_ref,
    call_kind, input_sha256, status, store_version_capability, before_exists,
    save_status, saved_state_sha256, readback_status, readback_state_sha256, verified_at,
    bootstrap_state_proof, gmt_create, gmt_modified
) VALUES (
    941001, UNHEX(REPEAT('e1', 16)), 941001, 941001, 941001, 1, 941001, 1, 1,
    REPEAT('b', 32), 1, '2.0.4', 3, 1, 'worker-b',
    'ORDINARY', @h, 'VERIFIED', 'UNAVAILABLE', 0,
    'SAVED', @h, 'MATCHED', @h, @n,
    JSON_OBJECT('initialMissing', TRUE, 'sessionId', 941001), @n, @n
);

INSERT INTO runtime_state_terminalization (
    id, terminalization_key, sdk_call_id, user_id, session_id, run_id, state_session_key, state_generation,
    pending_id, pending_name, frozen_tool_use_sha256, quiescence_proof_sha256, boot_epoch, claim_fence,
    before_state_sha256, after_state_sha256, reason_code, zero_execution_proof_sha256,
    store_version_capability, status, applied_at, verified_at,
    readback_status, readback_state_sha256, row_version, gmt_create, gmt_modified
) VALUES (
    941001, UNHEX(REPEAT('f1', 16)), 941001, 941001, 941001, 941001, REPEAT('b', 32), 1,
    'pending-ok', 'request_user_input', @h, @h2, 3, 1,
    @h, @h2, 'CANCEL', @h, 'UNAVAILABLE', 'VERIFIED', @n, @n,
    'MATCHED', @h2, 3, @n, @n
);

-- INSPECT_ALL_ZERO

UPDATE runtime_capacity SET occupied_count = 0 WHERE id = 1;
UPDATE runtime_capacity SET normal_waiting_count = 0 WHERE id = 1;
UPDATE runtime_dispatch SET run_id = 999001 WHERE id = 941002;
UPDATE runtime_dispatch SET boot_epoch = 1 WHERE id = 941001;
UPDATE runtime_sdk_call SET user_id = 999001 WHERE id = 941001;
UPDATE runtime_sdk_call SET status = 'RETURNED', verified_at = NULL, readback_status = 'MISMATCH',
    readback_state_sha256 = @h2 WHERE id = 941001;
UPDATE runtime_sdk_call SET call_no = 2 WHERE id = 941001;
UPDATE runtime_state_terminalization SET state_generation = 99 WHERE id = 941001;
UPDATE runtime_state_terminalization SET status = 'APPLIED', verified_at = NULL, readback_status = 'NONE',
    readback_state_sha256 = NULL WHERE id = 941001;

-- INSPECT_ALL_POSITIVE
