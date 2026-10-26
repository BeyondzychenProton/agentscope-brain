"""#29 受控 SQL 初始化库。无 CLI、HTTP、启动钩子、建库或外部服务调用。

调用者显式提供专用连接及已审核输入。连接须独占且无在途事务；此模块拥有事务。
SQL 标识符全为源码常量，所有输入使用 DB-API 参数绑定。异常只返回分类。
"""
from copy import deepcopy
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
import re
import struct
import unicodedata

import yaml

PROGRAM_VERSION = "issue29-bootstrap-v1"
SCOPE = "formal-web-phase1"


class BootstrapError(RuntimeError):
    """受控错误分类；不包含配置、SQL 参数或数据库异常原文。"""


def require(condition, code):
    if not condition:
        raise BootstrapError(code)


def canonical(value):
    """Schema v1：紧凑 UTF-8、键排序、数组保序；精确十进制，无浮点近似。"""
    if value is None:
        return b"null"
    if isinstance(value, bool):
        return b"true" if value else b"false"
    if isinstance(value, (int, Decimal)):
        require(not isinstance(value, Decimal) or value.is_finite(), "INVALID_NUMBER")
        text = format(value, "f")
        text = text.rstrip("0").rstrip(".") if "." in text else text
        return ("0" if text in ("-0", "") else text).encode("ascii")
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8", "strict")
    if isinstance(value, list):
        return b"[" + b",".join(canonical(v) for v in value) + b"]"
    require(isinstance(value, dict) and all(isinstance(k, str) for k in value), "UNSUPPORTED_JSON")
    return b"{" + b",".join(canonical(k) + b":" + canonical(value[k]) for k in sorted(value)) + b"}"


def digest(value):
    return hashlib.sha256(canonical(value)).digest()


def load_json(text):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "DUPLICATE_JSON_KEY")
            result[key] = value
        return result
    return json.loads(text, object_pairs_hook=pairs, parse_float=Decimal,
                      parse_constant=lambda _: require(False, "INVALID_NUMBER"))


def keys(obj, expected):
    require(isinstance(obj, dict) and set(obj) == set(expected.split()), "INVALID_FIELDS")


def text(value, limit=128):
    require(isinstance(value, str) and bool(value.strip()) and len(value) <= limit, "INVALID_TEXT")
    value.encode("utf-8", "strict")


def ref(value, limit=128):
    require(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/-]{0," + str(limit-1) + r"}", value), "INVALID_REFERENCE")


def resource_path(path):
    text(path, 255)
    require(path == unicodedata.normalize("NFC", path) and not any(ord(c) < 32 or ord(c) == 127 for c in path)
            and not any(c in path for c in "\\:") and all(p not in ("", ".", "..") and p == p.rstrip() for p in path.split("/"))
            and path.casefold() != "skill.md", "INVALID_RESOURCE_PATH")


class UniqueYamlLoader(yaml.SafeLoader):
    pass


def _yaml_mapping(loader, node):
    result = {}
    for key, value in node.value:
        name = loader.construct_object(key)
        require(isinstance(name, str) and name not in result, "INVALID_FRONTMATTER")
        result[name] = loader.construct_object(value)
    return result


UniqueYamlLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _yaml_mapping)


