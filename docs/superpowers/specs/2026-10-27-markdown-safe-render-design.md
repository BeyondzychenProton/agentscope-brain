# Markdown 安全渲染（前端切片 2）

日期：2026-10-27。承接工作台打磨切片；依据 #21 Q19 与工作台规格 §6。不宣称图片、改名或 #41 完成。

## 目标

已校验的 ASSISTANT `markdown-v1` 正文经 markdown-it + DOMPurify 安全展示；净化失败回退纯文本；fixture 锁住 XSS 与非法协议。

## 范围

包含：列表、表格、代码块（展示与复制）、http／https 链接；`html=false`；禁用 Markdown 图片加载；集中白名单；USER／`plain-text` 仍文本插值。

不做：用户侧 Markdown、平台素材图、SSE 流式未闭合特化、阅读跟随调优、改名、过程时间线。

## 管线

1. `history.displayMessages` 只做固定版本校验，输出 `{ bodyFormat, body }`，不产生 HTML。
2. `renderMarkdownV1(source, purify?)`：markdown-it 解析 → 注入的 purify（默认 DOMPurify）→ `{ kind:'html'|'plain', value }`。
3. `MarkdownBody`：仅 `html` 使用 `v-html`；`plain` 文本插值；代码块复制读 DOM 文本，失败如实提示。

权威输入永远是校验后的原文；净化 HTML 不可作为业务真相。

投影写入：USER 为 `plain-text`，ASSISTANT 为 `markdown-v1`（`AcceptedTextWriter`）。前端对历史误标为 `plain-text` 的 ASSISTANT 正文仍走 Markdown 渲染，避免旧会话表格／加粗继续原样显示。

## 安全规则

- 链接规范化后仅 `http:`／`https:`。
- 禁止原始 HTML、脚本、执行型组件；净化后再不交给会解释 HTML 的插件。
- purify／render 抛错或返回非字符串 → 回退原文纯文本。

## 验收

- ASSISTANT markdown-v1 可见排版与可复制代码块；USER 仍纯文本。
- 危险样例经渲染无 `onerror` 可执行面、无 `javascript:` href。
- 注入 purify 抛错时 kind 为 plain。
- `test:markdown` 与 `test:history`、typecheck 通过。
