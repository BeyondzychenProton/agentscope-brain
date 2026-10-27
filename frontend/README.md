# 正式 Web（#34／#35）

Vue／TypeScript／Vite 独立工程。`package.json` 固定全部直接依赖，`package-lock.json` 保存完整求解结果。Node **24.22.1**、npm **11.19.1**；本轮使用 `.codex-local/issue-34/` 下校验过官方 SHA-256 的便携运行时，未修改系统 Node 安装。

在对应运行时的 Node／npm 位于 PATH 后，从此目录运行：

```powershell
npm ci --ignore-scripts --no-audit --no-fund
npm run typecheck
npm run test:auth
npm run test:history
npm run test:sessions
npm run test:composer
npm run test:run-watch
npm run test:markdown
npm run test:session-title
npm run build
```

依次验证安装、类型、认证结果处理、Run 监视、Markdown、会话改名和生产构建。认证测试使用实际 store／transport 模块与合成 fetch 响应，验证部分成功／结果不明提示、身份清理及禁止自动重试，不连接真实服务。然后在 `backend/` 执行 Maven `verify`，生产 JAR 打包本目录 `dist/` 到 `static/`，由后端同源服务页面与 `/api`。没有前端构建产物时正式打包拒绝。不要把 node_modules、dist 或秘密提交到仓库。

`npm run dev` 仅在回环启动 Vite，代理 `/api` 至 `127.0.0.1:8080`。开发联调不代替已打包 JAR 的浏览器验证。生产入口使用同源 HTTPS；前端不持有资源服务凭据，不直接访问 SDK、模型、MCP 或 MinIO。

- `app/pages`：路由与页面组合；路由进入前从 `/api/me` 复核身份，受限身份仅到首次改密页。
- `features/auth`：Pinia 内存身份、登录／退出用例；密码不进入任何浏览器持久存储，提交结束清除输入。
- `transport`：同源 JSON／CSRF，写前获取当前 token；认证返回不缓存，不自动重试写请求。

当前交付登录、受限首次改密、海卓工作台壳与 TEXT 历史深链。新密码至少 12 个 Unicode 码点、有效 UTF-8 不超过 72 字节；密码仅用于本次请求，提交结束和离页清除，不进入 Pinia、草稿或浏览器持久存储。正常成功进入基础工作台；密码已改但会话保存失败引导使用新密码重新登录，提交结果不明停止再次提交并提示先验证登录。写请求均不自动重试。

工作台沿用海卓紫系外壳，侧栏接入真实最近会话、服务端全范围标题搜索与分页；搜索输入后按 Enter 或点击搜索，输入法组词时 Enter 不提交。点选打开已有 TEXT 历史并高亮，支持浏览器前进／后退及深链刷新；多员工按当前可见身份选择各自会话空间。新建仅清除当前会话定位，不创建空 Session。文字 composer 常驻（已登录且已选员工）；接纳后输入区不卸挂。活动 Run 期间可编辑草稿、禁止发送（无打断、无排队）；`RunWatch` 对目标 run 有界只读轮询，打开仍含 WAITING／RUNNING 的会话会续监视。页面 `busy` 只表示身份／历史加载，不单独否决发送。头部 `···` 与侧栏会话项可改名（共用对话框与 `PATCH /api/sessions/{id}/title`）；成功后就地更新列表与页头。过程时间线仍待后续切片。每次进入／窗口聚焦从 `me`／`capabilities` 复核身份；可通过 `/workbench?sessionId=<本人业务会话ID>` 在同一聊天壳内只读查看正式文字历史。固定版本正文经 UTF-8 长度和 SHA-256 校验后读回；USER／`plain-text` 仍文本插值；ASSISTANT 正文（`markdown-v1`，以及历史误标的 `plain-text`）经 markdown-it + DOMPurify 安全渲染（禁原始 HTML 与 Markdown 图片，链接仅 http／https，净化失败回退纯文本，代码块可复制；新写入的员工投影为 `markdown-v1`）。撤权保留本人历史与只读说明，401 清除当前显示并回登录，跨用户引用显示受控不可读。`npm run test:history` 验证固定版本校验；`npm run test:run-watch` 验证监视状态机；`npm run test:markdown` 验证协议过滤与回退；`npm run test:session-title` 验证改名校验与列表补丁。

#42 首个切片的 `test:sessions` 使用真实导航状态类验证只读 HTTP、精确 ID、服务端搜索条件、分页、迟到响应和退出清理，配合 `db/tests/执行会话列表只读隔离验收.py --browser` 在打包 JAR、独占回环 MySQL、本机 Chrome／Edge 上验证实际页面。浏览器自动化依赖只装入本地忽略目录，详见 `verification/issue-42/验收记录.md`。

#42 第二切片交付文字 composer、当前标签草稿和普通提交回查／封闭。默认 `brain.conversation.text-submission-enabled=false`，当前共享环境未开放发送；服务端能力字段 `textSubmissionAvailable` 控制新建发送入口，旧会话另以快照当前允许动作判定，最终授权始终由后端复核。隔离验收显式打开后，首条文字创建 Session 并展示正式用户消息和真实 WAITING 调度事实，不生成模拟员工回复。图片和 FORM 尚未接入。

草稿按当前可信用户、员工、Session／本地新建意图保存在 sessionStorage；原文与 submissionKey 在发送前固定保存。结果不明时冻结输入；恢复只回查原键，UNOBSERVED 不解冻、不重发。用户明确封闭后才可编辑并重新发送；先接纳则封闭返回原结果。Enter 发送／Shift+Enter 换行，composition 期间不发送；存储失败提示恢复边界并保留页内文字。身份失效、退出和已知身份切换只清应用前缀。`test:composer` 运行真实状态类的故障／恢复测试；新浏览器证据见 `verification/issue-42/text-admission/验收记录.md`。

聚焦身份重查会保留当前草稿编辑；结果未复核前暂停发送。接纳回查引起会话切换时会重新完成能力读取，避免刷新后回到新建页丢失入口。离页／退出后的迟到请求不会再次写入当前标签恢复记录。
