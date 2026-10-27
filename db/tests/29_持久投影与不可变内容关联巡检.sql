-- 只读跨行巡检；所有计数在合法基线上必须为 0。不修复、不改读最新版本。
-- 只对已建立投影的 Session 及新增投影记录负责；旧夹具 NULL/0 不冒充阶段 2 已接入。
SELECT 'projection_prefix', COUNT(*) FROM (
 SELECT s.id FROM business_session s LEFT JOIN session_projection_event e
 ON e.session_id=s.id AND e.stream_id=s.projection_stream_id
 GROUP BY s.id,s.last_projection_seq
 HAVING COUNT(e.id)<>s.last_projection_seq OR COALESCE(MAX(e.seq),0)<>s.last_projection_seq
 UNION ALL
 SELECT e.session_id FROM session_projection_event e LEFT JOIN business_session s ON s.id=e.session_id
 WHERE s.id IS NULL OR NOT(e.stream_id<=>s.projection_stream_id)
) bad;

SELECT 'projection_batch_integrity', COUNT(*) FROM session_projection_event e
WHERE NOT(e.batch_sha256<=>UNHEX(SHA2(e.batch_payload,256)))
 OR e.byte_count<>OCTET_LENGTH(e.batch_payload)
 OR NOT(JSON_UNQUOTE(JSON_EXTRACT(CONVERT(e.batch_payload USING utf8mb4),'$.protocolVersion'))<=>e.protocol_version)
 OR NOT(JSON_UNQUOTE(JSON_EXTRACT(CONVERT(e.batch_payload USING utf8mb4),'$.sessionId'))<=>CAST(e.session_id AS CHAR))
 OR NOT(UNHEX(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(CONVERT(e.batch_payload USING utf8mb4),'$.streamId')),'-',''))<=>e.stream_id)
 OR NOT(JSON_UNQUOTE(JSON_EXTRACT(CONVERT(e.batch_payload USING utf8mb4),'$.seq'))<=>CAST(e.seq AS CHAR))
 OR NOT(JSON_UNQUOTE(JSON_EXTRACT(CONVERT(e.batch_payload USING utf8mb4),'$.previousSeq'))<=>CAST(e.previous_seq AS CHAR))
 OR NOT(JSON_TYPE(JSON_EXTRACT(CONVERT(e.batch_payload USING utf8mb4),'$.sessionId'))<=>'STRING')
 OR NOT(JSON_TYPE(JSON_EXTRACT(CONVERT(e.batch_payload USING utf8mb4),'$.seq'))<=>'STRING')
 OR NOT(JSON_TYPE(JSON_EXTRACT(CONVERT(e.batch_payload USING utf8mb4),'$.previousSeq'))<=>'STRING')
 OR COALESCE(CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(CONVERT(e.batch_payload USING utf8mb4),'$.cursor'))),0)=0
 OR NOT(JSON_UNQUOTE(JSON_EXTRACT(CONVERT(e.batch_payload USING utf8mb4),'$.occurredAt'))<=>DATE_FORMAT(e.occurred_at,'%Y-%m-%dT%H:%i:%s.%fZ'))
 OR NOT(JSON_TYPE(JSON_EXTRACT(CONVERT(e.batch_payload USING utf8mb4),'$.changes'))<=>'ARRAY')
 OR NOT(JSON_LENGTH(JSON_EXTRACT(CONVERT(e.batch_payload USING utf8mb4),'$.changes'))<=>e.change_count);

SELECT 'projection_content_owner', COUNT(*) FROM projection_content c
LEFT JOIN business_session s ON s.id=c.session_id LEFT JOIN business_run r ON r.id=c.run_id
WHERE s.id IS NULL OR r.id IS NULL OR c.owner_user_id<>s.user_id OR r.actor_user_id<>s.user_id
 OR r.session_id<>s.id OR r.employee_release_id<>s.employee_release_id OR NOT(c.stream_id<=>s.projection_stream_id);

