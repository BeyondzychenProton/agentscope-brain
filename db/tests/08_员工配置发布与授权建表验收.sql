-- 仅在执行器新建的隔离空库运行；全部是合成数据，不接外部服务。
-- 断言单表约束与合法兼容形态，不证明应用发布协议或真实鉴权已实现。
SET @n=UTC_TIMESTAMP(6);
SET @h=UNHEX(REPEAT('ab',32));
SET @h2=UNHEX(REPEAT('cd',32));
START TRANSACTION;
INSERT INTO platform_user (id,display_name,status_changed_at,gmt_create,gmt_modified) VALUES (970001,'隔离合成用户970001',@n,@n,@n);
INSERT INTO platform_user (id,display_name,status_changed_at,gmt_create,gmt_modified) VALUES (970002,'隔离合成用户970002',@n,@n,@n);
INSERT INTO platform_user (id,display_name,status_changed_at,gmt_create,gmt_modified) VALUES (970003,'隔离合成用户970003',@n,@n,@n);
INSERT INTO digital_employee (id,employee_code,display_name,gmt_create,gmt_modified) VALUES (970001,'meeting-probe','会议员工',@n,@n);
INSERT INTO digital_employee (id,employee_code,display_name,gmt_create,gmt_modified) VALUES (970002,'other-probe','另一个合成员工',@n,@n);
INSERT INTO employee_draft (id,employee_id,config_json,gmt_create,gmt_modified) VALUES (970001,970001,JSON_OBJECT(),@n,@n);
INSERT INTO employee_release (id,employee_id,release_no,source_draft_revision,config_schema_version,config_json,dependency_sha256,config_sha256,manifest_sha256,published_by,published_at,availability_changed_by,availability_changed_at,gmt_create,gmt_modified) VALUES (970001,970001,1,1,1,JSON_OBJECT(),@h,@h,@h,970001,@n,970001,@n,@n,@n);
INSERT INTO employee_release (id,employee_id,release_no,source_draft_revision,config_schema_version,config_json,dependency_sha256,config_sha256,manifest_sha256,published_by,published_at,availability_changed_by,availability_changed_at,gmt_create,gmt_modified) VALUES (970002,970001,2,2,1,JSON_OBJECT(),@h,@h2,@h,970001,@n,970001,@n,@n,@n);
INSERT INTO employee_release (id,employee_id,release_no,source_draft_revision,config_schema_version,config_json,dependency_sha256,config_sha256,manifest_sha256,published_by,published_at,availability_changed_by,availability_changed_at,gmt_create,gmt_modified) VALUES (970003,970001,3,3,1,JSON_OBJECT(),@h,@h,@h,970001,@n,970001,@n,@n,@n);
INSERT INTO employee_release (id,employee_id,release_no,source_draft_revision,config_schema_version,config_json,dependency_sha256,config_sha256,manifest_sha256,published_by,published_at,availability_changed_by,availability_changed_at,gmt_create,gmt_modified) VALUES (970004,970002,1,1,1,JSON_OBJECT(),@h,@h,@h,970001,@n,970001,@n,@n,@n);
UPDATE digital_employee SET current_release_id=970003,last_release_no=3 WHERE id=970001;
INSERT INTO tool_binding_revision (id,tool_binding_version,provider_key,tool_name,binding_schema_version,binding_json,binding_sha256,created_by,gmt_create,gmt_modified) VALUES (970001,'tb_probe_v1','meeting','room.reserve',1,JSON_OBJECT(),@h,970001,@n,@n);
INSERT INTO tool_binding_revision (id,tool_binding_version,provider_key,tool_name,binding_schema_version,binding_json,binding_sha256,created_by,gmt_create,gmt_modified) VALUES (970002,'tb_probe_v2','meeting','room.reserve',1,JSON_OBJECT(),@h,970001,@n,@n);
INSERT INTO employee_use_grant (id,user_id,employee_id,grant_status,granted_by,granted_at,gmt_create,gmt_modified) VALUES (970001,970002,970001,'ACTIVE',970001,@n,@n,@n);
INSERT INTO employee_use_grant (id,user_id,employee_id,grant_status,granted_by,granted_at,revoked_by,revoked_at,gmt_create,gmt_modified) VALUES (970002,970002,970002,'REVOKED',970001,@n,970001,@n,@n,@n);
INSERT INTO employee_configuration_audit_event (id,actor_user_id,employee_id,action_code,outcome,result_code,reason_code,request_id,request_sha256,occurred_at,gmt_create,gmt_modified) VALUES (970001,970001,970001,'DRAFT_SAVE','FAILURE',NULL,'TRANSIENT','retry-key',@h,@n,@n,@n);
INSERT INTO employee_configuration_audit_event (id,actor_user_id,employee_id,action_code,outcome,result_code,reason_code,request_id,request_sha256,occurred_at,gmt_create,gmt_modified) VALUES (970002,970001,970001,'DRAFT_SAVE','FAILURE',NULL,'TRANSIENT','retry-key',@h,@n,@n,@n);
INSERT INTO employee_configuration_audit_event (id,actor_user_id,employee_id,action_code,outcome,result_code,reason_code,request_id,request_sha256,occurred_at,gmt_create,gmt_modified) VALUES (970003,970001,970001,'DRAFT_SAVE','SUCCESS','SAVED',NULL,'retry-key',@h,@n,@n,@n);
INSERT INTO employee_configuration_audit_event (id,actor_user_id,employee_id,action_code,outcome,result_code,reason_code,request_id,request_sha256,occurred_at,gmt_create,gmt_modified) VALUES (970004,970003,970001,'DRAFT_SAVE','SUCCESS','SAVED',NULL,'retry-key',@h,@n,@n,@n);
INSERT INTO employee_configuration_audit_event (id,actor_user_id,employee_id,action_code,outcome,result_code,reason_code,request_id,request_sha256,occurred_at,draft_revision,employee_release_id,gmt_create,gmt_modified) VALUES (970005,970001,970001,'EMPLOYEE_PUBLISH','SUCCESS','NO_CHANGE',NULL,'no-change-key',@h,@n,4,970003,@n,@n);
INSERT INTO employee_release_skill (id,employee_release_id,skill_name,description,metadata_json,skill_markdown,package_sha256,resource_count,gmt_create,gmt_modified) VALUES (970001,970001,'meeting-room','会议室预约',JSON_OBJECT('name','meeting-room','description','会议室预约'),'---
name: meeting-room
description: 会议室预约
---
# 正文',@h,0,@n,@n);
INSERT INTO employee_release_skill (id,employee_release_id,skill_name,description,metadata_json,skill_markdown,package_sha256,resource_count,gmt_create,gmt_modified) VALUES (970002,970002,'meeting-room','会议室预约',JSON_OBJECT('name','meeting-room','description','会议室预约'),'---
name: meeting-room
description: 会议室预约
---
# 正文',@h,0,@n,@n);
INSERT INTO employee_release_skill_resource (id,release_skill_id,resource_path,resource_content,content_sha256,gmt_create,gmt_modified) VALUES (970001,970001,'Plan.md','计划',UNHEX(SHA2('计划',256)),@n,@n);
INSERT INTO employee_release_skill_resource (id,release_skill_id,resource_path,resource_content,content_sha256,gmt_create,gmt_modified) VALUES (970002,970001,'references/会议规则.md','中文
保留换行',UNHEX(SHA2('中文
保留换行',256)),@n,@n);
INSERT INTO employee_release_skill_resource (id,release_skill_id,resource_path,resource_content,content_sha256,gmt_create,gmt_modified) VALUES (970003,970001,'empty.md','',UNHEX(SHA2('',256)),@n,@n);
INSERT INTO employee_release_skill_resource (id,release_skill_id,resource_path,resource_content,content_sha256,gmt_create,gmt_modified) VALUES (970004,970001,'references/café.md','重音',UNHEX(SHA2('重音',256)),@n,@n);
INSERT INTO employee_release_skill_resource (id,release_skill_id,resource_path,resource_content,content_sha256,gmt_create,gmt_modified) VALUES (970005,970002,'Plan.md','另一版本',UNHEX(SHA2('另一版本',256)),@n,@n);
INSERT INTO employee_release_skill_resource (id,release_skill_id,resource_path,resource_content,content_sha256,gmt_create,gmt_modified) VALUES (970006,970001,'ppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppa','a',UNHEX(SHA2('a',256)),@n,@n);
INSERT INTO employee_release_skill_resource (id,release_skill_id,resource_path,resource_content,content_sha256,gmt_create,gmt_modified) VALUES (970007,970001,'ppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppb','b',UNHEX(SHA2('b',256)),@n,@n);
INSERT INTO identity_audit_event (id,actor_user_id,target_user_id,action_code,outcome,employee_id,grant_id,authorization_version_before,authorization_version_after,occurred_at,gmt_create,gmt_modified) VALUES (970001,970001,970002,'EMPLOYEE_GRANT','SUCCESS',970001,970001,1,2,@n,@n,@n);
INSERT INTO identity_audit_event (id,actor_user_id,target_user_id,action_code,outcome,occurred_at,gmt_create,gmt_modified) VALUES (970002,970001,970002,'USER_CREATED','SUCCESS',@n,@n,@n);
INSERT INTO identity_audit_event (id,actor_user_id,target_user_id,action_code,outcome,employee_id,grant_id,authorization_version_before,authorization_version_after,occurred_at,gmt_create,gmt_modified) VALUES (970003,970001,970002,'EMPLOYEE_GRANT','SUCCESS',970001,970001,2,2,@n,@n,@n);
-- EXPECT_PASS
SELECT IF((SELECT COUNT(*) FROM employee_release WHERE employee_id=970001 AND config_sha256=@h)=2 AND (SELECT COUNT(*) FROM employee_release WHERE release_no=1 AND source_draft_revision=1)=2,'PASS','FAIL');
-- EXPECT_PASS
SELECT IF((SELECT COUNT(*) FROM employee_draft WHERE config_json=JSON_OBJECT() AND dependency_sha256 IS NULL AND saved_by IS NULL)=1 AND (SELECT COUNT(*) FROM tool_binding_revision WHERE binding_sha256=@h)=2,'PASS','FAIL');
-- EXPECT_PASS
SELECT IF((SELECT COUNT(*) FROM employee_configuration_audit_event WHERE request_id='retry-key' AND outcome='FAILURE' AND success_request_id IS NULL)=2 AND (SELECT COUNT(*) FROM employee_configuration_audit_event WHERE request_id='retry-key' AND outcome='SUCCESS')=2,'PASS','FAIL');
-- EXPECT_PASS
SELECT IF((SELECT draft_revision FROM employee_configuration_audit_event WHERE id=970005)<>(SELECT source_draft_revision FROM employee_release WHERE id=970003),'PASS','FAIL');
-- EXPECT_PASS
SELECT IF((SELECT COUNT(*) FROM employee_release_skill_resource WHERE resource_path='references/会议规则.md' AND resource_content='中文\n保留换行')=1 AND (SELECT COUNT(*) FROM employee_release_skill_resource WHERE resource_content='')=1 AND (SELECT COUNT(*) FROM employee_release_skill_resource WHERE CHAR_LENGTH(resource_path)=255)=2,'PASS','FAIL');
-- EXPECT_PASS
SELECT IF((SELECT COUNT(*) FROM identity_audit_event WHERE employee_id IS NULL AND grant_id IS NULL AND authorization_version_before IS NULL AND authorization_version_after IS NULL)=1 AND (SELECT COUNT(*) FROM identity_audit_event WHERE authorization_version_before=authorization_version_after)=1,'PASS','FAIL');
-- EXPECT_ERROR 1062 uk_employee_code
INSERT INTO digital_employee (employee_code,display_name,gmt_create,gmt_modified) VALUES ('meeting-probe','重复',@n,@n);
-- EXPECT_ERROR 3819 chk_digital_employee_text
UPDATE digital_employee SET employee_code=' ' WHERE id=970001;
-- EXPECT_ERROR 3819 chk_digital_employee_text
UPDATE digital_employee SET display_name=' ' WHERE id=970001;
-- EXPECT_ERROR 3819 chk_digital_employee_revision
UPDATE digital_employee SET row_version=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_digital_employee_revision
UPDATE digital_employee SET current_release_id=0 WHERE id=970001;
-- EXPECT_ERROR 1062 uk_employee_id
INSERT INTO employee_draft (employee_id,config_json,gmt_create,gmt_modified) VALUES (970001,JSON_OBJECT(),@n,@n);
-- EXPECT_ERROR 3819 chk_employee_draft_positive
UPDATE employee_draft SET employee_id=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_draft_positive
UPDATE employee_draft SET draft_revision=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_draft_positive
UPDATE employee_draft SET config_schema_version=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_draft_positive
UPDATE employee_draft SET saved_by=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_draft_json
UPDATE employee_draft SET config_json=JSON_ARRAY() WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_draft_json
UPDATE employee_draft SET config_json=CAST('null' AS JSON) WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_draft_json
UPDATE employee_draft SET config_json=CAST('7' AS JSON) WHERE id=970001;
-- EXPECT_ERROR 1048 config_json
UPDATE employee_draft SET config_json=NULL WHERE id=970001;
-- EXPECT_ERROR 1062 uk_employee_release_no
INSERT INTO employee_release (employee_id,release_no,source_draft_revision,config_schema_version,config_json,dependency_sha256,config_sha256,manifest_sha256,published_by,published_at,availability_changed_by,availability_changed_at,gmt_create,gmt_modified) VALUES (970001,1,50,1,JSON_OBJECT(),@h,@h,@h,970001,@n,970001,@n,@n,@n);
-- EXPECT_ERROR 1062 uk_employee_source_draft_revision
INSERT INTO employee_release (employee_id,release_no,source_draft_revision,config_schema_version,config_json,dependency_sha256,config_sha256,manifest_sha256,published_by,published_at,availability_changed_by,availability_changed_at,gmt_create,gmt_modified) VALUES (970001,50,1,1,JSON_OBJECT(),@h,@h,@h,970001,@n,970001,@n,@n,@n);
-- EXPECT_ERROR 3819 chk_employee_release_positive
UPDATE employee_release SET employee_id=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_release_positive
UPDATE employee_release SET release_no=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_release_positive
UPDATE employee_release SET source_draft_revision=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_release_positive
UPDATE employee_release SET config_schema_version=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_release_positive
UPDATE employee_release SET published_by=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_release_positive
UPDATE employee_release SET availability_revision=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_release_positive
UPDATE employee_release SET availability_changed_by=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_release_json
UPDATE employee_release SET config_json=JSON_ARRAY() WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_release_json
UPDATE employee_release SET config_json=CAST('null' AS JSON) WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_release_availability
UPDATE employee_release SET availability_status='BROKEN' WHERE id=970001;
-- EXPECT_ERROR 1062 uk_tool_binding_version
INSERT INTO tool_binding_revision (tool_binding_version,provider_key,tool_name,binding_schema_version,binding_json,binding_sha256,created_by,gmt_create,gmt_modified) VALUES ('tb_probe_v1','meeting','room.reserve',1,JSON_OBJECT(),@h,970001,@n,@n);
-- EXPECT_ERROR 3819 chk_tool_binding_text
UPDATE tool_binding_revision SET tool_binding_version=' ' WHERE id=970001;
-- EXPECT_ERROR 3819 chk_tool_binding_text
UPDATE tool_binding_revision SET provider_key=' ' WHERE id=970001;
-- EXPECT_ERROR 3819 chk_tool_binding_text
UPDATE tool_binding_revision SET tool_name=' ' WHERE id=970001;
-- EXPECT_ERROR 3819 chk_tool_binding_positive
UPDATE tool_binding_revision SET binding_schema_version=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_tool_binding_positive
UPDATE tool_binding_revision SET created_by=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_tool_binding_json
UPDATE tool_binding_revision SET binding_json=JSON_ARRAY() WHERE id=970001;
-- EXPECT_ERROR 3819 chk_tool_binding_json
UPDATE tool_binding_revision SET binding_json=CAST('null' AS JSON) WHERE id=970001;
-- EXPECT_ERROR 1062 uk_user_employee
INSERT INTO employee_use_grant (user_id,employee_id,grant_status,granted_by,granted_at,gmt_create,gmt_modified) VALUES (970002,970001,'ACTIVE',970001,@n,@n,@n);
-- EXPECT_ERROR 3819 chk_employee_grant_positive
UPDATE employee_use_grant SET user_id=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_grant_positive
UPDATE employee_use_grant SET employee_id=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_grant_positive
UPDATE employee_use_grant SET granted_by=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_grant_positive
UPDATE employee_use_grant SET revoked_by=0 WHERE id=970002;
-- EXPECT_ERROR 3819 chk_employee_grant_status|chk_employee_grant_revocation
UPDATE employee_use_grant SET grant_status='BROKEN' WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_grant_revocation
UPDATE employee_use_grant SET revoked_by=970001 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_grant_revocation
UPDATE employee_use_grant SET revoked_at=@n WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_grant_revocation
UPDATE employee_use_grant SET revoked_by=NULL WHERE id=970002;
-- EXPECT_ERROR 3819 chk_employee_grant_revocation
UPDATE employee_use_grant SET revoked_at=NULL WHERE id=970002;
-- EXPECT_ERROR 1062 uk_actor_success_request
INSERT INTO employee_configuration_audit_event (actor_user_id,employee_id,action_code,outcome,result_code,reason_code,request_id,request_sha256,occurred_at,gmt_create,gmt_modified) VALUES (970001,970001,'DRAFT_SAVE','SUCCESS','SAVED',NULL,'retry-key',@h,@n,@n,@n);
-- EXPECT_ERROR 1062 uk_actor_success_request
INSERT INTO employee_configuration_audit_event (actor_user_id,employee_id,action_code,outcome,result_code,request_id,request_sha256,occurred_at,gmt_create,gmt_modified) VALUES (970001,970002,'DRAFT_SAVE','SUCCESS','SAVED','retry-key',@h,@n,@n,@n);
-- EXPECT_ERROR 1062 uk_actor_success_request
INSERT INTO employee_configuration_audit_event (actor_user_id,employee_id,action_code,outcome,result_code,request_id,request_sha256,occurred_at,gmt_create,gmt_modified) VALUES (970001,970001,'EMPLOYEE_PUBLISH','SUCCESS','CREATED','retry-key',@h,@n,@n,@n);
-- EXPECT_ERROR 3819 chk_employee_config_audit_positive
UPDATE employee_configuration_audit_event SET actor_user_id=0 WHERE id=970005;
-- EXPECT_ERROR 3819 chk_employee_config_audit_positive
UPDATE employee_configuration_audit_event SET employee_id=0 WHERE id=970005;
-- EXPECT_ERROR 3819 chk_employee_config_audit_positive
UPDATE employee_configuration_audit_event SET draft_revision=0 WHERE id=970005;
-- EXPECT_ERROR 3819 chk_employee_config_audit_positive
UPDATE employee_configuration_audit_event SET employee_release_id=0 WHERE id=970005;
-- EXPECT_ERROR 3819 chk_employee_config_audit_action
UPDATE employee_configuration_audit_event SET action_code='BROKEN' WHERE id=970005;
-- EXPECT_ERROR 3819 chk_employee_config_audit_outcome|chk_employee_config_audit_result
UPDATE employee_configuration_audit_event SET outcome='BROKEN' WHERE id=970005;
-- EXPECT_ERROR 3819 chk_employee_config_audit_result
UPDATE employee_configuration_audit_event SET result_code=NULL WHERE id=970005;
-- EXPECT_ERROR 3819 chk_employee_config_audit_result
UPDATE employee_configuration_audit_event SET result_code='BROKEN' WHERE id=970005;
-- EXPECT_ERROR 3819 chk_employee_config_audit_result
UPDATE employee_configuration_audit_event SET reason_code='FAILURE_REASON' WHERE id=970005;
-- EXPECT_ERROR 3819 chk_employee_config_audit_result
UPDATE employee_configuration_audit_event SET reason_code=NULL WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_config_audit_result
UPDATE employee_configuration_audit_event SET reason_code=' ' WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_config_audit_result
UPDATE employee_configuration_audit_event SET result_code='SAVED' WHERE id=970001;
-- EXPECT_ERROR 3819 chk_employee_config_audit_request
UPDATE employee_configuration_audit_event SET request_id=' ' WHERE id=970005;
-- EXPECT_ERROR 3819 chk_employee_config_audit_summary
UPDATE employee_configuration_audit_event SET change_summary=JSON_ARRAY() WHERE id=970005;
-- EXPECT_ERROR 3819 chk_employee_config_audit_summary
UPDATE employee_configuration_audit_event SET change_summary=CAST('null' AS JSON) WHERE id=970005;
-- EXPECT_ERROR 3819 chk_employee_config_audit_summary
UPDATE employee_configuration_audit_event SET change_summary=CAST('7' AS JSON) WHERE id=970005;
-- EXPECT_ERROR 1062 uk_release_skill_name
INSERT INTO employee_release_skill (employee_release_id,skill_name,description,metadata_json,skill_markdown,package_sha256,resource_count,gmt_create,gmt_modified) VALUES (970001,'meeting-room','会议室预约',JSON_OBJECT('name','meeting-room','description','会议室预约'),'---
name: meeting-room
description: 会议室预约
---
# 正文',@h,0,@n,@n);
-- EXPECT_ERROR 3819 chk_release_skill_positive
UPDATE employee_release_skill SET employee_release_id=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_release_skill_text
UPDATE employee_release_skill SET skill_name=' ' WHERE id=970001;
-- EXPECT_ERROR 3819 chk_release_skill_text
UPDATE employee_release_skill SET description=' ' WHERE id=970001;
-- EXPECT_ERROR 3819 chk_release_skill_text
UPDATE employee_release_skill SET skill_markdown=' ' WHERE id=970001;
-- EXPECT_ERROR 3819 chk_release_skill_metadata
UPDATE employee_release_skill SET metadata_json=JSON_ARRAY() WHERE id=970001;
-- EXPECT_ERROR 3819 chk_release_skill_metadata
UPDATE employee_release_skill SET metadata_json=CAST('null' AS JSON) WHERE id=970001;
-- EXPECT_ERROR 1048 metadata_json
UPDATE employee_release_skill SET metadata_json=NULL WHERE id=970001;
-- EXPECT_ERROR 1062 uk_skill_resource_path
INSERT INTO employee_release_skill_resource (release_skill_id,resource_path,resource_content,content_sha256,gmt_create,gmt_modified) VALUES (970001,'plan.md','冲突',UNHEX(SHA2('冲突',256)),@n,@n);
-- EXPECT_ERROR 1062 uk_skill_resource_path
INSERT INTO employee_release_skill_resource (release_skill_id,resource_path,resource_content,content_sha256,gmt_create,gmt_modified) VALUES (970001,'references/cafe.md','冲突',UNHEX(SHA2('冲突',256)),@n,@n);
-- EXPECT_ERROR 3819 chk_release_resource_positive
UPDATE employee_release_skill_resource SET release_skill_id=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_release_resource_path
UPDATE employee_release_skill_resource SET resource_path=' ' WHERE id=970001;
-- EXPECT_ERROR 1048 resource_content
UPDATE employee_release_skill_resource SET resource_content=NULL WHERE id=970001;
-- EXPECT_ERROR 3819 chk_identity_audit_revision_pair
UPDATE identity_audit_event SET authorization_version_after=NULL WHERE id=970001;
-- EXPECT_ERROR 3819 chk_identity_audit_revision_pair
UPDATE identity_audit_event SET authorization_version_before=NULL WHERE id=970001;
-- EXPECT_ERROR 3819 chk_identity_audit_grant_employee
UPDATE identity_audit_event SET employee_id=NULL WHERE id=970001;
-- EXPECT_ERROR 3819 chk_identity_audit_grant_employee
UPDATE identity_audit_event SET employee_id=NULL,grant_id=970001 WHERE id=970002;
-- EXPECT_ERROR 3819 chk_identity_audit_grant_employee
UPDATE identity_audit_event SET employee_id=NULL,authorization_version_before=1,authorization_version_after=1 WHERE id=970002;
-- EXPECT_ERROR 3819 chk_identity_audit_grant_positive
UPDATE identity_audit_event SET employee_id=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_identity_audit_grant_positive
UPDATE identity_audit_event SET grant_id=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_identity_audit_grant_positive
UPDATE identity_audit_event SET authorization_version_before=0,authorization_version_after=0 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_identity_audit_grant_positive
UPDATE identity_audit_event SET authorization_version_before=0,authorization_version_after=1 WHERE id=970001;
-- EXPECT_ERROR 3819 chk_identity_audit_revision_order
UPDATE identity_audit_event SET authorization_version_before=3 WHERE id=970001;
UPDATE employee_configuration_audit_event SET change_summary=JSON_OBJECT('field','instructions') WHERE id=970005;
-- EXPECT_PASS
SELECT IF((SELECT JSON_UNQUOTE(JSON_EXTRACT(change_summary,'$.field')) FROM employee_configuration_audit_event WHERE id=970005)='instructions','PASS','FAIL');
UPDATE employee_release SET availability_status='DISABLED' WHERE id=970003;
-- EXPECT_PASS
SELECT IF((SELECT availability_status FROM employee_release WHERE id=970003)='DISABLED' AND (SELECT COUNT(*) FROM employee_configuration_audit_event WHERE id=970005 AND result_code='NO_CHANGE')=1,'PASS','FAIL');
ROLLBACK;
-- EXPECT_PASS
SELECT IF((SELECT COUNT(*) FROM digital_employee)+(SELECT COUNT(*) FROM employee_release)+(SELECT COUNT(*) FROM employee_release_skill)+(SELECT COUNT(*) FROM identity_audit_event)+(SELECT COUNT(*) FROM platform_user)=0,'PASS','FAIL');
