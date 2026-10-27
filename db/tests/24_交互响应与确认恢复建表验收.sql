-- 由隔离执行器先装载 26 号基线；每例 SAVEPOINT 回退；不输出秘密字节。
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_request_positive
UPDATE interaction_request SET row_version=0 WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_request_positive
UPDATE interaction_request SET correction_count=2 WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_request_type
UPDATE interaction_request SET interaction_type='ASK' WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_request_status
UPDATE interaction_request SET status='OPEN' WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_request_revision
UPDATE interaction_request SET revision_no=NULL WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_request_ready
UPDATE interaction_request SET current_revision_id=NULL,revision_no=NULL WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_request_pending
UPDATE interaction_request SET pending_id=NULL WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_request_form
UPDATE interaction_request SET status='EXPIRED' WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_request_form
UPDATE interaction_request SET confirmation_id=950004 WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_request_confirmation
UPDATE interaction_request SET confirmation_id=NULL WHERE id=950004;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_revision_type
UPDATE interaction_revision SET draft_data=JSON_OBJECT() WHERE id=950004;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_revision_type
UPDATE interaction_revision SET schema_dialect=NULL WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_revision_type
UPDATE interaction_revision SET contract_version='form-v2' WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_revision_body
UPDATE interaction_revision SET snapshot_body='' WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_revision_body
UPDATE interaction_revision SET snapshot_body=REPEAT('x',65537) WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_draft_save_pair
UPDATE interaction_revision SET last_save_request_key=UNHEX(REPEAT('11',16)) WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_response_positive
UPDATE interaction_response SET row_version=0 WHERE id=950002;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_response_type
UPDATE interaction_response SET accepted_message_id=NULL WHERE id=950002;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_response_type
UPDATE interaction_response SET accepted_message_id=950001 WHERE id=950003;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_response_type|chk_interaction_response_sync
UPDATE interaction_response SET sdk_sync_status='PENDING' WHERE id=950002;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_response_type
UPDATE interaction_response SET raw_data=JSON_OBJECT('token','synthetic-placeholder') WHERE id=950003;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_response_claim
UPDATE interaction_response SET claim_fence=NULL WHERE id=950002;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_response_after
UPDATE interaction_response SET after_checkpoint_sha256=NULL WHERE id=950002;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_response_form_state
UPDATE interaction_response SET consumed_at=NULL WHERE id=950002;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_response_form_state
UPDATE interaction_response SET status='ACCEPTED' WHERE id=950002;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_response_sync
UPDATE interaction_response SET sdk_sync_status='SYNCED' WHERE id=950003;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_response_continue
UPDATE interaction_response SET continue_request_key=UNHEX(REPEAT('11',16)) WHERE id=950002;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_response_time
UPDATE interaction_response SET consumed_at=TIMESTAMPADD(SECOND,-1,accepted_at) WHERE id=950002;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_confirmation_crypto_pair
UPDATE operation_confirmation SET crypto_format_version=NULL WHERE id=950004;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_confirmation_crypto_pair
UPDATE operation_confirmation SET crypto_format_version=2 WHERE id=950004;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_confirmation_crypto_pair
UPDATE operation_confirmation SET token_ciphertext=UNHEX(REPEAT('aa',47)) WHERE id=950004;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_confirmation_crypto_pair
UPDATE operation_confirmation SET token_nonce=NULL WHERE id=950004;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_confirmation_crypto_pair
UPDATE operation_confirmation SET key_id='' WHERE id=950004;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_confirmation_recovery_status|chk_confirmation_crypto_pair
UPDATE operation_confirmation SET recovery_status='OK' WHERE id=950004;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_nonce_cursor_counter
UPDATE crypto_nonce_cursor SET last_reserved_counter=4294967296 WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_nonce_cursor_counter
UPDATE crypto_nonce_cursor SET row_version=0 WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_nonce_cursor_identity
UPDATE crypto_nonce_cursor SET key_generation_ref='' WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 1062 uk_interaction_public
UPDATE interaction_request SET public_request_id=(SELECT k FROM (SELECT public_request_id k FROM interaction_request WHERE id=950002) x) WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 1062 uk_interaction_run_no
UPDATE interaction_request SET run_id=950002 WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 1062 uk_interaction_active_run
UPDATE interaction_request SET run_id=950004,run_request_no=2 WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 1062 uk_interaction_confirmation
UPDATE interaction_request SET confirmation_id=950003 WHERE id=950004;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 1062 uk_interaction_revision
UPDATE interaction_revision SET request_id=950002 WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 1062 uk_response_request
UPDATE interaction_response SET request_id=950003 WHERE id=950002;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 1062 uk_response_actor_submission
UPDATE interaction_response SET submission_key=UNHEX(LPAD(HEX(980003),32,'0')) WHERE id=950002;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 1062 uk_response_message
INSERT INTO interaction_response (request_id,interaction_type,actor_user_id,submission_key,submission_sha256,accepted_revision_id,snapshot_sha256,raw_data,normalized_data,readable_text,accepted_message_id,accepted_at,status,gmt_create,gmt_modified) VALUES (950001,'FORM',950001,UNHEX(LPAD(HEX(980001),32,'0')),@h,950001,UNHEX(SHA2(@fb,256)),JSON_OBJECT(),JSON_OBJECT(),'answer',950102,@n,'ACCEPTED',@n,@n);
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 1062 uk_confirmation_key_nonce
UPDATE operation_confirmation SET token_nonce=CONCAT(UNHEX('11223344'),UNHEX(LPAD(HEX(1),16,'0'))) WHERE id=950004;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 1062 uk_nonce_key_id
INSERT INTO crypto_nonce_cursor (key_id,nonce_prefix,key_generation_ref,gmt_create,gmt_modified) VALUES ('synthetic-key-v1',UNHEX('55667788'),'other',@n,@n);
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 1062 uk_operation_id
UPDATE operation_confirmation SET operation_id=950003 WHERE id=950004;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 1062 uk_confirmation_token_sha256
UPDATE operation_confirmation SET confirmation_token_sha256=UNHEX(SHA2('synthetic-digest-3',256)) WHERE id=950004;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 1406 token_ciphertext
UPDATE operation_confirmation SET token_ciphertext=UNHEX(REPEAT('aa',49)) WHERE id=950004;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 1406 key_id
UPDATE operation_confirmation SET key_id=REPEAT('a',65) WHERE id=950004;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 1366 key_id
UPDATE operation_confirmation SET key_id='非ASCII' WHERE id=950004;
ROLLBACK TO SAVEPOINT c;
-- EXPECT_PASS
SELECT IF(COUNT(*)=4,'PASS','FAIL') FROM interaction_request;
-- EXPECT_PASS
SELECT IF(COUNT(*)=2,'PASS','FAIL') FROM interaction_response;
-- EXPECT_PASS
SELECT IF(status='WAITING_INPUT' AND input_message_id=950002,'PASS','FAIL') FROM business_run WHERE id=950002;
-- EXPECT_PASS
SELECT IF(initial_run_id=950002,'PASS','FAIL') FROM business_message WHERE id=950102;
-- EXPECT_PASS
SELECT IF(status='CONSUMED' AND accepted_message_id IS NULL AND sdk_call_id IS NULL,'PASS','FAIL') FROM interaction_response WHERE id=950003;
-- EXPECT_PASS
SELECT IF(crypto_format_version=1 AND OCTET_LENGTH(token_nonce)=12 AND OCTET_LENGTH(token_ciphertext)=48,'PASS','FAIL') FROM operation_confirmation WHERE id=950004;
-- EXPECT_PASS
SELECT IF(snapshot_sha256=UNHEX(SHA2(snapshot_body,256)) AND draft_revision=1,'PASS','FAIL') FROM interaction_revision WHERE id=950001;
SAVEPOINT c;
UPDATE operation_confirmation SET crypto_format_version=NULL,key_id=NULL,token_nonce=NULL,token_ciphertext=NULL,recovery_status='MISSING' WHERE id=950004;
-- EXPECT_PASS
SELECT IF(recovery_status='MISSING' AND confirmation_token_sha256 IS NOT NULL,'PASS','FAIL') FROM operation_confirmation WHERE id=950004;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
UPDATE operation_confirmation SET recovery_status='UNAVAILABLE' WHERE id=950004;
-- EXPECT_PASS
SELECT IF(recovery_status='UNAVAILABLE' AND crypto_format_version=1,'PASS','FAIL') FROM operation_confirmation WHERE id=950004;
ROLLBACK TO SAVEPOINT c;

SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_draft_budget
UPDATE interaction_revision SET draft_data=JSON_OBJECT('tooLarge',REPEAT('x',65536)) WHERE id=950001;
ROLLBACK TO SAVEPOINT c;
SAVEPOINT c;
-- EXPECT_ERROR 3819 chk_interaction_answer_budget
UPDATE interaction_response SET raw_data=JSON_OBJECT('tooLarge',REPEAT('x',65536)) WHERE id=950002;
ROLLBACK TO SAVEPOINT c;

SAVEPOINT c;
-- EXPECT_ERROR 1406 token_nonce
UPDATE operation_confirmation SET token_nonce=UNHEX(REPEAT('aa',13)) WHERE id=950004;
ROLLBACK TO SAVEPOINT c;
