-- 仅用于刚建立的隔离空库，全部为合成元数据；不连接框架 KV、对象存储或真实业务接口。
-- EXPECT_ERROR 标记由隔离验收执行器按错误码及约束名核对；mysql 必须使用 --force。
-- 这些探针只验证数据库约束，以及设计文档规定的条件更新/批量置过期语句的行为，
-- 不证明框架 KV 往返、两阶段激活、任务恢复或授权并发已经实现。
SET @probe_now = UTC_TIMESTAMP(6);
SET @probe_digest = UNHEX(REPEAT('ab', 32));
SET @probe_digest_2 = UNHEX(REPEAT('cd', 32));
START TRANSACTION;

INSERT INTO memory_profile (id, user_id, employee_id, document_kv_version, document_sha256, memory_schema_version, policy_version, activated_at, gmt_create, gmt_modified)
VALUES (920001, 920001, 1, 1, UNHEX(SHA2('', 256)), 'v1', 'p1', @probe_now, @probe_now, @probe_now);
-- EXPECT_PASS
SELECT IF(generation = 1 AND document_revision = 1 AND last_input_sequence = 0 AND generation_input_cutoff = 0
 AND profile_status = 'READY' AND document_sha256 = UNHEX(SHA2('', 256)) AND prepared_operation_key IS NULL, 'PASS', 'FAIL')
 FROM memory_profile WHERE user_id = 920001 AND employee_id = 1;

-- EXPECT_ERROR 1062 uk_user_employee
INSERT INTO memory_profile (user_id, employee_id, document_kv_version, document_sha256, memory_schema_version, policy_version, activated_at, gmt_create, gmt_modified)
VALUES (920001, 1, 1, @probe_digest, 'v1', 'p1', @probe_now, @probe_now, @probe_now);
-- EXPECT_ERROR 3819 chk_memory_profile_status
UPDATE memory_profile SET profile_status = 'BROKEN' WHERE id = 920001;
-- EXPECT_ERROR 3819 chk_memory_profile_generation
UPDATE memory_profile SET generation = 0 WHERE id = 920001;
-- EXPECT_ERROR 3819 chk_memory_profile_revision
UPDATE memory_profile SET document_revision = 0 WHERE id = 920001;
-- EXPECT_ERROR 3819 chk_memory_profile_cutoff
UPDATE memory_profile SET generation_input_cutoff = last_input_sequence + 1 WHERE id = 920001;
-- EXPECT_ERROR 3819 chk_memory_profile_prepare
UPDATE memory_profile SET prepared_operation_key = UNHEX(REPEAT('aa', 16)) WHERE id = 920001;
-- EXPECT_ERROR 3819 chk_memory_profile_last_operation
UPDATE memory_profile SET last_operation_sha256 = @probe_digest WHERE id = 920001;
-- EXPECT_ERROR 3819 chk_memory_profile_versions
UPDATE memory_profile SET policy_version = '   ' WHERE id = 920001;

-- 输入序号分配：同一行锁内先加一再读取，两次接纳得到 1、2。
UPDATE memory_profile SET last_input_sequence = last_input_sequence + 1, gmt_modified = @probe_now WHERE user_id = 920001 AND employee_id = 1;
UPDATE memory_profile SET last_input_sequence = last_input_sequence + 1, gmt_modified = @probe_now WHERE user_id = 920001 AND employee_id = 1;
-- EXPECT_PASS
SELECT IF(last_input_sequence = 2, 'PASS', 'FAIL') FROM memory_profile WHERE user_id = 920001 AND employee_id = 1;

