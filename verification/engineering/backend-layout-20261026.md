# 正式后端目录整理验证

日期：2026-10-26，Asia/Shanghai。范围：用户要求的正式目录、业务模块和开发规范准备，不代表阶段 1 业务交付。

## 变更

- 正式 `backend/` 维持单应用／单 Maven 模块，增加唯一启动类和 Boot 可执行打包。
- 按既定九个业务模块建立职责声明，跨模块只使用 `api`，实现收在 `internal`；新增目录／包名／import／资源位置检查脚本。
- 阶段 0 原目录的 87 个源码、构建和证据文件移至 `archive/stage0-backend/`，逐文件 SHA-256 校验一致。旧 `target/` 移至归档并继续被 Git 忽略。
- 正式代码仅复用封闭 draft-07 校验器，除包名搬迁外实现逐字一致；对应两组测试随迁。
- 新增 dev／prod 与共用资源模板。私有 `resource.txt` 保留原位置并被 Git 忽略，不自动导入应用。Redis 未启用。
- 登录链完成前拒绝全部 HTTP，禁止隐式默认账号，并关闭拒绝路径的请求缓存，避免为匿名拒绝请求写入 Session。

## 验证与结果

环境：Windows，本机已核验 Oracle JDK 17.0.9，Wrapper 指定 Maven 3.9.16。命令从 `backend/` 执行：

```powershell
$env:JAVA_HOME = 'D:\code environment\jdk\jdk-17.0.9'
.\scripts\检查工程规范.ps1
.\mvnw.cmd -B -ntp verify
```

| 验证 | 结果 |
| --- | --- |
| 工程目录、包路径、模块 import 与私有资源位置 | PASS |
| PowerShell 脚本语法 | 0 个错误 |
| 正式入口隔离装配 | 1 项通过；进程内 H2／回环临时 HTTP，无 Session 建表；匿名请求 403 且无 Set-Cookie |
| 应用 draft-07 校验 | 10 项通过 |
| SDK 原生 ToolValidator 兼容入口 | 3 项通过 |
| Maven verify／Boot repackage | BUILD SUCCESS；14 项测试，失败／错误／跳过均 0 |
| 正式 JAR | 入口正确，无旧探针、历史证据、私有配置、resource.txt、测试配置或 H2 测试依赖 |
| 归档完整性 | 87 个文件哈希全部一致；未复制 resource.txt |
| 新文档及修正引用 | 6 份文档相对链接检查，无缺失 |

产物条目与 SHA-256 见 [artifact.json](backend-layout-20261026-artifact.json)，正式源文件指纹见 [sources.json](backend-layout-20261026-sources.json)。原始测试报告和构建日志位于本机 `backend/target/`，属于可再生输出。

GitNexus 当前仅索引其他仓库，本次没有使用其他仓库的图谱冒充核验；使用全仓文本引用扫描、工程检查及实际编译／测试／打包。import 检查不覆盖反射、完全限定调用、依赖环和事务正确性，后续改动仍需按用例审查。

## 实际边界

本次没有连接共用 MySQL／MinIO／Redis，没有执行业务 DDL、预置账号、调用模型／MCP、会议写入或部署。H2 装配不能替代真实 MySQL、正式认证、固定 24 小时、多浏览器、记忆学习或联合恢复验收。

用户本次明确开发与生产共用资源；这更新旧路线的独立资源假设，已写入开发规范。数据库名、bucket／对象数据区、实际权限和在线可用性仍未核实，不据 profile 宣称已有隔离。已关闭 Issue 未修改；Git 提交与推送未执行。
