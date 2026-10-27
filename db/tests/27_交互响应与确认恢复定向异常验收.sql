-- 每例独立装载基线并回滚；目标巡检必须命中，不允许由无关异常代替。

-- EXPECT_INSPECTION ANOMALY wrong_owner interaction_owner
UPDATE interaction_request SET owner_user_id=999999 WHERE id=950001;

-- EXPECT_INSPECTION ANOMALY wrong_session interaction_owner
UPDATE interaction_request SET session_id=950004 WHERE id=950001;

-- EXPECT_INSPECTION ANOMALY wrong_release interaction_owner
UPDATE interaction_request SET employee_release_id=999999 WHERE id=950001;

-- EXPECT_INSPECTION ANOMALY missing_run interaction_owner
UPDATE interaction_request SET run_id=999999 WHERE id=950001;

-- EXPECT_INSPECTION ANOMALY cross_current_revision interaction_current_revision
UPDATE interaction_request SET current_revision_id=950004 WHERE id=950001;

-- EXPECT_INSPECTION ANOMALY wrong_revision_no interaction_current_revision
UPDATE interaction_request SET revision_no=2 WHERE id=950001;

-- EXPECT_INSPECTION ANOMALY snapshot_tamper interaction_revision_snapshot
UPDATE interaction_revision SET snapshot_body='changed' WHERE id=950001;

-- EXPECT_INSPECTION ANOMALY orphan_revision interaction_revision_snapshot
UPDATE interaction_revision SET request_id=999999 WHERE id=950001;

-- EXPECT_INSPECTION ANOMALY actor_mismatch interaction_response_binding
UPDATE interaction_response SET actor_user_id=999999 WHERE id=950002;

-- EXPECT_INSPECTION ANOMALY stale_accepted_revision interaction_response_binding
UPDATE interaction_response SET accepted_revision_id=950001 WHERE id=950002;

-- EXPECT_INSPECTION ANOMALY accepted_snapshot_mismatch interaction_response_binding
UPDATE interaction_response SET snapshot_sha256=@h WHERE id=950002;

-- EXPECT_INSPECTION ANOMALY answered_without_response interaction_answered_fact
UPDATE interaction_request SET status='ANSWERED' WHERE id=950001;

-- EXPECT_INSPECTION ANOMALY answered_reopened interaction_answered_fact
UPDATE interaction_request SET status='WAITING' WHERE id=950002;

-- EXPECT_INSPECTION ANOMALY form_wrong_run interaction_form_message
UPDATE business_message SET initial_run_id=950001 WHERE id=950102;

-- EXPECT_INSPECTION ANOMALY form_wrong_session interaction_form_message
UPDATE business_message SET session_id=950001 WHERE id=950102;

-- EXPECT_INSPECTION ANOMALY form_wrong_submit_digest interaction_form_message
UPDATE business_message SET submission_sha256=@h2 WHERE id=950102;

-- EXPECT_INSPECTION ANOMALY source_wrong_owner interaction_source_checkpoint
UPDATE runtime_sdk_call SET user_id=999999 WHERE id=950001;

-- EXPECT_INSPECTION ANOMALY source_wrong_slot interaction_source_checkpoint
UPDATE runtime_sdk_call SET state_session_key='wrong-slot' WHERE id=950001;

-- EXPECT_INSPECTION ANOMALY source_wrong_release interaction_source_checkpoint
UPDATE runtime_sdk_call SET employee_release_id=999999 WHERE id=950001;

-- EXPECT_INSPECTION ANOMALY source_wrong_session interaction_source_checkpoint
UPDATE runtime_sdk_call SET session_id=950004 WHERE id=950001;

-- EXPECT_INSPECTION ANOMALY wrong_source_call interaction_source_checkpoint
UPDATE interaction_request SET source_sdk_call_id=950002 WHERE id=950001;

-- EXPECT_INSPECTION ANOMALY source_no_save_receipt interaction_source_checkpoint
UPDATE runtime_sdk_call SET save_receipt_ref=NULL WHERE id=950001;

-- EXPECT_INSPECTION ANOMALY source_unverified interaction_source_checkpoint
UPDATE runtime_sdk_call SET status='RETURNED',verified_at=NULL WHERE id=950001;

-- EXPECT_INSPECTION ANOMALY source_wrong_pending interaction_source_checkpoint
UPDATE runtime_sdk_call SET pending_id='other' WHERE id=950001;

-- EXPECT_INSPECTION ANOMALY source_wrong_checkpoint interaction_source_checkpoint
UPDATE interaction_request SET ready_checkpoint_sha256=@h2 WHERE id=950001;

-- EXPECT_INSPECTION ANOMALY consume_wrong_generation interaction_form_consumption
UPDATE runtime_sdk_call SET state_generation=2 WHERE id=950102;