-- 第 1 代任务：待处理/处理中/已成功；同一 Run 同一代次的提取与合并是两种任务，可以并存。
INSERT INTO memory_update_job (id, user_id, employee_id, generation, task_type, source_session_id, source_run_id, source_message_id, source_input_sequence, authorization_version_at_creation, memory_schema_version, policy_version, status, next_attempt_at, gmt_create, gmt_modified)
VALUES
(920101, 920001, 1, 1, 'FLUSH', 920001, 920101, 920101, 1, 1, 'v1', 'p1', 'PENDING', @probe_now, @probe_now, @probe_now),
(920102, 920001, 1, 1, 'CONSOLIDATE', 920001, 920101, 920101, 1, 1, 'v1', 'p1', 'PENDING', @probe_now, @probe_now, @probe_now);
INSERT INTO memory_update_job (id, user_id, employee_id, generation, task_type, source_session_id, source_run_id, source_message_id, source_input_sequence, authorization_version_at_creation, memory_schema_version, policy_version, status, next_attempt_at, claim_token, claimed_at, lease_expires_at, attempt_count, gmt_create, gmt_modified)
VALUES (920103, 920001, 1, 1, 'FLUSH', 920001, 920102, 920102, 2, 1, 'v1', 'p1', 'RUNNING', @probe_now, UNHEX(REPEAT('11', 16)), @probe_now, @probe_now + INTERVAL 5 MINUTE, 1, @probe_now, @probe_now);
INSERT INTO memory_update_job (id, user_id, employee_id, generation, task_type, source_session_id, source_run_id, source_message_id, source_input_sequence, authorization_version_at_creation, memory_schema_version, policy_version, status, next_attempt_at, applied_at, finished_at, gmt_create, gmt_modified)
VALUES (920104, 920001, 1, 1, 'FLUSH', 920001, 920103, 920103, 2, 1, 'v1', 'p1', 'SUCCEEDED', @probe_now, @probe_now, @probe_now, @probe_now, @probe_now);
-- EXPECT_PASS
SELECT IF((SELECT COUNT(*) FROM memory_update_job WHERE source_run_id = 920101 AND generation = 1) = 2
 AND (SELECT COUNT(*) FROM memory_update_job) = 4, 'PASS', 'FAIL');

-- EXPECT_ERROR 1062 uk_source_run_generation_task_type
INSERT INTO memory_update_job (user_id, employee_id, generation, task_type, source_session_id, source_run_id, source_message_id, source_input_sequence, authorization_version_at_creation, memory_schema_version, policy_version, next_attempt_at, gmt_create, gmt_modified)
VALUES (920001, 1, 1, 'FLUSH', 920001, 920101, 920101, 1, 1, 'v1', 'p1', @probe_now, @probe_now, @probe_now);
-- EXPECT_ERROR 3819 chk_memory_job_task_type
UPDATE memory_update_job SET task_type = 'LEARN' WHERE id = 920101;
-- EXPECT_ERROR 3819 chk_memory_job_status
UPDATE memory_update_job SET status = 'DONE' WHERE id = 920101;
-- EXPECT_ERROR 3819 chk_memory_job_generation
UPDATE memory_update_job SET generation = 0 WHERE id = 920101;
-- EXPECT_ERROR 3819 chk_memory_job_sequence
UPDATE memory_update_job SET source_input_sequence = 0 WHERE id = 920101;
-- EXPECT_ERROR 3819 chk_memory_job_authorization
UPDATE memory_update_job SET authorization_version_at_creation = 0 WHERE id = 920101;
-- EXPECT_ERROR 3819 chk_memory_job_versions
UPDATE memory_update_job SET policy_version = ' ' WHERE id = 920101;
-- EXPECT_ERROR 3819 chk_memory_job_running
UPDATE memory_update_job SET status = 'RUNNING' WHERE id = 920101;
-- EXPECT_ERROR 3819 chk_memory_job_terminal
UPDATE memory_update_job SET finished_at = NULL WHERE id = 920104;
-- EXPECT_ERROR 3819 chk_memory_job_terminal
UPDATE memory_update_job SET finished_at = @probe_now WHERE id = 920101;
-- EXPECT_ERROR 3819 chk_memory_job_reason
UPDATE memory_update_job SET status = 'CANCELLED', finished_at = @probe_now WHERE id = 920101;
-- EXPECT_ERROR 3819 chk_memory_job_success
UPDATE memory_update_job SET status = 'SUCCEEDED', finished_at = @probe_now, applied_at = @probe_now WHERE id = 920102;
-- EXPECT_ERROR 3819 chk_memory_job_candidate
UPDATE memory_update_job SET candidate_locator = 'memory/probe' WHERE id = 920101;

