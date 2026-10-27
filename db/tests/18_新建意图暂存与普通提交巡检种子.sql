-- 巡检种子：基线全零后注入跨行异常；全程由执行器包在事务中并回滚。
SET @n = UTC_TIMESTAMP(6);
SET @h = UNHEX(REPEAT('ab', 32));
SET @h2 = UNHEX(REPEAT('cd', 32));
START TRANSACTION;

INSERT INTO platform_user (id, display_name, status_changed_at, gmt_create, gmt_modified)
VALUES (931001, '巡检合成用户931001', @n, @n, @n);
INSERT INTO business_session (id, user_id, employee_id, employee_release_id, state_session_key, last_activity_at, gmt_create, gmt_modified)
VALUES (931001, 931001, 1, 1, REPEAT('c', 32), @n, @n, @n);
INSERT INTO business_message (id, session_id, user_id, employee_id, message_no, input_sequence, sender_kind, submission_key, submission_sha256, accepted_at, gmt_create, gmt_modified)
VALUES (931001, 931001, 931001, 1, 1, 1, 'USER', UNHEX(REPEAT('d1', 16)), @h, @n, @n, @n);
INSERT INTO business_message (id, session_id, user_id, employee_id, message_no, input_sequence, sender_kind, submission_key, submission_sha256, accepted_at, gmt_create, gmt_modified)
VALUES (931002, 931001, 931001, 1, 2, 2, 'USER', UNHEX(REPEAT('d9', 16)), @h2, @n, @n, @n);
INSERT INTO business_run (id, session_id, input_message_id, actor_user_id, employee_release_id, memory_generation_at_start, memory_revision_at_start, memory_policy_version, run_request_key, request_sha256, gmt_create, gmt_modified)
VALUES (931001, 931001, 931001, 931001, 1, 1, 1, 'p1', UNHEX(REPEAT('d2', 16)), @h, @n, @n);
UPDATE business_message SET initial_run_id = 931001 WHERE id = 931001;
INSERT INTO message_media (id, message_id, user_id, storage_profile, object_key, media_kind, mime_type, byte_count, content_sha256, gmt_create, gmt_modified)
VALUES (931001, 931001, 931001, 'synthetic-private-v1', 'synthetic/inspect/ok.png', 'IMAGE', 'image/png', 100, @h, @n, @n);

INSERT INTO session_intent (id, owner_user_id, client_intent_id, employee_id, employee_release_id, image_capability_sha256, image_capability_json, prepared_at, bound_session_id, first_submission_id, bound_at, gmt_create, gmt_modified)
VALUES (931001, 931001, UNHEX(REPEAT('e1', 16)), 1, 1, @h, JSON_OBJECT('maxBytes', 1000), @n, 931001, 931001, @n, @n, @n);
INSERT INTO session_intent (id, owner_user_id, client_intent_id, employee_id, employee_release_id, image_capability_sha256, image_capability_json, prepared_at, gmt_create, gmt_modified)
VALUES (931002, 931001, UNHEX(REPEAT('e2', 16)), 1, 1, @h, JSON_OBJECT('maxBytes', 1000), @n, @n, @n);

INSERT INTO ordinary_submission (
    id, user_id, submission_key, has_registered_intent, session_intent_id, employee_id,
    submission_sha256, upload_ref_json, status, accepted_session_id, accepted_message_id, accepted_run_id, accepted_at,
    gmt_create, gmt_modified
) VALUES (
    931001, 931001, UNHEX(REPEAT('d1', 16)), 1, 931001, 1, @h, JSON_ARRAY(), 'ACCEPTED',
    931001, 931001, 931001, @n, @n, @n
);
INSERT INTO ordinary_submission (
    id, user_id, submission_key, has_registered_intent, target_session_id, employee_id,
    submission_sha256, upload_ref_json, status, gmt_create, gmt_modified
) VALUES (
    931002, 931001, UNHEX(REPEAT('f1', 16)), 1, 931001, 1, @h2, JSON_ARRAY(), 'REGISTERED', @n, @n
);
INSERT INTO ordinary_submission (
    id, user_id, submission_key, has_registered_intent, status, closed_at, reason_code, close_request_key, close_request_sha256, gmt_create, gmt_modified
) VALUES (
    931003, 931001, UNHEX(REPEAT('f2', 16)), 0, 'CLOSED_UNACCEPTED', @n, 'USER_CLOSE', UNHEX(REPEAT('f3', 16)), @h2, @n, @n
);

INSERT INTO media_upload (
    id, upload_id, user_id, upload_request_key, upload_request_sha256, employee_id, employee_release_id,
    session_intent_id, storage_profile, object_key, media_kind, status, issued_at, expires_at,
    mime_type, byte_count, pixel_width, pixel_height, content_sha256, validation_version,
    message_id, message_media_id, gmt_create, gmt_modified
) VALUES (
    931001, UNHEX(REPEAT('a1', 16)), 931001, UNHEX(REPEAT('a2', 16)), @h, 1, 1, 931001,
    'synthetic-private-v1', 'synthetic/inspect/ok.png', 'IMAGE', 'BOUND', @n, @n + INTERVAL 24 HOUR,
    'image/png', 100, 10, 10, @h, 1, 931001, 931001, @n, @n
);
INSERT INTO media_upload (
    id, upload_id, user_id, upload_request_key, upload_request_sha256, employee_id, employee_release_id,
    session_id, storage_profile, object_key, media_kind, status, issued_at, expires_at,
    mime_type, byte_count, pixel_width, pixel_height, content_sha256, validation_version, gmt_create, gmt_modified
) VALUES (
    931002, UNHEX(REPEAT('a3', 16)), 931001, UNHEX(REPEAT('a4', 16)), @h2, 1, 1, 931001,
    'synthetic-private-v1', 'synthetic/inspect/ready.png', 'IMAGE', 'READY', @n, @n + INTERVAL 24 HOUR,
    'image/png', 100, 8, 8, @h2, 1, @n, @n
);

-- INSPECT_ALL_ZERO

UPDATE session_intent SET employee_release_id = 99 WHERE id = 931001;
UPDATE session_intent SET first_submission_id = 931002 WHERE id = 931001;
UPDATE media_upload SET issued_at = UTC_TIMESTAMP(6) - INTERVAL 25 HOUR,
    expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 HOUR WHERE id = 931002;
UPDATE media_upload SET message_media_id = 999001 WHERE id = 931001;
UPDATE media_upload SET deletion_status = 'PENDING' WHERE id = 931001;
UPDATE media_upload SET user_id = 999001 WHERE id = 931002;
UPDATE ordinary_submission SET accepted_message_id = 931002 WHERE id = 931001;

-- INSPECT_ALL_POSITIVE
