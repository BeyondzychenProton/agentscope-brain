-- 只在本机 MySQL 3306 执行一次；遇到同名数据库已存在时停止并先检查现有数据。
-- 本脚本不会删除或修改其他数据库。
CREATE DATABASE agentscope_brain_dev
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;
