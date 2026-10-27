# 会话改名（前端切片 3：头部 + 侧栏）

日期：2026-10-27。依据工作台规格 Q14 与[后端接口与事务锁序契约 §3／§10](../../正式应用设计/后端接口与事务锁序契约.md)。不做归档或 #41。

## 目标

本人普通会话可手工改名；头部 `···` 与侧栏共用同一对话框与 `PATCH` 写路径；成功后侧栏与（若为当前会话）页头就地更新。

## 做法

共用改名用例 + 单一 `RenameSessionDialog`；不就地双击编辑。

## 模块边界

| 单元 | 职责 | 非职责 |
| --- | --- | --- |
| 后端 SessionTitle | 本人校验、非空／≤200 字符、CAS `expectedSessionVersion`、`requestKey` 幂等、返回 SessionSummary | 不建消息／Run、不调模型 |
| sessionTitle 客户端 + 对话框 | 预校验、写请求、冲突文案 | 不改投影协议 |
| 头部 `···` | 仅已打开本人会话时可改名 | 新建空白页禁用 |
| 侧栏项 | 对该会话打开同一对话框 | 不归档 |

## 请求语义

`PATCH /api/sessions/{sessionId}/title`  
`{ requestKey, expectedSessionVersion, title }` → `200 SessionSummary`。

锁序：L1→L2/L3→L7→L15。同键不覆盖较新标题；版本冲突不写入。trim 后空或超长拒绝。

## 成功后更新

- 侧栏按 sessionId 打补丁 title／sessionVersion／updatedAt。  
- 当前路由会话：同步页头／snapshot 标题与 version。  
- 写请求不自动重试；对话框一轮固定一个 requestKey。

## 验收

1. 头部与侧栏均可改名，共用对话框。  
2. 成功无新消息／Run；列表与页头标题正确。  
3. 空／超长／冲突／404／401 受控提示。  
4. 无 sessionId 时头部改名不可用。

## 非目标

归档／恢复、版本历史、侧栏就地双击、打断 Run、投影协议变更、过程时间线。
