# GitHub 决策同步记录

> 当前状态：2026-10-20 用户明确授权“进行关闭，并完成相关的更新”后，已成功发布正式决策、将员工配置议题关闭为完成，并更新决策地图、存储映射及实体评审议题。此前的自动审批拒绝已不再阻塞本次获明确授权的同步。文件名沿用原草稿路径，正文已转为同步记录。

## 已核实的远端结果

| 议题 | 本次结果 |
| --- | --- |
| [数字员工定义、发布版本与用户授权关系](https://github.com/BeyondzychenProton/agentscope-brain/issues/6) | 已关闭，原因为 `completed`；有一条[正式决策评论](https://github.com/BeyondzychenProton/agentscope-brain/issues/6#issuecomment-6282089781)。 |
| [数据库设计决策地图](https://github.com/BeyondzychenProton/agentscope-brain/issues/1) | 保持开放；补充已完成决策索引与评论链接，当前前沿改为存储映射。 |
| [AgentState、长期记忆与 Workspace 隔离和存储映射](https://github.com/BeyondzychenProton/agentscope-brain/issues/7) | 保持开放；同步员工发布与 Skills MySQL 前置决策，记忆编辑及跨 Session 并发建议仍待用户回答。 |
| [数据库实体关系与字段约束评审](https://github.com/BeyondzychenProton/agentscope-brain/issues/9) | 保持开放；同步新增八个逻辑实体，字段、DDL 与完整约束仍待评审。 |

以上状态已重新读取核实。本次未提交、推送、建表、迁移或部署；关闭的只是设计决策。

## 本次完成的动作

1. 向用户指定仓库的[数字员工定义、发布版本与用户授权关系](https://github.com/BeyondzychenProton/agentscope-brain/issues/6)追加下述决策评论。
2. 将该设计决策议题关闭为完成；不把数据库或运行时实现标记为完成。
3. 在[数据库设计决策地图](https://github.com/BeyondzychenProton/agentscope-brain/issues/1)添加已完成决策的简述与评论链接，并把当前状态调整为设计已完成、实现与验收待后续。
4. 不提交、推送、部署或操作业务数据。

## 已发布到员工配置议题的评论

### 决策完成：一期数字员工配置、发布与授权（2026-10-20）

用户已逐项确认并认可原型，最后正式接受统一发布方式。本次关闭的是设计决策，不代表数据库或 AgentScope 集成已实施。

#### 已确认的一期行为

- 每个员工一份可修改草稿；保存不影响正式服务。管理员修改、保存后手动发布，只做必填配置与引用有效性检查。
- 网页维护模型及必要参数、指令、Skills，以及已接入 MCP 的子工具选择。Workspace、Memory 使用平台统一约定。
- 一次“发布员工”自动固定整套模型配置、指令、Skill 静态内容及工具绑定；一期无独立 Skill/工具发布操作。
- Skills 使用 MySQL，正式正文及参考资料拆为两张按员工发布版本固定的明细表；员工发布 JSON 只保存快照引用，运行通过固定发布版本的只读 `AgentSkillRepository` 装载，不强制先写本地目录。
- 后端配置文件维护 MCP 地址、连接参数与凭证引用；Java 实现身份注入、签名和操作确认，网页只选择已接入的服务与子工具。
- 新发现的工具须选择并重新发布后才进入员工配置。凭证值独立管理；后端连接配置变化不能静默改写旧发布版本的目标和工具契约。
- 新 Session 固定当前可用发布版本，Run 继承 Session 版本；已有会话不原地升级，已确认的显式“在新版继续”规则不变。
- 用户授权绑定 User × Employee；固定管理员只免此层显式授权，仍使用本人绑定手机号并经过工具策略、外部业务权限与具体写操作确认。
- 一期不实现回滚、发布审批、强制测试门槛或专门测试隔离。原有用户/员工数据隔离继续有效。

#### 技术映射

本地模型提出八个实体：`digital_employee`、`employee_draft`、`employee_release`、`tool_binding_revision`、`employee_use_grant`、`employee_configuration_audit_event`、`employee_release_skill`、`employee_release_skill_resource`。平台只读发布仓库提供固定 Skill 及文本资源，框架所需缓存或目录是可重建运行产物；用户记忆、会话和运行文件不进入发布快照。

发布在同一 SQL 事务中写入完整版本、工具绑定、发布审计并切换员工当前版本；对象准备与 SQL 不假定原子提交。新会话创建与发布按员工行串行，授权变更与 Run 创建按用户行串行，执行与工具边界继续复查实时授权。

既有员工/发布/目标发布引用对应员工及发布实体；`tool_operation.tool_binding_version` 保持现有 `VARCHAR(64)`，关联不可变工具绑定修订。字段、DDL 与跨表约束在完整实体评审中补齐。

#### 本地设计依据与限制

- `docs/数据库设计/数字员工配置与发布/一期配置与发布决策.md`
- `docs/数据库设计/数字员工配置与发布/数字员工配置发布与授权数据模型.md`
- `docs/数据库设计/数字员工配置与发布/Skills_MySQL管理与版本装载设计.md`
- `prototypes/session-ui-prototype.html`
- 已同步 `GLOSSARY.md` 与 `docs/一期总体设计.md`。

上述文件仍在本地，未提交/推送，不生成远端文件链接。原型内存状态流转与脚本已检查，浏览器视觉验收未完成。本次未建表、迁移、调用真实 MCP 或执行 AgentScope 装载/恢复验收。

后续：静态配置物化、AgentState/Workspace/Memory 隔离与真实恢复由“框架隔离与存储映射”跟踪；DDL/约束由“完整实体与约束评审”跟踪；保留与删除由“留存、删除与迁移”跟踪。

## 已同步到决策地图的简述

“确定数字员工定义、发布版本与用户授权关系”：设计决策完成。一份草稿、员工统一发布、不可变配置与工具绑定、按员工授权；MCP 后端接入、网页绑定，Skills 以 MySQL 快照和只读发布仓库装载。八个逻辑实体与发布/授权事务关系已有本地模型，DDL、框架装载恢复及留存删除由后续议题完成。链接指向上述员工配置议题的正式决策评论。
