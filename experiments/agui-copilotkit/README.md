# AgentScope → AG-UI → CopilotKit 本机验证

这是独立的临时文本聊天验证，不读写正式业务会话、Run、消息账本或长期记忆，也不依赖数据库、MCP 或对象存储。刷新或新建会产生新的临时 threadId。Java 和 Next.js 只监听 `127.0.0.1`，不要将此无登录验证服务公开部署。

依赖：JDK 17、Node.js（本机验证为 24.14.0）、Python 3 和 PyYAML。Java 使用 AgentScope 2.0.4、Spring Boot 4.1.2；页面使用 Next.js 16.4.3、React 19.2.4、CopilotKit 1.82.0 和 AG-UI client 1.0.2，具体依赖见锁文件。模型服务必须兼容 OpenAI Chat Completions SSE，并发送 `[DONE]` 终结标记；不会自动切换模型。

在 PowerShell 中，先在 `java` 目录构建并启动（JDK 路径按本机调整）：

```powershell
$env:JAVA_HOME = 'D:\code environment\jdk\jdk-17.0.9'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
..\..\..\backend\mvnw.cmd -DskipTests package
python -m pip install -r requirements.txt
python run-real.py
```

启动脚本默认只从被 Git 忽略的 `backend/config/application.yaml` 取 `brain.resources.model` 的地址、密钥和模型 ID，传给独立 Java 进程；不加载该文件中的业务服务。需要使用其他私有配置时，可设置 `MODEL_CONFIG_PATH`。不要把真实配置或进程环境写入日志、截图或仓库。

另开 PowerShell，在 `web` 目录运行：

```powershell
npm ci --legacy-peer-deps
npm run build
npm start
```

打开 http://127.0.0.1:3000。两个终端分别按 Ctrl+C 停止。`npm run dev` 可用于本地开发；生产构建也固定回环监听。模型端口或前端端口占用时先停止占用进程，不要对现有服务强制终止。

受控模型的浏览器验证见 [browser-tests/README.md](browser-tests/README.md)。真实模型验证在服务启动后执行：

```powershell
cd ..\browser-tests
npm ci
npx playwright install chromium
$env:REAL_MODEL = '1'
npm test -- tests/real-model.spec.js
```

该测试发送合成问题，记录首轮页面文本的不同可见更新数量，并确认第二轮回答引用第一轮中的合成暗号。它会实际调用已配置模型，产生对应的服务用量。取消测试证明应用关闭受控模型 HTTP 连接，并不证明供应商已经停止计算或计费。

已知限制和未完成的验收见 [验证记录](验证记录.md)。请勿把此实验当作正式工作台的恢复、业务状态或阶段验收证明。
