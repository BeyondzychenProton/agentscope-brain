-- 长期记忆控制记录的只读关联巡检：无物理外键，由此持续发现逻辑引用与不变量异常。
-- 0 表示该项未发现异常。真实上线前不把空开发库当成已上线系统。
-- 不在 SQL 范围内：memory_profile.document_sha256 与框架 KV 当前文档的核对，它需要读取 KV，由应用读时校验和应用侧巡检承担。
-- 第 6 项只能校验当前代次的截止序号，旧代次的截止值换代后不再保存。
-- 第 11 项不能评估授权与员工发布资格，命中后需人工复核；刚结束的 Run 有 10 分钟登记宽限。

SELECT 'memory_profile_user_orphan' AS check_name, COUNT(*) AS anomaly_count
FROM memory_profile AS p LEFT JOIN platform_user AS u ON u.id = p.user_id
WHERE ISNULL(u.id);

SELECT 'memory_job_profile_orphan' AS check_name, COUNT(*) AS anomaly_count
FROM memory_update_job AS j LEFT JOIN memory_profile AS p ON p.user_id = j.user_id AND p.employee_id = j.employee_id
WHERE ISNULL(p.id);

SELECT 'memory_job_source_run_invalid' AS check_name, COUNT(*) AS anomaly_count
FROM memory_update_job AS j LEFT JOIN business_run AS r ON r.id = j.source_run_id
WHERE ISNULL(r.id) OR r.status <> 'SUCCEEDED' OR r.session_id <> j.source_session_id;

SELECT 'memory_job_source_message_invalid' AS check_name, COUNT(*) AS anomaly_count
FROM memory_update_job AS j LEFT JOIN business_message AS m ON m.id = j.source_message_id
WHERE ISNULL(m.id) OR m.sender_kind <> 'USER' OR NOT (m.user_id <=> j.user_id) OR NOT (m.employee_id <=> j.employee_id)
   OR NOT (m.input_sequence <=> j.source_input_sequence);

SELECT 'memory_job_generation_conflict' AS check_name, COUNT(*) AS anomaly_count
FROM memory_update_job AS j JOIN memory_profile AS p ON p.user_id = j.user_id AND p.employee_id = j.employee_id
WHERE j.generation > p.generation OR (j.generation < p.generation AND j.status IN ('PENDING', 'RUNNING'));

SELECT 'memory_job_sequence_out_of_range' AS check_name, COUNT(*) AS anomaly_count
FROM memory_update_job AS j JOIN memory_profile AS p ON p.user_id = j.user_id AND p.employee_id = j.employee_id
WHERE j.source_input_sequence > p.last_input_sequence
   OR (j.generation = p.generation AND j.source_input_sequence <= p.generation_input_cutoff);

SELECT 'numbered_message_without_profile' AS check_name, COUNT(*) AS anomaly_count
FROM (SELECT DISTINCT user_id, employee_id FROM business_message WHERE sender_kind = 'USER') AS m
LEFT JOIN memory_profile AS p ON p.user_id = m.user_id AND p.employee_id = m.employee_id
WHERE ISNULL(p.id);

SELECT 'profile_sequence_behind_messages' AS check_name, COUNT(*) AS anomaly_count
FROM (SELECT user_id, employee_id, MAX(input_sequence) AS max_sequence FROM business_message WHERE sender_kind = 'USER' GROUP BY user_id, employee_id) AS m
JOIN memory_profile AS p ON p.user_id = m.user_id AND p.employee_id = m.employee_id
WHERE p.last_input_sequence < m.max_sequence;

SELECT 'message_session_mismatch' AS check_name, COUNT(*) AS anomaly_count
FROM business_message AS m LEFT JOIN business_session AS s ON s.id = m.session_id
WHERE m.sender_kind = 'USER' AND (ISNULL(s.id) OR s.user_id <> m.user_id OR s.employee_id <> m.employee_id);

SELECT 'run_memory_snapshot_invalid' AS check_name, COUNT(*) AS anomaly_count
FROM business_run AS r
LEFT JOIN business_session AS s ON s.id = r.session_id
LEFT JOIN memory_profile AS p ON p.user_id = s.user_id AND p.employee_id = s.employee_id
WHERE ISNULL(s.id) OR ISNULL(p.id) OR r.memory_generation_at_start > p.generation
   OR (r.memory_generation_at_start = p.generation AND r.memory_revision_at_start > p.document_revision);

SELECT 'succeeded_run_without_flush_job' AS check_name, COUNT(*) AS anomaly_count
FROM business_run AS r
JOIN business_message AS m ON m.id = r.input_message_id
JOIN business_session AS s ON s.id = r.session_id
JOIN memory_profile AS p ON p.user_id = s.user_id AND p.employee_id = s.employee_id
LEFT JOIN memory_update_job AS j ON j.source_run_id = r.id AND j.generation = r.memory_generation_at_start AND j.task_type = 'FLUSH'
WHERE r.status = 'SUCCEEDED' AND r.ended_at < UTC_TIMESTAMP(6) - INTERVAL 10 MINUTE
  AND r.memory_generation_at_start = p.generation AND m.input_sequence > p.generation_input_cutoff AND ISNULL(j.id);
