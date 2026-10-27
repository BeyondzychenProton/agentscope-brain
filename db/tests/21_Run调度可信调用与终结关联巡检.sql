-- Run 调度／容量／可信调用／独立终结跨行巡检；只输出 check_name, anomaly_count。
-- 唯一键与单行 CHECK 已由建表验收覆盖；此处只检跨行／引用／计数一致性。

SELECT 'capacity_held_count_mismatch' AS check_name, COUNT(*) AS anomaly_count
FROM runtime_capacity AS c
WHERE c.singleton_key = 1 AND c.occupied_count <> (
    SELECT COUNT(*) FROM runtime_dispatch WHERE permit_state = 'HELD'
)
UNION ALL
SELECT 'capacity_waiting_count_mismatch', COUNT(*)
FROM runtime_capacity AS c
WHERE c.singleton_key = 1 AND c.normal_waiting_count <> (
    SELECT COUNT(*) FROM runtime_dispatch WHERE counts_toward_waiting = 1
)
UNION ALL
SELECT 'dispatch_missing_run_or_source', COUNT(*)
FROM runtime_dispatch AS d
LEFT JOIN business_run AS r ON r.id = d.run_id
LEFT JOIN business_session AS s ON s.id = d.session_id
WHERE r.id IS NULL OR s.id IS NULL
   OR r.session_id <> d.session_id
   OR s.user_id <> d.user_id
   OR r.actor_user_id <> d.user_id
UNION ALL
SELECT 'dispatch_duplicate_held_or_stale_epoch', COUNT(*)
FROM runtime_dispatch AS d
JOIN runtime_capacity AS c ON c.singleton_key = 1
WHERE (d.permit_state = 'HELD' AND d.boot_epoch <> c.boot_epoch)
   OR (d.status = 'CLAIMED' AND d.boot_epoch < c.boot_epoch)
UNION ALL
SELECT 'sdk_call_owner_slot_mismatch', COUNT(*)
FROM runtime_sdk_call AS k
LEFT JOIN business_run AS r ON r.id = k.run_id
LEFT JOIN business_session AS s ON s.id = k.session_id
LEFT JOIN runtime_dispatch AS d ON d.id = k.dispatch_id
WHERE r.id IS NULL OR s.id IS NULL OR d.id IS NULL
   OR k.user_id <> r.actor_user_id OR k.user_id <> s.user_id OR k.user_id <> d.user_id
   OR k.session_id <> r.session_id OR k.session_id <> d.session_id
   OR k.run_id <> d.run_id
   OR k.state_session_key <> s.state_session_key
   OR k.employee_release_id <> r.employee_release_id
UNION ALL
SELECT 'sdk_call_save_readback_mismatch', COUNT(*)
FROM runtime_sdk_call
WHERE save_status = 'SAVED'
  AND status IN ('RETURNED', 'RECONCILING', 'STARTED')
  AND (
      readback_status IN ('MISMATCH', 'MISSING', 'UNCLEAR')
      OR (readback_status = 'MATCHED' AND (
          saved_state_sha256 IS NULL OR readback_state_sha256 IS NULL
          OR saved_state_sha256 <> readback_state_sha256
      ))
  )
UNION ALL
SELECT 'sdk_call_bootstrap_on_nonfirst', COUNT(*)
FROM runtime_sdk_call AS k
JOIN business_run AS r ON r.id = k.run_id
JOIN business_message AS m ON m.id = r.input_message_id
WHERE k.bootstrap_state_proof IS NOT NULL AND (
    k.call_no <> 1
    OR m.message_no <> 1
    OR m.sender_kind <> 'USER'
)
UNION ALL
SELECT 'terminalization_call_or_ownership_mismatch', COUNT(*)
FROM runtime_state_terminalization AS t
LEFT JOIN runtime_sdk_call AS k ON k.id = t.sdk_call_id
WHERE k.id IS NULL
   OR t.user_id <> k.user_id OR t.session_id <> k.session_id OR t.run_id <> k.run_id
   OR t.state_session_key <> k.state_session_key OR t.state_generation <> k.state_generation
UNION ALL
SELECT 'terminalization_applied_without_readback', COUNT(*)
FROM runtime_state_terminalization
WHERE status = 'APPLIED' AND (
    readback_status <> 'MATCHED'
    OR readback_state_sha256 IS NULL
    OR after_state_sha256 IS NULL
    OR after_state_sha256 <> readback_state_sha256
)
;