def skill_package(markdown, resources):
    text(markdown, 262144)
    require(len(markdown.encode()) <= 262144 and markdown.startswith("---\n") or
            len(markdown.encode()) <= 262144 and markdown.startswith("---\r\n"), "INVALID_SKILL_ENTRY")
    lines = markdown.splitlines(keepends=True)
    end = next((i for i in range(1, len(lines)) if lines[i].rstrip("\r\n") == "---"), None)
    require(end is not None, "INVALID_FRONTMATTER")
    metadata = yaml.load("".join(lines[1:end]), Loader=UniqueYamlLoader)
    require(isinstance(metadata, dict), "INVALID_FRONTMATTER")
    text(metadata.get("name"), 64)
    ref(metadata["name"], 64)
    text(metadata.get("description"), 2048)
    canonical(metadata)  # 无法无损表达的 metadata 拒绝，不截断。
    require(isinstance(resources, list) and len(resources) <= 100, "RESOURCE_LIMIT")
    seen = set()
    prepared = []
    for item in resources:
        keys(item, "path text")
        path, content = item["path"], item["text"]
        resource_path(path)
        require(isinstance(content, str) and len(content.encode()) <= 1048576, "RESOURCE_LIMIT")
        # MySQL 最终按 utf8mb4_0900_ai_ci 再执行唯一检查；此处保守预检常见等价路径。
        folded = "".join(c for c in unicodedata.normalize("NFKD", path.casefold()) if not unicodedata.combining(c))
        require(folded not in seen, "DUPLICATE_RESOURCE")
        seen.add(folded)
        prepared.append({"path": path, "text": content, "sha": hashlib.sha256(content.encode()).digest()})
    prepared.sort(key=lambda r: r["path"])
    def frame(value):
        return struct.pack(">Q", len(value)) + value
    parts = [frame(b"agentscope-skill-package-v1"), frame(markdown.encode()), frame(canonical(metadata))]
    for item in prepared:
        parts.extend([frame(item["path"].encode()), struct.pack(">Q", len(item["text"].encode())), frame(item["sha"])])
    require(len(markdown.encode()) + sum(len(r["text"].encode()) for r in prepared) <= 8388608, "SKILL_LIMIT")
    return {"name": metadata["name"], "description": metadata["description"], "metadata": metadata,
            "markdown": markdown, "resources": prepared, "sha": hashlib.sha256(b"".join(parts)).digest()}


def image_policy(policy, profiles):
    """发布固定的图片限额；格式与实际原件验证由后续 media 写路径负责。"""
    keys(policy, "storageProfile mimeTypes maxCount maxBytesPerImage maxBytesPerMessage maxPixelsPerImage")
    ref(policy["storageProfile"], 64)
    require(policy["storageProfile"] in {profile["id"] for profile in profiles}, "IMAGE_STORAGE_PROFILE_MISSING")
    require(isinstance(policy["mimeTypes"], list) and bool(policy["mimeTypes"])
            and all(isinstance(mime, str) and mime in ("image/jpeg", "image/png") for mime in policy["mimeTypes"])
            and len(set(policy["mimeTypes"])) == len(policy["mimeTypes"]), "INVALID_IMAGE_MIME_TYPES")
    for field in ("maxCount", "maxBytesPerImage", "maxBytesPerMessage", "maxPixelsPerImage"):
        require(type(policy[field]) is int and 0 < policy[field] <= 2147483647, "INVALID_IMAGE_LIMIT")
    require(policy["maxBytesPerImage"] <= policy["maxBytesPerMessage"]
            <= policy["maxCount"] * policy["maxBytesPerImage"], "INCONSISTENT_IMAGE_BUDGET")


def release_dependencies(config, binding_hashes):
    result = {"model": config["model"], "tools": binding_hashes,
              "workspace": config["workspace"], "memory": config["memory"]}
    if config.get("storageProfiles"):
        result["storageProfiles"] = sorted(config["storageProfiles"], key=lambda profile: profile["id"])
    if "images" in config:
        result["images"] = config["images"]
    return result


