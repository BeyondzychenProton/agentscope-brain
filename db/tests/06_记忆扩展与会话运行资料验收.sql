-- 仅用于刚建立的隔离空库，全部为合成元数据；不连接框架 KV、对象存储或真实业务接口。
-- EXPECT_ERROR 标记由隔离验收执行器按错误码及约束名核对；mysql 必须使用 --force。
-- 覆盖 02 号文件新增的记忆列（消息输入序号、Run 记忆快照、审计关联列）和 04 号文件的两张会话运行资料表。
-- 这些探针只验证数据库约束，不证明输入序号分配、资料归属校验、KV 往返或快照复制已经实现。
SET @probe_now = UTC_TIMESTAMP(6);
SET @probe_digest = UNHEX(REPEAT('ab', 32));
SET @probe_digest_2 = UNHEX(REPEAT('cd', 32));
START TRANSACTION;

-- 一、business_message：USER 必带员工与输入序号，ASSISTANT 必为空。
INSERT INTO business_message (id, session_id, user_id, employee_id, message_no, input_sequence, sender_kind, submission_key, submission_sha256, accepted_at, gmt_create, gmt_modified)
VALUES (940001, 940001, 940001, 1, 1, 1, 'USER', UNHEX(REPEAT('41',16)), @probe_digest, @probe_now, @probe_now, @probe_now);
INSERT INTO business_run (id, session_id, input_message_id, actor_user_id, employee_release_id, memory_generation_at_start, memory_revision_at_start, memory_policy_version, run_request_key, request_sha256, gmt_create, gmt_modified)
VALUES (940001, 940001, 940001, 940001, 1, 1, 1, 'p1', UNHEX(REPEAT('51',16)), @probe_digest, @probe_now, @probe_now);
INSERT INTO business_message (id, session_id, user_id, message_no, sender_kind, produced_by_run_id, accepted_at, gmt_create, gmt_modified)
VALUES (940002, 940001, 940001, 2, 'ASSISTANT', 940001, @probe_now, @probe_now, @probe_now);
-- 同一用户的另一员工可以从同一序号开始，序号只在（用户，员工）范围内唯一。
INSERT INTO business_message (id, session_id, user_id, employee_id, message_no, input_sequence, sender_kind, submission_key, submission_sha256, accepted_at, gmt_create, gmt_modified)
VALUES (940003, 940002, 940001, 2, 1, 1, 'USER', UNHEX(REPEAT('42',16)), @probe_digest, @probe_now, @probe_now, @probe_now);
-- EXPECT_PASS
SELECT IF((SELECT COUNT(*) FROM business_message) = 3 AND (SELECT COUNT(*) FROM business_message WHERE sender_kind = 'USER' AND input_sequence = 1) = 2
 AND (SELECT COUNT(*) FROM business_message WHERE sender_kind = 'ASSISTANT' AND employee_id IS NULL AND input_sequence IS NULL) = 1, 'PASS', 'FAIL');

-- EXPECT_ERROR 1062 uk_user_employee_input_sequence
INSERT INTO business_message (session_id, user_id, employee_id, message_no, input_sequence, sender_kind, submission_key, submission_sha256, accepted_at, gmt_create, gmt_modified)
VALUES (940001, 940001, 1, 3, 1, 'USER', UNHEX(REPEAT('43',16)), @probe_digest, @probe_now, @probe_now, @probe_now);
-- EXPECT_ERROR 3819 chk_business_message_sender
UPDATE business_message SET input_sequence = NULL WHERE id = 940001;
-- EXPECT_ERROR 3819 chk_business_message_sender
UPDATE business_message SET employee_id = NULL WHERE id = 940001;
-- EXPECT_ERROR 3819 chk_business_message_sender
UPDATE business_message SET input_sequence = 9 WHERE id = 940002;
-- EXPECT_ERROR 3819 chk_business_message_sender
UPDATE business_message SET employee_id = 1 WHERE id = 940002;
-- EXPECT_ERROR 3819 chk_business_message_input_order
UPDATE business_message SET input_sequence = 0 WHERE id = 940001;
-- EXPECT_ERROR 3819 chk_business_message_input_order
UPDATE business_message SET employee_id = 0 WHERE id = 940001;