SELECT 'projection_version_chain', COUNT(*) FROM projection_content c
LEFT JOIN projection_content_version firstv ON firstv.content_id=c.content_id AND firstv.content_version=1
LEFT JOIN projection_content_version cur ON cur.content_id=c.content_id AND cur.content_version=c.current_version
WHERE firstv.content_id IS NULL OR cur.content_id IS NULL
 OR c.first_seq<>firstv.projection_seq OR c.first_position<>firstv.projection_position
 OR c.current_version<>(SELECT COUNT(*) FROM projection_content_version v WHERE v.content_id=c.content_id)
 OR c.current_version<>(SELECT MAX(v.content_version) FROM projection_content_version v WHERE v.content_id=c.content_id)
 OR NOT(c.status<=>cur.content_status) OR NOT(c.close_reason<=>cur.close_reason)
 OR NOT(c.replacement_message_id<=>cur.replacement_message_id)
 OR EXISTS (SELECT 1 FROM projection_content_version v
 LEFT JOIN projection_content_version p ON p.content_id=v.content_id AND p.content_version=v.base_version
 WHERE v.content_id=c.content_id AND v.content_version>1 AND (p.content_id IS NULL
 OR v.projection_seq<p.projection_seq OR (v.projection_seq=p.projection_seq AND v.projection_position<=p.projection_position)
 OR p.content_status='PROMOTED' OR (p.content_status='CLOSED' AND (v.change_kind='TEXT_APPEND' OR v.content_status='OPEN'))
 OR (v.change_kind='TEXT_APPEND' AND (c.content_kind<>'TEXT' OR p.content_status<>'OPEN'))));

SELECT 'projection_version_payload', COUNT(*) FROM projection_content_version v
LEFT JOIN projection_content c ON c.content_id=v.content_id
WHERE c.content_id IS NULL OR (v.payload_storage='INLINE' AND NOT(v.body_sha256<=>UNHEX(SHA2(v.body,256))))
 OR (c.content_kind<>'TEXT' AND v.byte_count>65536)
 OR (c.content_kind='TEXT' AND (v.body_format NOT IN ('plain-text','markdown-v1') OR v.source_kind NOT IN ('RUN_PROGRESS','MESSAGE_BLOCK')))
 OR (c.content_kind='INTERACTION_REF' AND (v.source_kind<>'INTERACTION_REVISION' OR v.body_format<>'json-v1'))
 OR (c.content_kind='IMAGE_REF' AND v.source_kind<>'MESSAGE_MEDIA')
 OR (c.content_kind='RECEIPT' AND v.source_kind<>'TOOL_OPERATION')
 OR (c.content_kind<>'TEXT' AND v.body_format<>'json-v1')
 OR (v.body_format='json-v1' AND v.payload_storage='INLINE' AND NOT JSON_VALID(CONVERT(v.body USING utf8mb4)))
 OR (c.content_kind='TABLE' AND (v.table_rows IS NULL OR v.table_columns IS NULL))
 OR (c.content_kind<>'TABLE' AND (v.table_rows IS NOT NULL OR v.table_columns IS NOT NULL))
 OR (v.payload_storage='INLINE' AND v.body_format='json-v1' AND (
   (c.content_kind='TABLE' AND (
     NOT(JSON_TYPE(JSON_EXTRACT(IF(JSON_VALID(CONVERT(v.body USING utf8mb4)),CONVERT(v.body USING utf8mb4),'{}'),'$.rows'))<=>'ARRAY')
     OR NOT(JSON_TYPE(JSON_EXTRACT(IF(JSON_VALID(CONVERT(v.body USING utf8mb4)),CONVERT(v.body USING utf8mb4),'{}'),'$.columns'))<=>'ARRAY')
     OR NOT(JSON_LENGTH(JSON_EXTRACT(IF(JSON_VALID(CONVERT(v.body USING utf8mb4)),CONVERT(v.body USING utf8mb4),'{}'),'$.rows'))<=>v.table_rows)
     OR NOT(JSON_LENGTH(JSON_EXTRACT(IF(JSON_VALID(CONVERT(v.body USING utf8mb4)),CONVERT(v.body USING utf8mb4),'{}'),'$.columns'))<=>v.table_columns)))
   OR (JSON_CONTAINS_PATH(IF(JSON_VALID(CONVERT(v.body USING utf8mb4)),CONVERT(v.body USING utf8mb4),'{}'),'one','$.summary') AND
     OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(CONVERT(v.body USING utf8mb4)),CONVERT(v.body USING utf8mb4),'{}'),'$.summary')))<>v.summary_byte_count)));

