-- #29：来源引用巡检。只读；不修复、不重放、不把合法撤权或停用认作损坏。
-- 完整 manifest／正文摘要由 controlled_bootstrap.verify 在只读一致性事务中重算。
SELECT 'source_1' AS check_name, COUNT(*) AS anomaly_count FROM platform_user f
LEFT JOIN platform_user actor ON actor.id=f.created_by_user_id
LEFT JOIN bootstrap_batch b ON b.id=f.creation_bootstrap_batch_id
WHERE (f.creation_source_kind='USER_ACTION' AND (actor.id IS NULL OR f.creation_bootstrap_batch_id IS NOT NULL))
   OR (f.creation_source_kind='SYSTEM_BOOTSTRAP' AND (f.created_by_user_id IS NOT NULL OR b.id IS NULL OR b.status<>'APPLIED'));
SELECT 'source_2' AS check_name, COUNT(*) AS anomaly_count FROM platform_user f
LEFT JOIN platform_user actor ON actor.id=f.modified_by_user_id
LEFT JOIN bootstrap_batch b ON b.id=f.modification_bootstrap_batch_id
WHERE (f.modification_source_kind='USER_ACTION' AND (actor.id IS NULL OR f.modification_bootstrap_batch_id IS NOT NULL))
   OR (f.modification_source_kind='SYSTEM_BOOTSTRAP' AND (f.modified_by_user_id IS NOT NULL OR b.id IS NULL OR b.status<>'APPLIED'));
SELECT 'source_3' AS check_name, COUNT(*) AS anomaly_count FROM phone_reservation f
LEFT JOIN platform_user actor ON actor.id=f.ownership_checked_by
LEFT JOIN bootstrap_batch b ON b.id=f.ownership_bootstrap_batch_id
WHERE (f.ownership_source_kind='USER_ACTION' AND (actor.id IS NULL OR f.ownership_bootstrap_batch_id IS NOT NULL))
   OR (f.ownership_source_kind='SYSTEM_BOOTSTRAP' AND (f.ownership_checked_by IS NOT NULL OR b.id IS NULL OR b.status<>'APPLIED'));
SELECT 'source_4' AS check_name, COUNT(*) AS anomaly_count FROM employee_draft f
LEFT JOIN platform_user actor ON actor.id=f.saved_by
LEFT JOIN bootstrap_batch b ON b.id=f.save_bootstrap_batch_id
WHERE (f.save_source_kind='USER_ACTION' AND (actor.id IS NULL OR f.save_bootstrap_batch_id IS NOT NULL))
   OR (f.save_source_kind='SYSTEM_BOOTSTRAP' AND (f.saved_by IS NOT NULL OR b.id IS NULL OR b.status<>'APPLIED'));
SELECT 'source_5' AS check_name, COUNT(*) AS anomaly_count FROM employee_release f
LEFT JOIN platform_user actor ON actor.id=f.published_by
LEFT JOIN bootstrap_batch b ON b.id=f.bootstrap_batch_id
WHERE (f.creation_source_kind='USER_ACTION' AND (actor.id IS NULL OR f.bootstrap_batch_id IS NOT NULL))
   OR (f.creation_source_kind='SYSTEM_BOOTSTRAP' AND (f.published_by IS NOT NULL OR b.id IS NULL OR b.status<>'APPLIED'));
SELECT 'source_6' AS check_name, COUNT(*) AS anomaly_count FROM employee_release f
LEFT JOIN platform_user actor ON actor.id=f.availability_changed_by
LEFT JOIN bootstrap_batch b ON b.id=f.availability_bootstrap_batch_id
WHERE (f.availability_source_kind='USER_ACTION' AND (actor.id IS NULL OR f.availability_bootstrap_batch_id IS NOT NULL))
   OR (f.availability_source_kind='SYSTEM_BOOTSTRAP' AND (f.availability_changed_by IS NOT NULL OR b.id IS NULL OR b.status<>'APPLIED'));
SELECT 'source_7' AS check_name, COUNT(*) AS anomaly_count FROM tool_binding_revision f
LEFT JOIN platform_user actor ON actor.id=f.created_by
LEFT JOIN bootstrap_batch b ON b.id=f.bootstrap_batch_id
WHERE (f.creation_source_kind='USER_ACTION' AND (actor.id IS NULL OR f.bootstrap_batch_id IS NOT NULL))
   OR (f.creation_source_kind='SYSTEM_BOOTSTRAP' AND (f.created_by IS NOT NULL OR b.id IS NULL OR b.status<>'APPLIED'));
SELECT 'source_8' AS check_name, COUNT(*) AS anomaly_count FROM employee_use_grant f
LEFT JOIN platform_user actor ON actor.id=f.granted_by
LEFT JOIN bootstrap_batch b ON b.id=f.grant_bootstrap_batch_id
WHERE (f.grant_source_kind='USER_ACTION' AND (actor.id IS NULL OR f.grant_bootstrap_batch_id IS NOT NULL))
   OR (f.grant_source_kind='SYSTEM_BOOTSTRAP' AND (f.granted_by IS NOT NULL OR b.id IS NULL OR b.status<>'APPLIED'));
SELECT 'source_9' AS check_name, COUNT(*) AS anomaly_count FROM identity_audit_event f
LEFT JOIN platform_user actor ON actor.id=f.actor_user_id
LEFT JOIN bootstrap_batch b ON b.id=f.bootstrap_batch_id
WHERE (f.source_kind='USER_ACTION' AND (actor.id IS NULL OR f.bootstrap_batch_id IS NOT NULL))
   OR (f.source_kind='SYSTEM_BOOTSTRAP' AND (f.actor_user_id IS NOT NULL OR b.id IS NULL OR b.status<>'APPLIED'));
SELECT 'source_10' AS check_name, COUNT(*) AS anomaly_count FROM employee_configuration_audit_event f
LEFT JOIN platform_user actor ON actor.id=f.actor_user_id
LEFT JOIN bootstrap_batch b ON b.id=f.bootstrap_batch_id
WHERE (f.source_kind='USER_ACTION' AND (actor.id IS NULL OR f.bootstrap_batch_id IS NOT NULL))
   OR (f.source_kind='SYSTEM_BOOTSTRAP' AND (f.actor_user_id IS NOT NULL OR b.id IS NULL OR b.status<>'APPLIED'));
SELECT 'receipt_scope' AS check_name, COUNT(*) AS anomaly_count FROM employee_configuration_audit_event
WHERE receipt_scope_kind<>source_kind OR receipt_scope_id<>CASE WHEN source_kind='USER_ACTION' THEN actor_user_id ELSE bootstrap_batch_id END;
SELECT 'batch_result' AS check_name, COUNT(*) AS anomaly_count FROM bootstrap_batch b
LEFT JOIN platform_user u ON u.id=JSON_UNQUOTE(JSON_EXTRACT(b.result_summary,'$.userId'))
LEFT JOIN employee_release r ON r.id=JSON_UNQUOTE(JSON_EXTRACT(b.result_summary,'$.releaseId'))
LEFT JOIN employee_configuration_audit_event a ON a.id=JSON_UNQUOTE(JSON_EXTRACT(b.result_summary,'$.configurationAuditId'))
WHERE b.status='APPLIED' AND (u.id IS NULL OR r.id IS NULL OR a.id IS NULL OR a.bootstrap_batch_id<>b.id OR r.bootstrap_batch_id<>b.id);

