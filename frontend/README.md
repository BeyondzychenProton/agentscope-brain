# 正式 Web（#34）

Vue／TypeScript／Vite 独立工程。`package.json` 固定全部直接依赖，`package-lock.json` 保存完整求解结果。Node **24.22.1**、npm **11.19.1**；本轮使用 `.codex-local/issue-34/` 下校验过官方 SHA-256 的便携运行时，未修改系统 Node 安装。

在对应运行时的 Node／npm 位于 PATH 后，从此目录运行：

```powershell
npm ci --ignore-scripts --no-audit --no-fund
npm run typecheck
npm run build
```

三步分别验证安装、类型和生产构建。然后在 `backend/` 执行 Maven `verify`，生产 JAR 打包本目录 `dist/` 到 `static/`，由后端同源服务页面与 `/api`。没有前端构建产物时正式打包拒绝。不要把 node_modules、dist 或秘密提交到仓库。

`npm run dev` 仅在回环启动 Vite，代理 `/api` 至 `127.0.0.1:8080`。开发联调不代替已打包 JAR 的浏览器验证。生产入口使用同源 HTTPS；前端不持有资源服务凭据，不直接访问 SDK、模型、MCP 或 MinIO。

- `app/pages`：路由与页面组合；路由进入前从 `/api/me` 复核身份，受限身份仅到首次改密页。
- `features/auth`：Pinia 内存身份、登录／退出用例；密码不进入任何浏览器持久存储，提交结束清除输入。
- `transport`：同源 JSON／CSRF，写前获取当前 token；认证返回不缓存，不自动重试写请求。

本切片交付登录和受限页面。首次改密提交由 #35 实现，当前不收集新口令；工作台仅保留基础壳，不提供消息、素材、会话或执行入口。服务端独立拒绝提前访问业务。