-- 正反向核对：每个版本准确绑定一个批内位置；每个内容变更必须解析到指定版本。
WITH changes AS (
 SELECT e.session_id,e.stream_id,e.seq,j.* FROM session_projection_event e,
 JSON_TABLE(CONVERT(e.batch_payload USING utf8mb4),'$.changes[*]' COLUMNS (
 pos FOR ORDINALITY, kind VARCHAR(32) PATH '$.type', cid VARCHAR(64) PATH '$.contentId',
 ver VARCHAR(32) PATH '$.contentVersion', basever VARCHAR(32) PATH '$.baseContentVersion',
 firstseq VARCHAR(32) PATH '$.firstSeq', firstpos INT PATH '$.firstPosition',
 cstatus VARCHAR(16) PATH '$.status', reason VARCHAR(16) PATH '$.closeReason',
 replacement VARCHAR(32) PATH '$.replacementMessageId', bytes INT PATH '$.byteCount',
 sha VARCHAR(64) PATH '$.sha256', fmt VARCHAR(24) PATH '$.bodyFormat', delta LONGTEXT PATH '$.delta')) j
)
SELECT 'projection_version_event', COUNT(*) FROM (
 SELECT v.content_id FROM projection_content_version v LEFT JOIN projection_content c ON c.content_id=v.content_id
 LEFT JOIN changes j ON j.session_id=c.session_id AND j.stream_id=c.stream_id AND j.seq=v.projection_seq AND j.pos=v.projection_position
 LEFT JOIN projection_content_version p ON p.content_id=v.content_id AND p.content_version=v.base_version
 WHERE j.pos IS NULL OR NOT(j.kind<=>v.change_kind) OR NOT(UNHEX(REPLACE(j.cid,'-',''))<=>v.content_id)
 OR NOT(j.ver<=>CAST(v.content_version AS CHAR)) OR NOT(j.basever<=>CAST(v.base_version AS CHAR))
 OR NOT(j.firstseq<=>CAST(c.first_seq AS CHAR)) OR NOT(j.firstpos<=>c.first_position)
 OR NOT(j.cstatus<=>v.content_status) OR NOT(j.reason<=>v.close_reason)
 OR NOT(j.replacement<=>CAST(v.replacement_message_id AS CHAR)) OR NOT(j.bytes<=>v.byte_count)
 OR NOT(UNHEX(j.sha)<=>v.body_sha256) OR NOT(j.fmt<=>v.body_format)
 OR (v.change_kind='TEXT_APPEND' AND (p.payload_storage<>'INLINE' OR v.payload_storage<>'INLINE' OR j.delta IS NULL
     OR NOT(v.body<=>CONCAT(p.body,CONVERT(j.delta USING binary)))))
 UNION ALL
 SELECT UNHEX(REPLACE(j.cid,'-','')) FROM changes j LEFT JOIN projection_content c ON c.content_id=UNHEX(REPLACE(j.cid,'-',''))
 LEFT JOIN projection_content_version v ON v.content_id=c.content_id AND CAST(v.content_version AS CHAR)=j.ver
 WHERE j.kind IN ('CONTENT_SET','TEXT_APPEND','CONTENT_CLOSE') AND (v.content_id IS NULL
 OR c.session_id<>j.session_id OR c.stream_id<>j.stream_id OR v.projection_seq<>j.seq OR v.projection_position<>j.pos)
) bad;

SELECT 'projection_source_reference', COUNT(*) FROM projection_content_version v
JOIN projection_content c ON c.content_id=v.content_id
LEFT JOIN message_content_block b ON v.source_kind='MESSAGE_BLOCK' AND b.id=v.source_id
LEFT JOIN business_message m ON m.id=b.message_id
LEFT JOIN message_media media ON v.source_kind='MESSAGE_MEDIA' AND media.id=v.source_id
LEFT JOIN business_message mm ON mm.id=media.message_id
LEFT JOIN tool_operation o ON v.source_kind='TOOL_OPERATION' AND o.id=v.source_id
LEFT JOIN run_progress p ON v.source_kind='RUN_PROGRESS' AND p.id=v.source_id
WHERE (v.source_kind='MESSAGE_BLOCK' AND (b.id IS NULL OR b.block_kind<>'TEXT' OR m.id IS NULL
 OR m.user_id<>c.owner_user_id OR m.session_id<>c.session_id OR COALESCE(m.initial_run_id,m.produced_by_run_id)<>c.run_id
 OR NOT(v.body_sha256<=>UNHEX(SHA2(CONVERT(b.text_content USING binary),256))) OR v.byte_count<>OCTET_LENGTH(b.text_content)
 OR (m.sender_kind='USER' AND v.body_format<>'plain-text') OR (m.sender_kind='ASSISTANT' AND v.body_format<>'markdown-v1')))
 OR (v.source_kind='MESSAGE_MEDIA' AND (media.id IS NULL OR media.user_id<>c.owner_user_id OR mm.session_id<>c.session_id
 OR COALESCE(mm.initial_run_id,mm.produced_by_run_id)<>c.run_id OR media.media_kind<>'IMAGE'))
 OR (v.source_kind='TOOL_OPERATION' AND (o.id IS NULL OR o.run_id<>c.run_id OR o.actor_user_id<>c.owner_user_id))
 OR (v.source_kind='RUN_PROGRESS' AND (p.id IS NULL OR p.run_id<>c.run_id OR NOT(p.content_id<=>c.content_id)
 OR NOT(p.content_version<=>v.content_version)));