def prepare(request):
    """锁外校验和摘要；不读任何配置／资源文件，不解析秘密引用为秘密值。"""
    p = deepcopy(request)
    keys(p, "sourceRef bootstrapScope identity employee")
    ref(p["sourceRef"])
    require(p["bootstrapScope"] == SCOPE, "INVALID_SCOPE")
    identity, employee = p["identity"], p["employee"]
    keys(identity, "displayName phone ownershipSourceRef credentialRefVersion passwordHash")
    text(identity["displayName"], 100)
    require(isinstance(identity["phone"], str) and re.fullmatch(r"\+?[0-9]{6,31}", identity["phone"]), "INVALID_PHONE")
    ref(identity["ownershipSourceRef"])
    ref(identity["credentialRefVersion"])
    require(isinstance(identity["passwordHash"], str) and re.fullmatch(r"\{bcrypt\}\$2[aby]\$12\$[./A-Za-z0-9]{53}", identity["passwordHash"]), "INVALID_CREDENTIAL")
    keys(employee, "code displayName description model instructions skills tools workspace memory storageProfiles"
         + (" images" if isinstance(employee, dict) and "images" in employee else ""))
    ref(employee["code"], 64)
    text(employee["displayName"], 100)
    require(isinstance(employee["description"], str) and len(employee["description"]) <= 1000, "INVALID_TEXT")
    text(employee["instructions"], 262144)
    keys(employee["model"], "configRef modelId endpointRef credentialRef revision parameters")
    for k in ("configRef", "modelId", "endpointRef", "credentialRef", "revision"):
        ref(employee["model"][k])
    require(isinstance(employee["model"]["parameters"], dict) and
            set(employee["model"]["parameters"]) <= {"temperature", "topP", "maxTokens"}, "INVALID_MODEL_PARAMETERS")
    for k, v in employee["model"]["parameters"].items():
        require(not isinstance(v, bool) and isinstance(v, (int, Decimal)), "INVALID_MODEL_PARAMETERS")
        require((isinstance(v, int) and v > 0) if k == "maxTokens" else 0 <= v <= (2 if k == "temperature" else 1), "INVALID_MODEL_PARAMETERS")
    for k in ("workspace", "memory"):
        keys(employee[k], "policyRef revision")
        for v in employee[k].values():
            ref(v)
    require(isinstance(employee["storageProfiles"], list), "INVALID_STORAGE_PROFILES")
    for profile in employee["storageProfiles"]:
        keys(profile, "id sha256")
        ref(profile["id"], 64)
        require(re.fullmatch("[0-9a-f]{64}", profile["sha256"]) is not None, "INVALID_DIGEST")
    require(len({x["id"] for x in employee["storageProfiles"]}) == len(employee["storageProfiles"]), "DUPLICATE_STORAGE_PROFILE")
    employee["storageProfiles"].sort(key=lambda profile: profile["id"])
    if "images" in employee:
        image_policy(employee["images"], employee["storageProfiles"])
    require(isinstance(employee["skills"], list) and 1 <= len(employee["skills"]) <= 10, "INVALID_SKILLS")
    p["skills"] = []
    for entry in employee["skills"]:
        keys(entry, "markdown resources")
        p["skills"].append(skill_package(entry["markdown"], entry["resources"]))
    p["skills"].sort(key=lambda x: x["name"])
    require(len({s["name"].casefold() for s in p["skills"]}) == len(p["skills"]), "DUPLICATE_SKILL")
    require(isinstance(employee["tools"], list) and bool(employee["tools"]), "INVALID_BINDINGS")
    p["tools"] = []
    for tool in employee["tools"]:
        keys(tool, "version providerKey toolName binding")
        for k, limit in (("version", 64), ("providerKey", 64), ("toolName", 128)):
            ref(tool[k], limit)
        # 受控绑定只收固定目标／契约／Adapter／身份及策略引用，不收秘密值。
        keys(tool["binding"], "targetRef transport credentialRef inputSchemaRef adapterRef identityPolicyRef confirmationPolicyRef idempotencyPolicyRef reconciliationPolicyRef")
        for v in tool["binding"].values():
            ref(v)
        p["tools"].append({**tool, "sha": digest({"schemaVersion": 1, "binding": tool["binding"]})})
    p["tools"].sort(key=lambda x: (x["providerKey"], x["toolName"]))
    require(len({t["version"] for t in p["tools"]}) == len(p["tools"]) and
            len({(t["providerKey"], t["toolName"]) for t in p["tools"]}) == len(p["tools"]), "DUPLICATE_BINDING")
    stable = deepcopy(request)
    del stable["identity"]["passwordHash"]
    stable["employee"]["skills"].sort(key=lambda x: x["markdown"])
    for skill in stable["employee"]["skills"]:
        skill["resources"].sort(key=lambda x: x["path"])
    stable["employee"]["tools"].sort(key=lambda x: (x["providerKey"], x["toolName"]))
    stable["employee"]["storageProfiles"].sort(key=lambda x: x["id"])
    require(len(canonical(stable)) <= 16777216, "RELEASE_LIMIT")
    p["input_sha256"] = digest(stable).hex()
    return p


def execute(connection, sql, args=()):
    with connection.cursor() as cursor:
        cursor.execute(sql, args)
        if cursor.description:
            names = [d[0] for d in cursor.description]
            return [dict(zip(names, r)) for r in cursor.fetchall()]
        return cursor.lastrowid, cursor.rowcount


def insert(connection, table, values):
    # table 和列均来自源码常量；不作为公开执行 API 使用。
    timestamp = now()
    values = {**values, "gmt_create": timestamp, "gmt_modified": timestamp}
    return execute(connection, f"INSERT INTO {table} ({','.join(values)}) VALUES ({','.join(['%s']*len(values))})", tuple(values.values()))[0]


def now():
    return datetime.now(timezone.utc).replace(tzinfo=None)


def encoded(value):
    return canonical(value).decode()


