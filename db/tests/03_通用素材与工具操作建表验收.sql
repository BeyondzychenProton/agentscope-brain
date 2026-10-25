-- 仅用于刚建立的隔离空库，全部为合成元数据；不连接对象存储或真实业务接口。
-- EXPECT_ERROR 标记由隔离验收执行器按错误码及约束名核对；mysql 必须使用 --force。
-- 这些探针只验证数据库约束，不证明跨表权限、上传、确认消费或外部幂等已经实现。
SET @probe_now = UTC_TIMESTAMP(6);
SET @probe_digest = UNHEX(REPEAT('ab', 32));
SET @probe_operation_digest = UNHEX(REPEAT('cd', 32));
START TRANSACTION;

INSERT INTO platform_user (id, display_name, status_changed_at, gmt_create, gmt_modified)
VALUES (910001, '隔离合成用户', @probe_now, @probe_now, @probe_now);
INSERT INTO business_session (id, user_id, employee_id, employee_release_id, state_session_key, last_activity_at, gmt_create, gmt_modified)
VALUES (910001, 910001, 1, 1, REPEAT('a',32), @probe_now, @probe_now, @probe_now);
INSERT INTO business_session (id, user_id, employee_id, employee_release_id, source_session_id, state_session_key, last_activity_at, gmt_create, gmt_modified)
VALUES (910002, 910001, 1, 2, 910001, REPEAT('b',32), @probe_now, @probe_now, @probe_now);
-- EXPECT_ERROR 1062 uk_successful_source_session_id
INSERT INTO business_session (id, user_id, employee_id, employee_release_id, source_session_id, state_session_key, last_activity_at, gmt_create, gmt_modified)
VALUES (910003, 910001, 1, 3, 910001, REPEAT('c',32), @probe_now, @probe_now, @probe_now);

INSERT INTO session_upgrade_attempt (source_session_id, requested_by_user_id, target_release_id, status, history_message_count, history_sha256, source_last_message_no, expires_at, gmt_create, gmt_modified)
VALUES (910002, 910001, 3, 'READY_FOR_FIRST_MESSAGE', 0, @probe_digest, 0, @probe_now + INTERVAL 30 MINUTE, @probe_now, @probe_now);
-- EXPECT_ERROR 1062 uk_open_source_session_id
INSERT INTO session_upgrade_attempt (source_session_id, requested_by_user_id, target_release_id, status, history_message_count, history_sha256, source_last_message_no, expires_at, gmt_create, gmt_modified)
VALUES (910002, 910001, 4, 'READY_FOR_FIRST_MESSAGE', 0, @probe_digest, 0, @probe_now + INTERVAL 30 MINUTE, @probe_now, @probe_now);

INSERT INTO business_message (id, session_id, user_id, employee_id, message_no, input_sequence, sender_kind, submission_key, submission_sha256, accepted_at, gmt_create, gmt_modified)
VALUES (910001, 910001, 910001, 1, 1, 1, 'USER', UNHEX(REPEAT('11',16)), @probe_digest, @probe_now, @probe_now, @probe_now);
-- EXPECT_ERROR 1062 uk_user_submission_key
INSERT INTO business_message (session_id, user_id, employee_id, message_no, input_sequence, sender_kind, submission_key, submission_sha256, accepted_at, gmt_create, gmt_modified)
VALUES (910001, 910001, 1, 2, 2, 'USER', UNHEX(REPEAT('11',16)), @probe_digest, @probe_now, @probe_now, @probe_now);
-- EXPECT_ERROR 3819 chk_business_message_sender
UPDATE business_message SET sender_kind = 'ASSISTANT' WHERE id = 910001;

INSERT INTO business_run (id, session_id, input_message_id, actor_user_id, employee_release_id, memory_generation_at_start, memory_revision_at_start, memory_policy_version, run_request_key, request_sha256, gmt_create, gmt_modified)
VALUES (910001, 910001, 910001, 910001, 1, 1, 1, 'p1', UNHEX(REPEAT('22',16)), @probe_digest, @probe_now, @probe_now);
UPDATE business_message SET initial_run_id = 910001 WHERE id = 910001;
-- EXPECT_ERROR 1062 uk_busy_session_id
INSERT INTO business_run (session_id, input_message_id, actor_user_id, employee_release_id, memory_generation_at_start, memory_revision_at_start, memory_policy_version, run_request_key, request_sha256, gmt_create, gmt_modified)
VALUES (910001, 910001, 910001, 1, 1, 1, 'p1', UNHEX(REPEAT('33',16)), @probe_digest, @probe_now, @probe_now);

