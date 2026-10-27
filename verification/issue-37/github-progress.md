2026-10-27 完成：共享结构 07～10 已按授权执行，本机开发实例重启后完成共享数据集真实身份／能力验收；开发／隔离／真实验收证据齐全，可关闭本工单。

已实现：
- 工作台从真实 me／capabilities 读取当前身份、固定发布能力与允许动作；资格复核接缝重新查当前身份／授权，未实现的业务 POST 仍由服务端拒绝。
- 沿既定 snapshot／messages／content 路径读取指定本人业务会话的正式 TEXT；REPEATABLE READ 同视图取得水位与上界，签名分页定位绑定本人／会话／流／上界，固定版本正文校验 UTF-8 长度与 SHA-256。
- 撤权后账号仍有效、本人历史可读；旧安全上下文、停用账号、跨用户会话及内容被拒绝。浏览器仅安全字面展示文字，没有 composer／真实 Run、SSE、运行过程或 Markdown 扩展。
- 原 35 表登录基线与新 49 表工作台基线分别精确校验；旧结构可继续登录／读能力，缺历史结构明确不可用，部分新结构拒绝就绪。重启／重复初始化不补授权或重置密码。

验证：
- 隔离：52 项后端单元／装配 + 1 项正式产物隔离、前端 9 项认证 + 7 项历史测试通过；类型、前端构建、工程检查、Maven verify 通过。一次性回环 MySQL 最终 27 项检查通过；真实 Chrome 另观察撤权下 6 条文字、刷新、危险 HTML／javascript 链接的字面展示（可执行元素 0）、跨用户无内容及停用后返回登录。
- 共享：`agentscope_brain` 由 35→49 表，17 条 DDL 全部 APPLIED 且精确元数据匹配；原账号／发布／空业务表事实不变。正式 JAR 重启后 `/readyz` 就绪；用户本人登录后 me=READY／userId=1，capabilities 200、允许动作含 START_RUN；共享库无历史夹具、业务 Run=0、凭据版本仍为 2。模型／SDK／会议写增量仍为 0。

本地证据（本轮尚未提交／推送）：
- verification/issue-37/验收记录.md
- verification/issue-37/build-validation.json
- verification/issue-37/isolated-workbench.json
- verification/issue-37/browser-observations.json 及隔离截图
- verification/issue-37/real-preflight.json
- verification/issue-37/真实执行范围.md
- verification/issue-37/real-structure.json
- verification/issue-37/real-acceptance.json
- verification/issue-37/browser-real-workbench.png

JAR SHA-256：c709625bc6b6e2322b86ffba82d24fdb4692efcd05d6ffd4132bdf4335a2a7a9。

不修改已关闭的来源议题；不据此宣布阶段 1 全部完成。阶段 2 对话／投影恢复／真实 Run 不在本条范围。提交、推送或部署仍需另行指令。
