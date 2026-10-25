-- 一期29表只读逻辑关联巡检，全部返回 check_name/anomaly_count；0表示未命中。
-- 需先执行01～05；不对历史停用、撤权和旧修订一概报错。
-- 不连接框架KV、对象存储或MCP；清单/绑定摘要只比对记录，不重算完整规范包摘要。

SELECT 'employee_current_release_invalid' AS check_name, COUNT(*) AS anomaly_count
FROM digital_employee e LEFT JOIN employee_release r ON r.id=e.current_release_id
WHERE (e.current_release_id IS NOT NULL AND (r.id IS NULL OR r.employee_id<>e.id))
 OR e.last_release_no<>COALESCE((SELECT MAX(x.release_no) FROM employee_release x WHERE x.employee_id=e.id),0);

SELECT 'configuration_actor_orphan' AS check_name, COUNT(*) AS anomaly_count
FROM (
 SELECT d.id FROM employee_draft d LEFT JOIN digital_employee e ON e.id=d.employee_id LEFT JOIN platform_user u ON u.id=d.saved_by
 WHERE e.id IS NULL OR (d.saved_by IS NOT NULL AND u.id IS NULL)
 UNION ALL
 SELECT r.id FROM employee_release r LEFT JOIN digital_employee e ON e.id=r.employee_id LEFT JOIN platform_user p ON p.id=r.published_by LEFT JOIN platform_user a ON a.id=r.availability_changed_by
 WHERE e.id IS NULL OR p.id IS NULL OR a.id IS NULL
 UNION ALL
 SELECT b.id FROM tool_binding_revision b LEFT JOIN platform_user u ON u.id=b.created_by WHERE u.id IS NULL
 UNION ALL
 SELECT a.id FROM employee_configuration_audit_event a LEFT JOIN platform_user u ON u.id=a.actor_user_id LEFT JOIN digital_employee e ON e.id=a.employee_id WHERE u.id IS NULL OR e.id IS NULL
) bad;

-- 来源链遇环即停止；超过 MySQL 递归上限时查询报错，不能当作零异常。
WITH RECURSIVE session_chain AS (
 SELECT id AS origin_id,source_session_id AS next_id,JSON_ARRAY(id) AS visited,0 AS is_cycle
 FROM business_session WHERE source_session_id IS NOT NULL
 UNION ALL
 SELECT c.origin_id,p.source_session_id,JSON_ARRAY_APPEND(c.visited,'$',p.id),JSON_CONTAINS(c.visited,CAST(p.id AS JSON),'$')
 FROM session_chain c JOIN business_session p ON p.id=c.next_id WHERE c.is_cycle=0
)
SELECT 'session_release_or_upgrade_invalid' AS check_name, COUNT(*) AS anomaly_count
FROM (
 SELECT s.id FROM business_session s LEFT JOIN platform_user u ON u.id=s.user_id LEFT JOIN digital_employee e ON e.id=s.employee_id LEFT JOIN employee_release r ON r.id=s.employee_release_id
 WHERE u.id IS NULL OR e.id IS NULL OR r.id IS NULL OR r.employee_id<>s.employee_id
 UNION ALL
 SELECT s.id FROM business_session s LEFT JOIN business_session p ON p.id=s.source_session_id
 WHERE s.source_session_id IS NOT NULL AND (p.id IS NULL OR p.user_id<>s.user_id OR p.employee_id<>s.employee_id)
 UNION ALL
 SELECT DISTINCT origin_id FROM session_chain WHERE is_cycle=1
 UNION ALL
 SELECT a.id FROM session_upgrade_attempt a LEFT JOIN business_session s ON s.id=a.source_session_id LEFT JOIN employee_release r ON r.id=a.target_release_id LEFT JOIN business_session t ON t.id=a.target_session_id
 LEFT JOIN platform_user u ON u.id=a.requested_by_user_id LEFT JOIN business_run f ON f.id=a.first_run_id LEFT JOIN business_run v ON v.id=a.activation_run_id
 WHERE s.id IS NULL OR r.id IS NULL OR u.id IS NULL OR a.requested_by_user_id<>s.user_id OR r.employee_id<>s.employee_id OR r.id=s.employee_release_id
 OR (a.target_session_id IS NOT NULL AND (t.id IS NULL OR t.user_id<>s.user_id OR t.employee_id<>s.employee_id OR t.employee_release_id<>a.target_release_id OR NOT (t.source_session_id<=>s.id)))
 OR (a.first_run_id IS NOT NULL AND (f.id IS NULL OR NOT (f.session_id<=>a.target_session_id) OR f.actor_user_id<>s.user_id OR f.employee_release_id<>a.target_release_id))
 OR (a.activation_run_id IS NOT NULL AND (v.id IS NULL OR NOT (v.session_id<=>a.target_session_id) OR v.actor_user_id<>s.user_id OR v.employee_release_id<>a.target_release_id OR v.status<>'SUCCEEDED'))
) bad;

