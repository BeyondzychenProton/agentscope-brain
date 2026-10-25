-- 巡检查询验证种子：仅用于刚建立的隔离空库，合成数据；执行器在同一事务内先运行“基线”段并要求 05 号巡检全部为 0，
-- 再运行“异常”段并要求 05 号巡检的每一项都大于 0，最后回滚。
SET @probe_now = UTC_TIMESTAMP(6);
SET @probe_digest = UNHEX(REPEAT('ab', 32));
SET @probe_old = '2020-01-01 00:00:00.000000';
START TRANSACTION;

-- 基线：一组彼此一致的数据，所有巡检必须为 0。
INSERT INTO platform_user (id, display_name, status_changed_at, gmt_create, gmt_modified) VALUES
(950001, '巡检基线用户', @probe_now, @probe_now, @probe_now),
(950005, '巡检序号落后用户', @probe_now, @probe_now, @probe_now);
INSERT INTO business_session (id, user_id, employee_id, employee_release_id, state_session_key, last_activity_at, gmt_create, gmt_modified)
VALUES (950001, 950001, 1, 1, REPEAT('1', 32), @probe_now, @probe_now, @probe_now);
INSERT INTO memory_profile (id, user_id, employee_id, generation, document_revision, document_kv_version, document_sha256, memory_schema_version, policy_version,
    generation_input_cutoff, last_input_sequence, activated_at, gmt_create, gmt_modified)
VALUES (950001, 950001, 1, 2, 3, 1, @probe_digest, 'v1', 'p1', 5, 8, @probe_now, @probe_now, @probe_now);
INSERT INTO business_message (id, session_id, user_id, employee_id, message_no, input_sequence, sender_kind, submission_key, submission_sha256, accepted_at, gmt_create, gmt_modified)
VALUES (950001, 950001, 950001, 1, 1, 6, 'USER', UNHEX(REPEAT('61', 16)), @probe_digest, @probe_now, @probe_now, @probe_now);
INSERT INTO business_run (id, session_id, input_message_id, actor_user_id, employee_release_id, memory_generation_at_start, memory_revision_at_start, memory_policy_version,
    run_request_key, request_sha256, status, ended_at, gmt_create, gmt_modified)
VALUES (950001, 950001, 950001, 950001, 1, 2, 3, 'p1', UNHEX(REPEAT('71', 16)), @probe_digest, 'SUCCEEDED', @probe_old, @probe_now, @probe_now);
INSERT INTO memory_update_job (id, user_id, employee_id, generation, task_type, source_session_id, source_run_id, source_message_id, source_input_sequence,
    authorization_version_at_creation, memory_schema_version, policy_version, next_attempt_at, gmt_create, gmt_modified)
VALUES (950001, 950001, 1, 2, 'FLUSH', 950001, 950001, 950001, 6, 1, 'v1', 'p1', @probe_now, @probe_now, @probe_now);
-- INSPECT_ALL_ZERO

-- ANOMALIES
-- 1 memory_profile_user_orphan：控制记录的用户不存在。
INSERT INTO memory_profile (id, user_id, employee_id, document_kv_version, document_sha256, memory_schema_version, policy_version, activated_at, gmt_create, gmt_modified)
VALUES (950002, 950002, 1, 1, @probe_digest, 'v1', 'p1', @probe_now, @probe_now, @probe_now);
-- 2 memory_job_profile_orphan：任务找不到控制记录。
INSERT INTO memory_update_job (id, user_id, employee_id, generation, task_type, source_session_id, source_run_id, source_message_id, source_input_sequence,
    authorization_version_at_creation, memory_schema_version, policy_version, next_attempt_at, gmt_create, gmt_modified)
VALUES (950002, 950003, 1, 1, 'FLUSH', 950001, 950003, 950001, 6, 1, 'v1', 'p1', @probe_now, @probe_now, @probe_now);
-- 3 memory_job_source_run_invalid：来源 Run 不存在。
INSERT INTO memory_update_job (id, user_id, employee_id, generation, task_type, source_session_id, source_run_id, source_message_id, source_input_sequence,
    authorization_version_at_creation, memory_schema_version, policy_version, next_attempt_at, gmt_create, gmt_modified)
VALUES (950003, 950001, 1, 2, 'FLUSH', 950001, 959999, 950001, 6, 1, 'v1', 'p1', @probe_now, @probe_now, @probe_now);
-- 4 memory_job_source_message_invalid：来源消息不存在。
INSERT INTO memory_update_job (id, user_id, employee_id, generation, task_type, source_session_id, source_run_id, source_message_id, source_input_sequence,
    authorization_version_at_creation, memory_schema_version, policy_version, next_attempt_at, gmt_create, gmt_modified)