INSERT INTO message_media (id, message_id, user_id, storage_profile, object_key, media_kind, mime_type, byte_count, content_sha256, gmt_create, gmt_modified)
VALUES
(910001, 910001, 910001, 'private-v1', 'probe/image', 'IMAGE', 'image/png', 100, @probe_digest, @probe_now, @probe_now),
(910002, 910001, 910001, 'private-v1', 'probe/audio', 'AUDIO', 'audio/wav', 100, @probe_digest, @probe_now, @probe_now),
(910003, 910001, 910001, 'private-v1', 'probe/video', 'VIDEO', 'video/mp4', 100, @probe_digest, @probe_now, @probe_now),
(910004, 910001, 910001, 'private-v1', 'probe/pdf', 'FILE', 'application/pdf', 100, @probe_digest, @probe_now, @probe_now),
(910005, 910001, 910001, 'private-v1', 'probe/empty', 'FILE', 'application/octet-stream', 0, UNHEX(SHA2('',256)), @probe_now, @probe_now),
(910006, 910001, 910001, 'private-v1', CONCAT(REPEAT('k',511),'a'), 'FILE', 'application/octet-stream', 1, @probe_digest, @probe_now, @probe_now),
(910007, 910001, 910001, 'private-v1', CONCAT(REPEAT('k',511),'b'), 'FILE', 'application/octet-stream', 1, @probe_digest, @probe_now, @probe_now);
-- EXPECT_PASS
SELECT IF(COUNT(*)=7 AND COUNT(DISTINCT media_kind)=4, 'PASS', 'FAIL') FROM message_media;
-- EXPECT_ERROR 1062 uk_storage_profile_object_key
UPDATE message_media SET object_key = 'probe/image' WHERE id = 910002;
-- EXPECT_ERROR 3819 chk_message_media_locator
UPDATE message_media SET object_key = '   ' WHERE id = 910002;
-- EXPECT_ERROR 3819 chk_message_media_nonempty
UPDATE message_media SET byte_count = 0 WHERE id = 910001;
-- EXPECT_ERROR 3819 chk_message_media_mime
UPDATE message_media SET mime_type = 'not-a-mime' WHERE id = 910001;

INSERT INTO message_content_block (message_id, block_no, block_kind, text_content, media_id, gmt_create, gmt_modified)
VALUES (910001, 1, 'TEXT', '查看这些素材', NULL, @probe_now, @probe_now),
       (910001, 2, 'MEDIA', NULL, 910001, @probe_now, @probe_now),
       (910001, 3, 'MEDIA', NULL, 910004, @probe_now, @probe_now);
-- EXPECT_ERROR 3819 chk_message_block_payload
UPDATE message_content_block SET text_content = '文字混入素材块' WHERE message_id = 910001 AND block_no = 2;
-- EXPECT_ERROR 3819 chk_message_block_payload
UPDATE message_content_block SET block_kind = 'IMAGE' WHERE message_id = 910001 AND block_no = 2;
-- EXPECT_ERROR 1062 uk_media_id
INSERT INTO message_content_block (message_id, block_no, block_kind, media_id, gmt_create, gmt_modified)
VALUES (910001, 4, 'MEDIA', 910001, @probe_now, @probe_now);

INSERT INTO tool_operation (id, run_id, operation_no, actor_user_id, tool_provider_key, tool_name, tool_binding_version, arguments_schema_version,
    effect_kind, is_confirmation_required, identity_binding_kind, identity_binding_ref, identity_binding_version, request_id,
    idempotency_mode, provider_request_id, reconciliation_mode, normalized_arguments, arguments_sha256, operation_sha256, gmt_create, gmt_modified)
VALUES
(910001, 910001, 1, 910001, 'meeting-mcp', 'room.reserve', 'meeting-v1', 'reserve-v1', 'MUTATING', 1, 'MEETING_PHONE', '910001', 1,
 'probe-meeting', 'PROVIDER_KEY', 'probe-meeting', 'REQUEST_ID', JSON_OBJECT('roomId','synthetic-room'), @probe_digest, @probe_operation_digest, @probe_now, @probe_now),
(910002, 910001, 2, 910001, 'leave-http', 'leave.submit', 'leave-v1', 'leave-v1', 'MUTATING', 1, 'PLATFORM_USER', '910001', 1,
 'probe-leave', 'PROVIDER_KEY', 'probe-leave', 'RESOURCE_ID', JSON_OBJECT('days',1), @probe_digest, UNHEX(REPEAT('ce',32)), @probe_now, @probe_now),
(910003, 910001, 3, 910001, 'mail-http', 'mail.send', 'mail-v1', 'mail-v1', 'MUTATING', 1, 'PLATFORM_USER', '910001', 1,
 'probe-mail', 'NONE', NULL, 'MANUAL', JSON_OBJECT('subject','synthetic'), @probe_digest, UNHEX(REPEAT('cf',32)), @probe_now, @probe_now),
(910004, 910001, 4, 910001, 'meeting-mcp', 'room.search', 'meeting-v1', 'search-v1', 'READ_ONLY', 0, 'MEETING_PHONE', '910001', 1,
 'probe-search', 'NONE', NULL, 'MANUAL', JSON_OBJECT(), @probe_digest, UNHEX(REPEAT('d0',32)), @probe_now, @probe_now);
