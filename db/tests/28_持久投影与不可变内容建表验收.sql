-- 由执行器先建立 26＋30 合法基线；每例保存点回退，整批最终回滚。
SAVEPOINT constraint_case;
-- EXPECT_ERROR 1062 uk_projection_batch
INSERT INTO session_projection_event (session_id,stream_id,seq,previous_seq,protocol_version,batch_payload,byte_count,batch_sha256,change_count,occurred_at,gmt_create,gmt_modified) SELECT session_id,stream_id,seq,previous_seq,protocol_version,batch_payload,byte_count,batch_sha256,change_count,occurred_at,gmt_create,gmt_modified FROM session_projection_event WHERE session_id=950002 AND seq=1;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_event_seq
UPDATE session_projection_event SET previous_seq=2 WHERE session_id=950002 AND seq=2;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_event_seq
UPDATE session_projection_event SET session_id=0 WHERE session_id=950002 AND seq=2;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_event_protocol
UPDATE session_projection_event SET protocol_version='web-projection-v2' WHERE session_id=950002 AND seq=2;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_event_bytes
UPDATE session_projection_event SET byte_count=byte_count+1 WHERE session_id=950002 AND seq=2;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_event_bytes
UPDATE session_projection_event SET batch_payload=REPEAT('x',1048577),byte_count=1048577 WHERE session_id=950002 AND seq=2;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_event_count
UPDATE session_projection_event SET change_count=0 WHERE session_id=950002 AND seq=2;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_event_count
UPDATE session_projection_event SET change_count=129 WHERE session_id=950002 AND seq=2;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_event_json
UPDATE session_projection_event SET batch_payload='invalid-json',byte_count=12 WHERE session_id=950002 AND seq=2;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 1062 PRIMARY
INSERT INTO projection_content SELECT * FROM projection_content WHERE content_id=UNHEX('000000000000000000000000000ea601');
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 1062 uk_projection_anchor
UPDATE projection_content SET first_position=1 WHERE content_id=UNHEX('000000000000000000000000000ea602');
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_content_positive
UPDATE projection_content SET first_position=0 WHERE content_id=UNHEX('000000000000000000000000000ea601');
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_content_positive
UPDATE projection_content SET first_position=129 WHERE content_id=UNHEX('000000000000000000000000000ea601');
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_content_positive
UPDATE projection_content SET current_version=0 WHERE content_id=UNHEX('000000000000000000000000000ea601');
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_content_positive
UPDATE projection_content SET row_version=0 WHERE content_id=UNHEX('000000000000000000000000000ea601');
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_content_kind
UPDATE projection_content SET content_kind='THINKING' WHERE content_id=UNHEX('000000000000000000000000000ea601');
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_content_state
UPDATE projection_content SET close_reason=NULL WHERE content_id=UNHEX('000000000000000000000000000ea601');
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_content_state
UPDATE projection_content SET replacement_message_id=NULL WHERE content_id=UNHEX('000000000000000000000000000ea601');
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_content_state
UPDATE projection_content SET status='OPEN' WHERE content_id=UNHEX('000000000000000000000000000ea601');
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_content_state
UPDATE projection_content SET close_reason='BAD' WHERE content_id=UNHEX('000000000000000000000000000ea601');
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 1062 PRIMARY
INSERT INTO projection_content_version SELECT * FROM projection_content_version WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=1;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_positive
UPDATE projection_content_version SET base_version=8 WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=1;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_positive
UPDATE projection_content_version SET payload_version=2 WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=1;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_positive
UPDATE projection_content_version SET projection_position=0 WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=1;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_positive
UPDATE projection_content_version SET source_id=0 WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=1;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_change
UPDATE projection_content_version SET change_kind='TEXT_APPEND' WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=1;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_transition
UPDATE projection_content_version SET change_kind='TEXT_APPEND' WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=3;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_state
UPDATE projection_content_version SET close_reason=NULL WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=3;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_format
UPDATE projection_content_version SET body_format='html' WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=1;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_bytes
UPDATE projection_content_version SET body=NULL WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=1;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_bytes
UPDATE projection_content_version SET byte_count=byte_count+1 WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=1;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_bytes
UPDATE projection_content_version SET body=REPEAT('x',262145),byte_count=262145 WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=1;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_bytes
UPDATE projection_content_version SET payload_storage='URL' WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=1;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_bytes
UPDATE projection_content_version SET payload_storage='FIXED_REF',body=NULL WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=1;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_source
UPDATE projection_content_version SET source_kind='SDK_STATE' WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=1;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_source
UPDATE projection_content_version SET source_revision_id=99 WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=1;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_source
UPDATE projection_content_version SET interaction_type='FORM' WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=1;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_source
UPDATE projection_content_version SET source_revision_id=NULL WHERE content_id=UNHEX('000000000000000000000000000ea60a') AND content_version=1;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_source
UPDATE projection_content_version SET interaction_type=NULL WHERE content_id=UNHEX('000000000000000000000000000ea60a') AND content_version=1;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_source
UPDATE projection_content_version SET interaction_type='OTHER' WHERE content_id=UNHEX('000000000000000000000000000ea60a') AND content_version=1;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_budget
UPDATE projection_content_version SET summary_byte_count=8193 WHERE content_id=UNHEX('000000000000000000000000000ea604');
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_budget
UPDATE projection_content_version SET table_rows=51 WHERE content_id=UNHEX('000000000000000000000000000ea604');
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_budget
UPDATE projection_content_version SET table_columns=21 WHERE content_id=UNHEX('000000000000000000000000000ea604');
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_projection_version_budget
UPDATE projection_content_version SET table_columns=0 WHERE content_id=UNHEX('000000000000000000000000000ea604');
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_run_progress_projection
UPDATE run_progress SET content_version=NULL WHERE id=960001;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_run_progress_projection
UPDATE run_progress SET source_kind='RAW_THINKING' WHERE id=960001;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_run_progress_projection
UPDATE run_progress SET source_kind='MODEL_TEXT',sdk_call_key=NULL WHERE id=960001;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_run_progress_projection
UPDATE run_progress SET projection_seq=NULL WHERE id=960001;
ROLLBACK TO constraint_case;
SAVEPOINT constraint_case;
-- EXPECT_ERROR 3819 chk_session_projection_head
UPDATE business_session SET projection_stream_id=NULL WHERE id=950002;
ROLLBACK TO constraint_case;
SAVEPOINT positive_case;
UPDATE projection_content_version SET body=REPEAT('x',262144),byte_count=262144 WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=1;
-- EXPECT_PASS
SELECT IF((SELECT OCTET_LENGTH(body) FROM projection_content_version WHERE content_id=UNHEX('000000000000000000000000000ea601') AND content_version=1)=262144,'PASS','FAIL');
ROLLBACK TO positive_case;
SAVEPOINT positive_case;
UPDATE projection_content_version SET summary_byte_count=8192,table_rows=50,table_columns=20 WHERE content_id=UNHEX('000000000000000000000000000ea604');
-- EXPECT_PASS
SELECT IF((SELECT table_rows FROM projection_content_version WHERE content_id=UNHEX('000000000000000000000000000ea604'))=50,'PASS','FAIL');
ROLLBACK TO positive_case;
SAVEPOINT positive_case;
UPDATE session_projection_event SET change_count=128 WHERE session_id=950002 AND seq=1;
-- EXPECT_PASS
SELECT IF((SELECT change_count FROM session_projection_event WHERE session_id=950002 AND seq=1)=128,'PASS','FAIL');
ROLLBACK TO positive_case;
SAVEPOINT positive_case;
SET @large=CONCAT('{"padding":"',REPEAT('x',1048576-OCTET_LENGTH('{"padding":""}')),'"}'); UPDATE session_projection_event SET batch_payload=@large,byte_count=OCTET_LENGTH(@large) WHERE session_id=950002 AND seq=1;
-- EXPECT_PASS
SELECT IF(OCTET_LENGTH(@large)=1048576,'PASS','FAIL');
ROLLBACK TO positive_case;
SAVEPOINT positive_case;
SELECT 1 INTO @unused;
-- EXPECT_PASS
SELECT IF((SELECT body IS NULL AND payload_storage='FIXED_REF' FROM projection_content_version WHERE content_id=UNHEX('000000000000000000000000000ea60a') AND content_version=1),'PASS','FAIL');
ROLLBACK TO positive_case;
