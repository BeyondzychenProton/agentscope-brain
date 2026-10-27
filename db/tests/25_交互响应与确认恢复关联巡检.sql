-- 只读跨行巡检；同一一致性视图运行。结果为异常数，不输出展示、回答、nonce 或密文。
-- 已有历史行不因用户当前撤权而判为损坏；新接纳的实时鉴权由未来应用事务承担。
SELECT 'interaction_owner',COUNT(*) FROM interaction_request q
LEFT JOIN platform_user u ON u.id=q.owner_user_id
LEFT JOIN business_session s ON s.id=q.session_id
LEFT JOIN business_run r ON r.id=q.run_id
LEFT JOIN employee_release e ON e.id=q.employee_release_id
WHERE u.id IS NULL OR s.id IS NULL OR r.id IS NULL OR e.id IS NULL
 OR s.user_id<>q.owner_user_id OR r.actor_user_id<>q.owner_user_id OR r.session_id<>q.session_id
 OR s.employee_release_id<>q.employee_release_id OR r.employee_release_id<>q.employee_release_id OR e.employee_id<>s.employee_id;

SELECT 'interaction_current_revision',COUNT(*) FROM interaction_request q
LEFT JOIN interaction_revision v ON v.id=q.current_revision_id
WHERE (q.current_revision_id IS NOT NULL AND (v.id IS NULL OR v.request_id<>q.id OR v.revision_no<>q.revision_no
 OR v.interaction_type<>q.interaction_type OR NOT(v.confirmation_id<=>q.confirmation_id)))
 OR (q.status IN ('WAITING','ANSWERED') AND v.id IS NULL);

SELECT 'interaction_revision_snapshot',COUNT(*) FROM interaction_revision v
LEFT JOIN interaction_request q ON q.id=v.request_id
WHERE q.id IS NULL OR v.interaction_type<>q.interaction_type OR NOT(v.confirmation_id<=>q.confirmation_id)
 OR v.snapshot_sha256<>UNHEX(SHA2(v.snapshot_body,256));

SELECT 'interaction_response_binding',COUNT(*) FROM interaction_response a
LEFT JOIN interaction_request q ON q.id=a.request_id
LEFT JOIN interaction_revision v ON v.id=a.accepted_revision_id
WHERE q.id IS NULL OR v.id IS NULL OR q.status<>'ANSWERED' OR q.owner_user_id<>a.actor_user_id
 OR a.interaction_type<>q.interaction_type OR v.request_id<>q.id OR v.interaction_type<>a.interaction_type
 OR NOT(q.current_revision_id<=>a.accepted_revision_id) OR q.revision_no<>v.revision_no OR v.snapshot_sha256<>a.snapshot_sha256;

SELECT 'interaction_answered_fact',COUNT(*) FROM interaction_request q
LEFT JOIN interaction_response a ON a.request_id=q.id
WHERE (q.status='ANSWERED' AND a.id IS NULL) OR (q.status<>'ANSWERED' AND a.id IS NOT NULL);

SELECT 'interaction_form_message',COUNT(*) FROM interaction_response a
JOIN interaction_request q ON q.id=a.request_id
JOIN business_session s ON s.id=q.session_id
LEFT JOIN business_message m ON m.id=a.accepted_message_id
WHERE a.interaction_type='FORM' AND (m.id IS NULL OR m.sender_kind<>'USER' OR m.user_id<>a.actor_user_id
 OR m.session_id<>q.session_id OR m.employee_id<>s.employee_id OR NOT(m.initial_run_id<=>q.run_id)
 OR NOT(m.submission_key<=>a.submission_key) OR NOT(m.submission_sha256<=>a.submission_sha256) OR m.accepted_at<>a.accepted_at);

