-- 仅在执行器新建的隔离空库运行；合成数据，不连接真实 SDK／Store／派发循环。
-- EXPECT_ERROR 由隔离验收执行器用 --force 按错误码与约束名核对。
-- 覆盖 business_run.row_version／WAITING_INPUT 与 runtime_capacity／dispatch／sdk_call／terminalization。
SET @n = UTC_TIMESTAMP(6);
SET @h = UNHEX(REPEAT('ab', 32));
SET @h2 = UNHEX(REPEAT('cd', 32));
SET @dk = UNHEX(REPEAT('11', 16));
SET @dk2 = UNHEX(REPEAT('12', 16));
SET @ck = UNHEX(REPEAT('21', 16));
SET @ck2 = UNHEX(REPEAT('22', 16));
SET @tk = UNHEX(REPEAT('31', 16));
SET @tk2 = UNHEX(REPEAT('32', 16));
SET @rk = UNHEX(REPEAT('41', 16));
SET @ct = UNHEX(REPEAT('51', 16));
START TRANSACTION;

INSERT INTO platform_user (id, display_name, status_changed_at, gmt_create, gmt_modified)
VALUES (940001, '调度探针用户', @n, @n, @n);
INSERT INTO business_session (id, user_id, employee_id, employee_release_id, state_session_key, last_activity_at, gmt_create, gmt_modified)
VALUES (940001, 940001, 1, 1, REPEAT('a', 32), @n, @n, @n);
INSERT INTO business_message (id, session_id, user_id, employee_id, message_no, input_sequence, sender_kind, submission_key, submission_sha256, accepted_at, gmt_create, gmt_modified)
VALUES (940001, 940001, 940001, 1, 1, 1, 'USER', UNHEX(REPEAT('aa', 16)), @h, @n, @n, @n);
INSERT INTO business_run (id, session_id, input_message_id, actor_user_id, employee_release_id, memory_generation_at_start, memory_revision_at_start, memory_policy_version, run_request_key, request_sha256, status, row_version, gmt_create, gmt_modified)
VALUES (940001, 940001, 940001, 940001, 1, 1, 1, 'p1', UNHEX(REPEAT('bb', 16)), @h, 'WAITING_INPUT', 1, @n, @n);
UPDATE business_message SET initial_run_id = 940001 WHERE id = 940001;

-- —— business_run：WAITING_INPUT 仍占用活动唯一键；row_version CAS ——
-- EXPECT_PASS
SELECT IF(status = 'WAITING_INPUT' AND busy_session_id = 940001 AND row_version = 1, 'PASS', 'FAIL')
FROM business_run WHERE id = 940001;
-- EXPECT_ERROR 1062 uk_busy_session_id
INSERT INTO business_run (session_id, input_message_id, actor_user_id, employee_release_id, memory_generation_at_start, memory_revision_at_start, memory_policy_version, run_request_key, request_sha256, status, gmt_create, gmt_modified)
VALUES (940001, 940001, 940001, 1, 1, 1, 'p1', UNHEX(REPEAT('bc', 16)), @h2, 'ACCEPTED', @n, @n);
-- EXPECT_ERROR 3819 chk_business_run_row_version
UPDATE business_run SET row_version = 0 WHERE id = 940001;
UPDATE business_run SET status = 'RUNNING', row_version = 2, gmt_modified = @n WHERE id = 940001 AND row_version = 1;
-- EXPECT_PASS
SELECT IF(status = 'RUNNING' AND row_version = 2, 'PASS', 'FAIL') FROM business_run WHERE id = 940001;
UPDATE business_run SET status = 'WAITING_INPUT', row_version = 2, gmt_modified = @n WHERE id = 940001 AND row_version = 1;
-- EXPECT_PASS
SELECT IF(status = 'RUNNING' AND row_version = 2, 'PASS', 'FAIL') FROM business_run WHERE id = 940001;

-- —— runtime_capacity 正向与约束 ——
INSERT INTO runtime_capacity (id, singleton_key, foreground_limit, normal_waiting_limit, occupied_count, normal_waiting_count, boot_epoch, gmt_create, gmt_modified)
VALUES (1, 1, 2, 16, 0, 0, 1, @n, @n);
-- EXPECT_PASS
SELECT IF(singleton_key = 1 AND foreground_limit = 2 AND normal_waiting_limit = 16, 'PASS', 'FAIL') FROM runtime_capacity WHERE id = 1;
-- EXPECT_ERROR 1062 uk_runtime_capacity_singleton
INSERT INTO runtime_capacity (singleton_key, foreground_limit, normal_waiting_limit, gmt_create, gmt_modified)
VALUES (1, 2, 16, @n, @n);
-- EXPECT_ERROR 3819 chk_runtime_capacity_counts
UPDATE runtime_capacity SET occupied_count = 3 WHERE id = 1;
-- EXPECT_ERROR 3819 chk_runtime_capacity_counts
UPDATE runtime_capacity SET normal_waiting_count = 17 WHERE id = 1;
-- EXPECT_ERROR 3819 chk_runtime_capacity_singleton
UPDATE runtime_capacity SET singleton_key = 2 WHERE id = 1;