-- EXPECT_INSPECTION ANOMALY consume_wrong_fence interaction_form_consumption
UPDATE interaction_response SET claim_fence=3 WHERE id=950002;

-- EXPECT_INSPECTION ANOMALY consume_wrong_slot interaction_form_consumption
UPDATE runtime_sdk_call SET state_session_key='wrong-slot' WHERE id=950102;

-- EXPECT_INSPECTION ANOMALY consume_wrong_pending interaction_form_consumption
UPDATE runtime_sdk_call SET pending_id='other' WHERE id=950102;

-- EXPECT_INSPECTION ANOMALY consume_source_reused interaction_form_consumption
UPDATE interaction_response SET sdk_call_id=950002 WHERE id=950002;

-- EXPECT_INSPECTION ANOMALY consume_no_receipt interaction_form_consumption
UPDATE runtime_sdk_call SET save_receipt_ref=NULL WHERE id=950102;

-- EXPECT_INSPECTION ANOMALY consume_unverified interaction_form_consumption
UPDATE runtime_sdk_call SET status='RETURNED',verified_at=NULL WHERE id=950102;

-- EXPECT_INSPECTION ANOMALY consume_no_before_version interaction_form_consumption
UPDATE runtime_sdk_call SET before_store_version=NULL WHERE id=950102;

-- EXPECT_INSPECTION ANOMALY consume_no_independent_readback interaction_form_consumption
UPDATE runtime_sdk_call SET status='RETURNED',readback_status='MISSING',readback_state_sha256=NULL,verified_at=NULL WHERE id=950102;

-- EXPECT_INSPECTION ANOMALY confirmation_wrong_owner interaction_confirmation_contract
UPDATE operation_confirmation SET actor_user_id=999999 WHERE id=950004;

-- EXPECT_INSPECTION ANOMALY confirmation_wrong_operation interaction_confirmation_contract
UPDATE operation_confirmation SET operation_id=999999 WHERE id=950004;

-- EXPECT_INSPECTION ANOMALY confirmation_digest_mismatch interaction_confirmation_contract
UPDATE operation_confirmation SET operation_sha256=@h2 WHERE id=950004;

-- EXPECT_INSPECTION ANOMALY confirmation_wrong_identity_version interaction_confirmation_contract
UPDATE operation_confirmation SET identity_binding_version=2 WHERE id=950004;

-- EXPECT_INSPECTION ANOMALY confirmation_extended_expiry interaction_confirmation_contract
UPDATE operation_confirmation SET expires_at=TIMESTAMPADD(MINUTE,11,gmt_create) WHERE id=950004;

-- EXPECT_INSPECTION ANOMALY confirmation_not_consumed interaction_confirmation_contract
UPDATE operation_confirmation SET status='PENDING',consumed_at=NULL WHERE id=950003;

-- EXPECT_INSPECTION ANOMALY confirmation_after_deadline interaction_confirmation_contract
UPDATE interaction_response SET accepted_at=TIMESTAMPADD(MINUTE,11,@n),consumed_at=TIMESTAMPADD(MINUTE,11,@n) WHERE id=950003;

-- EXPECT_INSPECTION ANOMALY confirmation_made_form interaction_response_binding
UPDATE interaction_response SET interaction_type='FORM',raw_data=JSON_OBJECT(),normalized_data=JSON_OBJECT(),readable_text='bad',accepted_message_id=950001,status='ACCEPTED',consumed_at=NULL WHERE id=950003;

-- EXPECT_INSPECTION ANOMALY second_authorization interaction_second_authorization
UPDATE operation_confirmation SET status='CONSUMED',consumed_at=@n WHERE id=950004;

-- EXPECT_INSPECTION ANOMALY premature_ready_to_send interaction_second_authorization
UPDATE tool_operation SET status='READY_TO_SEND' WHERE id=950004;

-- EXPECT_INSPECTION ANOMALY missing_recovery_waiting interaction_confirmation_contract
UPDATE operation_confirmation SET crypto_format_version=NULL,key_id=NULL,token_nonce=NULL,token_ciphertext=NULL,recovery_status='MISSING' WHERE id=950004;

-- EXPECT_INSPECTION ANOMALY missing_key_cursor interaction_nonce_binding
UPDATE operation_confirmation SET key_id='missing-key' WHERE id=950004;

-- EXPECT_INSPECTION ANOMALY wrong_nonce_prefix interaction_nonce_binding
UPDATE crypto_nonce_cursor SET nonce_prefix=UNHEX('55667788') WHERE id=950001;

-- EXPECT_INSPECTION ANOMALY nonce_zero interaction_nonce_binding
UPDATE operation_confirmation SET token_nonce=CONCAT(UNHEX('11223344'),UNHEX(REPEAT('00',8))) WHERE id=950004;