-- 手工保存的激活语句：条件更新第 1 代第 1 修订；同一条件第二次执行因代次已变更新 0 行。
UPDATE memory_profile SET generation = generation + 1, document_revision = 1, document_kv_version = 1, document_sha256 = @probe_digest,
 generation_input_cutoff = last_input_sequence, prepared_operation_key = NULL, prepared_expires_at = NULL,
 last_operation_key = UNHEX(REPEAT('01', 16)), last_operation_sha256 = @probe_digest_2, activated_at = @probe_now, gmt_modified = @probe_now
 WHERE user_id = 920001 AND employee_id = 1 AND generation = 1 AND document_revision = 1;
SET @first_activation_rows = ROW_COUNT();
UPDATE memory_update_job SET status = 'STALE', reason_code = 'GENERATION_SUPERSEDED', finished_at = @probe_now, gmt_modified = @probe_now
 WHERE user_id = 920001 AND employee_id = 1 AND generation = 1 AND status IN ('PENDING', 'RUNNING');
SET @stale_rows = ROW_COUNT();
UPDATE memory_profile SET generation = generation + 1, document_revision = 1, document_kv_version = 1, document_sha256 = @probe_digest,
 generation_input_cutoff = last_input_sequence, gmt_modified = @probe_now
 WHERE user_id = 920001 AND employee_id = 1 AND generation = 1 AND document_revision = 1;
SET @second_activation_rows = ROW_COUNT();
-- EXPECT_PASS
SELECT IF(@first_activation_rows = 1 AND @second_activation_rows = 0 AND @stale_rows = 3
 AND (SELECT generation FROM memory_profile WHERE id = 920001) = 2
 AND (SELECT generation_input_cutoff FROM memory_profile WHERE id = 920001) = 2
 AND (SELECT COUNT(*) FROM memory_update_job WHERE status = 'STALE') = 3
 AND (SELECT status FROM memory_update_job WHERE id = 920104) = 'SUCCEEDED', 'PASS', 'FAIL');

-- 自动合并的条件提交：只改修订，不换代；基线修订过期时更新 0 行。
UPDATE memory_profile SET document_revision = document_revision + 1, document_kv_version = 2, document_sha256 = @probe_digest_2, gmt_modified = @probe_now
 WHERE user_id = 920001 AND employee_id = 1 AND generation = 2 AND document_revision = 1;
SET @merge_rows = ROW_COUNT();
UPDATE memory_profile SET document_revision = document_revision + 1, document_kv_version = 3, gmt_modified = @probe_now
 WHERE user_id = 920001 AND employee_id = 1 AND generation = 2 AND document_revision = 1;
SET @stale_merge_rows = ROW_COUNT();
-- EXPECT_PASS
SELECT IF(@merge_rows = 1 AND @stale_merge_rows = 0 AND (SELECT document_revision FROM memory_profile WHERE id = 920001) = 2
 AND (SELECT generation FROM memory_profile WHERE id = 920001) = 2, 'PASS', 'FAIL');

-- EXPECT_PASS
SELECT IF((SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
 WHERE table_schema = DATABASE() AND table_name = 'memory_update_job'
 AND index_name IN ('PRIMARY', 'uk_source_run_generation_task_type', 'idx_status_next_attempt_at', 'idx_status_lease_expires_at', 'idx_user_employee_generation_status')) = 5
 AND (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
 WHERE table_schema = DATABASE() AND table_name = 'memory_profile' AND index_name IN ('PRIMARY', 'uk_user_employee')) = 2, 'PASS', 'FAIL');

ROLLBACK;
-- EXPECT_PASS
SELECT IF((SELECT COUNT(*) FROM memory_profile) = 0 AND (SELECT COUNT(*) FROM memory_update_job) = 0, 'PASS', 'FAIL');