-- —— runtime_dispatch 正向：普通 WAITING 计数归属；HELD 唯一 ——
INSERT INTO runtime_dispatch (
    id, dispatch_key, user_id, employee_id, session_id, run_id, employee_release_id,
    dispatch_kind, work_kind, source_kind, source_id, source_version, request_key, request_sha256,
    status, permit_state, counts_toward_waiting, boot_epoch, gmt_create, gmt_modified
) VALUES (
    940001, @dk, 940001, 1, 940001, 940001, 1,
    'ORDINARY', 'ORIGINAL_RUN', 'ORDINARY_SUBMISSION', 1001, 1, @rk, @h,
    'WAITING', 'NONE', 1, 1, @n, @n
);
-- EXPECT_PASS
SELECT IF(status = 'WAITING' AND counts_toward_waiting = 1 AND held_session_id IS NULL, 'PASS', 'FAIL')
FROM runtime_dispatch WHERE id = 940001;
UPDATE runtime_dispatch SET status = 'CLAIMED', permit_state = 'HELD', counts_toward_waiting = 0,
    claim_token = @ct, claimed_at = @n, claim_fence = 1, gmt_modified = @n WHERE id = 940001;
-- EXPECT_PASS
SELECT IF(permit_state = 'HELD' AND held_session_id = 940001, 'PASS', 'FAIL') FROM runtime_dispatch WHERE id = 940001;

-- EXPECT_ERROR 1062 uk_dispatch_key
INSERT INTO runtime_dispatch (
    dispatch_key, user_id, employee_id, session_id, run_id, employee_release_id,
    dispatch_kind, work_kind, source_kind, source_id, source_version, request_key, request_sha256,
    status, permit_state, boot_epoch, gmt_create, gmt_modified
) VALUES (
    @dk, 940001, 1, 940001, 940001, 1, 'ORDINARY', 'ORIGINAL_RUN_B', 'ORDINARY_SUBMISSION', 1002, 1,
    UNHEX(REPEAT('42', 16)), @h2, 'WAITING', 'NONE', 1, @n, @n
);
-- EXPECT_ERROR 1062 uk_dispatch_source_action
INSERT INTO runtime_dispatch (
    dispatch_key, user_id, employee_id, session_id, run_id, employee_release_id,
    dispatch_kind, work_kind, source_kind, source_id, source_version, request_key, request_sha256,
    status, permit_state, boot_epoch, gmt_create, gmt_modified
) VALUES (
    @dk2, 940001, 1, 940001, 940001, 1, 'ORDINARY', 'ORIGINAL_RUN', 'ORDINARY_SUBMISSION', 1001, 1,
    UNHEX(REPEAT('43', 16)), @h2, 'WAITING', 'NONE', 1, @n, @n
);
-- EXPECT_ERROR 1062 uk_dispatch_held_session
INSERT INTO runtime_dispatch (
    dispatch_key, user_id, employee_id, session_id, run_id, employee_release_id,
    dispatch_kind, work_kind, source_kind, source_id, source_version, request_key, request_sha256,
    status, permit_state, claim_token, claimed_at, boot_epoch, gmt_create, gmt_modified
) VALUES (
    UNHEX(REPEAT('13', 16)), 940001, 1, 940001, 940001, 1, 'CONTINUATION', 'FORM_CONTINUE', 'INTERACTION_RESPONSE', 2001, 1,
    UNHEX(REPEAT('44', 16)), @h2, 'CLAIMED', 'HELD', UNHEX(REPEAT('52', 16)), @n, 1, @n, @n
);
-- EXPECT_ERROR 3819 chk_runtime_dispatch_waiting_count
UPDATE runtime_dispatch SET counts_toward_waiting = 1 WHERE id = 940001;
-- EXPECT_ERROR 3819 chk_runtime_dispatch_claimed
UPDATE runtime_dispatch SET status = 'CLAIMED', permit_state = 'NONE', claim_token = NULL, claimed_at = NULL WHERE id = 940001;

