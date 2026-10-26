package com.agentscope.brain.identity.internal.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

/** 登录链实施前关闭所有 HTTP 入口，并禁止 Boot 自动生成临时登录账号。 */
@Configuration(proxyBeanMethods = false)
class ClosedAccessConfiguration {
    @Bean
    SecurityFilterChain closedApplicationAccess(HttpSecurity http) throws Exception {
        // 尚无登录入口，不为被拒绝的匿名请求创建待登录 Session。
        http.requestCache(cache -> cache.disable());
        http.authorizeHttpRequests(requests -> requests.anyRequest().denyAll());
        return http.build();
    }

    @Bean
    UserDetailsService noImplicitAccounts() {
        return username -> {
            throw new UsernameNotFoundException("Platform authentication is not configured");
        };
    }
}