-- 二、business_run：创建时必须固定记忆代次、修订和策略版本。
-- EXPECT_ERROR 1364 memory_generation_at_start
INSERT INTO business_run (session_id, input_message_id, actor_user_id, employee_release_id, run_request_key, request_sha256, gmt_create, gmt_modified)
VALUES (940002, 940003, 940001, 1, UNHEX(REPEAT('52',16)), @probe_digest, @probe_now, @probe_now);
-- EXPECT_ERROR 3819 chk_business_run_memory_snapshot
UPDATE business_run SET memory_generation_at_start = 0 WHERE id = 940001;
-- EXPECT_ERROR 3819 chk_business_run_memory_snapshot
UPDATE business_run SET memory_revision_at_start = 0 WHERE id = 940001;
-- EXPECT_ERROR 3819 chk_business_run_memory_snapshot
UPDATE business_run SET memory_policy_version = '   ' WHERE id = 940001;

-- 三、conversation_audit_event：记忆列全部可空，仅有与动作码无关的成对约束。
INSERT INTO conversation_audit_event (target_user_id, action_code, outcome, entry_kind, occurred_at, gmt_create, gmt_modified)
VALUES (940001, 'ARCHIVE', 'SUCCESS', 'WEB', @probe_now, @probe_now, @probe_now);
INSERT INTO conversation_audit_event (target_user_id, employee_id, memory_generation, action_code, outcome, entry_kind, occurred_at, gmt_create, gmt_modified)
VALUES (940001, 1, 1, 'MEMORY_PROFILE_INIT', 'SUCCESS', 'SYSTEM', @probe_now, @probe_now, @probe_now);
INSERT INTO conversation_audit_event (actor_user_id, target_user_id, employee_id, memory_generation, document_revision_before, document_revision_after, action_code, outcome, entry_kind, occurred_at, gmt_create, gmt_modified)
VALUES (940001, 940001, 1, 2, 3, 1, 'MEMORY_CLEAR', 'SUCCESS', 'WEB', @probe_now, @probe_now, @probe_now);
INSERT INTO conversation_audit_event (target_user_id, employee_id, memory_generation, document_revision_before, document_revision_after, memory_update_job_id, action_code, outcome, entry_kind, occurred_at, gmt_create, gmt_modified)
VALUES (940001, 1, 2, 1, 2, 940001, 'MEMORY_AUTO_MERGE', 'SUCCESS', 'SYSTEM', @probe_now, @probe_now, @probe_now);
-- EXPECT_PASS
SELECT IF((SELECT COUNT(*) FROM conversation_audit_event) = 4
 AND (SELECT COUNT(*) FROM conversation_audit_event WHERE employee_id IS NULL AND memory_generation IS NULL AND memory_update_job_id IS NULL) = 1, 'PASS', 'FAIL');

-- EXPECT_ERROR 3819 chk_conversation_audit_revision_pair
INSERT INTO conversation_audit_event (target_user_id, employee_id, memory_generation, document_revision_before, action_code, outcome, entry_kind, occurred_at, gmt_create, gmt_modified)
VALUES (940001, 1, 2, 3, 'MEMORY_SAVE', 'SUCCESS', 'WEB', @probe_now, @probe_now, @probe_now);
-- EXPECT_ERROR 3819 chk_conversation_audit_revision_pair
INSERT INTO conversation_audit_event (target_user_id, employee_id, memory_generation, document_revision_after, action_code, outcome, entry_kind, occurred_at, gmt_create, gmt_modified)
VALUES (940001, 1, 2, 3, 'MEMORY_SAVE', 'SUCCESS', 'WEB', @probe_now, @probe_now, @probe_now);
-- EXPECT_ERROR 3819 chk_conversation_audit_revision_generation
INSERT INTO conversation_audit_event (target_user_id, employee_id, document_revision_before, document_revision_after, action_code, outcome, entry_kind, occurred_at, gmt_create, gmt_modified)
VALUES (940001, 1, 3, 1, 'MEMORY_SAVE', 'SUCCESS', 'WEB', @probe_now, @probe_now, @probe_now);
-- EXPECT_ERROR 3819 chk_conversation_audit_memory_employee
INSERT INTO conversation_audit_event (target_user_id, memory_generation, action_code, outcome, entry_kind, occurred_at, gmt_create, gmt_modified)
VALUES (940001, 2, 'MEMORY_SAVE', 'SUCCESS', 'WEB', @probe_now, @probe_now, @probe_now);
-- EXPECT_ERROR 3819 chk_conversation_audit_memory_employee
INSERT INTO conversation_audit_event (target_user_id, memory_update_job_id, action_code, outcome, entry_kind, occurred_at, gmt_create, gmt_modified)
VALUES (940001, 940001, 'MEMORY_AUTO_MERGE', 'SUCCESS', 'SYSTEM', @probe_now, @probe_now, @probe_now);
-- EXPECT_ERROR 3819 chk_conversation_audit_memory_positive
INSERT INTO conversation_audit_event (target_user_id, employee_id, action_code, outcome, entry_kind, occurred_at, gmt_create, gmt_modified)
VALUES (940001, 0, 'WORKSPACE_ADMIN_VIEW', 'SUCCESS', 'WEB', @probe_now, @probe_now, @probe_now);
-- EXPECT_ERROR 3819 chk_conversation_audit_memory_positive
INSERT INTO conversation_audit_event (target_user_id, employee_id, memory_generation, action_code, outcome, entry_kind, occurred_at, gmt_create, gmt_modified)
VALUES (940001, 1, 0, 'MEMORY_SAVE', 'SUCCESS', 'WEB', @probe_now, @probe_now, @probe_now);

