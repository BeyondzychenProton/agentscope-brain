-- 仅在执行器新建的隔离空库运行；合成数据，不连接 MinIO／HTTP／真实 Run。
-- EXPECT_ERROR 由隔离验收执行器用 --force 按错误码与约束名核对。
-- 覆盖 session_intent／media_upload／ordinary_submission 字段约束、唯一性与合法形态。
SET @n = UTC_TIMESTAMP(6);
SET @h = UNHEX(REPEAT('ab', 32));
SET @h2 = UNHEX(REPEAT('cd', 32));
SET @cid = UNHEX(REPEAT('11', 16));
SET @cid2 = UNHEX(REPEAT('22', 16));
SET @uid = UNHEX(REPEAT('33', 16));
SET @uid2 = UNHEX(REPEAT('44', 16));
SET @sk = UNHEX(REPEAT('55', 16));
SET @sk2 = UNHEX(REPEAT('66', 16));
SET @sk3 = UNHEX(REPEAT('77', 16));
SET @rk = UNHEX(REPEAT('88', 16));
SET @rk2 = UNHEX(REPEAT('99', 16));
START TRANSACTION;

INSERT INTO platform_user (id, display_name, status_changed_at, gmt_create, gmt_modified)
VALUES (930001, '意图探针用户', @n, @n, @n);
INSERT INTO business_session (id, user_id, employee_id, employee_release_id, state_session_key, last_activity_at, gmt_create, gmt_modified)
VALUES (930001, 930001, 1, 1, REPEAT('a', 32), @n, @n, @n);
INSERT INTO business_message (id, session_id, user_id, employee_id, message_no, input_sequence, sender_kind, submission_key, submission_sha256, accepted_at, gmt_create, gmt_modified)
VALUES (930001, 930001, 930001, 1, 1, 1, 'USER', UNHEX(REPEAT('aa', 16)), @h, @n, @n, @n);
INSERT INTO business_run (id, session_id, input_message_id, actor_user_id, employee_release_id, memory_generation_at_start, memory_revision_at_start, memory_policy_version, run_request_key, request_sha256, gmt_create, gmt_modified)
VALUES (930001, 930001, 930001, 930001, 1, 1, 1, 'p1', UNHEX(REPEAT('bb', 16)), @h, @n, @n);
UPDATE business_message SET initial_run_id = 930001 WHERE id = 930001;
INSERT INTO message_media (id, message_id, user_id, storage_profile, object_key, media_kind, mime_type, byte_count, content_sha256, gmt_create, gmt_modified)
VALUES (930001, 930001, 930001, 'synthetic-private-v1', 'synthetic/intent/bound.png', 'IMAGE', 'image/png', 100, @h, @n, @n);

-- —— session_intent 正向 ——
INSERT INTO session_intent (id, owner_user_id, client_intent_id, employee_id, employee_release_id, image_capability_sha256, image_capability_json, prepared_at, gmt_create, gmt_modified)
VALUES (930001, 930001, @cid, 1, 1, @h, JSON_OBJECT('maxBytes', 1048576, 'mime', JSON_ARRAY('image/png')), @n, @n, @n);
-- EXPECT_PASS
SELECT IF(COUNT(*) = 1 AND bound_session_id IS NULL, 'PASS', 'FAIL') FROM session_intent WHERE id = 930001;
-- EXPECT_ERROR 1062 uk_owner_client_intent
INSERT INTO session_intent (owner_user_id, client_intent_id, employee_id, employee_release_id, image_capability_sha256, image_capability_json, prepared_at, gmt_create, gmt_modified)
VALUES (930001, @cid, 1, 2, @h, JSON_OBJECT('maxBytes', 1), @n, @n, @n);
INSERT INTO session_intent (id, owner_user_id, client_intent_id, employee_id, employee_release_id, image_capability_sha256, image_capability_json, prepared_at, bound_session_id, first_submission_id, bound_at, gmt_create, gmt_modified)
VALUES (930002, 930001, @cid2, 1, 1, @h, JSON_OBJECT('maxBytes', 2), @n, 930001, 1, @n, @n, @n);
-- EXPECT_ERROR 1062 uk_bound_session_id
INSERT INTO session_intent (owner_user_id, client_intent_id, employee_id, employee_release_id, image_capability_sha256, image_capability_json, prepared_at, bound_session_id, first_submission_id, bound_at, gmt_create, gmt_modified)
VALUES (930001, UNHEX(REPEAT('12', 16)), 1, 1, @h, JSON_OBJECT('x', 1), @n, 930001, 2, @n, @n, @n);
-- EXPECT_ERROR 3819 chk_session_intent_bind_pair
UPDATE session_intent SET bound_session_id = 930099 WHERE id = 930001;
-- EXPECT_ERROR 3819 chk_session_intent_capability
UPDATE session_intent SET image_capability_json = JSON_ARRAY(1) WHERE id = 930001;
-- EXPECT_ERROR 3819 chk_session_intent_positive
UPDATE session_intent SET row_version = 0 WHERE id = 930001;

