package com.agentscope.brain.baseline;

import com.agentscope.brain.validation.ClosedDraft7Validator;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.networknt.schema.SchemaRegistry;
import io.agentscope.core.tool.ToolValidator;
import io.minio.MinioClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 实际启动 Boot servlet 隔离装配；只使用内存 H2 执行 SELECT 1，无业务库、DDL 或外部客户端请求。 */
class IsolatedAssemblyTest {
    @Test
    void fixedClasspathStartsBootAndMybatisWithBothSchemaEntrypoints() throws Exception {
        try (var context = new SpringApplicationBuilder(ProbeConfiguration.class)
                .run("--server.address=127.0.0.1", "--server.port=0", "--spring.main.banner-mode=off",
                        "--spring.datasource.url=jdbc:h2:mem:issue24;DB_CLOSE_DELAY=0",
                        "--spring.datasource.username=sa", "--spring.datasource.password=",
                        "--spring.sql.init.mode=never", "--spring.session.jdbc.initialize-schema=never",
                        "--spring.session.jdbc.cleanup-cron=-")) {
            assertThat(SpringBootVersion.getVersion()).isEqualTo("4.1.2");
            assertThat(context.getBean(ProbeMapper.class).one()).isEqualTo(1);
            var sessionFactory = context.getBean(SqlSessionFactory.class);
            assertThat(sessionFactory.getConfiguration()).isInstanceOf(MybatisConfiguration.class);
            assertThat(sessionFactory.getConfiguration().getEnvironment().getDataSource())
                    .isSameAs(context.getBean(DataSource.class));
            assertThat(context.getBean(JdbcIndexedSessionRepository.class)).isNotNull();
            assertThat(context.getBean(RequestMappingHandlerAdapter.class).getMessageConverters())
                    .anyMatch(JacksonJsonHttpMessageConverter.class::isInstance);
            JsonMapper webMapper = context.getBean(JsonMapper.class);
            assertThat(webMapper.readTree("{\"count\":1}").get("count").asInt()).isEqualTo(1);
            assertThat(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree("{\"count\":1}").get("count").asInt()).isEqualTo(1);
            assertThat(ToolValidator.validateInput("{\"count\":1}", Map.of(
                    "type", "object", "properties", Map.of("count", Map.of("type", "integer")))))
                    .isNull();
            assertThat(new ClosedDraft7Validator().validate(
                    "{\"type\":\"string\",\"format\":\"date\"}", "\"2025-02-29\""))
                    .isNotEmpty();
            assertThat(Flux.just(1, 2).reduce(Integer::sum).block()).isEqualTo(3);
            try (var client = MinioClient.builder().endpoint("http://127.0.0.1:1").build()) {
                assertThat(client).isNotNull(); // 只装配客户端；不调用网络方法。
            }

            Map<Class<?>, String> jars = Map.ofEntries(
                    Map.entry(JsonMapper.class, "jackson-databind-3.1.7.jar"),
                    Map.entry(com.fasterxml.jackson.databind.ObjectMapper.class, "jackson-databind-2.21.7.jar"),
                    Map.entry(com.fasterxml.jackson.annotation.JsonProperty.class, "jackson-annotations-2.21.jar"),
                    Map.entry(Flux.class, "reactor-core-3.8.8.jar"),
                    Map.entry(kotlin.KotlinVersion.class, "kotlin-stdlib-2.3.21.jar"),
                    Map.entry(okhttp3.OkHttpClient.class, "okhttp-jvm-5.3.2.jar"),
                    Map.entry(MinioClient.class, "minio-9.0.3.jar"),
                    Map.entry(ToolValidator.class, "agentscope-core-2.0.4.jar"),
                    Map.entry(SchemaRegistry.class, "json-schema-validator-3.0.6.jar"),
                    Map.entry(com.ethlo.time.ITU.class, "itu-1.14.0.jar"),
                    Map.entry(org.springframework.core.SpringVersion.class, "spring-core-7.0.10.jar"),
                    Map.entry(org.springframework.security.core.Authentication.class, "spring-security-core-7.1.2.jar"),
                    Map.entry(JdbcIndexedSessionRepository.class, "spring-session-jdbc-4.1.1.jar"),
                    Map.entry(Class.forName("com.mysql.cj.jdbc.Driver"), "mysql-connector-j-9.7.0.jar"),
                    Map.entry(org.mybatis.spring.SqlSessionTemplate.class, "mybatis-spring-4.0.0.jar"),
                    Map.entry(MybatisConfiguration.class, "mybatis-plus-core-3.5.17.jar"));
            StringBuilder origins = new StringBuilder("class\timplementationVersion\tcodeSource\n");
            for (Class<?> type : jars.keySet().stream().sorted(java.util.Comparator.comparing(Class::getName)).toList()) {
                String location = type.getProtectionDomain().getCodeSource().getLocation().toString();
                assertThat(location).endsWith("/" + jars.get(type));
                origins.append(type.getName()).append('\t')
                        .append(type.getPackage().getImplementationVersion()).append('\t')
                        .append(location).append('\n');
            }
            Path evidence = Path.of("target", "issue24");
            Files.createDirectories(evidence);
            Files.writeString(evidence.resolve("runtime-class-origins.tsv"), origins);
            StringBuilder conditions = new StringBuilder();
            ConditionEvaluationReport.get(context.getBeanFactory()).getConditionAndOutcomesBySource()
                    .forEach((source, outcomes) -> {
                        conditions.append(source).append(" fullMatch=").append(outcomes.isFullMatch()).append('\n');
                        outcomes.forEach(outcome -> conditions.append("  ")
                                .append(outcome.getOutcome()).append('\n'));
                    });
            Files.writeString(evidence.resolve("autoconfiguration.txt"), conditions);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan(basePackageClasses = ProbeMapper.class)
    static class ProbeConfiguration {
        @Bean
        UserDetailsService noBusinessAccounts() {
            return username -> { throw new UsernameNotFoundException("No accounts in the dependency probe"); };
        }
    }

    @Mapper
    interface ProbeMapper {
        @Select("SELECT 1")
        int one();
    }
}