-- EXPECT_PASS
SELECT IF(COUNT(*)=4 AND SUM(is_confirmation_required)=3 AND COUNT(DISTINCT tool_provider_key)=3, 'PASS', 'FAIL') FROM tool_operation;
-- EXPECT_ERROR 1062 uk_request_id
UPDATE tool_operation SET request_id = 'probe-meeting' WHERE id = 910002;
-- EXPECT_ERROR 3819 chk_tool_operation_idempotency
UPDATE tool_operation SET provider_request_id = NULL WHERE id = 910001;
-- EXPECT_ERROR 3819 chk_tool_operation_idempotency
UPDATE tool_operation SET provider_request_id = 'unsupported-key' WHERE id = 910003;
-- EXPECT_ERROR 3819 chk_tool_operation_effect
UPDATE tool_operation SET effect_kind = 'RESERVE' WHERE id = 910001;
-- EXPECT_ERROR 3819 chk_tool_operation_confirmation
UPDATE tool_operation SET is_confirmation_required = 2 WHERE id = 910001;
-- EXPECT_ERROR 3819 chk_tool_operation_identity
UPDATE tool_operation SET identity_binding_version = 0 WHERE id = 910001;
-- EXPECT_ERROR 3819 chk_tool_operation_target
UPDATE tool_operation SET tool_binding_version = '' WHERE id = 910001;

INSERT INTO operation_confirmation (id, operation_id, run_id, actor_user_id, arguments_sha256, operation_sha256, authorization_version,
    security_version, identity_binding_version, confirmation_token_sha256, expires_at, gmt_create, gmt_modified)
VALUES (910001, 910001, 910001, 910001, @probe_digest, @probe_operation_digest, 1, 1, 1, UNHEX(REPEAT('ee',32)), @probe_now + INTERVAL 10 MINUTE, @probe_now, @probe_now),
       (910002, 910002, 910001, 910001, @probe_digest, UNHEX(REPEAT('ce',32)), 1, 1, 1, UNHEX(REPEAT('ef',32)), @probe_now + INTERVAL 10 MINUTE, @probe_now, @probe_now);
-- EXPECT_ERROR 3819 chk_operation_confirmation_consumed
UPDATE operation_confirmation SET status = 'CONSUMED' WHERE id = 910001;
-- EXPECT_ERROR 3819 chk_operation_confirmation_version
UPDATE operation_confirmation SET identity_binding_version = 0 WHERE id = 910001;
-- EXPECT_ERROR 3819 chk_operation_confirmation_expiry
UPDATE operation_confirmation SET expires_at = @probe_now - INTERVAL 1 MINUTE WHERE id = 910001;
-- EXPECT_ERROR 1062 uk_operation_id
UPDATE operation_confirmation SET operation_id = 910001 WHERE id = 910002;
-- EXPECT_ERROR 1062 uk_confirmation_token_sha256
UPDATE operation_confirmation SET confirmation_token_sha256 = UNHEX(REPEAT('ee',32)) WHERE id = 910002;

UPDATE operation_confirmation SET status = 'CONSUMED', consumed_at = @probe_now WHERE id = 910001;
UPDATE tool_operation SET status = 'READY_TO_SEND' WHERE id = 910001;
UPDATE tool_operation SET status = 'UNKNOWN', sent_at = @probe_now WHERE id = 910003;
-- EXPECT_PASS
SELECT IF((SELECT status FROM tool_operation WHERE id=910003)='UNKNOWN'
 AND (SELECT status FROM operation_confirmation WHERE id=910001)='CONSUMED'
 AND (SELECT COUNT(*) FROM operation_confirmation WHERE operation_id=910004)=0, 'PASS','FAIL');

-- EXPECT_ERROR 3819 chk_conversation_audit_admin_actor
INSERT INTO conversation_audit_event (target_user_id, session_id, action_code, outcome, entry_kind, occurred_at, gmt_create, gmt_modified)
VALUES (910001, 910001, 'VIEW_MEDIA', 'SUCCESS', 'ADMIN_WEB', @probe_now, @probe_now, @probe_now);
INSERT INTO conversation_audit_event (actor_user_id, target_user_id, session_id, action_code, outcome, entry_kind, occurred_at, gmt_create, gmt_modified)
VALUES (910001, 910001, 910001, 'VIEW_MEDIA', 'SUCCESS', 'ADMIN_WEB', @probe_now, @probe_now, @probe_now);
ROLLBACK;
-- EXPECT_PASS
SELECT IF((SELECT COUNT(*) FROM platform_user)=0 AND (SELECT COUNT(*) FROM business_session)=0
 AND (SELECT COUNT(*) FROM business_message)=0 AND (SELECT COUNT(*) FROM message_content_block)=0
 AND (SELECT COUNT(*) FROM message_media)=0 AND (SELECT COUNT(*) FROM business_run)=0
 AND (SELECT COUNT(*) FROM session_upgrade_attempt)=0 AND (SELECT COUNT(*) FROM tool_operation)=0
 AND (SELECT COUNT(*) FROM operation_confirmation)=0 AND (SELECT COUNT(*) FROM conversation_audit_event)=0, 'PASS', 'FAIL');