-- —— media_upload 正向：意图目标 ISSUED → READY → BOUND ——
INSERT INTO media_upload (
    id, upload_id, user_id, upload_request_key, upload_request_sha256, employee_id, employee_release_id,
    session_intent_id, storage_profile, object_key, media_kind, status, issued_at, expires_at, gmt_create, gmt_modified
) VALUES (
    930001, @uid, 930001, @rk, @h, 1, 1, 930001, 'synthetic-private-v1', 'synthetic/upload/a.png',
    'IMAGE', 'ISSUED', @n, @n + INTERVAL 24 HOUR, @n, @n
);
-- EXPECT_PASS
SELECT IF(status = 'ISSUED' AND mime_type IS NULL AND expires_at = issued_at + INTERVAL 24 HOUR, 'PASS', 'FAIL')
FROM media_upload WHERE id = 930001;
UPDATE media_upload SET status = 'READY', mime_type = 'image/png', byte_count = 100, pixel_width = 10, pixel_height = 10,
    content_sha256 = @h, validation_version = 1, gmt_modified = @n WHERE id = 930001;
-- EXPECT_PASS
SELECT IF(status = 'READY' AND validation_version = 1, 'PASS', 'FAIL') FROM media_upload WHERE id = 930001;
UPDATE media_upload SET status = 'BOUND', message_id = 930001, message_media_id = 930001, gmt_modified = @n WHERE id = 930001;
-- EXPECT_PASS
SELECT IF(status = 'BOUND' AND message_media_id = 930001, 'PASS', 'FAIL') FROM media_upload WHERE id = 930001;

-- EXPECT_ERROR 1062 uk_upload_id
INSERT INTO media_upload (
    upload_id, user_id, upload_request_key, upload_request_sha256, employee_id, employee_release_id,
    session_id, storage_profile, object_key, media_kind, status, issued_at, expires_at, gmt_create, gmt_modified
) VALUES (
    @uid, 930001, @rk2, @h2, 1, 1, 930001, 'synthetic-private-v1', 'synthetic/upload/dup-id.png',
    'IMAGE', 'ISSUED', @n, @n + INTERVAL 24 HOUR, @n, @n
);
-- EXPECT_ERROR 1062 uk_user_upload_request
INSERT INTO media_upload (
    upload_id, user_id, upload_request_key, upload_request_sha256, employee_id, employee_release_id,
    session_id, storage_profile, object_key, media_kind, status, issued_at, expires_at, gmt_create, gmt_modified
) VALUES (
    @uid2, 930001, @rk, @h2, 1, 1, 930001, 'synthetic-private-v1', 'synthetic/upload/dup-req.png',
    'IMAGE', 'ISSUED', @n, @n + INTERVAL 24 HOUR, @n, @n
);
-- EXPECT_ERROR 1062 uk_storage_profile_object_key
INSERT INTO media_upload (
    upload_id, user_id, upload_request_key, upload_request_sha256, employee_id, employee_release_id,
    session_id, storage_profile, object_key, media_kind, status, issued_at, expires_at, gmt_create, gmt_modified
) VALUES (
    UNHEX(REPEAT('3a', 16)), 930001, UNHEX(REPEAT('3b', 16)), @h2, 1, 1, 930001,
    'synthetic-private-v1', 'synthetic/upload/a.png', 'IMAGE', 'ISSUED', @n, @n + INTERVAL 24 HOUR, @n, @n
);
-- EXPECT_ERROR 1062 uk_message_media_id
INSERT INTO media_upload (
    upload_id, user_id, upload_request_key, upload_request_sha256, employee_id, employee_release_id,
    session_id, storage_profile, object_key, media_kind, status, issued_at, expires_at,
    mime_type, byte_count, pixel_width, pixel_height, content_sha256, validation_version,
    message_id, message_media_id, gmt_create, gmt_modified
) VALUES (
    UNHEX(REPEAT('3c', 16)), 930001, UNHEX(REPEAT('3d', 16)), @h2, 1, 1, 930001,
    'synthetic-private-v1', 'synthetic/upload/bound-dup.png', 'IMAGE', 'BOUND', @n, @n + INTERVAL 24 HOUR,
    'image/png', 100, 10, 10, @h, 1, 930001, 930001, @n, @n
);
-- EXPECT_ERROR 3819 chk_media_upload_target
INSERT INTO media_upload (
    upload_id, user_id, upload_request_key, upload_request_sha256, employee_id, employee_release_id,
    storage_profile, object_key, media_kind, status, issued_at, expires_at, gmt_create, gmt_modified
) VALUES (
    UNHEX(REPEAT('3e', 16)), 930001, UNHEX(REPEAT('3f', 16)), @h2, 1, 1,
    'synthetic-private-v1', 'synthetic/upload/no-target.png', 'IMAGE', 'ISSUED', @n, @n + INTERVAL 24 HOUR, @n, @n
);
-- EXPECT_ERROR 3819 chk_media_upload_target
INSERT INTO media_upload (
    upload_id, user_id, upload_request_key, upload_request_sha256, employee_id, employee_release_id,
    session_id, session_intent_id, storage_profile, object_key, media_kind, status, issued_at, expires_at, gmt_create, gmt_modified
) VALUES (
    UNHEX(REPEAT('40', 16)), 930001, UNHEX(REPEAT('41', 16)), @h2, 1, 1, 930001, 930001,
    'synthetic-private-v1', 'synthetic/upload/both-target.png', 'IMAGE', 'ISSUED', @n, @n + INTERVAL 24 HOUR, @n, @n
);
-- EXPECT_ERROR 3819 chk_media_upload_ttl
INSERT INTO media_upload (
    upload_id, user_id, upload_request_key, upload_request_sha256, employee_id, employee_release_id,
    session_id, storage_profile, object_key, media_kind, status, issued_at, expires_at, gmt_create, gmt_modified
) VALUES (
    UNHEX(REPEAT('42', 16)), 930001, UNHEX(REPEAT('43', 16)), @h2, 1, 1, 930001,
    'synthetic-private-v1', 'synthetic/upload/bad-ttl.png', 'IMAGE', 'ISSUED', @n, @n + INTERVAL 25 HOUR, @n, @n
);
-- EXPECT_ERROR 3819 chk_media_upload_ready_facts
UPDATE media_upload SET status = 'READY', message_id = NULL, message_media_id = NULL, mime_type = NULL,
    byte_count = NULL, pixel_width = NULL, pixel_height = NULL, content_sha256 = NULL, validation_version = 0
