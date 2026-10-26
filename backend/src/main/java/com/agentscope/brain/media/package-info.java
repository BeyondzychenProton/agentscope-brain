/**
 * 消息素材的上传准备、核验、绑定、鉴权读取与废弃。MinIO、暂存和清理竞争由 internal 管理。
 * 对外协作只使用 api；internal 不允许其他模块引用。详见 backend/开发规范.md。
 */
package com.agentscope.brain.media;