SELECT 'interaction_source_checkpoint',COUNT(*) FROM interaction_request q
LEFT JOIN runtime_sdk_call c ON c.id=q.source_sdk_call_id
LEFT JOIN business_session s ON s.id=q.session_id
WHERE (q.source_sdk_call_id IS NOT NULL AND (c.id IS NULL OR c.user_id<>q.owner_user_id OR c.session_id<>q.session_id
 OR c.run_id<>q.run_id OR c.employee_release_id<>q.employee_release_id OR c.employee_id<>s.employee_id
 OR c.state_session_key<>s.state_session_key
 OR (q.status IN ('WAITING','ANSWERED') AND (c.status<>'VERIFIED' OR c.save_receipt_ref IS NULL
 OR c.save_status<>'SAVED' OR c.readback_status<>'MATCHED' OR c.ended_at IS NULL
 OR NOT(c.pending_id<=>q.pending_id) OR NOT(c.pending_name<=>q.pending_name)
 OR NOT(c.frozen_tool_use_sha256<=>q.frozen_tool_use_sha256)
 OR NOT(c.saved_state_sha256<=>q.ready_checkpoint_sha256) OR NOT(c.readback_state_sha256<=>q.ready_checkpoint_sha256)))))
 OR (q.interaction_type='FORM' AND q.status IN ('WAITING','ANSWERED') AND q.source_sdk_call_id IS NULL);

SELECT 'interaction_form_consumption',COUNT(*) FROM interaction_response a
JOIN interaction_request q ON q.id=a.request_id
LEFT JOIN runtime_sdk_call c ON c.id=a.sdk_call_id
LEFT JOIN runtime_sdk_call src ON src.id=q.source_sdk_call_id
LEFT JOIN business_session s ON s.id=q.session_id
LEFT JOIN runtime_dispatch d ON d.id=c.dispatch_id
WHERE a.interaction_type='FORM' AND a.sdk_call_id IS NOT NULL AND (
 c.id IS NULL OR src.id IS NULL OR d.id IS NULL OR c.id=src.id OR c.call_no<=src.call_no
 OR c.user_id<>q.owner_user_id OR c.session_id<>q.session_id OR c.run_id<>q.run_id OR c.employee_release_id<>q.employee_release_id
 OR c.employee_id<>s.employee_id OR c.state_session_key<>s.state_session_key OR c.state_generation<>src.state_generation
 OR NOT(c.response_id<=>a.id) OR c.call_kind<>'FORM_CONTINUE' OR c.claim_fence<>a.claim_fence
 OR d.run_id<>q.run_id OR d.session_id<>q.session_id OR d.user_id<>q.owner_user_id
 OR d.claim_fence<>c.claim_fence OR d.boot_epoch<>c.boot_epoch
 OR NOT(c.pending_id<=>q.pending_id) OR NOT(c.pending_name<=>q.pending_name)
 OR NOT(c.frozen_tool_use_sha256<=>q.frozen_tool_use_sha256)
 OR c.before_exists<>1 OR NOT(c.before_evidence_ref<=>a.before_checkpoint_ref)
 OR NOT(c.before_state_sha256<=>a.before_checkpoint_sha256) OR a.before_checkpoint_sha256<>q.ready_checkpoint_sha256
 OR (a.status='CONSUMED' AND (c.status<>'VERIFIED' OR c.save_status<>'SAVED' OR c.readback_status<>'MATCHED'
   OR c.verified_at IS NULL OR c.ended_at IS NULL OR c.save_receipt_ref IS NULL
   OR NOT(c.saved_state_sha256<=>a.after_checkpoint_sha256) OR NOT(c.readback_state_sha256<=>a.after_checkpoint_sha256)
   OR (c.store_version_capability='VERSIONED' AND (c.before_store_version IS NULL OR c.saved_store_version<=c.before_store_version)))));

SELECT 'interaction_confirmation_contract',COUNT(*) FROM interaction_request q
LEFT JOIN operation_confirmation k ON k.id=q.confirmation_id
LEFT JOIN tool_operation o ON o.id=k.operation_id
LEFT JOIN interaction_response a ON a.request_id=q.id
WHERE q.interaction_type='OPERATION_CONFIRMATION' AND q.confirmation_id IS NOT NULL AND (
 k.id IS NULL OR o.id IS NULL OR k.run_id<>q.run_id OR o.run_id<>q.run_id OR k.actor_user_id<>q.owner_user_id OR o.actor_user_id<>q.owner_user_id
 OR k.operation_sha256<>o.operation_sha256 OR k.arguments_sha256<>o.arguments_sha256
 OR k.identity_binding_version<>o.identity_binding_version OR k.authorization_version<>q.authorization_version OR k.security_version<>q.security_version
 OR o.is_confirmation_required<>1 OR k.expires_at<>TIMESTAMPADD(MINUTE,10,k.gmt_create)
 OR (q.status='WAITING' AND (k.status<>'PENDING' OR o.status<>'PROPOSED' OR k.recovery_status<>'AVAILABLE'))
 OR (q.status='ANSWERED' AND (k.status<>'CONSUMED' OR a.id IS NULL OR a.interaction_type<>'OPERATION_CONFIRMATION'
   OR NOT(a.consumed_at<=>k.consumed_at) OR a.accepted_at>=k.expires_at OR k.consumed_at>=k.expires_at
   OR o.status NOT IN ('READY_TO_SEND','SENT','SUCCEEDED','FAILED','UNKNOWN','CANCELLED'))));

