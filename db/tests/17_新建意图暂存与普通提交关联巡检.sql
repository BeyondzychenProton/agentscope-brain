-- 新建意图／上传暂存／普通提交跨行巡检；只输出 check_name, anomaly_count。
-- 唯一键与单行 CHECK 已由建表验收覆盖；此处只检跨行／引用一致性。

SELECT 'intent_bind_without_first_message' AS check_name, COUNT(*) AS anomaly_count
FROM session_intent AS i
LEFT JOIN business_session AS s ON s.id = i.bound_session_id
LEFT JOIN business_message AS m ON m.session_id = i.bound_session_id AND m.message_no = 1 AND m.sender_kind = 'USER'
WHERE i.bound_session_id IS NOT NULL AND (
    s.id IS NULL OR s.user_id <> i.owner_user_id
    OR s.employee_id <> i.employee_id OR s.employee_release_id <> i.employee_release_id OR m.id IS NULL
)
UNION ALL
SELECT 'intent_first_submission_mismatch', COUNT(*)
FROM session_intent AS i
JOIN ordinary_submission AS o ON o.id = i.first_submission_id
WHERE i.first_submission_id IS NOT NULL AND (
    o.user_id <> i.owner_user_id
    OR o.status <> 'ACCEPTED'
    OR o.session_intent_id <> i.id
    OR o.accepted_session_id <> i.bound_session_id
)
UNION ALL
SELECT 'upload_expired_still_ready', COUNT(*)
FROM media_upload
WHERE status = 'READY' AND expires_at < UTC_TIMESTAMP(6)
UNION ALL
SELECT 'upload_bound_without_formal_media', COUNT(*)
FROM media_upload AS u
LEFT JOIN message_media AS m ON m.id = u.message_media_id
LEFT JOIN business_message AS msg ON msg.id = u.message_id
WHERE u.status = 'BOUND' AND (
    m.id IS NULL OR msg.id IS NULL OR m.message_id <> u.message_id
    OR m.user_id <> u.user_id OR msg.user_id <> u.user_id
    OR m.storage_profile <> u.storage_profile OR m.object_key <> u.object_key
    OR (u.content_sha256 IS NOT NULL AND m.content_sha256 <> u.content_sha256)
)
UNION ALL
SELECT 'upload_formal_still_temp_deleting', COUNT(*)
FROM media_upload
WHERE status = 'BOUND' AND deletion_status IN ('PENDING', 'CLAIMED', 'FAILED', 'DONE')
UNION ALL
SELECT 'upload_owner_or_target_mismatch', COUNT(*)
FROM media_upload AS u
LEFT JOIN business_session AS s ON s.id = u.session_id
LEFT JOIN session_intent AS i ON i.id = u.session_intent_id
WHERE (u.session_id IS NOT NULL AND (s.id IS NULL OR s.user_id <> u.user_id
        OR s.employee_id <> u.employee_id OR s.employee_release_id <> u.employee_release_id))
   OR (u.session_intent_id IS NOT NULL AND (i.id IS NULL OR i.owner_user_id <> u.user_id
        OR i.employee_id <> u.employee_id OR i.employee_release_id <> u.employee_release_id))
UNION ALL
SELECT 'submission_accepted_incomplete', COUNT(*)
FROM ordinary_submission AS o
LEFT JOIN business_session AS s ON s.id = o.accepted_session_id
LEFT JOIN business_message AS m ON m.id = o.accepted_message_id
LEFT JOIN business_run AS r ON r.id = o.accepted_run_id
WHERE o.status = 'ACCEPTED' AND (
    o.has_registered_intent <> 1
    OR o.submission_sha256 IS NULL OR o.upload_ref_json IS NULL
    OR o.accepted_session_id IS NULL OR o.accepted_message_id IS NULL OR o.accepted_run_id IS NULL
    OR o.accepted_at IS NULL
    OR s.id IS NULL OR s.user_id <> o.user_id
    OR m.id IS NULL OR m.user_id <> o.user_id OR m.submission_key <> o.submission_key
    OR r.id IS NULL OR r.actor_user_id <> o.user_id OR r.input_message_id <> o.accepted_message_id
)
UNION ALL
SELECT 'submission_control_message_mismatch', COUNT(*)
FROM ordinary_submission AS o
JOIN business_message AS m ON m.user_id = o.user_id AND m.submission_key = o.submission_key
WHERE o.status = 'ACCEPTED' AND (
    o.accepted_message_id <> m.id OR o.accepted_session_id <> m.session_id
)
;
