-- 定向异常：每段在基线后突变，执行器跑巡检并 ROLLBACK。
-- EXPECT_INSPECTION ANOMALY|CLEAN <case> <target_check>

-- EXPECT_INSPECTION ANOMALY release_mismatch intent_bind_without_first_message
UPDATE session_intent SET employee_release_id = 88 WHERE id = 931001;

-- EXPECT_INSPECTION ANOMALY first_submission_wrong intent_first_submission_mismatch
UPDATE session_intent SET first_submission_id = 931002 WHERE id = 931001;

-- EXPECT_INSPECTION ANOMALY expired_ready upload_expired_still_ready
UPDATE media_upload SET issued_at = UTC_TIMESTAMP(6) - INTERVAL 30 HOUR,
    expires_at = UTC_TIMESTAMP(6) - INTERVAL 6 HOUR WHERE id = 931002;

-- EXPECT_INSPECTION ANOMALY bound_missing_media upload_bound_without_formal_media
UPDATE media_upload SET message_media_id = 888001 WHERE id = 931001;

-- EXPECT_INSPECTION ANOMALY bound_temp_delete upload_formal_still_temp_deleting
UPDATE media_upload SET deletion_status = 'CLAIMED', deletion_claimed_at = UTC_TIMESTAMP(6), deletion_fence = 1 WHERE id = 931001;

-- EXPECT_INSPECTION ANOMALY upload_owner_mismatch upload_owner_or_target_mismatch
UPDATE media_upload SET user_id = 777001 WHERE id = 931002;

-- EXPECT_INSPECTION ANOMALY accepted_wrong_message submission_accepted_incomplete
UPDATE ordinary_submission SET accepted_message_id = 931002 WHERE id = 931001;

-- EXPECT_INSPECTION ANOMALY control_message_drift submission_control_message_mismatch
UPDATE ordinary_submission SET accepted_message_id = 931002 WHERE id = 931001;

-- EXPECT_INSPECTION CLEAN legal_registered_text_empty_uploads
UPDATE ordinary_submission SET upload_ref_json = JSON_ARRAY() WHERE id = 931002;

-- EXPECT_INSPECTION CLEAN legal_tombstone_untouched
UPDATE ordinary_submission SET reason_code = 'USER_CLOSE' WHERE id = 931003;