SELECT 'run_message_media_invalid' AS check_name, COUNT(*) AS anomaly_count
FROM (
 SELECT r.id FROM business_run r LEFT JOIN business_session s ON s.id=r.session_id LEFT JOIN business_message m ON m.id=r.input_message_id
 WHERE s.id IS NULL OR m.id IS NULL OR r.employee_release_id<>s.employee_release_id OR r.actor_user_id<>s.user_id OR m.session_id<>r.session_id OR m.sender_kind<>'USER'
 UNION ALL
 SELECT m.id FROM business_message m LEFT JOIN business_session s ON s.id=m.session_id LEFT JOIN business_run r ON r.id=m.initial_run_id LEFT JOIN business_run p ON p.id=m.produced_by_run_id
 WHERE s.id IS NULL OR m.user_id<>s.user_id
 OR (m.sender_kind='USER' AND (m.employee_id<>s.employee_id OR r.id IS NULL OR r.input_message_id<>m.id))
 OR (m.sender_kind='ASSISTANT' AND (p.id IS NULL OR p.status<>'SUCCEEDED' OR p.session_id<>m.session_id))
 UNION ALL
 SELECT x.id FROM message_media x LEFT JOIN business_message m ON m.id=x.message_id WHERE m.id IS NULL OR x.user_id<>m.user_id
 UNION ALL
 SELECT b.id FROM message_content_block b LEFT JOIN business_message m ON m.id=b.message_id LEFT JOIN message_media x ON x.id=b.media_id
 WHERE m.id IS NULL OR (b.block_kind='MEDIA' AND (x.id IS NULL OR x.message_id<>b.message_id))
) bad;

SELECT 'binding_manifest_or_operation_invalid' AS check_name, COUNT(*) AS anomaly_count
FROM (
 SELECT r.id FROM employee_release r JOIN JSON_TABLE(r.config_json,'$.tools[*]' COLUMNS (binding_version VARCHAR(64) PATH '$.toolBindingVersion',provider_key VARCHAR(64) PATH '$.providerKey',tool_name VARCHAR(128) PATH '$.toolName',digest VARCHAR(64) PATH '$.bindingSha256')) j
 LEFT JOIN tool_binding_revision b ON BINARY b.tool_binding_version=BINARY j.binding_version
 WHERE b.id IS NULL OR NOT (UNHEX(j.digest)<=>b.binding_sha256) OR NOT (BINARY j.provider_key<=>BINARY b.provider_key) OR NOT (BINARY j.tool_name<=>BINARY b.tool_name)
 UNION ALL
 SELECT o.id FROM tool_operation o LEFT JOIN business_run n ON n.id=o.run_id LEFT JOIN employee_release r ON r.id=n.employee_release_id
 LEFT JOIN tool_binding_revision b ON b.tool_binding_version=o.tool_binding_version
 WHERE n.id IS NULL OR r.id IS NULL OR b.id IS NULL OR b.provider_key<>o.tool_provider_key OR b.tool_name<>o.tool_name
 OR NOT EXISTS (SELECT 1 FROM JSON_TABLE(r.config_json,'$.tools[*]' COLUMNS (binding_version VARCHAR(64) PATH '$.toolBindingVersion')) j WHERE BINARY j.binding_version=BINARY o.tool_binding_version)
) bad;