-- EXPECT_INSPECTION ANOMALY nonce_not_reserved interaction_nonce_binding
UPDATE operation_confirmation SET token_nonce=CONCAT(UNHEX('11223344'),UNHEX(LPAD(HEX(3),16,'0'))) WHERE id=950004;

-- EXPECT_INSPECTION ANOMALY nonce_rollback interaction_nonce_binding
UPDATE crypto_nonce_cursor SET last_reserved_counter=1 WHERE id=950001;

-- EXPECT_INSPECTION ANOMALY orphan_sdk_response interaction_sdk_response_reference
UPDATE runtime_sdk_call SET response_id=999999 WHERE id=950102;

-- EXPECT_INSPECTION ANOMALY missing_sdk_response interaction_sdk_response_reference
UPDATE runtime_sdk_call SET response_id=NULL WHERE id=950102;

-- EXPECT_INSPECTION ANOMALY sync_wrong_call interaction_confirmation_sync
UPDATE interaction_response SET sdk_sync_status='SYNCED',sdk_sync_call_id=950001,sdk_sync_evidence_ref='synthetic-proof' WHERE id=950003;

-- EXPECT_INSPECTION CLEAN accepted_unclaimed
DELETE FROM runtime_sdk_call WHERE id=950102; UPDATE interaction_response SET status='ACCEPTED',sdk_call_id=NULL,claim_fence=NULL,before_checkpoint_ref=NULL,before_checkpoint_sha256=NULL,after_checkpoint_ref=NULL,after_checkpoint_sha256=NULL,consumed_at=NULL WHERE id=950002;

-- EXPECT_INSPECTION CLEAN reconciling_preserves_answer
UPDATE interaction_response SET status='RECONCILING',consumed_at=NULL WHERE id=950002; UPDATE runtime_sdk_call SET status='RECONCILING',verified_at=NULL WHERE id=950102;

-- EXPECT_INSPECTION CLEAN old_revision_readonly
INSERT INTO interaction_revision (id,request_id,revision_no,interaction_type,contract_version,schema_dialect,snapshot_body,snapshot_sha256,draft_data,draft_revision,gmt_create,gmt_modified) VALUES (950101,950001,2,'FORM','form-v1','draft-07',@fb,UNHEX(SHA2(@fb,256)),JSON_OBJECT(),1,@n,@n); UPDATE interaction_request SET current_revision_id=950101,revision_no=2,row_version=2 WHERE id=950001;

-- EXPECT_INSPECTION CLEAN confirmation_no_sdk_pending
SELECT 1 WHERE FALSE;

-- EXPECT_INSPECTION CLEAN confirmation_sync_pending
UPDATE interaction_response SET sdk_sync_status='PENDING' WHERE id=950003;

-- EXPECT_INSPECTION CLEAN permission_changed_historical_facts
UPDATE platform_user SET authorization_version=2,security_version=2 WHERE id=950001;

-- EXPECT_INSPECTION CLEAN legacy_digest_not_recoverable
UPDATE interaction_request SET status='INVALIDATED' WHERE id=950004; UPDATE operation_confirmation SET status='INVALIDATED',crypto_format_version=NULL,key_id=NULL,token_nonce=NULL,token_ciphertext=NULL,recovery_status='MISSING' WHERE id=950004;

-- EXPECT_INSPECTION CLEAN new_key_same_counter
INSERT INTO crypto_nonce_cursor (key_id,nonce_prefix,last_reserved_counter,key_generation_ref,gmt_create,gmt_modified) VALUES ('synthetic-key-v2',UNHEX('55667788'),1,'synthetic-generation-v2',@n,@n); UPDATE operation_confirmation SET key_id='synthetic-key-v2',token_nonce=CONCAT(UNHEX('55667788'),UNHEX(LPAD(HEX(1),16,'0'))) WHERE id=950004;

-- EXPECT_INSPECTION CLEAN preparing_source_not_yet_verified
UPDATE interaction_request SET status='PREPARING' WHERE id=950001;
UPDATE runtime_sdk_call SET status='RETURNED',verified_at=NULL,save_receipt_ref=NULL,save_status='NONE',readback_status='NONE',saved_state_sha256=NULL,readback_state_sha256=NULL WHERE id=950001;

-- EXPECT_INSPECTION ANOMALY operation_without_original_confirmation interaction_second_authorization
DELETE FROM operation_confirmation WHERE id=950003;

-- BINARY(12) 的短输入会补零；此样本补成 counter=0，必须由关联巡检检出。
-- 原始参数的精确 12 字节校验仍由阶段 4 实现；其他短输入可能补成非零，不能靠 DDL 证明。
-- EXPECT_INSPECTION ANOMALY short_nonce_padded_to_zero interaction_nonce_binding
UPDATE operation_confirmation SET token_nonce=UNHEX('11223344') WHERE id=950004;
