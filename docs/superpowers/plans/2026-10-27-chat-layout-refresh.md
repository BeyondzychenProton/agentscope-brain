# 工作台消息布局与刷新触发 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: test-driven-development（若本任务有可测纯逻辑则先测后改）。步骤按序执行；每步完成后勾选。

**Goal:** 按 `docs/superpowers/specs/2026-10-27-chat-layout-refresh-design.md`，在工作台实现 DeepSeek 风格消息列，并把整页刷新从 `window focus` 改为仅 `visibilitychange`（可见时）+ 既有会话切换/首屏加载。

**Architecture:** 单文件改动 `frontend/src/pages/WorkbenchPage.vue`——模板去掉作者名，scoped CSS 收窄居中列与用户气泡/助手无气泡；生命周期用 `document.visibilitychange` 替换 `window` `focus`。不抽组件、不改 `history.ts` / Composer。

**Tech Stack:** Vue 3 SFC、既有 workbench CSS 变量。

**Spec:** `docs/superpowers/specs/2026-10-27-chat-layout-refresh-design.md`

---

## Task 1: 刷新触发 — 去掉 focus，改用 visibilitychange

**Files:**
- Modify: `frontend/src/pages/WorkbenchPage.vue`（script：`focusRefresh` / `onMounted` / `onUnmounted`）

- [ ] **Step 1: 定位并替换监听**

将：

```ts
function focusRefresh() { void refresh(true) }

onMounted(() => {
  void refresh()
  window.addEventListener('focus', focusRefresh)
})
onUnmounted(() => {
  clear()
  window.removeEventListener('focus', focusRefresh)
})
```

改为：

```ts
function onVisibilityRefresh() {
  if (document.visibilityState === 'visible') void refresh(true)
}

onMounted(() => {
  void refresh()
  document.addEventListener('visibilitychange', onVisibilityRefresh)
})
onUnmounted(() => {
  clear()
  document.removeEventListener('visibilitychange', onVisibilityRefresh)
})
```

保留：`watch(sessionId, … selectHistory)`、`onMounted` 首次 `refresh()`。

- [ ] **Step 2: 自检（本机）**

1. 打开工作台某会话，DevTools Network 过滤 content。
2. 同页点击消息区 / 输入框 / 侧栏空白：不应因点击成批重拉 content。
3. 切到另一浏览器标签再切回：应出现一次 `refresh(true)` 相关请求。
4. 左侧切换另一会话：历史仍正常加载。

---

## Task 2: 消息布局 — DeepSeek 经典样式

**Files:**
- Modify: `frontend/src/pages/WorkbenchPage.vue`（template + scoped style）

- [ ] **Step 1: 模板**

在消息 `article` 内删除 `.message-author` 段落（「我」/ `employeeLabel`）。保留 `MarkdownBody` / `.message-text` 分支不变。

- [ ] **Step 2: CSS**

按规格调整（名称可微调，语义对齐即可）：

1. `.chat`：`max-width: 720px; width: 100%; margin-inline: auto;`
2. `.formal-message`：去掉或放宽与列冲突的旧 `max-width`；用户块右对齐（`display:flex; flex-direction:column; align-items:flex-end` 或等价）
3. `.user .message-text`：`width: fit-content; max-width: 70%;` 保留浅色气泡
4. 助手：`.formal-message.assistant .message-text` 与 `MarkdownBody` 根上的 `.message-text` —— 无 `background` / `border`（或透明），仅保留字号与行高；必要时用 `:deep` 或助手修饰类覆盖 `MarkdownBody` 上的气泡样式
5. 删除仅服务于作者名的 `.message-author` / `.user .message-author` 规则
6. 窄屏：用户气泡勿贴死左缘（可保留小左边距）

注意：`MarkdownBody.vue` 根节点带 `message-text` class；助手去气泡时必须覆盖该节点，避免助手仍显示白底边框。

- [ ] **Step 3: 视觉自检**

1. 宽屏：消息列居中，明显窄于主栏。
2. 短用户句：紧凑右气泡，非拉满。
3. 助手长文：无气泡纯文本，仍可读、可换行。
4. 无「我」/员工名。

---

## Task 3: 回归与收尾

- [ ] **Step 1: 相关前端测试**

在 `frontend` 目录跑既有测试（本切片无强制新测；确认未误伤）：

```bash
npm test
```

（若仓库脚本不同，用 `package.json` 中的 test 脚本。）

- [ ] **Step 2: 对照规格验收清单**

逐条核对 spec「验收」1–5；全部通过后再提交。

- [ ] **Step 3: 提交（仅当用户要求 commit 时）**

建议信息：`fix: DeepSeek 风格消息列并限制标签可见性刷新`

---

## 非目标（实现时勿做）

- content 缓存、拆消息组件、改 Composer 宽度、改后端、像素级复刻 DeepSeek「已思考」UI。
