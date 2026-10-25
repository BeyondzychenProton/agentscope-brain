-- 阿里巴巴规约禁止物理外键；此只读脚本核对身份引用和关键跨表不变量。
-- 0 表示该项未发现异常。真实账号初始化前，固定管理员数量单独核对，不把空开发库当成已上线系统。

SELECT 'credential_orphan' AS check_name, COUNT(*) AS anomaly_count
FROM user_credential AS c LEFT JOIN platform_user AS u ON u.id = c.user_id
WHERE ISNULL(u.id);

SELECT 'admin_invalid_user' AS check_name, COUNT(*) AS anomaly_count
FROM fixed_admin_assignment AS a LEFT JOIN platform_user AS u ON u.id = a.user_id
WHERE ISNULL(u.id) OR u.account_status <> 'ACTIVE';

SELECT 'phone_owner_orphan' AS check_name, COUNT(*) AS anomaly_count
FROM phone_reservation AS p LEFT JOIN platform_user AS u ON u.id = p.owner_user_id
WHERE ISNULL(u.id);

SELECT 'phone_checker_orphan' AS check_name, COUNT(*) AS anomaly_count
FROM phone_reservation AS p LEFT JOIN platform_user AS u ON u.id = p.ownership_checked_by
WHERE NOT ISNULL(p.ownership_checked_by) AND ISNULL(u.id);

SELECT 'user_current_phone_count' AS check_name, COUNT(*) AS anomaly_count
FROM (
    SELECT u.id
    FROM platform_user AS u LEFT JOIN phone_reservation AS p ON p.owner_user_id = u.id AND p.reservation_status = 'CURRENT'
    WHERE u.account_status = 'ACTIVE'
    GROUP BY u.id
    HAVING SUM(CASE WHEN ISNULL(p.id) THEN 0 ELSE 1 END) <> 1
) AS bad_user;

SELECT 'meeting_state_invalid' AS check_name, COUNT(*) AS anomaly_count
FROM meeting_identity_check_state AS s
LEFT JOIN platform_user AS u ON u.id = s.user_id
LEFT JOIN phone_reservation AS p ON p.id = s.checked_phone_id
WHERE ISNULL(u.id)
   OR (NOT ISNULL(s.checked_phone_id) AND ISNULL(p.id))
   OR (s.probe_status = 'PASSED' AND (
       p.owner_user_id <> s.user_id OR p.reservation_status <> 'CURRENT'
       OR s.checked_phone_revision <> u.phone_revision
   ));

SELECT 'meeting_attempt_invalid' AS check_name, COUNT(*) AS anomaly_count
FROM meeting_identity_check_attempt AS a
LEFT JOIN platform_user AS u ON u.id = a.user_id
LEFT JOIN phone_reservation AS p ON p.id = a.phone_id
WHERE ISNULL(u.id) OR ISNULL(p.id);

SELECT 'audit_target_orphan' AS check_name, COUNT(*) AS anomaly_count
FROM identity_audit_event AS e LEFT JOIN platform_user AS u ON u.id = e.target_user_id
WHERE ISNULL(u.id);

SELECT 'audit_actor_orphan' AS check_name, COUNT(*) AS anomaly_count
FROM identity_audit_event AS e LEFT JOIN platform_user AS u ON u.id = e.actor_user_id
WHERE NOT ISNULL(e.actor_user_id) AND ISNULL(u.id);