SELECT 'skill_manifest_or_count_invalid' AS check_name, COUNT(*) AS anomaly_count
FROM (
 SELECT r.id FROM employee_release r JOIN JSON_TABLE(r.config_json,'$.skills[*]' COLUMNS (snapshot_id BIGINT PATH '$.snapshotId',skill_name VARCHAR(64) PATH '$.name',digest VARCHAR(64) PATH '$.packageSha256')) j
 LEFT JOIN employee_release_skill s ON s.id=j.snapshot_id
 WHERE s.id IS NULL OR s.employee_release_id<>r.id OR NOT (BINARY s.skill_name<=>BINARY j.skill_name) OR NOT (s.package_sha256<=>UNHEX(j.digest))
 UNION ALL
 SELECT s.id FROM employee_release_skill s LEFT JOIN employee_release r ON r.id=s.employee_release_id
 WHERE r.id IS NULL OR s.resource_count<>(SELECT COUNT(*) FROM employee_release_skill_resource x WHERE x.release_skill_id=s.id)
 OR NOT EXISTS (SELECT 1 FROM JSON_TABLE(r.config_json,'$.skills[*]' COLUMNS (snapshot_id BIGINT PATH '$.snapshotId')) j WHERE j.snapshot_id=s.id)
) bad;

SELECT 'skill_resource_digest_invalid' AS check_name, COUNT(*) AS anomaly_count
FROM employee_release_skill_resource x LEFT JOIN employee_release_skill s ON s.id=x.release_skill_id
WHERE s.id IS NULL OR x.content_sha256<>UNHEX(SHA2(CAST(x.resource_content AS BINARY),256));

SELECT 'grant_or_authorization_audit_invalid' AS check_name, COUNT(*) AS anomaly_count
FROM (
 SELECT g.id FROM employee_use_grant g LEFT JOIN platform_user u ON u.id=g.user_id LEFT JOIN digital_employee e ON e.id=g.employee_id LEFT JOIN platform_user a ON a.id=g.granted_by LEFT JOIN platform_user b ON b.id=g.revoked_by
 WHERE u.id IS NULL OR e.id IS NULL OR a.id IS NULL OR (g.revoked_by IS NOT NULL AND b.id IS NULL)
 UNION ALL
 SELECT a.id FROM identity_audit_event a LEFT JOIN digital_employee e ON e.id=a.employee_id LEFT JOIN employee_use_grant g ON g.id=a.grant_id
 WHERE (a.employee_id IS NOT NULL AND e.id IS NULL) OR (a.grant_id IS NOT NULL AND (g.id IS NULL OR g.user_id<>a.target_user_id OR g.employee_id<>a.employee_id))
) bad;

SELECT 'confirmation_or_identity_invalid' AS check_name, COUNT(*) AS anomaly_count
FROM (
 SELECT c.id FROM operation_confirmation c LEFT JOIN tool_operation o ON o.id=c.operation_id
 WHERE o.id IS NULL OR c.run_id<>o.run_id OR c.actor_user_id<>o.actor_user_id OR c.arguments_sha256<>o.arguments_sha256 OR c.operation_sha256<>o.operation_sha256 OR c.identity_binding_version<>o.identity_binding_version
 OR (c.status='CONSUMED' AND c.consumed_at>c.expires_at)
 UNION ALL
 SELECT o.id FROM tool_operation o LEFT JOIN business_run r ON r.id=o.run_id
 WHERE r.id IS NULL OR r.actor_user_id<>o.actor_user_id
 OR (o.identity_binding_kind='MEETING_PHONE' AND NOT EXISTS (SELECT 1 FROM phone_reservation p WHERE CAST(p.id AS CHAR)=o.identity_binding_ref))
 OR (o.identity_binding_kind='PLATFORM_USER' AND NOT EXISTS (SELECT 1 FROM platform_user u WHERE u.id=o.actor_user_id AND CAST(u.id AS CHAR)=o.identity_binding_ref))
 OR (o.is_confirmation_required=1 AND o.status IN ('READY_TO_SEND','SENT','SUCCEEDED','UNKNOWN') AND NOT EXISTS (
   SELECT 1 FROM operation_confirmation c WHERE c.operation_id=o.id AND c.status='CONSUMED'
   AND c.run_id=o.run_id AND c.actor_user_id=o.actor_user_id AND c.arguments_sha256=o.arguments_sha256
   AND c.operation_sha256=o.operation_sha256 AND c.identity_binding_version=o.identity_binding_version AND c.consumed_at<=c.expires_at))
) bad;