def source(batch, prefix=""):
    return {prefix + "source_kind": "SYSTEM_BOOTSTRAP", prefix + "bootstrap_batch_id": batch}


def release_config(p, skills):
    e = p["employee"]
    config = {"configSchemaVersion": 1, "model": e["model"], "instructions": e["instructions"],
            "skills": skills, "tools": [{"providerKey": t["providerKey"], "toolName": t["toolName"],
                "toolBindingVersion": t["version"], "bindingSha256": t["sha"].hex()} for t in p["tools"]],
            "workspace": e["workspace"], "memory": e["memory"]}
    if e["storageProfiles"]:
        config["storageProfiles"] = e["storageProfiles"]
    if "images" in e:
        config["images"] = e["images"]
    return config


def create_facts(c, p, batch):
    i, e, t = p["identity"], p["employee"], now()
    user = insert(c, "platform_user", {"display_name": i["displayName"], "status_changed_at": t,
        **source(batch, "creation_"), **source(batch, "modification_")})
    credential = insert(c, "user_credential", {"user_id": user, "password_hash": i["passwordHash"], "bootstrap_batch_id": batch})
    phone = insert(c, "phone_reservation", {"phone_normalized": i["phone"], "owner_user_id": user,
        "reservation_status": "CURRENT", "ownership_source_ref": i["ownershipSourceRef"],
        "ownership_checked_at": t, "reserved_at": t, **source(batch, "ownership_")})
    employee = insert(c, "digital_employee", {"employee_code": e["code"], "display_name": e["displayName"],
        "description": e["description"], "bootstrap_batch_id": batch})
    bindings = []
    for tool in p["tools"]:
        binding_id = insert(c, "tool_binding_revision", {"tool_binding_version": tool["version"],
            "provider_key": tool["providerKey"], "tool_name": tool["toolName"], "binding_schema_version": 1,
            "binding_json": encoded(tool["binding"]), "binding_sha256": tool["sha"],
            "creation_source_kind": "SYSTEM_BOOTSTRAP", "bootstrap_batch_id": batch})
        bindings.append({"id": str(binding_id), "version": tool["version"]})
    dependency = digest(release_dependencies(e, [t["sha"].hex() for t in p["tools"]]))
    config = release_config(p, [{"name": s["name"], "packageSha256": s["sha"].hex()} for s in p["skills"]])
    effective_sha = digest(config)
    draft = insert(c, "employee_draft", {"employee_id": employee, "config_json": encoded({**config, "skills": e["skills"]}),
        "dependency_sha256": dependency, **source(batch, "save_")})
    release = insert(c, "employee_release", {"employee_id": employee, "release_no": 1, "source_draft_revision": 1,
        "config_schema_version": 1, "config_json": encoded(config), "dependency_sha256": dependency,
        "config_sha256": effective_sha, "manifest_sha256": digest(config), "published_at": t,
        "availability_changed_at": t, "creation_source_kind": "SYSTEM_BOOTSTRAP", "bootstrap_batch_id": batch,
        **source(batch, "availability_")})
    skill_refs = []
    for skill in p["skills"]:
        sid = insert(c, "employee_release_skill", {"employee_release_id": release, "skill_name": skill["name"],
            "description": skill["description"], "metadata_json": encoded(skill["metadata"]),
            "skill_markdown": skill["markdown"], "package_sha256": skill["sha"],
            "resource_count": len(skill["resources"]), "bootstrap_batch_id": batch})
        resources = []
        for resource in skill["resources"]:
            rid = insert(c, "employee_release_skill_resource", {"release_skill_id": sid,
                "resource_path": resource["path"], "resource_content": resource["text"],
                "content_sha256": resource["sha"], "bootstrap_batch_id": batch})
            resources.append(str(rid))
        skill_refs.append({"id": str(sid), "resources": resources})
    config["skills"] = [{"name": s["name"], "snapshotId": r["id"], "packageSha256": s["sha"].hex()}
                         for s, r in zip(p["skills"], skill_refs)]
    execute(c, "UPDATE employee_release SET config_json=%s,manifest_sha256=%s WHERE id=%s", (encoded(config), digest(config), release))
    execute(c, "UPDATE digital_employee SET current_release_id=%s,last_release_no=1 WHERE id=%s", (release, employee))
    grant = insert(c, "employee_use_grant", {"user_id": user, "employee_id": employee, "grant_status": "ACTIVE",
        "granted_at": t, **source(batch, "grant_")})
    identity_audit = []
    for action in ("USER_CREATED", "EMPLOYEE_GRANT"):
        identity_audit.append(str(insert(c, "identity_audit_event", {"target_user_id": user, "action_code": action,
            "outcome": "SUCCESS", "request_id": action, "employee_id": employee if action == "EMPLOYEE_GRANT" else None,
            "grant_id": grant if action == "EMPLOYEE_GRANT" else None, "occurred_at": t, **source(batch)})))
    audit = insert(c, "employee_configuration_audit_event", {"employee_id": employee, "action_code": "EMPLOYEE_PUBLISH",
        "outcome": "SUCCESS", "result_code": "CREATED", "draft_revision": 1, "employee_release_id": release,
        "request_id": "bootstrap/publish", "request_sha256": bytes.fromhex(p["input_sha256"]),
        "receipt_scope_kind": "SYSTEM_BOOTSTRAP", "receipt_scope_id": batch, "occurred_at": t, **source(batch)})
    return {"userId": str(user), "credentialId": str(credential), "phoneId": str(phone),
        "employeeId": str(employee), "draftId": str(draft), "releaseId": str(release), "grantId": str(grant),
        "bindings": bindings, "skills": skill_refs, "identityAuditIds": identity_audit, "configurationAuditId": str(audit),
        "inputSha256": p["input_sha256"], "credentialRefVersion": i["credentialRefVersion"], "storageProfiles": sorted(e["storageProfiles"], key=lambda x: x["id"])}


