# 工作台消息布局与刷新触发（前端）

日期：2026-10-27。仅改工作台消息展示与页面刷新触发；不改投影协议、后端或 content 校验逻辑。

## 目标

1. 消息区对齐经典 DeepSeek 阅读感：列宽居中收窄；用户短气泡右对齐；助手无气泡纯文本；去掉作者名。
2. 刷新只发生在：首次进入、切换业务会话、浏览器标签（页可见性）切回；同页内点击对话框或消息区不得重拉历史与 content。

## 根因

- 布局：`.formal-message` 虽有 `max-width: 760px`，主栏仍偏宽；用户/助手共用带边框气泡，用户气泡易拉满；作者名增加视觉噪音。
- 刷新：`WorkbenchPage` 在 `window` `focus` 上调用 `refresh(true)`。同页点击、从 DevTools 点回页面等都会触发整页重拉（identity、capabilities、会话列表、snapshot、逐条 content），Network 中出现成批 UUID 请求。

## 模块边界

| 单元 | 职责 | 非职责 |
| --- | --- | --- |
| `WorkbenchPage.vue` 模板/CSS | DeepSeek 风格消息列与气泡；去掉作者名 | 抽新消息组件、改 Markdown 渲染器 |
| `WorkbenchPage.vue` 生命周期 | 用 `visibilitychange` 替代 `focus` 触发 `refresh(true)` | content 内存缓存、改 `displayMessages` |
| `watch(sessionId)` / `onMounted` | 保持：换会话 `selectHistory`；首次 `refresh` | 行为不变 |

`TextComposer` 本切片不强制与 720px 列对齐；需要时可另开任务。

## 布局规格

- `.chat`：`max-width: 720px`；水平居中。
- 删除「我」/数字员工显示名（`.message-author`）。
- 用户：右对齐；`.message-text` 为 `width: fit-content`、`max-width: ~70%`；保留浅色气泡底与圆角。
- 助手：左对齐；正文无边框、无背景；`MarkdownBody` 继续用于 markdown/plain-text。
- 窄屏：用户气泡左边距仍可保留，避免贴边。

## 刷新规格

| 场景 | 行为 |
| --- | --- |
| `onMounted` | `refresh()`（保留） |
| `sessionId` 变化 | `selectHistory()`（保留） |
| `document.visibilityState === 'visible'` | `refresh(true)` |
| 同页 focus / 点击 | 不刷新（移除 `window` `focus` 监听） |

卸载时对称移除 `visibilitychange` 监听。不改 `loadSeq`、失败清页、RunWatch 轮询路径。

## 错误

刷新与会话切换的失败处理沿用现有 `failed` / `ApiFailure` 分支；本切片不新增错误类型。

## 非目标

- 像素级复刻 DeepSeek 配色或「已思考」折叠
- content 请求缓存或合并
- 拆分 `MessageList` 组件
- 改后端投影或校验
- 强制约束输入框宽度

## 验收

1. 宽屏下消息列明显窄于主栏并居中；短用户句为紧凑右气泡；助手为无气泡正文。
2. 消息上方无「我」/员工名。
3. 同页点击对话框、消息、输入框：Network 不出现因 focus 触发的成批 content 重拉。
4. 浏览器标签切走再切回：仍会 `refresh(true)` 拉最新历史。
5. 左侧切换业务会话：历史仍正常加载。