-- 四、workspace_entry：同一 Session 同一路径至多一个 ACTIVE 条目。
INSERT INTO workspace_entry (id, user_id, employee_id, session_id, source_run_id, employee_release_id, entry_path, entry_kind, storage_type, kv_version, mime_type, byte_count, content_sha256, gmt_create, gmt_modified)
VALUES (940001, 940001, 1, 940001, 940001, 1, 'plan/Plan.md', 'PLAN', 'KV_TEXT', 1, 'text/markdown', 10, @probe_digest, @probe_now, @probe_now);
INSERT INTO workspace_entry (id, user_id, employee_id, session_id, source_run_id, employee_release_id, entry_path, entry_kind, storage_type, storage_profile, object_key, mime_type, byte_count, content_sha256, gmt_create, gmt_modified)
VALUES (940002, 940001, 1, 940001, 940001, 1, 'files/a.pdf', 'GENERATED', 'OBJECT_FILE', 'private-v1', 'ws/940002/a', 'application/pdf', 0, UNHEX(SHA2('', 256)), @probe_now, @probe_now);
-- 另一个 Session 可以有同名路径。
INSERT INTO workspace_entry (id, user_id, employee_id, session_id, source_run_id, employee_release_id, entry_path, entry_kind, storage_type, kv_version, mime_type, byte_count, content_sha256, gmt_create, gmt_modified)
VALUES (940004, 940001, 1, 940002, 940001, 1, 'plan/Plan.md', 'PLAN', 'KV_TEXT', 1, 'text/markdown', 10, @probe_digest, @probe_now, @probe_now);
-- EXPECT_PASS
SELECT IF((SELECT COUNT(*) FROM workspace_entry) = 3 AND (SELECT active_entry_path FROM workspace_entry WHERE id = 940001) = 'plan/Plan.md'
 AND (SELECT content_revision FROM workspace_entry WHERE id = 940001) = 1, 'PASS', 'FAIL');