SELECT 'projection_interaction_reference', COUNT(*) FROM projection_content_version v
JOIN projection_content c ON c.content_id=v.content_id
LEFT JOIN interaction_request q ON q.id=v.source_id
LEFT JOIN interaction_revision r ON r.id=v.source_revision_id
LEFT JOIN business_session s ON s.id=c.session_id
WHERE v.source_kind='INTERACTION_REVISION' AND (c.content_kind<>'INTERACTION_REF' OR q.id IS NULL OR r.id IS NULL
 OR q.owner_user_id<>c.owner_user_id OR q.session_id<>c.session_id OR q.run_id<>c.run_id
 OR q.employee_release_id<>s.employee_release_id OR r.request_id<>q.id OR r.interaction_type<>q.interaction_type
 OR v.interaction_type<>q.interaction_type OR NOT(v.body_sha256<=>r.snapshot_sha256)
 OR NOT(r.snapshot_sha256<=>UNHEX(SHA2(r.snapshot_body,256))) OR v.byte_count<>OCTET_LENGTH(r.snapshot_body)
 OR (v.payload_storage='INLINE' AND NOT(v.body<=>r.snapshot_body)));

SELECT 'projection_progress_reference', COUNT(*) FROM run_progress p
LEFT JOIN projection_content c ON c.content_id=p.content_id
LEFT JOIN projection_content_version v ON v.content_id=p.content_id AND v.content_version=p.content_version
LEFT JOIN business_run r ON r.id=p.run_id LEFT JOIN business_session s ON s.id=r.session_id
LEFT JOIN runtime_sdk_call k ON k.sdk_call_key=p.sdk_call_key
LEFT JOIN tool_operation o ON o.id=p.source_id
LEFT JOIN interaction_request q ON q.id=p.source_id
WHERE p.payload_version IS NOT NULL AND (c.content_id IS NULL OR v.content_id IS NULL OR r.id IS NULL
 OR p.run_id<>c.run_id OR NOT(p.projection_stream_id<=>c.stream_id) OR p.first_projection_seq<>c.first_seq
 OR p.first_projection_position<>c.first_position OR p.projection_seq<>v.projection_seq OR p.projection_position<>v.projection_position
 OR NOT(p.content_status<=>v.content_status) OR NOT(p.close_reason<=>v.close_reason)
 OR NOT(p.replacement_message_id<=>v.replacement_message_id)
 OR (p.source_kind='PLATFORM_STAGE' AND p.source_id<>p.run_id)
 OR (p.source_kind='MODEL_TEXT' AND (k.id IS NULL OR k.id<>p.source_id))
 OR (p.sdk_call_key IS NOT NULL AND (k.id IS NULL OR k.run_id<>p.run_id OR k.session_id<>c.session_id
 OR k.user_id<>c.owner_user_id OR k.employee_release_id<>r.employee_release_id OR k.state_session_key<>s.state_session_key))
 OR (p.source_kind='TOOL_FACT' AND (o.id IS NULL OR o.run_id<>p.run_id OR o.actor_user_id<>c.owner_user_id))
 OR (p.source_kind='INTERACTION_FACT' AND (q.id IS NULL OR q.run_id<>p.run_id OR q.owner_user_id<>c.owner_user_id)));

