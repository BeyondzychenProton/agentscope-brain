-- 只在专用本地开发库运行；配套验收器使用 mysql --force 继续执行预期失败语句。
-- 不使用存储过程、外键或真实人员资料。所有合成数据位于同一事务，最后回滚。
START TRANSACTION;

-- 同名员工可共存，固定管理员仍是普通平台用户表中的一员。
INSERT INTO platform_user (display_name, status_changed_at, gmt_create, gmt_modified)
VALUES ('测试管理员', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
SET @admin_id = LAST_INSERT_ID();
INSERT INTO platform_user (display_name, created_by_user_id, modified_by_user_id, status_changed_at, gmt_create, gmt_modified)
VALUES ('同名测试员工', @admin_id, @admin_id, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
SET @user_a_id = LAST_INSERT_ID();
INSERT INTO platform_user (display_name, created_by_user_id, modified_by_user_id, status_changed_at, gmt_create, gmt_modified)
VALUES ('同名测试员工', @admin_id, @admin_id, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
SET @user_b_id = LAST_INSERT_ID();

-- 凭据与用户的 1:1 关系由唯一索引保障；用户存在性由应用层保障。
INSERT INTO user_credential (user_id, password_hash, is_password_change_required, gmt_create, gmt_modified)
VALUES (@admin_id, 'TEST_ONLY_HASH', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
INSERT INTO user_credential (user_id, password_hash, is_password_change_required, gmt_create, gmt_modified)
VALUES (@user_a_id, 'TEST_ONLY_HASH', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
INSERT INTO user_credential (user_id, password_hash, is_password_change_required, gmt_create, gmt_modified)
VALUES (@user_b_id, 'TEST_ONLY_HASH', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
-- EXPECT_ERROR 1062 uk_user_id
INSERT INTO user_credential (user_id, password_hash, gmt_create, gmt_modified)
VALUES (@user_a_id, 'TEST_DUPLICATE_HASH', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

-- 单例键限制固定管理员最多一人。
INSERT INTO fixed_admin_assignment (singleton_key, user_id, initialization_ref, initialized_at, gmt_create, gmt_modified)
VALUES (1, @admin_id, 'TEST_BOOTSTRAP', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
-- EXPECT_ERROR 1062 uk_singleton_key
INSERT INTO fixed_admin_assignment (singleton_key, user_id, initialization_ref, initialized_at, gmt_create, gmt_modified)
VALUES (1, @user_a_id, 'TEST_SECOND_ADMIN', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
-- EXPECT_ERROR 3819 chk_fixed_admin_singleton
INSERT INTO fixed_admin_assignment (singleton_key, user_id, initialization_ref, initialized_at, gmt_create, gmt_modified)
VALUES (2, @user_a_id, 'TEST_BAD_KEY', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

-- 号码全局唯一，每名用户最多一个 CURRENT 号码。
INSERT INTO phone_reservation (phone_normalized, owner_user_id, reservation_status, ownership_source_ref, ownership_checked_at, reserved_at, gmt_create, gmt_modified)
VALUES ('19900000001', @admin_id, 'CURRENT', 'TEST_ROSTER', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
INSERT INTO phone_reservation (phone_normalized, owner_user_id, reservation_status, ownership_source_ref, ownership_checked_by, ownership_checked_at, reserved_at, gmt_create, gmt_modified)
VALUES ('19900000002', @user_a_id, 'CURRENT', 'TEST_ROSTER', @admin_id, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
SET @old_phone_id = LAST_INSERT_ID();
INSERT INTO phone_reservation (phone_normalized, owner_user_id, reservation_status, ownership_source_ref, ownership_checked_by, ownership_checked_at, reserved_at, gmt_create, gmt_modified)
VALUES ('19900000003', @user_b_id, 'CURRENT', 'TEST_ROSTER', @admin_id, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
-- EXPECT_ERROR 1062 uk_current_owner_user_id
INSERT INTO phone_reservation (phone_normalized, owner_user_id, reservation_status, ownership_source_ref, ownership_checked_at, reserved_at, gmt_create, gmt_modified)
VALUES ('19900000004', @user_a_id, 'CURRENT', 'TEST_ROSTER', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

-- PASSED 必须含检查号码、手机号版本、时间和成功 HTTP 状态。
INSERT INTO meeting_identity_check_state (user_id, checked_phone_id, checked_phone_revision, probe_status, probed_at, last_http_status, gmt_create, gmt_modified)
VALUES (@user_a_id, @old_phone_id, 1, 'PASSED', UTC_TIMESTAMP(6), 200, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
-- EXPECT_ERROR 3819 chk_meeting_check_passed
INSERT INTO meeting_identity_check_state (user_id, probe_status, probed_at, last_http_status, gmt_create, gmt_modified)
VALUES (@user_b_id, 'PASSED', UTC_TIMESTAMP(6), 200, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

-- EXPECT_ERROR 3819 chk_meeting_check_passed
UPDATE meeting_identity_check_state SET last_http_status = NULL WHERE user_id = @user_a_id;
-- EXPECT_ERROR 3819 chk_platform_user_versions
UPDATE platform_user SET phone_revision = 0 WHERE id = @user_a_id;
-- EXPECT_ERROR 3819 chk_platform_user_versions
UPDATE platform_user SET security_version = 0 WHERE id = @user_a_id;
-- EXPECT_ERROR 3819 chk_platform_user_versions
UPDATE platform_user SET authorization_version = 0 WHERE id = @user_a_id;

-- 改号保持 UserId，旧号转 HELD，旧探测状态作废。
UPDATE phone_reservation SET reservation_status = 'HELD', held_at = UTC_TIMESTAMP(6), gmt_modified = UTC_TIMESTAMP(6)
WHERE id = @old_phone_id AND owner_user_id = @user_a_id AND reservation_status = 'CURRENT';
INSERT INTO phone_reservation (phone_normalized, owner_user_id, reservation_status, ownership_source_ref, ownership_checked_by, ownership_checked_at, reserved_at, gmt_create, gmt_modified)
VALUES ('19900000004', @user_a_id, 'CURRENT', 'TEST_ROSTER_CORRECTION', @admin_id, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
SET @new_phone_id = LAST_INSERT_ID();
UPDATE platform_user SET phone_revision = phone_revision + 1, security_version = security_version + 1,
    modified_by_user_id = @admin_id, gmt_modified = UTC_TIMESTAMP(6)
WHERE id = @user_a_id;
UPDATE meeting_identity_check_state SET checked_phone_id = NULL, checked_phone_revision = NULL,
    probe_status = 'UNCHECKED', probed_at = NULL, last_http_status = NULL,
    version = version + 1, gmt_modified = UTC_TIMESTAMP(6)
WHERE user_id = @user_a_id;
INSERT INTO meeting_identity_check_attempt (user_id, phone_id, phone_revision, attempt_kind, result_code, http_status, observed_at, gmt_create, gmt_modified)
VALUES (@user_a_id, @old_phone_id, 1, 'PROBE', 'PASSED', 200, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
INSERT INTO identity_audit_event (actor_user_id, target_user_id, action_code, outcome, old_phone_id, new_phone_id, occurred_at, gmt_create, gmt_modified)
VALUES (@admin_id, @user_a_id, 'PHONE_CORRECTED', 'SUCCESS', @old_phone_id, @new_phone_id, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
-- EXPECT_ERROR 1062 uk_phone_normalized
INSERT INTO phone_reservation (phone_normalized, owner_user_id, reservation_status, ownership_source_ref, ownership_checked_at, reserved_at, held_at, gmt_create, gmt_modified)
VALUES ('19900000002', @user_b_id, 'HELD', 'TEST_BAD_REUSE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

-- CHECK 对非法账号状态和布尔值生效。
-- EXPECT_ERROR 3819 chk_platform_user_status
UPDATE platform_user SET account_status = 'BROKEN', gmt_modified = UTC_TIMESTAMP(6) WHERE id = @user_b_id;
-- EXPECT_ERROR 3819 chk_user_credential_change_required
UPDATE user_credential SET is_password_change_required = 2, gmt_modified = UTC_TIMESTAMP(6) WHERE user_id = @user_b_id;

-- 停用、重置凭据、重新启用，UserId 不变且安全版本持续提升。
UPDATE platform_user SET account_status = 'DISABLED', security_version = security_version + 1,
    status_changed_at = UTC_TIMESTAMP(6), modified_by_user_id = @admin_id, gmt_modified = UTC_TIMESTAMP(6)
WHERE id = @user_b_id;
UPDATE user_credential SET password_hash = 'TEST_RESET_HASH', is_password_change_required = 1,
    credential_version = credential_version + 1, gmt_modified = UTC_TIMESTAMP(6)
WHERE user_id = @user_b_id;
UPDATE platform_user SET account_status = 'ACTIVE', security_version = security_version + 1,
    status_changed_at = UTC_TIMESTAMP(6), modified_by_user_id = @admin_id, gmt_modified = UTC_TIMESTAMP(6)
WHERE id = @user_b_id;
INSERT INTO identity_audit_event (actor_user_id, target_user_id, action_code, outcome, occurred_at, gmt_create, gmt_modified)
VALUES
    (@admin_id, @user_b_id, 'USER_DISABLED', 'SUCCESS', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    (@admin_id, @user_b_id, 'PASSWORD_RESET', 'SUCCESS', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    (@admin_id, @user_b_id, 'USER_REENABLED', 'SUCCESS', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

-- 只检查数据库能表达的事实；引用完整性和运行时鉴权由应用层另行验收。
-- EXPECT_PASS
SELECT CASE WHEN
    (SELECT COUNT(*) FROM platform_user WHERE display_name = '同名测试员工') = 2
    AND (SELECT COUNT(*) FROM fixed_admin_assignment) = 1
    AND (SELECT COUNT(*) FROM phone_reservation WHERE owner_user_id = @user_a_id AND reservation_status = 'CURRENT') = 1
    AND (SELECT COUNT(*) FROM phone_reservation WHERE owner_user_id = @user_a_id AND reservation_status = 'HELD') = 1
    AND (SELECT phone_revision FROM platform_user WHERE id = @user_a_id) = 2
    AND (SELECT security_version FROM platform_user WHERE id = @user_a_id) = 2
    AND (SELECT probe_status FROM meeting_identity_check_state WHERE user_id = @user_a_id) = 'UNCHECKED'
    AND (SELECT security_version FROM platform_user WHERE id = @user_b_id) = 3
    AND (SELECT credential_version FROM user_credential WHERE user_id = @user_b_id) = 2
    AND (SELECT is_password_change_required FROM user_credential WHERE user_id = @user_b_id) = 1
    AND (SELECT COUNT(*) FROM identity_audit_event WHERE target_user_id = @user_b_id) = 3
    THEN 'PASS' ELSE 'FAIL' END AS acceptance_result;

ROLLBACK;
-- EXPECT_PASS
SELECT CASE WHEN
    (SELECT COUNT(*) FROM platform_user WHERE display_name = '测试管理员') = 0
    AND (SELECT COUNT(*) FROM phone_reservation WHERE phone_normalized = '19900000002') = 0
    THEN 'PASS' ELSE 'FAIL' END AS rollback_result;
