-- 每个标记后的语句独立叠加到10号完整基线，在同一连接巡检后回滚。
-- ANOMALY要求指定组命中，CLEAN要求全部12组为0；不是应用接口验收。

-- EXPECT_INSPECTION ANOMALY current_release employee_current_release_invalid
UPDATE digital_employee SET current_release_id=999999 WHERE id=980001;
-- EXPECT_INSPECTION ANOMALY draft_actor configuration_actor_orphan
UPDATE employee_draft SET saved_by=999999 WHERE id=980001;
-- EXPECT_INSPECTION ANOMALY session_release session_release_or_upgrade_invalid
UPDATE business_session SET employee_release_id=980003 WHERE id=980001;
-- EXPECT_INSPECTION ANOMALY media_owner run_message_media_invalid
UPDATE message_media SET user_id=980001 WHERE id=980001;
-- EXPECT_INSPECTION ANOMALY operation_binding binding_manifest_or_operation_invalid
UPDATE tool_operation SET tool_binding_version='tb_full_v1' WHERE id=980001;
-- EXPECT_INSPECTION ANOMALY resource_count skill_manifest_or_count_invalid
UPDATE employee_release_skill SET resource_count=2 WHERE id=980002;
-- EXPECT_INSPECTION ANOMALY resource_digest skill_resource_digest_invalid
UPDATE employee_release_skill_resource SET resource_content=CONCAT(resource_content,'篡改') WHERE id=980001;
-- EXPECT_INSPECTION ANOMALY grant_owner grant_or_authorization_audit_invalid
UPDATE employee_use_grant SET user_id=980001 WHERE id=980001;
-- EXPECT_INSPECTION ANOMALY confirmation_actor confirmation_or_identity_invalid
UPDATE operation_confirmation SET actor_user_id=980001 WHERE id=980001;
-- EXPECT_INSPECTION ANOMALY memory_employee memory_employee_or_source_invalid
UPDATE memory_update_job SET employee_id=980002 WHERE id=980001;
-- EXPECT_INSPECTION ANOMALY workspace_source workspace_ownership_invalid
UPDATE workspace_snapshot_reference SET source_session_id=980002 WHERE id=980001;
-- EXPECT_INSPECTION ANOMALY publish_receipt configuration_receipt_release_invalid
UPDATE employee_configuration_audit_event SET employee_release_id=980003 WHERE id=980001;

-- EXPECT_INSPECTION ANOMALY session_self_cycle session_release_or_upgrade_invalid
UPDATE business_session SET source_session_id=id WHERE id=980003;
-- EXPECT_INSPECTION ANOMALY session_parent_missing session_release_or_upgrade_invalid
UPDATE business_session SET source_session_id=999999 WHERE id=980003;
-- EXPECT_INSPECTION ANOMALY session_parent_other_employee session_release_or_upgrade_invalid
UPDATE business_session SET source_session_id=980002 WHERE id=980003;
-- EXPECT_INSPECTION ANOMALY session_parent_other_user session_release_or_upgrade_invalid
INSERT INTO business_session (id,user_id,employee_id,employee_release_id,source_session_id,lifecycle_status,state_session_key,last_activity_at,gmt_create,gmt_modified) VALUES (980004,980001,980001,980002,980002,'ABANDONED','directed-cross-user',@n,@n,@n);
-- EXPECT_INSPECTION ANOMALY session_two_node_cycle session_release_or_upgrade_invalid
INSERT INTO business_session (id,user_id,employee_id,employee_release_id,source_session_id,lifecycle_status,state_session_key,last_activity_at,gmt_create,gmt_modified) VALUES (980004,980002,980001,980002,980005,'ABANDONED','directed-cycle-a',@n,@n,@n),(980005,980002,980001,980002,980004,'ABANDONED','directed-cycle-b',@n,@n,@n);
-- EXPECT_INSPECTION ANOMALY upgrade_requester session_release_or_upgrade_invalid
UPDATE session_upgrade_attempt SET requested_by_user_id=980001 WHERE id=980001;
-- EXPECT_INSPECTION ANOMALY first_run_missing session_release_or_upgrade_invalid
UPDATE session_upgrade_attempt SET first_run_id=999999 WHERE id=980001;
-- EXPECT_INSPECTION ANOMALY first_run_other_session session_release_or_upgrade_invalid
UPDATE session_upgrade_attempt SET first_run_id=980001 WHERE id=980001;
-- EXPECT_INSPECTION ANOMALY activation_run_missing session_release_or_upgrade_invalid
UPDATE session_upgrade_attempt SET activation_run_id=999999 WHERE id=980001;
-- EXPECT_INSPECTION ANOMALY activation_run_other_session session_release_or_upgrade_invalid
UPDATE session_upgrade_attempt SET activation_run_id=980001 WHERE id=980001;

-- EXPECT_INSPECTION ANOMALY ready_without_confirmation confirmation_or_identity_invalid
DELETE FROM operation_confirmation WHERE id=980001;
UPDATE tool_operation SET status='READY_TO_SEND',sent_at=NULL,resolved_at=NULL WHERE id=980001;
-- EXPECT_INSPECTION ANOMALY sent_without_confirmation confirmation_or_identity_invalid
DELETE FROM operation_confirmation WHERE id=980001;
UPDATE tool_operation SET status='SENT',resolved_at=NULL WHERE id=980001;
-- EXPECT_INSPECTION ANOMALY succeeded_without_confirmation confirmation_or_identity_invalid
DELETE FROM operation_confirmation WHERE id=980001;
-- EXPECT_INSPECTION ANOMALY unknown_without_confirmation confirmation_or_identity_invalid
DELETE FROM operation_confirmation WHERE id=980001;
UPDATE tool_operation SET status='UNKNOWN',resolved_at=NULL WHERE id=980001;
-- EXPECT_INSPECTION ANOMALY confirmation_not_consumed confirmation_or_identity_invalid
UPDATE operation_confirmation SET status='PENDING',consumed_at=NULL WHERE id=980001;
-- EXPECT_INSPECTION ANOMALY platform_identity_other_user confirmation_or_identity_invalid
UPDATE tool_operation SET identity_binding_kind='PLATFORM_USER',identity_binding_ref='980001' WHERE id=980001;
-- EXPECT_INSPECTION ANOMALY confirmation_identity_version confirmation_or_identity_invalid
UPDATE operation_confirmation SET identity_binding_version=2 WHERE id=980001;

-- EXPECT_INSPECTION CLEAN historical_workspace_revision all
UPDATE workspace_entry SET content_revision=2,content_sha256=@h2 WHERE id=980001;
-- EXPECT_INSPECTION CLEAN platform_identity_actor all
UPDATE tool_operation SET identity_binding_kind='PLATFORM_USER',identity_binding_ref='980002' WHERE id=980001;
