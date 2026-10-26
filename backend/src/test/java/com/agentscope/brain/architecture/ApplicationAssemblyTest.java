package com.agentscope.brain.architecture;

import com.agentscope.brain.AgentScopeBrainApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 用正式装配入口验证骨架；仅连接进程内 H2 和回环 HTTP。 */
class ApplicationAssemblyTest {
    @Test
    void applicationAssemblesWithoutSharedResourcesOrImplicitAccounts() throws Exception {
        try (var context = new SpringApplicationBuilder(AgentScopeBrainApplication.class)
                .run("--spring.config.location=classpath:/application-test.yaml",
                        "--spring.profiles.active=test", "--spring.main.banner-mode=off")) {
            try (var connection = context.getBean(DataSource.class).getConnection()) {
                assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
                assertThat(connection.getMetaData().getTables(null, null, "SPRING_SESSION", null).next())
                        .isFalse();
            }
            assertThat(context.getBean(JdbcIndexedSessionRepository.class)).isNotNull();
            assertThatThrownBy(() -> context.getBean(UserDetailsService.class).loadUserByUsername("user"))
                    .isInstanceOf(org.springframework.security.core.userdetails.UsernameNotFoundException.class);

            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/me"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        }
    }
}