VALUES (950004, 950001, 1, 2, 'CONSOLIDATE', 950001, 950001, 959999, 6, 1, 'v1', 'p1', @probe_now, @probe_now, @probe_now);
-- 5 memory_job_generation_conflict：任务代次大于当前代次，以及旧代次仍有待处理任务。
INSERT INTO memory_update_job (id, user_id, employee_id, generation, task_type, source_session_id, source_run_id, source_message_id, source_input_sequence,
    authorization_version_at_creation, memory_schema_version, policy_version, next_attempt_at, gmt_create, gmt_modified)
VALUES (950005, 950001, 1, 3, 'FLUSH', 950001, 950001, 950001, 6, 1, 'v1', 'p1', @probe_now, @probe_now, @probe_now),
       (950006, 950001, 1, 1, 'FLUSH', 950001, 950001, 950001, 6, 1, 'v1', 'p1', @probe_now, @probe_now, @probe_now);
-- 6 memory_job_sequence_out_of_range：来源序号不大于当前代次截止值。
INSERT INTO memory_update_job (id, user_id, employee_id, generation, task_type, source_session_id, source_run_id, source_message_id, source_input_sequence,
    authorization_version_at_creation, memory_schema_version, policy_version, next_attempt_at, gmt_create, gmt_modified)
VALUES (950007, 950001, 1, 2, 'FLUSH', 950001, 950002, 950001, 5, 1, 'v1', 'p1', @probe_now, @probe_now, @probe_now);
-- 7 numbered_message_without_profile：有编号用户消息而无控制记录；同时也是第 9 项的会话缺失。
INSERT INTO business_message (id, session_id, user_id, employee_id, message_no, input_sequence, sender_kind, submission_key, submission_sha256, accepted_at, gmt_create, gmt_modified)
VALUES (950003, 950004, 950004, 1, 1, 1, 'USER', UNHEX(REPEAT('62', 16)), @probe_digest, @probe_now, @probe_now, @probe_now);
-- 8 profile_sequence_behind_messages：控制记录的最后序号小于已存在消息的序号。
INSERT INTO business_session (id, user_id, employee_id, employee_release_id, state_session_key, last_activity_at, gmt_create, gmt_modified)
VALUES (950005, 950005, 1, 1, REPEAT('2', 32), @probe_now, @probe_now, @probe_now);
INSERT INTO memory_profile (id, user_id, employee_id, document_kv_version, document_sha256, memory_schema_version, policy_version, last_input_sequence, activated_at, gmt_create, gmt_modified)
VALUES (950005, 950005, 1, 1, @probe_digest, 'v1', 'p1', 1, @probe_now, @probe_now, @probe_now);
INSERT INTO business_message (id, session_id, user_id, employee_id, message_no, input_sequence, sender_kind, submission_key, submission_sha256, accepted_at, gmt_create, gmt_modified)
VALUES (950004, 950005, 950005, 1, 1, 3, 'USER', UNHEX(REPEAT('63', 16)), @probe_digest, @probe_now, @probe_now, @probe_now);
-- 9 message_session_mismatch：消息的员工与所属 Session 不一致。
INSERT INTO business_message (id, session_id, user_id, employee_id, message_no, input_sequence, sender_kind, submission_key, submission_sha256, accepted_at, gmt_create, gmt_modified)
VALUES (950002, 950001, 950001, 2, 2, 1, 'USER', UNHEX(REPEAT('64', 16)), @probe_digest, @probe_now, @probe_now, @probe_now);
-- 10 run_memory_snapshot_invalid：Run 启动代次大于当前代次。
INSERT INTO business_run (id, session_id, input_message_id, actor_user_id, employee_release_id, memory_generation_at_start, memory_revision_at_start, memory_policy_version,
    run_request_key, request_sha256, status, ended_at, gmt_create, gmt_modified)
VALUES (950002, 950001, 950001, 950001, 1, 9, 1, 'p1', UNHEX(REPEAT('72', 16)), @probe_digest, 'SUCCEEDED', @probe_old, @probe_now, @probe_now);
-- 11 succeeded_run_without_flush_job：成功 Run 的来源合格却没有提取任务。
INSERT INTO business_message (id, session_id, user_id, employee_id, message_no, input_sequence, sender_kind, submission_key, submission_sha256, accepted_at, gmt_create, gmt_modified)
VALUES (950006, 950001, 950001, 1, 3, 7, 'USER', UNHEX(REPEAT('65', 16)), @probe_digest, @probe_now, @probe_now, @probe_now);
INSERT INTO business_run (id, session_id, input_message_id, actor_user_id, employee_release_id, memory_generation_at_start, memory_revision_at_start, memory_policy_version,
    run_request_key, request_sha256, status, ended_at, gmt_create, gmt_modified)
VALUES (950003, 950001, 950006, 950001, 1, 2, 3, 'p1', UNHEX(REPEAT('73', 16)), @probe_digest, 'SUCCEEDED', @probe_old, @probe_now, @probe_now);
-- INSPECT_ALL_POSITIVE