def row(c, table, identifier):
    require(isinstance(identifier, str) and re.fullmatch(r"[1-9][0-9]*", identifier), "INVALID_TARGET_ID")
    found = execute(c, f"SELECT * FROM {table} WHERE id=%s", (identifier,))
    require(len(found) == 1, "MISSING_TARGET")
    return found[0]


def check_actor(c, fact, actor, kind, batch_column, batch):
    if fact[kind] == "SYSTEM_BOOTSTRAP":
        require(fact[actor] is None and fact[batch_column] == batch, "INVALID_SYSTEM_SOURCE")
    else:
        require(fact[kind] == "USER_ACTION" and fact[actor] is not None and fact[batch_column] is None, "INVALID_USER_SOURCE")
        row(c, "platform_user", str(fact[actor]))


def read_manifest(c, batch, result):
    """独立从持久正文重算完整包／绑定／发布摘要；不使用调用者的准备缓存。"""
    user = row(c, "platform_user", result["userId"])
    require(user["creation_source_kind"] == "SYSTEM_BOOTSTRAP" and user["creation_bootstrap_batch_id"] == batch["id"], "INVALID_CREATION_SOURCE")
    check_actor(c, user, "modified_by_user_id", "modification_source_kind", "modification_bootstrap_batch_id", batch["id"])
    credential = row(c, "user_credential", result["credentialId"])
    phone = row(c, "phone_reservation", result["phoneId"])
    require(credential["user_id"] == user["id"] and credential["bootstrap_batch_id"] == batch["id"], "CREDENTIAL_OWNER_MISMATCH")
    require(execute(c, "SELECT COUNT(*) AS n FROM phone_reservation WHERE owner_user_id=%s AND reservation_status='CURRENT'", (user["id"],))[0]["n"] == 1, "MISSING_CURRENT_PHONE")
    require(execute(c, "SELECT COUNT(*) AS n FROM platform_user")[0]["n"] == 1 and
            execute(c, "SELECT COUNT(*) AS n FROM fixed_admin_assignment")[0]["n"] == 0, "NOT_SINGLE_ORDINARY_USER")
    check_actor(c, phone, "ownership_checked_by", "ownership_source_kind", "ownership_bootstrap_batch_id", batch["id"])
    employee = row(c, "digital_employee", result["employeeId"])
    release = row(c, "employee_release", result["releaseId"])
    require(employee["bootstrap_batch_id"] == batch["id"] and release["employee_id"] == employee["id"]
            and release["bootstrap_batch_id"] == batch["id"] and release["creation_source_kind"] == "SYSTEM_BOOTSTRAP", "RELEASE_OWNER_MISMATCH")
    current_release = row(c, "employee_release", str(employee["current_release_id"]))
    require(current_release["employee_id"] == employee["id"], "CURRENT_RELEASE_MISMATCH")
    draft = row(c, "employee_draft", result["draftId"])
    require(draft["employee_id"] == employee["id"], "DRAFT_OWNER_MISMATCH")
    check_actor(c, draft, "saved_by", "save_source_kind", "save_bootstrap_batch_id", batch["id"])
    check_actor(c, release, "availability_changed_by", "availability_source_kind", "availability_bootstrap_batch_id", batch["id"])
    config = load_json(release["config_json"])
    require(digest(config) == release["manifest_sha256"], "RELEASE_MANIFEST_MISMATCH")
    require(config.get("storageProfiles", []) == result["storageProfiles"], "STORAGE_PROFILE_MISMATCH")
    if "images" in config:
        image_policy(config["images"], config.get("storageProfiles", []))
    effective = deepcopy(config)
    for skill in effective["skills"]:
        skill.pop("snapshotId")
    require(digest(effective) == release["config_sha256"], "RELEASE_CONFIG_MISMATCH")
    require(len(config["skills"]) == len(result["skills"]) and len(config["tools"]) == len(result["bindings"]), "RELEASE_REFERENCE_MISMATCH")
    targets = [{"kind": "employee", "id": str(employee["id"]), "sha256": digest({"code": employee["employee_code"]}).hex()},
               {"kind": "release", "id": str(release["id"]), "sha256": digest({"employeeId": str(employee["id"]),
                   "releaseNo": str(release["release_no"]), "sourceDraftRevision": str(release["source_draft_revision"]),
                   "schemaVersion": release["config_schema_version"], "manifestSha256": release["manifest_sha256"].hex(),
                   "configSha256": release["config_sha256"].hex(), "dependencySha256": release["dependency_sha256"].hex()}).hex()}]
    binding_hashes = []
    for index, reference in enumerate(result["bindings"]):
        binding = row(c, "tool_binding_revision", reference["id"])
        actual = digest({"schemaVersion": binding["binding_schema_version"], "binding": load_json(binding["binding_json"])})
        require(binding["bootstrap_batch_id"] == batch["id"] and binding["creation_source_kind"] == "SYSTEM_BOOTSTRAP"
                and actual == binding["binding_sha256"] and binding["tool_binding_version"] == reference["version"], "BINDING_MISMATCH")
        require(config["tools"][index] == {"providerKey": binding["provider_key"], "toolName": binding["tool_name"],
                "toolBindingVersion": binding["tool_binding_version"], "bindingSha256": actual.hex()}, "BINDING_REFERENCE_MISMATCH")
        binding_hashes.append(actual.hex())
        targets.append({"kind": "binding", "id": reference["id"], "sha256": digest({"version": reference["version"],
            "providerKey": binding["provider_key"], "toolName": binding["tool_name"], "contentSha256": actual.hex()}).hex()})
    require(digest(release_dependencies(config, binding_hashes))
            == release["dependency_sha256"], "DEPENDENCY_MISMATCH")
    actual_skill_ids = [str(r["id"]) for r in execute(c, "SELECT id FROM employee_release_skill WHERE employee_release_id=%s ORDER BY id", (release["id"],))]
    require(set(actual_skill_ids) == {s["id"] for s in result["skills"]}, "UNINDEXED_SKILL")
    for index, reference in enumerate(result["skills"]):
        skill = row(c, "employee_release_skill", reference["id"])
        resources = execute(c, "SELECT * FROM employee_release_skill_resource WHERE release_skill_id=%s ORDER BY resource_path", (skill["id"],))
        require(len(resources) == skill["resource_count"] and {str(r["id"]) for r in resources} == set(reference["resources"]), "RESOURCE_REFERENCE_MISMATCH")
        for r in resources:
            require(r["bootstrap_batch_id"] == batch["id"] and hashlib.sha256(r["resource_content"].encode()).digest() == r["content_sha256"], "RESOURCE_DIGEST_MISMATCH")
            targets.append({"kind": "skill_resource", "id": str(r["id"]), "sha256": digest({"skillId": reference["id"],
                "path": r["resource_path"], "contentSha256": r["content_sha256"].hex()}).hex()})
        rebuilt = skill_package(skill["skill_markdown"], [{"path": r["resource_path"], "text": r["resource_content"]} for r in resources])
        require(skill["employee_release_id"] == release["id"] and skill["bootstrap_batch_id"] == batch["id"] and
                rebuilt["sha"] == skill["package_sha256"] and rebuilt["name"] == skill["skill_name"] and
                rebuilt["description"] == skill["description"] and rebuilt["metadata"] == load_json(skill["metadata_json"]), "SKILL_MISMATCH")
        require(config["skills"][index] == {"name": rebuilt["name"], "snapshotId": reference["id"], "packageSha256": rebuilt["sha"].hex()}, "SKILL_REFERENCE_MISMATCH")
        targets.append({"kind": "skill", "id": reference["id"], "sha256": rebuilt["sha"].hex()})
    grant = row(c, "employee_use_grant", result["grantId"])
    require(grant["user_id"] == user["id"] and grant["employee_id"] == employee["id"], "GRANT_OWNER_MISMATCH")
    check_actor(c, grant, "granted_by", "grant_source_kind", "grant_bootstrap_batch_id", batch["id"])
    if grant["grant_status"] == "REVOKED":
        row(c, "platform_user", str(grant["revoked_by"]))
    targets.append({"kind": "grant", "id": result["grantId"], "sha256": digest({"userId": result["userId"], "employeeId": result["employeeId"]}).hex()})
    receipt = row(c, "employee_configuration_audit_event", result["configurationAuditId"])
    require(receipt["actor_user_id"] is None and receipt["bootstrap_batch_id"] == batch["id"] and receipt["source_kind"] == "SYSTEM_BOOTSTRAP"
            and receipt["receipt_scope_kind"] == "SYSTEM_BOOTSTRAP" and receipt["receipt_scope_id"] == batch["id"]
            and receipt["employee_id"] == employee["id"] and receipt["employee_release_id"] == release["id"]
            and receipt["outcome"] == "SUCCESS" and receipt["action_code"] == "EMPLOYEE_PUBLISH" and receipt["result_code"] == "CREATED"
            and receipt["request_id"] == "bootstrap/publish" and receipt["request_sha256"].hex() == result["inputSha256"], "INVALID_RECEIPT")
    for identifier, action in zip(result["identityAuditIds"], ("USER_CREATED", "EMPLOYEE_GRANT"), strict=True):
        audit = row(c, "identity_audit_event", identifier)
        require(audit["actor_user_id"] is None and audit["source_kind"] == "SYSTEM_BOOTSTRAP" and audit["bootstrap_batch_id"] == batch["id"]
                and audit["target_user_id"] == user["id"] and audit["action_code"] == action and audit["outcome"] == "SUCCESS"
                and (action != "EMPLOYEE_GRANT" or (audit["employee_id"] == employee["id"] and audit["grant_id"] == grant["id"])), "INVALID_IDENTITY_AUDIT")
    require(execute(c, "SELECT COUNT(*) AS n FROM identity_audit_event WHERE bootstrap_batch_id=%s AND outcome='SUCCESS'", (batch["id"],))[0]["n"] == 2
            and execute(c, "SELECT COUNT(*) AS n FROM employee_configuration_audit_event WHERE bootstrap_batch_id=%s AND outcome='SUCCESS'", (batch["id"],))[0]["n"] == 1, "UNINDEXED_SUCCESS_AUDIT")
    targets.extend({"kind": "storage_profile", **profile} for profile in result["storageProfiles"])
    return {"sourceRef": batch["source_ref"], "bootstrapScope": batch["bootstrap_scope"],
            "identityRefs": {"userId": result["userId"], "credentialId": result["credentialId"], "phoneId": result["phoneId"],
                             "credentialRefVersion": result["credentialRefVersion"]},
            "immutableTargets": sorted(targets, key=lambda x: (x["kind"], x["id"]))}