-- 排序规则不区分大小写，与框架 KV 主键一致：大小写不同的同一路径被拒绝。
-- EXPECT_ERROR 1062 uk_session_active_entry_path
INSERT INTO workspace_entry (user_id, employee_id, session_id, source_run_id, employee_release_id, entry_path, entry_kind, storage_type, kv_version, mime_type, byte_count, content_sha256, gmt_create, gmt_modified)
VALUES (940001, 1, 940001, 940001, 1, 'PLAN/plan.md', 'NOTE', 'KV_TEXT', 1, 'text/markdown', 1, @probe_digest, @probe_now, @probe_now);
-- EXPECT_ERROR 1062 uk_storage_profile_object_key
INSERT INTO workspace_entry (user_id, employee_id, session_id, source_run_id, employee_release_id, entry_path, entry_kind, storage_type, storage_profile, object_key, mime_type, byte_count, content_sha256, gmt_create, gmt_modified)
VALUES (940001, 1, 940001, 940001, 1, 'files/b.pdf', 'GENERATED', 'OBJECT_FILE', 'private-v1', 'ws/940002/a', 'application/pdf', 1, @probe_digest, @probe_now, @probe_now);
-- EXPECT_ERROR 3819 chk_workspace_entry_path
UPDATE workspace_entry SET entry_path = '   ' WHERE id = 940002;
-- EXPECT_ERROR 3819 chk_workspace_entry_kind
UPDATE workspace_entry SET entry_kind = 'DIARY' WHERE id = 940002;
-- EXPECT_ERROR 3819 chk_workspace_entry_revision
UPDATE workspace_entry SET content_revision = 0 WHERE id = 940001;
-- EXPECT_ERROR 3819 chk_workspace_entry_mime
UPDATE workspace_entry SET mime_type = 'plain' WHERE id = 940001;
-- EXPECT_ERROR 3819 chk_workspace_entry_deleted
UPDATE workspace_entry SET entry_status = 'DELETED' WHERE id = 940002;
-- EXPECT_ERROR 3819 chk_workspace_entry_deleted
UPDATE workspace_entry SET deleted_at = @probe_now WHERE id = 940002;
-- EXPECT_ERROR 3819 chk_workspace_entry_storage
UPDATE workspace_entry SET kv_version = NULL WHERE id = 940001;
-- EXPECT_ERROR 3819 chk_workspace_entry_storage
UPDATE workspace_entry SET object_key = 'ws/940001/x' WHERE id = 940001;
-- EXPECT_ERROR 3819 chk_workspace_entry_storage
UPDATE workspace_entry SET kv_version = 1 WHERE id = 940002;
-- EXPECT_ERROR 3819 chk_workspace_entry_storage
UPDATE workspace_entry SET object_key = '   ' WHERE id = 940002;
-- EXPECT_ERROR 3819 chk_workspace_entry_storage
UPDATE workspace_entry SET storage_profile = NULL WHERE id = 940002;

-- 墓碑释放路径：旧条目 DELETED 后，同一路径可重新创建为新行，修订从 1 重新计。
UPDATE workspace_entry SET entry_status = 'DELETED', deleted_at = @probe_now WHERE id = 940001;
INSERT INTO workspace_entry (id, user_id, employee_id, session_id, source_run_id, employee_release_id, entry_path, entry_kind, storage_type, kv_version, mime_type, byte_count, content_sha256, gmt_create, gmt_modified)
VALUES (940003, 940001, 1, 940001, 940001, 1, 'plan/Plan.md', 'PLAN', 'KV_TEXT', 1, 'text/markdown', 5, @probe_digest_2, @probe_now, @probe_now);
-- EXPECT_PASS
SELECT IF((SELECT COUNT(*) FROM workspace_entry WHERE session_id = 940001 AND entry_path = 'plan/Plan.md') = 2
 AND (SELECT COUNT(*) FROM workspace_entry WHERE session_id = 940001 AND active_entry_path IS NOT NULL AND entry_path = 'plan/Plan.md') = 1
 AND (SELECT active_entry_path IS NULL FROM workspace_entry WHERE id = 940001) = 1
 AND (SELECT content_revision FROM workspace_entry WHERE id = 940003) = 1, 'PASS', 'FAIL');

-- 五、workspace_snapshot_reference：同一目标 Session 对同一来源同一修订只有一个快照。
INSERT INTO workspace_snapshot_reference (id, user_id, employee_id, source_entry_id, source_content_revision, source_content_sha256, source_session_id, source_run_id, target_session_id,
    storage_type, snapshot_kv_version, mime_type, byte_count, snapshot_sha256, created_by_user_id, gmt_create, gmt_modified)
VALUES (940001, 940001, 1, 940004, 1, @probe_digest, 940002, 940001, 940001, 'KV_TEXT', 1, 'text/markdown', 10, @probe_digest, 940001, @probe_now, @probe_now);
INSERT INTO workspace_snapshot_reference (id, user_id, employee_id, source_entry_id, source_content_revision, source_content_sha256, source_session_id, source_run_id, target_session_id,
    storage_type, storage_profile, object_key, mime_type, byte_count, snapshot_sha256, created_by_user_id, gmt_create, gmt_modified)
