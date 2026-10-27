-- 每例重建 26＋30 基线，执行定向突变、只读巡检，再 ROLLBACK。
-- EXPECT_INSPECTION ANOMALY missing_middle_batch projection_prefix
DELETE FROM session_projection_event WHERE session_id=950002 AND seq=2;
-- EXPECT_INSPECTION ANOMALY head_ahead projection_prefix
UPDATE business_session SET last_projection_seq=4 WHERE id=950002;
-- EXPECT_INSPECTION ANOMALY head_behind projection_prefix
UPDATE business_session SET last_projection_seq=2 WHERE id=950002;
-- EXPECT_INSPECTION ANOMALY wrong_stream projection_prefix
UPDATE session_projection_event SET stream_id=UNHEX('000000000000000000000000000f423f') WHERE session_id=950002 AND seq=2;
-- EXPECT_INSPECTION ANOMALY orphan_batch projection_prefix
UPDATE session_projection_event SET session_id=999999 WHERE session_id=950002 AND seq=2;
-- EXPECT_INSPECTION ANOMALY batch_digest projection_batch_integrity
UPDATE session_projection_event SET batch_sha256=UNHEX(REPEAT('11',32)) WHERE session_id=950002 AND seq=2;
-- EXPECT_INSPECTION ANOMALY envelope_seq projection_batch_integrity
UPDATE session_projection_event SET batch_payload=CONVERT(CAST(JSON_SET(CONVERT(batch_payload USING utf8mb4),'$.seq','99') AS CHAR CHARACTER SET utf8mb4) USING binary),byte_count=OCTET_LENGTH(batch_payload),batch_sha256=UNHEX(SHA2(batch_payload,256)) WHERE session_id=950002 AND seq=2;
-- EXPECT_INSPECTION ANOMALY envelope_stream projection_batch_integrity
UPDATE session_projection_event SET batch_payload=CONVERT(CAST(JSON_SET(CONVERT(batch_payload USING utf8mb4),'$.streamId','wrong') AS CHAR CHARACTER SET utf8mb4) USING binary),byte_count=OCTET_LENGTH(batch_payload),batch_sha256=UNHEX(SHA2(batch_payload,256)) WHERE session_id=950002 AND seq=2;
-- EXPECT_INSPECTION ANOMALY batch_count projection_batch_integrity
UPDATE session_projection_event SET change_count=2 WHERE session_id=950002 AND seq=2;
-- EXPECT_INSPECTION ANOMALY content_wrong_owner projection_content_owner
UPDATE projection_content SET owner_user_id=950002 WHERE content_id=UNHEX('000000000000000000000000000ea601');
-- EXPECT_INSPECTION ANOMALY content_wrong_session projection_content_owner
UPDATE projection_content SET session_id=950001 WHERE content_id=UNHEX('000000000000000000000000000ea601');
-- EXPECT_INSPECTION ANOMALY content_wrong_run projection_content_owner
UPDATE projection_content SET run_id=950001 WHERE content_id=UNHEX('000000000000000000000000000ea601');
-- EXPECT_INSPECTION ANOMALY anchor_moved projection_version_chain
UPDATE projection_content SET first_position=12 WHERE content_id=UNHEX('000000000000000000000000000ea601');
-- EXPECT_INSPECTION ANOMALY missing_fixed_version projection_version_chain
DELETE FROM projection_content_version WHERE content_id=UNHEX('000000000000000000000000000ea60a') AND content_version=1;
-- EXPECT_INSPECTION ANOMALY missing_predecessor projection_version_chain
DELETE FROM projection_content_version WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=2;
-- EXPECT_INSPECTION ANOMALY head_version_missing projection_version_chain
UPDATE projection_content SET current_version=4 WHERE content_id=UNHEX('000000000000000000000000000ea601');
-- EXPECT_INSPECTION ANOMALY head_state_disagrees projection_version_chain
UPDATE projection_content SET status='CLOSED',close_reason='COMPLETED',replacement_message_id=NULL WHERE content_id=UNHEX('000000000000000000000000000ea601');
-- EXPECT_INSPECTION ANOMALY closed_then_append projection_version_chain
UPDATE projection_content_version SET content_status='CLOSED',close_reason='WAITING' WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=1;
-- EXPECT_INSPECTION ANOMALY promoted_then_append projection_version_chain
UPDATE projection_content_version SET content_status='PROMOTED',close_reason='PROMOTED',replacement_message_id=951002 WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=1;
-- EXPECT_INSPECTION ANOMALY version_event_order projection_version_chain
UPDATE projection_content_version SET projection_seq=1,projection_position=1 WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=2;
-- EXPECT_INSPECTION ANOMALY body_digest projection_version_payload
UPDATE projection_content_version SET body_sha256=UNHEX(REPEAT('22',32)) WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=1;
-- EXPECT_INSPECTION ANOMALY structure_over_64k projection_version_payload
UPDATE projection_content_version SET body=REPEAT('x',65537),byte_count=65537,body_sha256=UNHEX(SHA2(REPEAT('x',65537),256)) WHERE content_id=UNHEX('000000000000000000000000000ea604') AND content_version=1;
-- EXPECT_INSPECTION ANOMALY table_actual_rows projection_version_payload
UPDATE projection_content_version SET table_rows=2 WHERE content_id=UNHEX('000000000000000000000000000ea604') AND content_version=1;
-- EXPECT_INSPECTION ANOMALY summary_actual_bytes projection_version_payload
UPDATE projection_content_version SET summary_byte_count=1 WHERE content_id=UNHEX('000000000000000000000000000ea603') AND content_version=1;
-- EXPECT_INSPECTION ANOMALY wrong_content_source_kind projection_version_payload
UPDATE projection_content SET content_kind='TEXT' WHERE content_id=UNHEX('000000000000000000000000000ea60a');
-- EXPECT_INSPECTION ANOMALY missing_event_content_version projection_version_event
UPDATE session_projection_event SET batch_payload=CONVERT(CAST(JSON_SET(CONVERT(batch_payload USING utf8mb4),'$.changes[0].contentVersion','999') AS CHAR CHARACTER SET utf8mb4) USING binary),byte_count=OCTET_LENGTH(batch_payload),batch_sha256=UNHEX(SHA2(batch_payload,256)) WHERE session_id=950002 AND seq=2;
-- EXPECT_INSPECTION ANOMALY event_bad_base projection_version_event
UPDATE session_projection_event SET batch_payload=CONVERT(CAST(JSON_SET(CONVERT(batch_payload USING utf8mb4),'$.changes[0].baseContentVersion','0') AS CHAR CHARACTER SET utf8mb4) USING binary),byte_count=OCTET_LENGTH(batch_payload),batch_sha256=UNHEX(SHA2(batch_payload,256)) WHERE session_id=950002 AND seq=2;
-- EXPECT_INSPECTION ANOMALY event_bad_delta projection_version_event
UPDATE session_projection_event SET batch_payload=CONVERT(CAST(JSON_SET(CONVERT(batch_payload USING utf8mb4),'$.changes[0].delta','错误追加') AS CHAR CHARACTER SET utf8mb4) USING binary),byte_count=OCTET_LENGTH(batch_payload),batch_sha256=UNHEX(SHA2(batch_payload,256)) WHERE session_id=950002 AND seq=2;
-- EXPECT_INSPECTION ANOMALY event_missing_content projection_version_event
UPDATE session_projection_event SET batch_payload=CONVERT(CAST(JSON_SET(CONVERT(batch_payload USING utf8mb4),'$.changes[0].contentId','00000000-0000-0000-0000-000000000099') AS CHAR CHARACTER SET utf8mb4) USING binary),byte_count=OCTET_LENGTH(batch_payload),batch_sha256=UNHEX(SHA2(batch_payload,256)) WHERE session_id=950002 AND seq=2;
-- EXPECT_INSPECTION ANOMALY fixed_message_source_missing projection_source_reference
UPDATE projection_content_version SET source_id=999999 WHERE content_id=UNHEX('000000000000000000000000000ea615') AND content_version=1;
-- EXPECT_INSPECTION ANOMALY fixed_message_source_changed projection_source_reference
UPDATE message_content_block SET text_content='被修改' WHERE id=960021;
-- EXPECT_INSPECTION ANOMALY wrong_media_owner projection_source_reference
UPDATE message_media SET user_id=950002 WHERE id=960020;
-- EXPECT_INSPECTION ANOMALY wrong_tool_run projection_source_reference
UPDATE tool_operation SET run_id=950004,operation_no=2 WHERE id=950003;
-- EXPECT_INSPECTION ANOMALY progress_source_missing projection_source_reference
UPDATE projection_content_version SET source_id=999999 WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=1;
-- EXPECT_INSPECTION ANOMALY request_missing projection_interaction_reference
UPDATE projection_content_version SET source_id=999999 WHERE content_id=UNHEX('000000000000000000000000000ea60a') AND content_version=1;
-- EXPECT_INSPECTION ANOMALY revision_missing projection_interaction_reference
UPDATE projection_content_version SET source_revision_id=999999 WHERE content_id=UNHEX('000000000000000000000000000ea60a') AND content_version=1;
-- EXPECT_INSPECTION ANOMALY wrong_request_revision projection_interaction_reference
UPDATE projection_content_version SET source_revision_id=950002 WHERE content_id=UNHEX('000000000000000000000000000ea60a') AND content_version=1;
-- EXPECT_INSPECTION ANOMALY wrong_historical_revision projection_interaction_reference
UPDATE projection_content_version SET source_revision_id=950011 WHERE content_id=UNHEX('000000000000000000000000000ea60a') AND content_version=1;
-- EXPECT_INSPECTION ANOMALY wrong_interaction_type projection_interaction_reference
UPDATE projection_content_version SET interaction_type='OPERATION_CONFIRMATION' WHERE content_id=UNHEX('000000000000000000000000000ea60a') AND content_version=1;
-- EXPECT_INSPECTION ANOMALY request_wrong_owner projection_interaction_reference
UPDATE interaction_request SET owner_user_id=950002 WHERE id=950001;
-- EXPECT_INSPECTION ANOMALY request_wrong_session projection_interaction_reference
UPDATE interaction_request SET session_id=950002 WHERE id=950001;
-- EXPECT_INSPECTION ANOMALY request_wrong_run projection_interaction_reference
UPDATE interaction_request SET run_id=950002,run_request_no=2 WHERE id=950001;
-- EXPECT_INSPECTION ANOMALY request_wrong_release projection_interaction_reference
UPDATE interaction_request SET employee_release_id=999999 WHERE id=950001;
-- EXPECT_INSPECTION ANOMALY revision_tamper projection_interaction_reference
UPDATE interaction_revision SET snapshot_body='{}' WHERE id=950001;
-- EXPECT_INSPECTION ANOMALY progress_wrong_run projection_progress_reference
UPDATE run_progress SET run_id=950001 WHERE id=960001;
-- EXPECT_INSPECTION ANOMALY progress_wrong_anchor projection_progress_reference
UPDATE run_progress SET first_projection_position=2 WHERE id=960001;
-- EXPECT_INSPECTION ANOMALY progress_wrong_call projection_progress_reference
UPDATE run_progress SET sdk_call_key=UNHEX(LPAD(HEX(1250001),32,'0')) WHERE id=960001;
-- EXPECT_INSPECTION ANOMALY progress_wrong_source projection_progress_reference
UPDATE run_progress SET source_id=999999 WHERE id=960001;
-- EXPECT_INSPECTION ANOMALY call_wrong_slot projection_progress_reference
UPDATE runtime_sdk_call SET state_session_key='wrong-slot' WHERE id=950102;
-- EXPECT_INSPECTION ANOMALY progress_wrong_close projection_progress_reference
UPDATE run_progress SET replacement_message_id=999999 WHERE id=960003;
-- EXPECT_INSPECTION ANOMALY promotion_wrong_message projection_success_promotion
UPDATE projection_content SET replacement_message_id=950001 WHERE content_id=UNHEX('000000000000000000000000000ea601');
-- EXPECT_INSPECTION ANOMALY promotion_wrong_message_owner projection_success_promotion
UPDATE business_message SET user_id=950002 WHERE id=951002;
-- EXPECT_INSPECTION ANOMALY promotion_wrong_message_run projection_success_promotion
UPDATE business_message SET produced_by_run_id=950003 WHERE id=951002;
-- EXPECT_INSPECTION ANOMALY success_without_formal_message projection_success_promotion
DELETE FROM business_message WHERE id=951002;
-- EXPECT_INSPECTION ANOMALY run_failure_with_success_batch projection_success_promotion
UPDATE business_run SET status='FAILED' WHERE id=950002;
-- EXPECT_INSPECTION ANOMALY success_batch_missing_run_set projection_success_promotion
UPDATE session_projection_event SET batch_payload=CONVERT(CAST(JSON_REMOVE(CONVERT(batch_payload USING utf8mb4),'$.changes[2]') AS CHAR CHARACTER SET utf8mb4) USING binary),byte_count=OCTET_LENGTH(batch_payload),batch_sha256=UNHEX(SHA2(batch_payload,256)) WHERE session_id=950002 AND seq=3;
-- EXPECT_INSPECTION ANOMALY missing_preview_replacement projection_success_promotion
UPDATE session_projection_event SET batch_payload=CONVERT(CAST(JSON_SET(CONVERT(batch_payload USING utf8mb4),'$.changes[1].replacesContentIds',JSON_ARRAY()) AS CHAR CHARACTER SET utf8mb4) USING binary),byte_count=OCTET_LENGTH(batch_payload),batch_sha256=UNHEX(SHA2(batch_payload,256)) WHERE session_id=950002 AND seq=3;
-- EXPECT_INSPECTION ANOMALY wrong_preview_replacement projection_success_promotion
UPDATE session_projection_event SET batch_payload=CONVERT(CAST(JSON_SET(CONVERT(batch_payload USING utf8mb4),'$.changes[1].replacesContentIds',JSON_ARRAY('00000000-0000-0000-0000-000000000099')) AS CHAR CHARACTER SET utf8mb4) USING binary),byte_count=OCTET_LENGTH(batch_payload),batch_sha256=UNHEX(SHA2(batch_payload,256)) WHERE session_id=950002 AND seq=3;
-- EXPECT_INSPECTION ANOMALY missing_sdk_persistence projection_success_promotion
DELETE FROM runtime_sdk_call WHERE run_id=950002;
-- EXPECT_INSPECTION ANOMALY duplicate_success_change projection_success_promotion
UPDATE session_projection_event SET batch_payload=CONVERT(CAST(JSON_ARRAY_APPEND(CONVERT(batch_payload USING utf8mb4),'$.changes',JSON_OBJECT('type','RUN_SET','runId','950002','status','SUCCEEDED')) AS CHAR CHARACTER SET utf8mb4) USING binary),byte_count=OCTET_LENGTH(batch_payload),batch_sha256=UNHEX(SHA2(batch_payload,256)) WHERE session_id=950002 AND seq=3;
-- EXPECT_INSPECTION ANOMALY message_wrong_batch_run projection_success_promotion
UPDATE session_projection_event SET batch_payload=CONVERT(CAST(JSON_SET(CONVERT(batch_payload USING utf8mb4),'$.changes[1].runId','950003') AS CHAR CHARACTER SET utf8mb4) USING binary),byte_count=OCTET_LENGTH(batch_payload),batch_sha256=UNHEX(SHA2(batch_payload,256)) WHERE session_id=950002 AND seq=3;
-- EXPECT_INSPECTION ANOMALY forbidden_batch_field projection_controlled_payload
UPDATE session_projection_event SET batch_payload=CONVERT(CAST(JSON_SET(CONVERT(batch_payload USING utf8mb4),'$.changes[0].token','synthetic-redaction-marker') AS CHAR CHARACTER SET utf8mb4) USING binary),byte_count=OCTET_LENGTH(batch_payload),batch_sha256=UNHEX(SHA2(batch_payload,256)) WHERE session_id=950002 AND seq=2;
-- EXPECT_INSPECTION ANOMALY forbidden_nested_payload projection_controlled_payload
UPDATE projection_content_version SET body='{"nested":{"sdkState":"synthetic-redaction-marker"}}',byte_count=OCTET_LENGTH(body),body_sha256=UNHEX(SHA2(body,256)) WHERE content_id=UNHEX('000000000000000000000000000ea604') AND content_version=1;
-- EXPECT_INSPECTION ANOMALY forbidden_progress_payload projection_controlled_payload
UPDATE run_progress SET structured_payload=JSON_OBJECT('rawThinking','synthetic-redaction-marker') WHERE id=960001;
-- EXPECT_INSPECTION ANOMALY unknown_change_type projection_controlled_payload
UPDATE session_projection_event SET batch_payload=CONVERT(CAST(JSON_SET(CONVERT(batch_payload USING utf8mb4),'$.changes[0].type','NATIVE_THINKING') AS CHAR CHARACTER SET utf8mb4) USING binary),byte_count=OCTET_LENGTH(batch_payload),batch_sha256=UNHEX(SHA2(batch_payload,256)) WHERE session_id=950002 AND seq=2;
-- EXPECT_INSPECTION CLEAN historical_revision_is_not_latest
SELECT 1 INTO @unused;
-- EXPECT_INSPECTION ANOMALY numeric_bigint_sequence projection_batch_integrity
UPDATE session_projection_event SET batch_payload=CONVERT(CAST(JSON_SET(CONVERT(batch_payload USING utf8mb4),'$.seq',2) AS CHAR CHARACTER SET utf8mb4) USING binary),byte_count=OCTET_LENGTH(batch_payload),batch_sha256=UNHEX(SHA2(batch_payload,256)) WHERE session_id=950002 AND seq=2;
-- EXPECT_INSPECTION ANOMALY latest_call_unverified projection_success_promotion
UPDATE runtime_sdk_call SET status='RECONCILING' WHERE id=950102;
-- EXPECT_INSPECTION ANOMALY cumulative_run_text_budget projection_run_text_budget
UPDATE projection_content SET run_id=950002 WHERE content_id=UNHEX(LPAD(HEX(960002),32,'0'));
UPDATE projection_content_version SET body=REPEAT('x',262144),byte_count=262144,body_sha256=UNHEX(SHA2(body,256)) WHERE content_id=UNHEX(LPAD(HEX(960002),32,'0'));
-- EXPECT_INSPECTION ANOMALY multiple_final_blocks_budget projection_run_text_budget
INSERT INTO message_content_block (id,message_id,block_no,block_kind,text_content,gmt_create,gmt_modified) VALUES (960022,951002,2,'TEXT',REPEAT('x',262144),@n,@n);
UPDATE projection_content SET session_id=950002,run_id=950002,stream_id=UNHEX(LPAD(HEX(951002),32,'0')),first_position=3 WHERE content_id=UNHEX(LPAD(HEX(960021),32,'0'));
UPDATE projection_content_version SET source_id=960022,byte_count=262144,body_sha256=UNHEX(SHA2(REPEAT('x',262144),256)),body_format='markdown-v1',projection_position=3 WHERE content_id=UNHEX(LPAD(HEX(960021),32,'0'));
-- EXPECT_INSPECTION CLEAN draft_change_does_not_change_display
UPDATE interaction_revision SET draft_data=JSON_OBJECT('draft','新草稿'),draft_revision=draft_revision+1 WHERE id=950011;
-- EXPECT_INSPECTION CLEAN tool_success_survives_run_failure
SELECT 1 INTO @unused;
-- EXPECT_INSPECTION CLEAN no_nonce_needed_for_reference
UPDATE operation_confirmation SET token_nonce=NULL,token_ciphertext=NULL,key_id=NULL,crypto_format_version=NULL,recovery_status='MISSING'; DELETE FROM crypto_nonce_cursor;
-- EXPECT_INSPECTION CLEAN independent_session_local_sequences
SELECT 1 INTO @unused;
-- EXPECT_INSPECTION CLEAN mutable_tool_state_does_not_rewrite_snapshot
UPDATE tool_operation SET normalized_result=JSON_OBJECT('summary','后续状态') WHERE id=950003;
