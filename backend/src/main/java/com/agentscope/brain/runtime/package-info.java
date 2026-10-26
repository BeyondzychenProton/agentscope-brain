/**
 * 原 Run 的调度、续接、取消、可信 SDK 调用与安全终结。状态 CAS、容量和 fence 由 internal 管理。
 * 对外协作只使用 api；internal 不允许其他模块引用。详见 backend/开发规范.md。
 */
package com.agentscope.brain.runtime;