-- 收尾的消息、Run 成功、被提升版本及 replacesContentIds 必须在同一批；原消息唯一键继续生效。
WITH commits AS (
 SELECT e.session_id,e.stream_id,e.seq,j.* FROM session_projection_event e,
 JSON_TABLE(CONVERT(e.batch_payload USING utf8mb4),'$.changes[*]' COLUMNS (
 kind VARCHAR(32) PATH '$.type', mid VARCHAR(32) PATH '$.messageId', rid VARCHAR(32) PATH '$.runId',
 sender VARCHAR(16) PATH '$.senderKind', msgno VARCHAR(32) PATH '$.messageNo', replacements JSON PATH '$.replacesContentIds')) j
 WHERE j.kind='MESSAGE_COMMIT'
), successes AS (
 SELECT e.session_id,e.stream_id,e.seq,j.rid FROM session_projection_event e,
 JSON_TABLE(CONVERT(e.batch_payload USING utf8mb4),'$.changes[*]' COLUMNS (
 kind VARCHAR(32) PATH '$.type', rid VARCHAR(32) PATH '$.runId', rstatus VARCHAR(24) PATH '$.status')) j
 WHERE j.kind='RUN_SET' AND j.rstatus='SUCCEEDED'
)
SELECT 'projection_success_promotion', COUNT(*) FROM (
 SELECT c.content_id AS bad FROM projection_content c
 JOIN projection_content_version v ON v.content_id=c.content_id AND v.content_version=c.current_version
 LEFT JOIN business_message m ON m.id=c.replacement_message_id LEFT JOIN business_run r ON r.id=c.run_id
 WHERE c.status='PROMOTED' AND (m.id IS NULL OR m.sender_kind<>'ASSISTANT' OR m.produced_by_run_id<>c.run_id
 OR m.session_id<>c.session_id OR m.user_id<>c.owner_user_id OR r.status<>'SUCCEEDED'
 OR NOT EXISTS (SELECT 1 FROM commits j WHERE j.mid=CAST(m.id AS CHAR) AND j.session_id=c.session_id
 AND j.stream_id=c.stream_id AND j.seq=v.projection_seq AND JSON_CONTAINS(j.replacements,JSON_QUOTE(BIN_TO_UUID(c.content_id))))
 OR NOT EXISTS (SELECT 1 FROM successes j WHERE j.rid=CAST(c.run_id AS CHAR) AND j.session_id=c.session_id AND j.stream_id=c.stream_id AND j.seq=v.projection_seq))
 UNION ALL
 SELECT CAST(m.id AS BINARY) FROM business_message m JOIN business_session s ON s.id=m.session_id
 LEFT JOIN business_run r ON r.id=m.produced_by_run_id
 WHERE m.sender_kind='ASSISTANT' AND s.projection_stream_id IS NOT NULL AND (r.id IS NULL OR r.status<>'SUCCEEDED'
 OR m.user_id<>s.user_id OR r.actor_user_id<>s.user_id OR r.session_id<>s.id
 OR (SELECT COUNT(*) FROM commits j WHERE j.mid=CAST(m.id AS CHAR) AND j.session_id=s.id AND j.stream_id=s.projection_stream_id)<>1)
 UNION ALL
 SELECT CAST(r.id AS BINARY) FROM business_run r JOIN business_session s ON s.id=r.session_id
 WHERE r.status='SUCCEEDED' AND s.projection_stream_id IS NOT NULL AND (
 (SELECT COUNT(*) FROM business_message m WHERE m.produced_by_run_id=r.id AND m.sender_kind='ASSISTANT')<>1
 OR (SELECT COUNT(*) FROM successes j WHERE j.rid=CAST(r.id AS CHAR) AND j.session_id=s.id AND j.stream_id=s.projection_stream_id)<>1
 OR NOT EXISTS (SELECT 1 FROM runtime_sdk_call k WHERE k.run_id=r.id AND k.session_id=s.id AND k.user_id=s.user_id
 AND k.employee_release_id=r.employee_release_id AND k.state_session_key=s.state_session_key AND k.status='VERIFIED'
 AND k.employee_id=s.employee_id AND k.call_no=(SELECT MAX(latest.call_no) FROM runtime_sdk_call latest WHERE latest.run_id=r.id)
 AND k.save_status='SAVED' AND k.readback_status='MATCHED' AND k.save_receipt_ref IS NOT NULL AND k.verified_at IS NOT NULL
 AND k.saved_state_sha256=k.readback_state_sha256
 AND (k.store_version_capability<>'VERSIONED' OR k.saved_store_version=k.readback_store_version)))
 UNION ALL
 SELECT CAST(j.mid AS BINARY) FROM commits j LEFT JOIN business_message m ON CAST(m.id AS CHAR)=j.mid
 LEFT JOIN business_run r ON r.id=m.produced_by_run_id
 WHERE m.id IS NULL OR NOT(j.sender<=>m.sender_kind) OR NOT(j.msgno<=>CAST(m.message_no AS CHAR)) OR m.session_id<>j.session_id
 OR (m.sender_kind='ASSISTANT' AND (NOT(j.rid<=>CAST(m.produced_by_run_id AS CHAR)) OR r.status<>'SUCCEEDED'
 OR NOT EXISTS (SELECT 1 FROM successes z WHERE z.rid=j.rid AND z.session_id=j.session_id AND z.stream_id=j.stream_id AND z.seq=j.seq)))
 UNION ALL
 SELECT CAST(j.mid AS BINARY) FROM commits j, JSON_TABLE(COALESCE(j.replacements,JSON_ARRAY()),'$[*]' COLUMNS (cid VARCHAR(64) PATH '$')) x
 LEFT JOIN projection_content c ON c.content_id=UNHEX(REPLACE(x.cid,'-',''))
 LEFT JOIN projection_content_version v ON v.content_id=c.content_id AND v.content_version=c.current_version
 WHERE c.content_id IS NULL OR c.status<>'PROMOTED' OR NOT(CAST(c.replacement_message_id AS CHAR)<=>j.mid)
 OR c.session_id<>j.session_id OR c.stream_id<>j.stream_id OR v.projection_seq<>j.seq
 UNION ALL
 SELECT CAST(j.rid AS BINARY) FROM successes j LEFT JOIN business_run r ON CAST(r.id AS CHAR)=j.rid
 WHERE r.id IS NULL OR r.status<>'SUCCEEDED' OR r.session_id<>j.session_id
) bad;

