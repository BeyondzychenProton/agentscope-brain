/**
 * 业务会话的一致快照、不可变展示版本、持久批次与 SSE。读取路径不执行模型或会议写操作。
 * 对外协作只使用 api；internal 不允许其他模块引用。详见 backend/开发规范.md。
 */
package com.agentscope.brain.projection;