WHERE id = 930001;
-- 恢复 BOUND 合法态供后续探针
UPDATE media_upload SET status = 'BOUND', mime_type = 'image/png', byte_count = 100, pixel_width = 10, pixel_height = 10,
    content_sha256 = @h, validation_version = 1, message_id = 930001, message_media_id = 930001 WHERE id = 930001;
-- EXPECT_ERROR 3819 chk_media_upload_bound_pair
UPDATE media_upload SET message_media_id = NULL WHERE id = 930001;
-- EXPECT_ERROR 3819 chk_media_upload_kind
UPDATE media_upload SET media_kind = 'AUDIO' WHERE id = 930001;
-- EXPECT_ERROR 3819 chk_media_upload_no_ready_after_delete_fail
UPDATE media_upload SET status = 'READY', message_id = NULL, message_media_id = NULL, deletion_status = 'PENDING' WHERE id = 930001;

INSERT INTO media_upload (
    id, upload_id, user_id, upload_request_key, upload_request_sha256, employee_id, employee_release_id,
    session_id, storage_profile, object_key, media_kind, status, issued_at, expires_at,
    mime_type, byte_count, pixel_width, pixel_height, content_sha256, validation_version,
    reason_code, gmt_create, gmt_modified
) VALUES (
    930002, UNHEX(REPEAT('50', 16)), 930001, UNHEX(REPEAT('51', 16)), @h2, 1, 1, 930001,
    'synthetic-private-v1', 'synthetic/upload/blocked.png', 'IMAGE', 'BLOCKED', @n, @n + INTERVAL 24 HOUR,
    NULL, NULL, NULL, NULL, NULL, 0, 'EXPIRED', @n, @n
);
-- EXPECT_PASS
SELECT IF(status = 'BLOCKED' AND reason_code = 'EXPIRED', 'PASS', 'FAIL') FROM media_upload WHERE id = 930002;
-- EXPECT_ERROR 3819 chk_media_upload_blocked_reason
UPDATE media_upload SET reason_code = NULL WHERE id = 930002;

