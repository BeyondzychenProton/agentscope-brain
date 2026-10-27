-- 本机开启普通文字接纳／执行的前置控制事实。
-- 不创建用户、不改密码、不改授权。在 agentscope_brain 上由维护者显式执行。
-- document_sha256 使用空文档 SHA-256：e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855

SET @n = UTC_TIMESTAMP(6);
SET @empty_sha = UNHEX('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');

INSERT INTO runtime_capacity (
    singleton_key, foreground_limit, normal_waiting_limit, occupied_count, normal_waiting_count,
    boot_epoch, row_version, gmt_create, gmt_modified
) VALUES (1, 2, 16, 0, 0, 1, 1, @n, @n)
ON DUPLICATE KEY UPDATE
    foreground_limit = 2,
    normal_waiting_limit = 16,
    gmt_modified = @n;

INSERT INTO memory_profile (
    user_id, employee_id, profile_status, generation, document_revision, document_kv_version,
    document_sha256, memory_schema_version, policy_version, generation_input_cutoff, last_input_sequence,
    activated_at, gmt_create, gmt_modified
)
SELECT u.id, e.id, 'READY', 1, 1, 1, @empty_sha, 'v1', 'ordinary-text-v1', 0, 0, @n, @n, @n
FROM platform_user u
JOIN digital_employee e
LEFT JOIN memory_profile p ON p.user_id = u.id AND p.employee_id = e.id
WHERE u.account_status = 'ACTIVE' AND p.id IS NULL;

SELECT 'runtime_capacity' AS kind, singleton_key, foreground_limit, normal_waiting_limit, occupied_count, normal_waiting_count, boot_epoch
FROM runtime_capacity WHERE singleton_key = 1;
SELECT 'memory_profile' AS kind, user_id, employee_id, profile_status, generation, document_revision, last_input_sequence
FROM memory_profile ORDER BY user_id, employee_id;