-- —— runtime_sdk_call 正向与伪版本／未配对 VERIFIED 拒绝 ——
INSERT INTO runtime_sdk_call (
    id, sdk_call_key, run_id, session_id, dispatch_id, call_no, user_id, employee_id, employee_release_id,
    state_session_key, state_generation, sdk_version, boot_epoch, claim_fence, executor_ref,
    call_kind, input_sha256, status, store_version_capability, before_exists, gmt_create, gmt_modified
) VALUES (
    940001, @ck, 940001, 940001, 940001, 1, 940001, 1, 1,
    REPEAT('a', 32), 1, '2.0.4', 1, 1, 'worker-a',
    'ORDINARY', @h, 'PREPARED', 'UNAVAILABLE', 0, @n, @n
);
-- EXPECT_PASS
SELECT IF(status = 'PREPARED' AND store_version_capability = 'UNAVAILABLE', 'PASS', 'FAIL')
FROM runtime_sdk_call WHERE id = 940001;
-- EXPECT_ERROR 1062 uk_sdk_call_key
INSERT INTO runtime_sdk_call (
    sdk_call_key, run_id, session_id, dispatch_id, call_no, user_id, employee_id, employee_release_id,
    state_session_key, state_generation, sdk_version, boot_epoch, claim_fence, executor_ref,
    call_kind, input_sha256, status, store_version_capability, before_exists, gmt_create, gmt_modified
) VALUES (
    @ck, 940001, 940001, 940001, 2, 940001, 1, 1, REPEAT('a', 32), 1, '2.0.4', 1, 1, 'worker-a',
    'ORDINARY', @h2, 'PREPARED', 'UNAVAILABLE', 0, @n, @n
);
-- EXPECT_ERROR 1062 uk_run_call_no
INSERT INTO runtime_sdk_call (
    sdk_call_key, run_id, session_id, dispatch_id, call_no, user_id, employee_id, employee_release_id,
    state_session_key, state_generation, sdk_version, boot_epoch, claim_fence, executor_ref,
    call_kind, input_sha256, status, store_version_capability, before_exists, gmt_create, gmt_modified
) VALUES (
    @ck2, 940001, 940001, 940001, 1, 940001, 1, 1, REPEAT('a', 32), 1, '2.0.4', 1, 1, 'worker-a',
    'ORDINARY', @h2, 'PREPARED', 'UNAVAILABLE', 0, @n, @n
);
-- EXPECT_ERROR 3819 chk_runtime_sdk_call_unversioned
UPDATE runtime_sdk_call SET before_store_version = 1 WHERE id = 940001;
-- EXPECT_ERROR 3819 chk_runtime_sdk_call_verified
UPDATE runtime_sdk_call SET status = 'VERIFIED', save_status = 'SAVED', saved_state_sha256 = @h,
    readback_status = 'NONE', verified_at = @n WHERE id = 940001;
UPDATE runtime_sdk_call SET status = 'STARTED', started_at = @n, gmt_modified = @n WHERE id = 940001;
UPDATE runtime_sdk_call SET status = 'VERIFIED', ended_at = @n, save_status = 'SAVED', saved_state_sha256 = @h,
    readback_status = 'MATCHED', readback_state_sha256 = @h, verified_at = @n, gmt_modified = @n
WHERE id = 940001;
-- EXPECT_PASS
SELECT IF(status = 'VERIFIED' AND readback_status = 'MATCHED', 'PASS', 'FAIL') FROM runtime_sdk_call WHERE id = 940001;
INSERT INTO runtime_sdk_call (
    id, sdk_call_key, run_id, session_id, dispatch_id, call_no, user_id, employee_id, employee_release_id,
    state_session_key, state_generation, sdk_version, boot_epoch, claim_fence, executor_ref,
    call_kind, input_sha256, status, store_version_capability, before_exists,
    before_store_version, saved_store_version, readback_store_version,
    save_status, saved_state_sha256, readback_status, readback_state_sha256, verified_at,
    bootstrap_state_proof, gmt_create, gmt_modified
) VALUES (
    940002, UNHEX(REPEAT('23', 16)), 940001, 940001, 940001, 2, 940001, 1, 1,
    REPEAT('a', 32), 1, '2.0.4', 1, 2, 'worker-a',
    'ORDINARY', @h2, 'VERIFIED', 'VERSIONED', 0,
    1, 2, 2, 'SAVED', @h2, 'MATCHED', @h2, @n,
    JSON_OBJECT('initialMissing', TRUE, 'ownerUserId', 940001, 'sessionId', 940001), @n, @n
);
-- EXPECT_PASS
SELECT IF(store_version_capability = 'VERSIONED' AND JSON_TYPE(bootstrap_state_proof) = 'OBJECT', 'PASS', 'FAIL')
FROM runtime_sdk_call WHERE id = 940002;
-- EXPECT_ERROR 3819 chk_runtime_sdk_call_version_fields
UPDATE runtime_sdk_call SET before_store_version = 0 WHERE id = 940002;