SELECT 'memory_employee_or_source_invalid' AS check_name, COUNT(*) AS anomaly_count
FROM (
 SELECT p.id FROM memory_profile p LEFT JOIN platform_user u ON u.id=p.user_id LEFT JOIN digital_employee e ON e.id=p.employee_id WHERE u.id IS NULL OR e.id IS NULL
 UNION ALL
 SELECT j.id FROM memory_update_job j LEFT JOIN memory_profile p ON p.user_id=j.user_id AND p.employee_id=j.employee_id
 LEFT JOIN business_run r ON r.id=j.source_run_id LEFT JOIN business_session s ON s.id=j.source_session_id LEFT JOIN business_message m ON m.id=j.source_message_id
 WHERE p.id IS NULL OR r.id IS NULL OR s.id IS NULL OR m.id IS NULL OR r.status<>'SUCCEEDED' OR r.session_id<>s.id OR r.input_message_id<>m.id
 OR s.user_id<>j.user_id OR s.employee_id<>j.employee_id OR m.sender_kind<>'USER' OR m.user_id<>j.user_id OR NOT (m.employee_id<=>j.employee_id) OR NOT (m.input_sequence<=>j.source_input_sequence)
) bad;

SELECT 'workspace_ownership_invalid' AS check_name, COUNT(*) AS anomaly_count
FROM (
 SELECT e.id FROM workspace_entry e LEFT JOIN business_session s ON s.id=e.session_id LEFT JOIN business_run r ON r.id=e.source_run_id LEFT JOIN employee_release v ON v.id=e.employee_release_id
 WHERE s.id IS NULL OR r.id IS NULL OR v.id IS NULL OR e.user_id<>s.user_id OR e.employee_id<>s.employee_id OR r.session_id<>s.id OR r.employee_release_id<>e.employee_release_id OR v.employee_id<>e.employee_id
 UNION ALL
 SELECT x.id FROM workspace_snapshot_reference x LEFT JOIN workspace_entry e ON e.id=x.source_entry_id LEFT JOIN business_session s ON s.id=x.source_session_id LEFT JOIN business_session t ON t.id=x.target_session_id LEFT JOIN business_run r ON r.id=x.source_run_id LEFT JOIN platform_user u ON u.id=x.created_by_user_id
 WHERE e.id IS NULL OR s.id IS NULL OR t.id IS NULL OR r.id IS NULL OR u.id IS NULL OR e.session_id<>x.source_session_id OR x.source_session_id=x.target_session_id
 OR x.user_id<>s.user_id OR x.user_id<>t.user_id OR x.employee_id<>s.employee_id OR x.employee_id<>t.employee_id OR r.session_id<>s.id
 OR x.source_content_revision>e.content_revision
 OR (x.source_content_revision=e.content_revision AND (x.source_run_id<>e.source_run_id OR x.source_content_sha256<>e.content_sha256))
) bad;

SELECT 'configuration_receipt_release_invalid' AS check_name, COUNT(*) AS anomaly_count
FROM employee_configuration_audit_event a LEFT JOIN employee_release r ON r.id=a.employee_release_id
WHERE (a.employee_release_id IS NOT NULL AND (r.id IS NULL OR r.employee_id<>a.employee_id))
 OR (a.action_code='EMPLOYEE_PUBLISH' AND a.outcome='SUCCESS' AND (a.employee_release_id IS NULL OR a.draft_revision IS NULL))
 OR (a.action_code='EMPLOYEE_PUBLISH' AND a.outcome='SUCCESS' AND a.result_code='CREATED' AND NOT (a.draft_revision<=>r.source_draft_revision));
