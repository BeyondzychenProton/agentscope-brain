# Issue tracker: GitHub

本仓库的 Issue 和规格记录在 GitHub Issues，通过 gh CLI 操作。

仓库：BeyondzychenProton/agentscope-brain
远程：https://github.com/BeyondzychenProton/agentscope-brain.git

## 操作约定

在仓库目录运行 gh；在其他目录运行时，显式指定
--repo BeyondzychenProton/agentscope-brain。

- 创建：gh issue create --title "<标题>" --body-file "<正文文件>"
- 读取：gh issue view <number> --json number,title,body,state,labels,comments
- 列表：gh issue list --state open --json number,title,body,labels
- 评论：gh issue comment <number> --body-file "<评论文件>"
- 添加或移除标签：gh issue edit <number> --add-label "<标签>" / --remove-label "<标签>"
- 关闭：gh issue close <number>

多行正文通过 UTF-8 文件和 --body-file 传入。

修改前重新读取目标 Issue 的状态和内容；修改后回读核对。
已关闭的 Issue 保持原样，不编辑、不评论、不改标签、不重新打开。

## Pull requests as a triage surface

**PRs as a request surface: no.**

## 技能指令的含义

- “publish to the issue tracker”：创建 GitHub Issue。
- “fetch the relevant ticket”：读取对应的 GitHub Issue。

## Wayfinding operations

- Map：带 wayfinder:map 标签的单个 Issue，维护 Notes、
  Decisions-so-far 和 Fog。
- 子任务：优先用 GitHub 原生 sub-issue 关联 Map。
  不可用时，在 Map 正文维护任务清单，并在子任务正文顶部写
  Part of #<map>。
- 类型标签：wayfinder:research、wayfinder:prototype、
  wayfinder:grilling、wayfinder:task。
- 阻塞关系：优先用 GitHub 原生 issue dependencies。
  API 中使用 Issue 的数字 database id，不能用编号或 node_id 代替。
  不可用时，在正文顶部写 Blocked by: #<number>。
- 可执行任务：按 Map 顺序选择未关闭、无人认领且没有未关闭阻塞项的子任务。
- 认领：为任务设置当前开发者为 assignee。
- 解决：在尚未关闭的任务中记录结果，关闭任务，再更新仍开放的 Map。
  若 Map 已关闭，向用户说明，等待另行指定记录位置。
