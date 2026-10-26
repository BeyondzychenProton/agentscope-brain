/**
 * 可信平台用户、认证与受限改密、退出和执行资格。Security 与 Session 实现放 internal，跨模块身份接口放 api。
 * 对外协作只使用 api；internal 不允许其他模块引用。详见 backend/开发规范.md。
 */
package com.agentscope.brain.identity;
