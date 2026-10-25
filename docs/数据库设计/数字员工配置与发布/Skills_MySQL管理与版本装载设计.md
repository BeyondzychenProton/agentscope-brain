# Skills MySQL 管理与版本装载设计

> 2026-10-20：用户已确认采用本优化方案。本文已回填当前项目，替代此前“Skill 全文直接嵌入员工发布 JSON，并强制先投影到本地目录”的实现设想；保留员工统一发布、会话固定版本及既有权限边界。
>
> 依据：[AgentScope Java Skills 存储与定义配置调研](<../../调研/Skills/AgentScope Java Skills存储与定义配置调研.md>)。源码基线为 Java `v2.0.4`、提交 `0b6743b263ba8e39eb417e35048026f1e0196548`，不是已经集成的依赖。本次只同步设计和原型说明，没有执行 DDL 或 Java 集成验收。

> 2026-10-22：正式快照的字段、路径比较、文本限额和包摘要编码已形成[员工建表基线](一期员工配置发布授权建表规格.md)及 05 号 DDL，并通过[全库隔离 SQL 验收](员工配置发布授权与全库建表验收记录.md)。这些平台规则不等于框架原生限制；完整文本包、不可变版本与只读装载边界继续有效，真实装载及完整包摘要仍待应用验收。

## 1. 一期方案

MySQL 保存 Skill 草稿及不可变正式内容；平台按业务会话固定的员工发布版本提供只读 `ReleaseSkillRepository`，实现 AgentScope 的 `AgentSkillRepository`；框架负责 `AgentSkill` 表示、装载和正文/文本资源读取。

管理员仍只需修改员工草稿、保存、发布员工。一次发布同时固定模型、指令、完整 Skill 包和 MCP 工具绑定；不增加独立 Skill 发布入口、Skill 市场、用户自建能力或回滚流程。

## 2. 存储分工

| 内容 | 存储位置 | 规则 |
| --- | --- | --- |
| Skill 草稿 | `employee_draft.config_json` | 继续复用员工唯一草稿，保存完整 `SKILL.md` 及文本参考资源；无需另建 Skill 草稿生命周期。 |
| 正式 Skill 主体 | `employee_release_skill` | 按员工发布版本保存不可变主体、metadata 和包摘要。 |
| 正式文本资源 | `employee_release_skill_resource` | 按 Skill 快照保存相对路径、文本及摘要。 |
| 员工发布清单 | `employee_release.config_json` | 只保存本次选用 Skill 的快照 ID、名称和包摘要；不重复保存正式全文。 |
| 运行目录或缓存 | 框架运行时受控存储 | 是可重建运行产物，不承担配置权威来源；一期不强制自建本地目录同步流程。 |

### 2.1 两张正式明细表

| 表 | 逻辑字段 | 主要约束 |
| --- | --- | --- |
| `employee_release_skill` | `id`、`employee_release_id`、`skill_name`、`description`、`metadata_json`、`skill_markdown`、`package_sha256`、`resource_count` | `(employee_release_id, skill_name)` 唯一。`skill_markdown` 包含完整 frontmatter 与正文，其余名称/描述/metadata 是该原文同一次解析的索引或派生结果。 |
| `employee_release_skill_resource` | `id`、`release_skill_id`、`resource_path`、`resource_content`、`content_sha256` | `(release_skill_id, resource_path)` 唯一。参考资源属于这一份 Skill 快照，不能按同名 Skill 查找“最新资源”。 |

沿用团队自增 `BIGINT UNSIGNED id`、`gmt_create`、`gmt_modified`、中文注释等规约；不照搬官方自动建表中的外键和级联删除。正式内容只追加、不原地编辑。字段长度、完整索引和 DDL 已在员工建表规格中落实。

两张表在原先六个配置/发布/授权实体之外保存 Skill 明细，共八个逻辑实体；没有增加管理员需要操作的独立模块。

### 2.2 发布清单示意

```json
{
  "configSchemaVersion": 1,
  "skills": [
    {
      "name": "meeting-room-reservation",
      "snapshotId": 501,
      "packageSha256": "由实际发布内容计算的 SHA-256"
    }
  ]
}
```

读取时同时验证 ID、所属员工发布版本、名称、资源数量及摘要。内部 `snapshotId` 不引入独立的 Skill 发布号。

## 3. 完整 Skill 包

网页可分字段编辑名称、描述、正文，保存时统一生成完整 `SKILL.md`，也可导入完整文档；使用固定版本的解析器校验，避免 UI 字段与 frontmatter 不一致。

```markdown
---
name: meeting-room-reservation
description: 当用户需要查询会议室、发起预约或核对预约结果时使用。
---

# 会议室预约

1. 读取 references/会议规则.md，明确需要收集的字段。
2. 使用员工已配置的查询工具取得候选结果。
3. 按平台确认机制展示本次提案，确认后执行写操作。
4. 根据工具返回及核对结果说明实际状态。
```