-- —— runtime_state_terminalization：独立证据、终结键唯一、条件转换 ——
INSERT INTO runtime_state_terminalization (
    id, terminalization_key, sdk_call_id, user_id, session_id, run_id, state_session_key, state_generation,
    pending_id, pending_name, frozen_tool_use_sha256, quiescence_proof_sha256, boot_epoch, claim_fence,
    before_state_sha256, reason_code, zero_execution_proof_sha256, store_version_capability, status,
    gmt_create, gmt_modified
) VALUES (
    940001, @tk, 940001, 940001, 940001, 940001, REPEAT('a', 32), 1,
    'pending-1', 'request_user_input', @h, @h2, 1, 1,
    @h, 'CANCEL', @h, 'UNAVAILABLE', 'PREPARED', @n, @n
);
-- EXPECT_PASS
SELECT IF(status = 'PREPARED' AND applied_at IS NULL, 'PASS', 'FAIL') FROM runtime_state_terminalization WHERE id = 940001;
UPDATE runtime_state_terminalization SET status = 'APPLIED', applied_at = @n, after_state_sha256 = @h2,
    row_version = 2, gmt_modified = @n WHERE id = 940001 AND status = 'PREPARED' AND row_version = 1;
-- EXPECT_PASS
SELECT IF(status = 'APPLIED' AND row_version = 2, 'PASS', 'FAIL') FROM runtime_state_terminalization WHERE id = 940001;
UPDATE runtime_state_terminalization SET status = 'VERIFIED', verified_at = @n, readback_status = 'MATCHED',
    readback_state_sha256 = @h2, row_version = 3, gmt_modified = @n
WHERE id = 940001 AND status = 'APPLIED' AND row_version = 2;
-- EXPECT_PASS
SELECT IF(status = 'VERIFIED' AND readback_status = 'MATCHED', 'PASS', 'FAIL') FROM runtime_state_terminalization WHERE id = 940001;

-- EXPECT_ERROR 1062 uk_terminalization_key
INSERT INTO runtime_state_terminalization (
    terminalization_key, sdk_call_id, user_id, session_id, run_id, state_session_key, state_generation,
    pending_id, pending_name, frozen_tool_use_sha256, quiescence_proof_sha256, boot_epoch, claim_fence,
    before_state_sha256, reason_code, zero_execution_proof_sha256, store_version_capability, status,
    gmt_create, gmt_modified
) VALUES (
    @tk, 940002, 940001, 940001, 940001, REPEAT('a', 32), 1,
    'pending-2', 'request_user_input', @h, @h2, 1, 2,
    @h, 'CANCEL', @h, 'UNAVAILABLE', 'PREPARED', @n, @n
);
-- EXPECT_ERROR 1062 uk_terminalization_sdk_call
INSERT INTO runtime_state_terminalization (
    terminalization_key, sdk_call_id, user_id, session_id, run_id, state_session_key, state_generation,
    pending_id, pending_name, frozen_tool_use_sha256, quiescence_proof_sha256, boot_epoch, claim_fence,
    before_state_sha256, reason_code, zero_execution_proof_sha256, store_version_capability, status,
    gmt_create, gmt_modified
) VALUES (
    @tk2, 940001, 940001, 940001, 940001, REPEAT('a', 32), 1,
    'pending-3', 'request_user_input', @h, @h2, 1, 3,
    @h, 'CANCEL', @h, 'UNAVAILABLE', 'PREPARED', @n, @n
);
-- EXPECT_ERROR 3819 chk_runtime_term_verified
UPDATE runtime_state_terminalization SET status = 'VERIFIED', verified_at = @n, readback_status = 'NONE' WHERE id = 940001;
-- 旧版本条件更新不影响已核验行
UPDATE runtime_state_terminalization SET status = 'RECONCILING', row_version = 99 WHERE id = 940001 AND row_version = 1;
-- EXPECT_PASS
SELECT IF(status = 'VERIFIED' AND row_version = 3, 'PASS', 'FAIL') FROM runtime_state_terminalization WHERE id = 940001;

ROLLBACK;