def verify(connection, source_ref, expected_input_sha256=None):
    """只读核对；调用者在独立只读一致性事务中调用。不创建缺失数据。"""
    batches = execute(connection, "SELECT * FROM bootstrap_batch WHERE source_ref=%s", (source_ref,))
    require(len(batches) == 1 and batches[0]["status"] == "APPLIED", "BATCH_NOT_APPLIED")
    batch = batches[0]
    result = load_json(batch["result_summary"])
    if expected_input_sha256 is not None:
        require(result["inputSha256"] == expected_input_sha256, "SOURCE_CONTENT_CONFLICT")
    manifest = read_manifest(connection, batch, result)
    require(batch["manifest_version"] == 1 and batch["bootstrap_scope"] == SCOPE and batch["applied_at"] is not None
            and manifest == load_json(batch["manifest_json"]) and digest(manifest) == batch["manifest_sha256"], "BOOTSTRAP_MANIFEST_MISMATCH")
    return {"batchId": str(batch["id"]), "sourceRef": source_ref, "manifestSha256": batch["manifest_sha256"].hex(),
            "result": result}


def apply(connection, request):
    """一次受控平台事务；唯一范围锁合并竞争，提交异常一律要求独立回查。

    不捕获后换键重试，不在启动时调用，不补齐已有库。失败不另行提交 FAILED 伪批次。
    """
    p = prepare(request)
    try:
        connection.begin()
        existing = execute(connection, "SELECT * FROM bootstrap_batch WHERE source_ref=%s OR bootstrap_scope=%s", (p["sourceRef"], SCOPE))
        if existing:
            require(len(existing) == 1 and existing[0]["source_ref"] == p["sourceRef"] and existing[0]["bootstrap_scope"] == SCOPE, "SCOPE_CONFLICT")
            require(existing[0]["status"] == "APPLIED", "RECONCILIATION_REQUIRED")
            answer = verify(connection, p["sourceRef"], p["input_sha256"])
            connection.rollback()
            return answer
        # 首次竞争前结束探查快照。随后插入唯一键，等待者的独立读回必须看到胜者提交。
        connection.rollback()
        connection.begin()
        # 唯一键插入承担首次竞争；只做无变化 UPDATE，不改变原批次或结果。
        execute(connection, "SELECT LAST_INSERT_ID(0)")
        inserted_id, _ = execute(connection, """INSERT INTO bootstrap_batch
            (source_ref,bootstrap_scope,status,program_version,created_at,gmt_create,gmt_modified)
            VALUES (%s,%s,'PREPARING',%s,%s,%s,%s) ON DUPLICATE KEY UPDATE id=id""",
            (p["sourceRef"], p["bootstrapScope"], PROGRAM_VERSION, now(), now(), now()))
        batches = execute(connection, "SELECT * FROM bootstrap_batch WHERE bootstrap_scope=%s FOR UPDATE", (SCOPE,))
        require(len(batches) == 1, "SCOPE_CONFLICT")
        batch = batches[0]
        require(batch["source_ref"] == p["sourceRef"], "SCOPE_CONFLICT")
        if batch["status"] == "APPLIED":
            result = verify(connection, p["sourceRef"], p["input_sha256"])
            connection.rollback()  # 重复执行不更新任何事实。
            return result
        require(batch["status"] == "PREPARING", "RECONCILIATION_REQUIRED")
        # PREPARING 只有本函数未提交事务内可用；任何持久 PREPARING 都必须人工核对。
        require(inserted_id == batch["id"] and batch["row_version"] == 1 and batch["manifest_json"] is None, "RECONCILIATION_REQUIRED")
        tables = execute(connection, "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'")
        table_names = [r["TABLE_NAME"] if "TABLE_NAME" in r else r["table_name"] for r in tables]
        require(all(re.fullmatch(r"[a-z_]+", name) for name in table_names), "INVALID_TABLE_NAME")
        populated = sum(execute(connection, f"SELECT COUNT(*) AS n FROM {name}")[0]["n"] for name in table_names if name != "bootstrap_batch")
        require(populated == 0, "TARGET_NOT_EMPTY")
        result = create_facts(connection, p, batch["id"])
        manifest = read_manifest(connection, batch, result)
        changed = execute(connection, """UPDATE bootstrap_batch SET manifest_json=%s,manifest_sha256=%s,result_summary=%s,
            status='APPLIED',applied_at=%s,row_version=row_version+1,gmt_modified=%s
            WHERE id=%s AND status='PREPARING' AND row_version=1""",
            (encoded(manifest), digest(manifest), encoded(result), now(), now(), batch["id"]))[1]
        require(changed == 1, "BATCH_CAS_CONFLICT")
        answer = verify(connection, p["sourceRef"], p["input_sha256"])
    except BaseException as error:
        try:
            connection.rollback()
        except Exception:
            raise BootstrapError("CONNECTION_LOST_RECONCILE_ORIGINAL_SOURCE") from None
        if isinstance(error, (BootstrapError, KeyboardInterrupt, SystemExit)):
            raise
        raise BootstrapError("DATABASE_OPERATION_FAILED") from None
    try:
        connection.commit()
    except Exception:
        # 无法知道 COMMIT 是否落地；调用方必须丢弃连接，按原键独立 verify。
        raise BootstrapError("COMMIT_OUTCOME_UNKNOWN") from None
    return answer
