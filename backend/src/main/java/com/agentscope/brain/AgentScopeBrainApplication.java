package com.agentscope.brain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 正式后端的唯一启动与装配入口。 */
@SpringBootApplication
public class AgentScopeBrainApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentScopeBrainApplication.class, args);
    }
}
