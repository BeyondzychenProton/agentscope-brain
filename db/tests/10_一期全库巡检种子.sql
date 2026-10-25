-- 完整29表关联巡检合成基线。仅供一次性隔离实例，不代表发布Schema、框架KV或外部原件可用。
SET @n=UTC_TIMESTAMP(6);
SET @h=UNHEX(REPEAT('ab',32));
SET @h2=UNHEX(REPEAT('cd',32));
START TRANSACTION;
INSERT INTO platform_user (id,display_name,authorization_version,status_changed_at,gmt_create,gmt_modified) VALUES (980001,'合成管理员',1,@n,@n,@n);
INSERT INTO platform_user (id,display_name,authorization_version,status_changed_at,gmt_create,gmt_modified) VALUES (980002,'合成普通用户',5,@n,@n,@n);
INSERT INTO user_credential (id,user_id,password_hash,gmt_create,gmt_modified) VALUES (980001,980001,'SYNTHETIC_NOT_A_REAL_HASH',@n,@n);
INSERT INTO user_credential (id,user_id,password_hash,gmt_create,gmt_modified) VALUES (980002,980002,'SYNTHETIC_NOT_A_REAL_HASH',@n,@n);
INSERT INTO fixed_admin_assignment (id,user_id,initialization_ref,initialized_at,gmt_create,gmt_modified) VALUES (980001,980001,'ISOLATED-SEED',@n,@n,@n);
INSERT INTO phone_reservation (id,phone_normalized,owner_user_id,reservation_status,ownership_source_ref,ownership_checked_by,ownership_checked_at,reserved_at,gmt_create,gmt_modified) VALUES (980001,'19900099901',980001,'CURRENT','SYNTHETIC',980001,@n,@n,@n,@n);
INSERT INTO phone_reservation (id,phone_normalized,owner_user_id,reservation_status,ownership_source_ref,ownership_checked_by,ownership_checked_at,reserved_at,gmt_create,gmt_modified) VALUES (980002,'19900099902',980002,'CURRENT','SYNTHETIC',980001,@n,@n,@n,@n);
INSERT INTO meeting_identity_check_state (id,user_id,checked_phone_id,checked_phone_revision,probe_status,probed_at,last_http_status,gmt_create,gmt_modified) VALUES (980001,980002,980002,1,'PASSED',@n,200,@n,@n);
INSERT INTO meeting_identity_check_attempt (id,user_id,phone_id,phone_revision,attempt_kind,result_code,http_status,observed_at,gmt_create,gmt_modified) VALUES (980001,980002,980002,1,'PROBE','PASSED',200,@n,@n,@n);
INSERT INTO digital_employee (id,employee_code,display_name,current_release_id,last_release_no,gmt_create,gmt_modified) VALUES (980001,'meeting-full','合成员工980001',980002,2,@n,@n);
INSERT INTO digital_employee (id,employee_code,display_name,current_release_id,last_release_no,gmt_create,gmt_modified) VALUES (980002,'other-full','合成员工980002',980003,1,@n,@n);
INSERT INTO employee_draft (id,employee_id,draft_revision,config_json,dependency_sha256,saved_by,gmt_create,gmt_modified) VALUES (980001,980001,4,JSON_OBJECT('configSchemaVersion',1,'instructions','仅合成验收'),@h,980001,@n,@n);
INSERT INTO employee_draft (id,employee_id,draft_revision,config_json,dependency_sha256,saved_by,gmt_create,gmt_modified) VALUES (980002,980002,1,JSON_OBJECT('configSchemaVersion',1,'instructions','仅合成验收'),@h,980001,@n,@n);
INSERT INTO tool_binding_revision (id,tool_binding_version,provider_key,tool_name,binding_schema_version,binding_json,binding_sha256,created_by,gmt_create,gmt_modified) VALUES (980001,'tb_full_v1','meeting','room.reserve',1,JSON_OBJECT('providerKey','meeting','toolName','room.reserve','adapterRevision','synthetic-v1'),@h,980001,@n,@n);
INSERT INTO tool_binding_revision (id,tool_binding_version,provider_key,tool_name,binding_schema_version,binding_json,binding_sha256,created_by,gmt_create,gmt_modified) VALUES (980002,'tb_full_v2','meeting','room.reserve',1,JSON_OBJECT('providerKey','meeting','toolName','room.reserve','adapterRevision','synthetic-v1'),@h2,980001,@n,@n);
INSERT INTO employee_release (id,employee_id,release_no,source_draft_revision,config_schema_version,config_json,dependency_sha256,config_sha256,manifest_sha256,published_by,published_at,availability_status,availability_changed_by,availability_changed_at,gmt_create,gmt_modified) VALUES (980001,980001,1,1,1,JSON_OBJECT('configSchemaVersion',1,'model',JSON_OBJECT('profileKey','synthetic-only','modelId','synthetic-model'),'instructions','仅合成验收','skills',JSON_ARRAY(JSON_OBJECT('name','meeting-room','snapshotId',980001,'packageSha256',LOWER(HEX(@h)))),'tools',JSON_ARRAY(JSON_OBJECT('providerKey','meeting','toolName','room.reserve','toolBindingVersion','tb_full_v1','bindingSha256',LOWER(HEX(@h)))),'workspace',JSON_OBJECT('rule','default'),'memory',JSON_OBJECT('rule','employee-user')),@h,@h,@h,980001,@n,'DISABLED',980001,@n,@n,@n);
INSERT INTO employee_release (id,employee_id,release_no,source_draft_revision,config_schema_version,config_json,dependency_sha256,config_sha256,manifest_sha256,published_by,published_at,availability_status,availability_changed_by,availability_changed_at,gmt_create,gmt_modified) VALUES (980002,980001,2,3,1,JSON_OBJECT('configSchemaVersion',1,'model',JSON_OBJECT('profileKey','synthetic-only','modelId','synthetic-model'),'instructions','仅合成验收','skills',JSON_ARRAY(JSON_OBJECT('name','meeting-room','snapshotId',980002,'packageSha256',LOWER(HEX(@h2)))),'tools',JSON_ARRAY(JSON_OBJECT('providerKey','meeting','toolName','room.reserve','toolBindingVersion','tb_full_v2','bindingSha256',LOWER(HEX(@h2)))),'workspace',JSON_OBJECT('rule','default'),'memory',JSON_OBJECT('rule','employee-user')),@h,@h2,@h,980001,@n,'AVAILABLE',980001,@n,@n,@n);
INSERT INTO employee_release (id,employee_id,release_no,source_draft_revision,config_schema_version,config_json,dependency_sha256,config_sha256,manifest_sha256,published_by,published_at,availability_status,availability_changed_by,availability_changed_at,gmt_create,gmt_modified) VALUES (980003,980002,1,1,1,JSON_OBJECT('configSchemaVersion',1,'model',JSON_OBJECT('profileKey','synthetic-only','modelId','synthetic-model'),'instructions','仅合成验收','skills',JSON_ARRAY(JSON_OBJECT('name','meeting-room','snapshotId',980003,'packageSha256',LOWER(HEX(@h)))),'tools',JSON_ARRAY(JSON_OBJECT('providerKey','meeting','toolName','room.reserve','toolBindingVersion','tb_full_v1','bindingSha256',LOWER(HEX(@h)))),'workspace',JSON_OBJECT('rule','default'),'memory',JSON_OBJECT('rule','employee-user')),@h,@h,@h,980001,@n,'AVAILABLE',980001,@n,@n,@n);
INSERT INTO employee_release_skill (id,employee_release_id,skill_name,description,metadata_json,skill_markdown,package_sha256,resource_count,gmt_create,gmt_modified) VALUES (980001,980001,'meeting-room','会议预约',JSON_OBJECT('name','meeting-room','description','会议预约'),'---
name: meeting-room
description: 会议预约
---
# 合成入口',@h,1,@n,@n);
INSERT INTO employee_release_skill_resource (id,release_skill_id,resource_path,resource_content,content_sha256,gmt_create,gmt_modified) VALUES (980001,980001,'references/会议规则.md','先确认再预约
中文资源',UNHEX(SHA2('先确认再预约\n中文资源',256)),@n,@n);
INSERT INTO employee_release_skill (id,employee_release_id,skill_name,description,metadata_json,skill_markdown,package_sha256,resource_count,gmt_create,gmt_modified) VALUES (980002,980002,'meeting-room','会议预约',JSON_OBJECT('name','meeting-room','description','会议预约'),'---
name: meeting-room
description: 会议预约
---
# 合成入口',@h2,1,@n,@n);
INSERT INTO employee_release_skill_resource (id,release_skill_id,resource_path,resource_content,content_sha256,gmt_create,gmt_modified) VALUES (980002,980002,'references/会议规则.md','先确认再预约
中文资源',UNHEX(SHA2('先确认再预约\n中文资源',256)),@n,@n);
INSERT INTO employee_release_skill (id,employee_release_id,skill_name,description,metadata_json,skill_markdown,package_sha256,resource_count,gmt_create,gmt_modified) VALUES (980003,980003,'meeting-room','会议预约',JSON_OBJECT('name','meeting-room','description','会议预约'),'---
name: meeting-room
description: 会议预约
---
# 合成入口',@h,1,@n,@n);
INSERT INTO employee_release_skill_resource (id,release_skill_id,resource_path,resource_content,content_sha256,gmt_create,gmt_modified) VALUES (980003,980003,'references/会议规则.md','先确认再预约
中文资源',UNHEX(SHA2('先确认再预约\n中文资源',256)),@n,@n);
INSERT INTO employee_use_grant (id,user_id,employee_id,grant_status,granted_by,granted_at,gmt_create,gmt_modified) VALUES (980001,980002,980001,'ACTIVE',980001,@n,@n,@n);
INSERT INTO employee_use_grant (id,user_id,employee_id,grant_status,granted_by,granted_at,revoked_by,revoked_at,gmt_create,gmt_modified) VALUES (980002,980002,980002,'REVOKED',980001,@n,980001,@n,@n,@n);
INSERT INTO identity_audit_event (id,actor_user_id,target_user_id,action_code,outcome,employee_id,grant_id,authorization_version_before,authorization_version_after,occurred_at,gmt_create,gmt_modified) VALUES (980001,980001,980002,'EMPLOYEE_GRANT','SUCCESS',980001,980001,4,5,@n,@n,@n);
INSERT INTO employee_configuration_audit_event (id,actor_user_id,employee_id,action_code,outcome,result_code,draft_revision,employee_release_id,request_id,request_sha256,occurred_at,gmt_create,gmt_modified) VALUES (980001,980001,980001,'EMPLOYEE_PUBLISH','SUCCESS','NO_CHANGE',4,980002,'full-no-change',@h,@n,@n,@n);
INSERT INTO business_session (id,user_id,employee_id,employee_release_id,lifecycle_status,state_session_key,last_message_no,last_activity_at,gmt_create,gmt_modified) VALUES (980001,980002,980001,980001,'READ_ONLY','000000000000000000000000000ef421',2,@n,@n,@n);
INSERT INTO business_session (id,user_id,employee_id,employee_release_id,source_session_id,lifecycle_status,state_session_key,last_message_no,last_activity_at,gmt_create,gmt_modified) VALUES (980002,980002,980001,980002,980001,'ACTIVE','000000000000000000000000000ef422',2,@n,@n,@n);
INSERT INTO business_session (id,user_id,employee_id,employee_release_id,lifecycle_status,state_session_key,last_message_no,last_activity_at,gmt_create,gmt_modified) VALUES (980003,980002,980002,980003,'ACTIVE','000000000000000000000000000ef423',0,@n,@n,@n);
INSERT INTO business_message (id,session_id,user_id,employee_id,message_no,input_sequence,sender_kind,submission_key,submission_sha256,accepted_at,gmt_create,gmt_modified) VALUES (980001,980001,980002,980001,1,1,'USER',UNHEX('000000000000000000000000000ef421'),@h,@n,@n,@n);
INSERT INTO business_message (id,session_id,user_id,employee_id,message_no,input_sequence,sender_kind,submission_key,submission_sha256,accepted_at,gmt_create,gmt_modified) VALUES (980002,980002,980002,980001,1,2,'USER',UNHEX('000000000000000000000000000ef422'),@h,@n,@n,@n);
INSERT INTO business_run (id,session_id,input_message_id,actor_user_id,employee_release_id,memory_generation_at_start,memory_revision_at_start,memory_policy_version,run_request_key,request_sha256,status,ended_at,gmt_create,gmt_modified) VALUES (980001,980001,980001,980002,980001,1,1,'p1',UNHEX('000000000000000000000000000ef485'),@h,'SUCCEEDED',@n,@n,@n);
INSERT INTO business_run (id,session_id,input_message_id,actor_user_id,employee_release_id,memory_generation_at_start,memory_revision_at_start,memory_policy_version,run_request_key,request_sha256,status,ended_at,gmt_create,gmt_modified) VALUES (980002,980002,980002,980002,980002,1,1,'p1',UNHEX('000000000000000000000000000ef486'),@h,'SUCCEEDED',@n,@n,@n);
UPDATE business_message SET initial_run_id=id WHERE id IN (980001,980002);
INSERT INTO business_message (id,session_id,user_id,message_no,sender_kind,produced_by_run_id,accepted_at,gmt_create,gmt_modified) VALUES (980003,980001,980002,2,'ASSISTANT',980001,@n,@n,@n);
INSERT INTO business_message (id,session_id,user_id,message_no,sender_kind,produced_by_run_id,accepted_at,gmt_create,gmt_modified) VALUES (980004,980002,980002,2,'ASSISTANT',980002,@n,@n,@n);
INSERT INTO message_media (id,message_id,user_id,storage_profile,object_key,media_kind,mime_type,byte_count,content_sha256,gmt_create,gmt_modified) VALUES (980001,980002,980002,'synthetic-private-v1','synthetic/full/image.png','IMAGE','image/png',100,@h,@n,@n);
INSERT INTO message_content_block (id,message_id,block_no,block_kind,text_content,gmt_create,gmt_modified) VALUES (980001,980001,1,'TEXT','合成消息',@n,@n);
INSERT INTO message_content_block (id,message_id,block_no,block_kind,text_content,gmt_create,gmt_modified) VALUES (980002,980002,1,'TEXT','合成消息',@n,@n);
INSERT INTO message_content_block (id,message_id,block_no,block_kind,media_id,gmt_create,gmt_modified) VALUES (980003,980002,2,'MEDIA',980001,@n,@n);
INSERT INTO message_content_block (id,message_id,block_no,block_kind,text_content,gmt_create,gmt_modified) VALUES (980004,980003,1,'TEXT','合成消息',@n,@n);
INSERT INTO message_content_block (id,message_id,block_no,block_kind,text_content,gmt_create,gmt_modified) VALUES (980005,980004,1,'TEXT','合成消息',@n,@n);
INSERT INTO session_upgrade_attempt (id,source_session_id,target_session_id,requested_by_user_id,target_release_id,status,history_message_count,history_sha256,source_last_message_no,expires_at,first_run_id,activation_run_id,finished_at,gmt_create,gmt_modified) VALUES (980001,980001,980002,980002,980002,'SUCCEEDED',2,@h,2,@n+INTERVAL 30 MINUTE,980002,980002,@n,@n,@n);
INSERT INTO run_progress (id,run_id,event_no,event_kind,display_text,occurred_at,gmt_create,gmt_modified) VALUES (980001,980002,1,'STATUS','合成完成事件',@n,@n,@n);
INSERT INTO tool_operation (id,run_id,operation_no,actor_user_id,tool_provider_key,tool_name,tool_binding_version,arguments_schema_version,effect_kind,is_confirmation_required,identity_binding_kind,identity_binding_ref,identity_binding_version,request_id,idempotency_mode,provider_request_id,reconciliation_mode,normalized_arguments,arguments_sha256,operation_sha256,status,sent_at,resolved_at,gmt_create,gmt_modified) VALUES (980001,980002,1,980002,'meeting','room.reserve','tb_full_v2','synthetic-v1','MUTATING',1,'MEETING_PHONE','980002',1,'full-operation-1','PROVIDER_KEY','full-operation-1','REQUEST_ID',JSON_OBJECT('synthetic',true),@h,@h2,'SUCCEEDED',@n,@n,@n,@n);
INSERT INTO operation_confirmation (id,operation_id,run_id,actor_user_id,arguments_sha256,operation_sha256,authorization_version,security_version,identity_binding_version,confirmation_token_sha256,status,expires_at,consumed_at,gmt_create,gmt_modified) VALUES (980001,980001,980002,980002,@h,@h2,5,1,1,@h,'CONSUMED',@n+INTERVAL 10 MINUTE,@n,@n,@n);
INSERT INTO memory_profile (id,user_id,employee_id,document_kv_version,document_sha256,memory_schema_version,policy_version,last_input_sequence,activated_at,gmt_create,gmt_modified) VALUES (980001,980002,980001,1,@h,'v1','p1',2,@n,@n,@n);
INSERT INTO memory_update_job (id,user_id,employee_id,generation,task_type,source_session_id,source_run_id,source_message_id,source_input_sequence,authorization_version_at_creation,memory_schema_version,policy_version,next_attempt_at,gmt_create,gmt_modified) VALUES (980001,980002,980001,1,'FLUSH',980001,980001,980001,1,5,'v1','p1',@n,@n,@n);
INSERT INTO memory_update_job (id,user_id,employee_id,generation,task_type,source_session_id,source_run_id,source_message_id,source_input_sequence,authorization_version_at_creation,memory_schema_version,policy_version,next_attempt_at,gmt_create,gmt_modified) VALUES (980002,980002,980001,1,'FLUSH',980002,980002,980002,2,5,'v1','p1',@n,@n,@n);
INSERT INTO conversation_audit_event (id,actor_user_id,target_user_id,session_id,run_id,operation_id,employee_id,action_code,outcome,entry_kind,occurred_at,gmt_create,gmt_modified) VALUES (980001,980001,980002,980002,980002,980001,980001,'ADMIN_CONTENT_VIEW','SUCCESS','ADMIN_WEB',@n,@n,@n);
INSERT INTO workspace_entry (id,user_id,employee_id,session_id,source_run_id,employee_release_id,entry_path,entry_kind,storage_type,kv_version,mime_type,byte_count,content_sha256,gmt_create,gmt_modified) VALUES (980001,980002,980001,980001,980001,980001,'plan.md','PLAN','KV_TEXT',1,'text/markdown',4,@h,@n,@n);
INSERT INTO workspace_snapshot_reference (id,user_id,employee_id,source_entry_id,source_content_revision,source_content_sha256,source_session_id,source_run_id,target_session_id,storage_type,snapshot_kv_version,mime_type,byte_count,snapshot_sha256,created_by_user_id,gmt_create,gmt_modified) VALUES (980001,980002,980001,980001,1,@h,980001,980001,980002,'KV_TEXT',1,'text/markdown',4,@h,980002,@n,@n);
-- INSPECT_ALL_ZERO
-- 下列异常仅为验证巡检命中，不代表应用允许这样写。
UPDATE digital_employee SET current_release_id=999999,last_release_no=3 WHERE id=980001;
UPDATE employee_draft SET saved_by=999999 WHERE id=980001;
UPDATE business_session SET employee_release_id=980003 WHERE id=980001;
UPDATE message_media SET user_id=980001 WHERE id=980001;
UPDATE tool_operation SET tool_binding_version='tb_full_v1' WHERE id=980001;
UPDATE employee_release_skill SET resource_count=2 WHERE id=980002;
UPDATE employee_release_skill_resource SET resource_content=CONCAT(resource_content,'篡改') WHERE id=980001;
UPDATE employee_use_grant SET user_id=980001 WHERE id=980001;
UPDATE operation_confirmation SET actor_user_id=980001 WHERE id=980001;
UPDATE memory_update_job SET employee_id=980002 WHERE id=980001;
UPDATE workspace_snapshot_reference SET source_session_id=980002 WHERE id=980001;
UPDATE employee_configuration_audit_event SET employee_release_id=980003 WHERE id=980001;
-- INSPECT_ALL_POSITIVE