如果原包带 `references/会议规则.md` 和 `references/字段说明.md`，两份文件的实际文本也必须固化，不能只保存引用路径。资源路径采用统一的相对路径格式，拒绝绝对路径、目录穿越、重复或归一化后冲突的路径。

对入口原文、每份资源和完整包清单计算摘要；清单稳定排序，摘要编码明确包含路径、长度与实际字节，不能做无边界字符串拼接。保留完整 metadata，不只保留名称和描述。

一期接入受控文本入口及文本参考资料，不开放任意命令、脚本执行或模型自行创建/晋升 Skill。非文本资源不放进 `LONGTEXT` 冒充文本；如实际业务需要，另行确认对象存储与框架消费链路，本次不宣称已经支持二进制 Skill 资源。

## 4. 统一发布事务

1. 固定已保存员工草稿修订，解析完整 Skill 文本包，校验资源及摘要。
2. 按既有顺序检查并锁定管理员、员工和草稿，重新核对修订，分配员工发布版本。
3. 在同一 SQL 事务中插入员工发布记录、全部 Skill 主体及资源明细、工具绑定、完整配置清单和发布审计。
4. 完整内容及引用都写入后，切换员工当前发布版本并提交。任一步失败则一起回滚，不暴露半份 Skill。

平台发布服务统一管理事务，不假定官方 Repository 自行打开的 JDBC 连接或事务会自动参加 Spring 事务。保存草稿不改正式快照，重复发布继续遵守既有员工发布幂等规则。

本期文本 Skill 使用同一 MySQL，可以将版本、内容及版本指针统一提交；若其他配置以后引用对象存储，仍不能假定对象与 SQL 原子提交。

## 5. 按发布版本只读装载

运行入口先完成原有账号、员工使用授权及发布可用性检查，从可信 Session 读取 `employee_id` 与 `employee_release_id`。不接受浏览器或模型指定任意发布版本绕过鉴权。

`ReleaseSkillRepository` 仅从该固定发布版本读取正文与资源，校验清单和摘要后构造 `AgentSkill`，通过 `HarnessAgent.Builder.skillRepository(...)` 接入。它只承担数据库查询和框架对象转换，不重复实现框架 Skill 加载机制。

| 接口 | 固定版本约束 |
| --- | --- |
| `getAllSkills()` | 仅返回该发布清单的完整 Skill 与文本资源。 |
| `getAllSkillNames()` | 仅返回同一清单的名称。 |
| `getSkill(name)` | 仅查当前固定版本，未命中不回退到草稿、全库或最新版。 |
| `skillExists(name)` | 使用同一范围。 |
| `getSource()` | 返回包含员工和发布版本的稳定来源，例如 `employee_1_release_12`；返回对象也采用相同来源。 |
| `save(...)`、`delete(...)`、写入标志 | 运行仓库始终只读，不允许切换为可写；维护通过员工草稿和发布服务进行。 |

实现时还须补齐固定接口的仓库说明等必要方法。可以按员工发布版本批量加载一次并缓存只读目录；不能把带用户记忆的整个 Harness 运行实例作为跨用户缓存。

官方 `MysqlSkillRepository` 证明框架支持“主体 + 资源”MySQL 存取；其默认表按名称唯一，默认枚举整库，`writeable(false)` 也不能阻止其他后台修改相同记录。因此正式入口不能直接依赖一份按名称覆盖的可变表，需上述固定版本适配。

## 6. 防止旁路覆盖

Harness 可合成多个 Skill 来源。根据调研，Workspace 和用户目录中的同名 Skill 可能覆盖外部 Repository，故必须同时控制来源：

- 业务 Skill 只由本员工固定发布版本提供，不并入可变的 Git/Nacos/MySQL 最新目录。
- 不在共享或用户可写的 Skill 配置路径放置覆盖版本；用户和模型的文件工具不得改写这些受保护路径。
- 最终可见清单和内容必须与发布清单一致，检查同名覆盖及清单外新增内容。
- `disableDynamicSkills()` 不替代固定来源、不可变内容或权限检查。

即使框架下一轮重新合成清单，旧会话的 Repository 仍返回旧版快照。用户记忆、可写 Workspace、MCP 调用身份继续按原有用户和员工边界处理。

## 7. 实施验收与边界

后续需真实验证：完整 metadata 和中文参考资料的数据库往返；不启用 shell 时的正文/资源读取；V1/V2 同名 Skill 在发布及重启后仍正确隔离；缺资源、坏摘要、跨版本引用被拒绝；发布失败整体回滚；所有读取入口及更高优先级来源均不能泄漏或覆盖清单外内容。

本次没有 Java 构建、真实 Harness 装载或 MySQL 执行证据。原型仍是内存演示；其中的正文编辑区不代表完整包导入、metadata 编辑或全部资源管理已实施。

原调研是框架能力证据，保持原文；本文是用户已确认的项目选择。完整字段/DDL 由实体评审补齐，Workspace/Memory 与真实恢复继续由存储映射议题推进。外发 GitHub 的审批边界不因本次方案确认而改变。
