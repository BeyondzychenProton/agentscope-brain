-- 定向异常：每段在基线后突变，执行器跑巡检并 ROLLBACK。
-- EXPECT_INSPECTION ANOMALY|CLEAN <case> <target_check>

-- EXPECT_INSPECTION ANOMALY held_count_drift capacity_held_count_mismatch
UPDATE runtime_capacity SET occupied_count = 0 WHERE id = 1;

-- EXPECT_INSPECTION ANOMALY waiting_count_drift capacity_waiting_count_mismatch
UPDATE runtime_capacity SET normal_waiting_count = 0 WHERE id = 1;

-- EXPECT_INSPECTION ANOMALY dispatch_missing_run dispatch_missing_run_or_source
UPDATE runtime_dispatch SET run_id = 888001 WHERE id = 941002;

-- EXPECT_INSPECTION ANOMALY stale_epoch_held dispatch_duplicate_held_or_stale_epoch
UPDATE runtime_dispatch SET boot_epoch = 1 WHERE id = 941001;

-- EXPECT_INSPECTION ANOMALY sdk_owner_drift sdk_call_owner_slot_mismatch
UPDATE runtime_sdk_call SET user_id = 777001 WHERE id = 941001;

-- EXPECT_INSPECTION ANOMALY save_readback_drift sdk_call_save_readback_mismatch
UPDATE runtime_sdk_call SET status = 'RETURNED', verified_at = NULL, readback_status = 'MISMATCH',
    readback_state_sha256 = UNHEX(REPEAT('cd', 32)) WHERE id = 941001;

-- EXPECT_INSPECTION ANOMALY bootstrap_nonfirst sdk_call_bootstrap_on_nonfirst
UPDATE runtime_sdk_call SET call_no = 2 WHERE id = 941001;

-- EXPECT_INSPECTION ANOMALY term_owner_drift terminalization_call_or_ownership_mismatch
UPDATE runtime_state_terminalization SET state_generation = 77 WHERE id = 941001;

-- EXPECT_INSPECTION ANOMALY term_applied_no_readback terminalization_applied_without_readback
UPDATE runtime_state_terminalization SET status = 'APPLIED', verified_at = NULL, readback_status = 'NONE',
    readback_state_sha256 = NULL WHERE id = 941001;

-- EXPECT_INSPECTION CLEAN legal_waiting_input_busy
UPDATE business_run SET status = 'WAITING_INPUT', row_version = row_version + 1, gmt_modified = UTC_TIMESTAMP(6)
WHERE id = 941001 AND row_version = 1;

-- EXPECT_INSPECTION CLEAN legal_continuation_no_waiting_count
INSERT INTO runtime_dispatch (
    dispatch_key, user_id, employee_id, session_id, run_id, employee_release_id,
    dispatch_kind, work_kind, source_kind, source_id, source_version, request_key, request_sha256,
    status, permit_state, counts_toward_waiting, boot_epoch, gmt_create, gmt_modified
) VALUES (
    UNHEX(REPEAT('d9', 16)), 941001, 1, 941001, 941001, 1,
    'CONTINUATION', 'EXPLICIT_CONTINUE', 'USER_CONTINUE', 4199, 1, UNHEX(REPEAT('da', 16)), UNHEX(REPEAT('ab', 32)),
    'WAITING', 'NONE', 0, 3, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
);