SELECT 'projection_run_text_budget',COUNT(*) FROM (
 SELECT c.run_id FROM projection_content c JOIN projection_content_version firstv ON firstv.content_id=c.content_id AND firstv.content_version=1
 JOIN projection_content_version cur ON cur.content_id=c.content_id AND cur.content_version=c.current_version
 LEFT JOIN message_content_block b ON firstv.source_kind='MESSAGE_BLOCK' AND b.id=firstv.source_id
 LEFT JOIN business_message m ON m.id=b.message_id
 WHERE c.content_kind='TEXT' AND (firstv.source_kind='RUN_PROGRESS' OR m.sender_kind='ASSISTANT')
 GROUP BY c.run_id HAVING SUM(cur.byte_count)>262144
) bad;

-- 防御性字段名巡检。受控 Adapter 的语义脱敏及递归白名单在阶段 2 另外验收。
SELECT 'projection_controlled_payload',COUNT(*) FROM (
 SELECT e.id FROM session_projection_event e, JSON_TABLE(CONVERT(e.batch_payload USING utf8mb4),'$.changes[*]'
 COLUMNS (kind VARCHAR(32) PATH '$.type')) j
 WHERE j.kind IS NULL OR j.kind NOT IN ('SESSION_SET','RUN_SET','CONTENT_SET','TEXT_APPEND','CONTENT_CLOSE','MESSAGE_COMMIT','TOOL_SET','INTERACTION_SET')
 UNION ALL
 SELECT e.id FROM session_projection_event e WHERE REGEXP_LIKE(CONVERT(e.batch_payload USING utf8mb4),
 '"(token|confirmationToken|token_ciphertext|token_nonce|ciphertext|sdkState|rawThinking|apiKey|systemPrompt)"[[:space:]]*:','i')
 UNION ALL
 SELECT v.content_version FROM projection_content_version v WHERE v.body_format='json-v1' AND v.payload_storage='INLINE'
 AND REGEXP_LIKE(CONVERT(v.body USING utf8mb4),'"(token|confirmationToken|token_ciphertext|token_nonce|ciphertext|sdkState|rawThinking|apiKey|systemPrompt)"[[:space:]]*:','i')
 UNION ALL
 SELECT p.id FROM run_progress p WHERE p.structured_payload IS NOT NULL AND REGEXP_LIKE(CAST(p.structured_payload AS CHAR CHARACTER SET utf8mb4),
 '"(token|confirmationToken|token_ciphertext|token_nonce|ciphertext|sdkState|rawThinking|apiKey|systemPrompt)"[[:space:]]*:','i')
) bad;
