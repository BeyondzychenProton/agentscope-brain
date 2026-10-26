# 受控初始化（Issue #29）

本目录实现已确认的 SYSTEM_BOOTSTRAP SQL 契约。它是由操作者显式调用的初始化函数库，**没有维护 CLI、HTTP 管理接口、应用启动写入或自动修复**。隔离验证、真实输入准备及 SDK 离线核验后，用户已明确批准执行，真实 MySQL 8.4.11 目标 `agentscope_brain` 已初建 30 表并完成单用户／完整发布预置，MinIO 私有桶也已创建；独立读回及同源只读重复通过。见 `verification/issue-29/真实初始化验收记录.md`。目标当前已有数据，不能重跑初建 DDL。

权威：[#29](https://github.com/BeyondzychenProton/agentscope-brain/issues/29)、[#22 数据契约 §12／§13](https://github.com/BeyondzychenProton/agentscope-brain/issues/22#issuecomment-6394913987)、[#23 阶段 1](https://github.com/BeyondzychenProton/agentscope-brain/issues/23#issuecomment-6396363970)。

## 使用边界与结构顺序

平台初建按**完整文件名**依次选择 `db/schema/01_用户与管理员建表.sql`、`02_会话运行消息建表.sql`、`03_记忆控制建表.sql`、`04_会话运行资料建表.sql`、`05_员工配置发布与授权建表.sql`、`06_初始化来源与批次.sql`。06 包含新表及对初建表的 ALTER，仅用于首次创建的无数据目标，不是存量迁移。MySQL DDL 隐式提交，因此结构部署与数据初始化分别验收，不能声称同事务。

仓库可同时有其他切片的 06 文件；禁止用 `06*` 或整目录 glob 当作本工单的结构输入。原 01～05 和 07／08 等历史合成探针不改写。这里的新来源约束不允许直接装载历史管理员种子；使用独立新数据集。

实际预置前还须明确：完整目标库名及空库事实、唯一普通平台用户及可信号码归属记录、受控初始凭据版本、完整的真实模型／工具／Skill／静态策略发布材料。真实资源共用时依仓库规范核对数据区。本库不读取 `resource.txt`、YAML 连接配置或任何登录路径，不创建数据库，不猜测目标。

## 窄接口

- `prepare(request)`：锁外严格验证输入、解析完整 Skill frontmatter／文本资源、形成规范摘要；无网络和 SQL。
- `apply(connection, request)`：使用调用者明确提供的专用 PyMySQL 兼容连接；连接必须无在途事务、采用默认 MySQL affected-row 语义，事务由本函数拥有。唯一 `source_ref`／`bootstrap_scope` 处理首次竞争。插入用户、凭据、号码、员工、草稿、固定发布／Skill／资源／绑定、显式授权、身份和配置成功审计，再同事务完成 APPLIED。返回批次、manifest 摘要及脱敏结果引用。
- `verify(connection, source_ref, expected_input_sha256=None)`：只读核对已有 APPLIED。调用者用**独立连接的只读一致性事务**执行；不重放 `apply`、不补缺失数据。验证引用、正文重算、发布与绑定摘要、当前手机号／发布指针、成功审计及原 manifest；可同时检查原输入指纹。

提交异常返回 `COMMIT_OUTCOME_UNKNOWN`，立即丢弃原连接，按**原 sourceRef、原稳定输入与原结果**独立核对；已应用只返回原结果，确定未应用且全库仍空时才可按原键再次受控执行。连接在提交前丢失也先按原来源核对。持久的 PREPARING／FAILED／RECONCILING 不自动接管，不新建来源绕过同一期 scope。

同来源不同稳定内容拒绝；只改变输入的密码哈希不会变更不可变指纹，也不会重设已存在的凭据。后续合法改密、改号、撤权、账号／发布停用遵守各自真实 actor 与审计规则，不把创建来源永久套用到修改动作。

## 输入与编码

输入结构以 `prepare` 的字段白名单及隔离 fixture 为可执行契约。顶层为 `sourceRef`、固定 `bootstrapScope=formal-web-phase1`、`identity`、`employee`。

`identity` 要求姓名、规范登录号、可信归属记录引用、初始凭据引用版本、`{bcrypt}` cost=12 哈希。初始口令的长度／UTF-8 限制须由受控凭据准备方执行，哈希本身无法证明明文策略。禁止将真实输入或哈希复制到可提交 fixture／证据。

`employee` 包含完整指令、版本化模型／连接／凭据引用与受控参数、工具绑定及静态策略引用、完整 Skill 入口与文本资源。所有引用须在真实预置前解析和审核为固定配置；函数不声称这些引用已在真实配置仓库或 SDK 中核验。模型参数目前只接受 `temperature`、`topP`、`maxTokens`；新字段及完整真实适配配置在输入对接时验证，不能静默截断。

工具绑定保留目标、传输、凭据、输入契约、Adapter、可信身份、确认、幂等与结果核对的**不可变引用**。这不是目标 MCP 的 tools/list 或在线语义验证。`storageProfiles` 记录稳定别名和内容摘要；本轮文本合成数据不依赖对象存储，列表为空。真实材料引用 storage_profile 时须另行提供并核对实际配置内容，本函数只核验已登记清单，不读取 MinIO 或外部秘密。

真实图文发布另带可选字段 `images`，其完整字段是 `storageProfile`、`mimeTypes`、`maxCount`、`maxBytesPerImage`、`maxBytesPerMessage`、`maxPixelsPerImage`。存储别名必须在 `storageProfiles` 登记，格式仅允许 JPEG／PNG，限额须为正整数且单图／总量／数量预算一致。具体限额不是永久写死的数据库约束，而是本次固定发布内容。非空存储清单与图片策略同时进入发布正文及依赖摘要，独立读回会对比批次原清单。旧文字 fixture 没有 `images` 且存储清单为空时，原编码不变；它不代表真实图文发布完整。

本轮用户确认的实际值是：最多 4 张、单张 5 MiB、同条消息合计 10 MiB、单张 1600 万像素、JPEG／PNG；`minio-originals-v1` 对应用户确认的私有桶 `agentscope-brain` 和根前缀 `originals/`。完整固定引用正文和秘密解析只在本机私有准备材料；本库自身不将声明的摘要当作对象存储在线或正式 Adapter 已实现的证明。

Schema v1 JSON：对象键排序、紧凑 UTF-8、列表保序；精确十进制去尾零，拒绝浮点近似、重复 JSON 键和非有限数。集合型 Skill／工具／资源先按稳定名称或路径排序。文本不改换行、不 trim、不归一化；资源路径则要求 NFC、相对路径，拒绝盘符、反斜线、空段、点段和等价冲突。MySQL 排序规则的最终等价冲突由唯一索引再验证。

Skill 包摘要的二进制编码为：64 位大端长度＋版本标识 UTF-8 `agentscope-skill-package-v1`、64 位长度＋完整入口 UTF-8、64 位长度＋完整 frontmatter 规范 JSON；随后按路径排序逐项追加路径长度＋UTF-8、原文 UTF-8 长度、摘要长度＋SHA-256 原始 32 字节。支持的 metadata 完整保留，不能无损表达的类型拒绝。入口／资源／包／总准备材料限额分别为 256 KiB／1 MiB／8 MiB／16 MiB。

bootstrap manifest 按已定 §12 编码，只保存来源／scope、用户／凭据／号码稳定引用及版本、不可变目标 ID／摘要。ID 使用十进制字符串，摘要使用小写 hex，目标先按 kind、再按稳定 ID 字符串排序。密码哈希、首次改密标记、当前安全版本、授权／可用性状态均不进入 manifest。初始稳定输入只保存指纹用于拒绝同键异内容，不保存请求副本。

## 复现与后续接入

在仓库根目录，使用已有 Python 与上述锁定的库，运行 `python db/tests/执行初始化隔离验收.py`。执行器只使用本轮创建的临时 MySQL 数据目录／随机回环端口，核验 `@@datadir` 后才执行 DDL。单用户数据集与异常探针数据集分开，最后关闭并删除整个自建实例。

最新隔离结果为 36 组 MySQL 场景、40 项编码／输入检查。证据见 `verification/issue-29/验收记录.md`、`probe-summary.json` 及 `真实初始化输入核对.md`。真实冻结 Skill 已通过固定 SDK 的文件仓库只读装载，真实模型配置已通过禁止 HTTP 的 SDK 构造检查；这不是正式 MySQL `ReleaseSkillRepository`、应用启动或在线模型验证。后端启动前只读核验、真实账号登录及完整运行装配仍需所属阶段完成；本目录不增加 Java 服务或启动钩子。