VALUES (940002, 940001, 1, 940002, 1, UNHEX(SHA2('', 256)), 940001, 940001, 940002, 'OBJECT_FILE', 'private-v1', 'ws/940002/a', 'application/pdf', 0, UNHEX(SHA2('', 256)), 940001, @probe_now, @probe_now);
-- 来源改过后再引用，修订号不同，得到新的快照。
INSERT INTO workspace_snapshot_reference (id, user_id, employee_id, source_entry_id, source_content_revision, source_content_sha256, source_session_id, source_run_id, target_session_id,
    storage_type, snapshot_kv_version, mime_type, byte_count, snapshot_sha256, created_by_user_id, gmt_create, gmt_modified)
VALUES (940003, 940001, 1, 940004, 2, @probe_digest_2, 940002, 940001, 940001, 'KV_TEXT', 1, 'text/markdown', 12, @probe_digest_2, 940001, @probe_now, @probe_now);
-- EXPECT_PASS
SELECT IF((SELECT COUNT(*) FROM workspace_snapshot_reference) = 3
 AND (SELECT COUNT(*) FROM workspace_snapshot_reference WHERE source_entry_id = 940004) = 2, 'PASS', 'FAIL');

-- EXPECT_ERROR 1062 uk_target_session_source_revision
INSERT INTO workspace_snapshot_reference (user_id, employee_id, source_entry_id, source_content_revision, source_content_sha256, source_session_id, source_run_id, target_session_id,
    storage_type, snapshot_kv_version, mime_type, byte_count, snapshot_sha256, created_by_user_id, gmt_create, gmt_modified)
VALUES (940001, 1, 940004, 1, @probe_digest, 940002, 940001, 940001, 'KV_TEXT', 1, 'text/markdown', 10, @probe_digest, 940001, @probe_now, @probe_now);
-- EXPECT_ERROR 3819 chk_workspace_snapshot_digest
UPDATE workspace_snapshot_reference SET snapshot_sha256 = @probe_digest_2 WHERE id = 940001;
-- EXPECT_ERROR 3819 chk_workspace_snapshot_revision
UPDATE workspace_snapshot_reference SET source_content_revision = 0 WHERE id = 940001;
-- EXPECT_ERROR 3819 chk_workspace_snapshot_mime
UPDATE workspace_snapshot_reference SET mime_type = 'plain' WHERE id = 940001;
-- EXPECT_ERROR 3819 chk_workspace_snapshot_storage
UPDATE workspace_snapshot_reference SET snapshot_kv_version = NULL WHERE id = 940001;
-- EXPECT_ERROR 3819 chk_workspace_snapshot_storage
UPDATE workspace_snapshot_reference SET object_key = 'ws/x' WHERE id = 940001;
-- EXPECT_ERROR 3819 chk_workspace_snapshot_storage
UPDATE workspace_snapshot_reference SET snapshot_kv_version = 1 WHERE id = 940002;
-- EXPECT_ERROR 3819 chk_workspace_snapshot_storage
UPDATE workspace_snapshot_reference SET storage_profile = NULL WHERE id = 940002;

-- EXPECT_PASS
SELECT IF((SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
 WHERE table_schema = DATABASE() AND table_name = 'workspace_entry'
 AND index_name IN ('PRIMARY', 'uk_session_active_entry_path', 'uk_storage_profile_object_key', 'idx_user_employee_session', 'idx_source_run_id')) = 5
 AND (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
 WHERE table_schema = DATABASE() AND table_name = 'workspace_snapshot_reference'
 AND index_name IN ('PRIMARY', 'uk_target_session_source_revision', 'idx_source_entry_id', 'idx_user_employee_target_session')) = 4
 AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'business_message'
 AND index_name = 'uk_user_employee_input_sequence') = 3, 'PASS', 'FAIL');

ROLLBACK;
-- EXPECT_PASS
SELECT IF((SELECT COUNT(*) FROM workspace_entry) = 0 AND (SELECT COUNT(*) FROM workspace_snapshot_reference) = 0
 AND (SELECT COUNT(*) FROM business_message) = 0 AND (SELECT COUNT(*) FROM business_run) = 0
 AND (SELECT COUNT(*) FROM conversation_audit_event) = 0, 'PASS', 'FAIL');
