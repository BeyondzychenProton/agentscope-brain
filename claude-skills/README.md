# Matt Pocock Skills for Claude Code

这个目录包含从 [mattpocock/skills](https://github.com/mattpocock/skills) 仓库转换而来的技能包，已适配 Claude Code 使用。

## 📦 已转换的技能

总共 **31 个技能**，分为 3 个类别：

### 🔧 Engineering（工程类 - 20个技能）

- **ask-matt** - 向 Matt 提问的技能
- **code-review** - 代码审查（两轴审查：标准和规范）
- **codebase-design** - 代码库设计
- **diagnosing-bugs** - 诊断 Bug
- **domain-modeling** - 领域建模
- **grill-with-docs** - 基于文档的深度提问
- **implement** - 基于规范或工单实现功能
- **implement-spec** - 实现规范
- **improve-codebase-architecture** - 改进代码库架构
- **pr** - Pull Request 相关
- **prototype** - 原型开发
- **research** - 研究
- **retro** - 回顾
- **setup-matt-pocock-skills** - 设置 Matt Pocock 技能
- **tdd** - 测试驱动开发
- **to-spec** - 将对话转换为规范
- **to-tickets** - 转换为工单
- **triage** - 问题分类
- **wayfinder** - 路径查找
- **wizard** - 向导

### 💡 Productivity（生产力类 - 7个技能）

- **grill-me** - 对我进行深度提问
- **grilling** - 深度提问技能
- **handoff** - 工作交接
- **teach** - 教学
- **to-questionnaire** - 转换为问卷
- **wait-what** - 等等，什么？
- **writing-for-agents** - 为 Agent 写作

### 🔨 Misc（其他类 - 4个技能）

- **git-guardrails-claude-code** - Git 护栏（Claude Code 版）
- **migrate-to-shoehorn** - 迁移到 Shoehorn
- **scaffold-exercises** - 脚手架练习
- **setup-pre-commit** - 设置 pre-commit

## 🚀 如何使用

### 方法 1: 手动安装（推荐）

1. 将整个 `claude-skills` 文件夹复制到 Claude Code 的技能目录
2. 在 Claude Code 中刷新技能列表
3. 通过 `/技能名` 命令调用相应技能

### 方法 2: 符号链接

```bash
# Windows (需要管理员权限)
mklink /D "C:\Users\你的用户名\.claude\skills\mattpocock" "D:\code project\agentscope\claude-skills"

# Linux/Mac
ln -s "/path/to/agentscope/claude-skills" ~/.claude/skills/mattpocock
```

## 📝 技能使用示例

### 代码审查
```
/code-review main
```
对比当前分支和 main 分支，进行双轴审查（标准+规范）

### 实现功能
```
/implement
```
基于规范或工单实现功能，包含 TDD 和代码审查流程

### 深度提问
```
/grilling
```
通过深度提问帮助你澄清想法和设计决策

### 问题分类
```
/triage
```
管理 issue tracker 中的问题，进行分类和优先级排序

## 🔍 技能格式

每个技能都包含：

- **SKILL.md** - 主要的技能定义文件
- **frontmatter** - YAML 格式的元数据（名称、描述等）
- **其他支持文件** - 部分技能包含额外的模板或参考文档

### Frontmatter 结构

```yaml
---
name: 技能名称
description: 技能描述
disable-model-invocation: true  # 可选，禁用模型调用
---
```

## 🛠️ 转换说明

这些技能文件已通过自动化脚本从 mattpocock/skills v1.3.1 转换而来：

- ✅ 保持了原始的 frontmatter 格式
- ✅ 保留了所有支持文件（如模板、参考文档）
- ✅ 维持了目录结构和分类
- ✅ 完全兼容 Claude Code 的技能系统

## 📚 更多资源

- 原始仓库：https://github.com/mattpocock/skills
- Claude Code 文档：https://claude.com/docs

## 🙏 致谢

感谢 [Matt Pocock](https://github.com/mattpocock) 创建并维护这些优秀的技能包。

## 📄 许可证

这些技能包遵循原始仓库的许可证。请查看 [mattpocock/skills](https://github.com/mattpocock/skills) 获取详细信息。