-- —— ordinary_submission：纯文字登记、墓碑、接纳 ——
INSERT INTO ordinary_submission (
    id, user_id, submission_key, has_registered_intent, target_session_id, employee_id,
    submission_sha256, upload_ref_json, status, gmt_create, gmt_modified
) VALUES (
    930001, 930001, @sk, 1, 930001, 1, @h, JSON_ARRAY(), 'REGISTERED', @n, @n
);
-- EXPECT_PASS
SELECT IF(has_registered_intent = 1 AND JSON_LENGTH(upload_ref_json) = 0 AND status = 'REGISTERED', 'PASS', 'FAIL')
FROM ordinary_submission WHERE id = 930001;
INSERT INTO ordinary_submission (
    id, user_id, submission_key, has_registered_intent, status, closed_at, reason_code, close_request_key, close_request_sha256, gmt_create, gmt_modified
) VALUES (
    930002, 930001, @sk2, 0, 'CLOSED_UNACCEPTED', @n, 'USER_CLOSE', UNHEX(REPEAT('c1', 16)), @h2, @n, @n
);
-- EXPECT_PASS
SELECT IF(has_registered_intent = 0 AND employee_id IS NULL AND upload_ref_json IS NULL AND status = 'CLOSED_UNACCEPTED', 'PASS', 'FAIL')
FROM ordinary_submission WHERE id = 930002;
UPDATE ordinary_submission SET status = 'ACCEPTED', accepted_session_id = 930001, accepted_message_id = 930001,
    accepted_run_id = 930001, accepted_at = @n, gmt_modified = @n WHERE id = 930001;
-- EXPECT_PASS
SELECT IF(status = 'ACCEPTED' AND accepted_message_id = 930001, 'PASS', 'FAIL') FROM ordinary_submission WHERE id = 930001;

INSERT INTO ordinary_submission (
    id, user_id, submission_key, has_registered_intent, session_intent_id, employee_id,
    submission_sha256, upload_ref_json, status, gmt_create, gmt_modified
) VALUES (
    930003, 930001, @sk3, 1, 930001, 1, @h2, JSON_ARRAY(LOWER(HEX(@uid))), 'REGISTERED', @n, @n
);
-- EXPECT_PASS
SELECT IF(target_session_id IS NULL AND session_intent_id = 930001 AND JSON_LENGTH(upload_ref_json) = 1, 'PASS', 'FAIL')
FROM ordinary_submission WHERE id = 930003;

-- EXPECT_ERROR 1062 uk_user_submission_key
INSERT INTO ordinary_submission (
    user_id, submission_key, has_registered_intent, target_session_id, employee_id,
    submission_sha256, upload_ref_json, status, gmt_create, gmt_modified
) VALUES (930001, @sk, 1, 930001, 1, @h, JSON_ARRAY(), 'REGISTERED', @n, @n);
-- EXPECT_ERROR 3819 chk_ordinary_submission_tombstone|chk_ordinary_submission_open
UPDATE ordinary_submission SET status = 'REGISTERED', closed_at = NULL, reason_code = NULL WHERE id = 930002;
-- EXPECT_ERROR 3819 chk_ordinary_submission_intent
UPDATE ordinary_submission SET target_session_id = 930001 WHERE id = 930003;
-- EXPECT_ERROR 3819 chk_ordinary_submission_accepted
UPDATE ordinary_submission SET status = 'ACCEPTED', accepted_session_id = NULL, accepted_message_id = NULL,
    accepted_run_id = NULL, accepted_at = NULL WHERE id = 930003;
-- EXPECT_ERROR 3819 chk_ordinary_submission_tombstone
UPDATE ordinary_submission SET has_registered_intent = 0, employee_id = NULL, submission_sha256 = NULL,
    upload_ref_json = NULL, target_session_id = NULL, session_intent_id = NULL,
    status = 'CLOSED_UNACCEPTED', closed_at = @n, reason_code = 'X' WHERE id = 930001;
-- EXPECT_ERROR 1062 uk_accepted_message_id
INSERT INTO ordinary_submission (
    user_id, submission_key, has_registered_intent, target_session_id, employee_id,
    submission_sha256, upload_ref_json, status, accepted_session_id, accepted_message_id, accepted_run_id, accepted_at,
    gmt_create, gmt_modified
) VALUES (
    930001, UNHEX(REPEAT('de', 16)), 1, 930001, 1, @h, JSON_ARRAY(), 'ACCEPTED', 930001, 930001, 930099, @n, @n, @n
);

-- EXPECT_PASS
SELECT IF(
    (SELECT COUNT(*) FROM session_intent) = 2
    AND (SELECT COUNT(*) FROM media_upload) = 2
    AND (SELECT COUNT(*) FROM ordinary_submission) = 3
    AND (SELECT COUNT(*) FROM ordinary_submission WHERE has_registered_intent = 0) = 1
    AND (SELECT COUNT(*) FROM ordinary_submission WHERE status = 'ACCEPTED') = 1,
    'PASS', 'FAIL'
);

ROLLBACK;