SELECT 'interaction_second_authorization',(
SELECT COUNT(*) FROM operation_confirmation k
LEFT JOIN interaction_request q ON q.confirmation_id=k.id
LEFT JOIN interaction_response a ON a.request_id=q.id
LEFT JOIN tool_operation o ON o.id=k.operation_id
WHERE (k.recovery_status='AVAILABLE' AND k.status='CONSUMED' AND (q.id IS NULL OR q.interaction_type<>'OPERATION_CONFIRMATION'
 OR q.status<>'ANSWERED' OR a.id IS NULL OR a.interaction_type<>'OPERATION_CONFIRMATION' OR a.status<>'CONSUMED'))
 OR (o.is_confirmation_required=1 AND o.status IN ('READY_TO_SEND','SENT','SUCCEEDED','UNKNOWN') AND k.status<>'CONSUMED')
) + (
SELECT COUNT(*) FROM tool_operation o LEFT JOIN operation_confirmation k ON k.operation_id=o.id
WHERE o.is_confirmation_required=1 AND o.status IN ('READY_TO_SEND','SENT','SUCCEEDED','UNKNOWN') AND k.id IS NULL
);

SELECT 'interaction_nonce_binding',COUNT(*) FROM operation_confirmation k
LEFT JOIN crypto_nonce_cursor n ON n.key_id=k.key_id
WHERE k.crypto_format_version IS NOT NULL AND (n.id IS NULL OR LEFT(k.token_nonce,4)<>n.nonce_prefix
 OR CAST(CONV(HEX(RIGHT(k.token_nonce,8)),16,10) AS DECIMAL(20,0))<1
 OR CAST(CONV(HEX(RIGHT(k.token_nonce,8)),16,10) AS DECIMAL(20,0))>n.last_reserved_counter);

SELECT 'interaction_sdk_response_reference',COUNT(*) FROM runtime_sdk_call c
LEFT JOIN interaction_response a ON a.id=c.response_id
LEFT JOIN interaction_request q ON q.id=a.request_id
WHERE c.response_id IS NOT NULL AND (a.id IS NULL OR q.id IS NULL OR c.run_id<>q.run_id OR c.session_id<>q.session_id
 OR c.user_id<>q.owner_user_id OR c.employee_release_id<>q.employee_release_id
 OR (a.interaction_type='FORM' AND (NOT(a.sdk_call_id<=>c.id) OR c.call_kind<>'FORM_CONTINUE'))
 OR (a.interaction_type='OPERATION_CONFIRMATION' AND NOT(a.sdk_sync_call_id<=>c.id)))
 OR (c.call_kind='FORM_CONTINUE' AND c.response_id IS NULL);

SELECT 'interaction_confirmation_sync',COUNT(*) FROM interaction_response a
JOIN interaction_request q ON q.id=a.request_id
LEFT JOIN runtime_sdk_call c ON c.id=a.sdk_sync_call_id
LEFT JOIN business_session s ON s.id=q.session_id
WHERE a.interaction_type='OPERATION_CONFIRMATION' AND a.sdk_sync_call_id IS NOT NULL AND (
 c.id IS NULL OR c.run_id<>q.run_id OR c.session_id<>q.session_id OR c.user_id<>q.owner_user_id
 OR c.employee_release_id<>q.employee_release_id OR c.state_session_key<>s.state_session_key OR NOT(c.response_id<=>a.id)
 OR (a.sdk_sync_status='SYNCED' AND (c.status<>'VERIFIED' OR c.save_receipt_ref IS NULL OR c.verified_at IS NULL)));
