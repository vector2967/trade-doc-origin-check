package com.tradedoc.origincheck;

import com.tradedoc.origincheck.agent.CheckAgent;
import com.tradedoc.origincheck.check.OriginCheckWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 체험하기(계정 없이 써 보기) API. 실제 MySQL(컨테이너) + 가짜 agent. Upstage 를 부르지 않는다.
 * 워커·정리 작업은 자동 실행을 끄고 테스트에서 직접 부른다.
 */
@SpringBootTest(properties = {
        "app.agent.mode=test",
        "app.agent.initial-delay-ms=3600000",
        "app.agent.poll-interval-ms=3600000",
        "app.jwt.secret=" + DemoApiIntegrationTest.SECRET,
        "app.jwt.expires-min=60",
        "app.demo.tokens-per-ip-hour=1000",   // 테스트끼리 같은 IP 를 쓰므로 넉넉히. 제한 자체는 단위 테스트에서 본다
        "app.demo.cleanup-interval-ms=3600000",
})
@AutoConfigureMockMvc
@Testcontainers
class DemoApiIntegrationTest {

    static final String SECRET = "demo-integration-secret-demo-integration-secret";

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    static final OriginCheckApiIntegrationTest.StubAgent AGENT = new OriginCheckApiIntegrationTest.StubAgent();

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) throws Exception {
        Path dir = Files.createTempDirectory("origin-check-demo-it");
        registry.add("app.storage.dir", dir::toString);
    }

    @TestConfiguration
    static class StubAgentConfig {
        @Bean
        CheckAgent checkAgent() {
            return AGENT;
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    OriginCheckWorker worker;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        AGENT.reset();
    }

    // ---------- 1. 체험 사용자 ----------

    @Test
    void 체험_토큰을_받으면_me_demo_가_true() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/demo"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token_type").value("bearer"))
                .andExpect(jsonPath("$.expires_in").value(7200))
                .andReturn();
        String token = body(r).get("access_token").asString();

        MvcResult me = mvc.perform(auth(get("/api/v1/auth/me"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demo").value(true))
                .andExpect(jsonPath("$.name").value("체험 사용자"))
                .andExpect(jsonPath("$.company").doesNotExist())
                .andReturn();
        String email = body(me).get("email").asString();
        assertThat(email).startsWith("demo-").endsWith("@demo.invalid");

        // DB: 체험 표시와 24시간 뒤 삭제 시각
        var row = jdbc.queryForMap("select demo, timestampdiff(second, created_at, expires_at) s from users where email = ?", email);
        assertThat(row.get("demo")).isIn(true, 1, 1L);
        assertThat(((Number) row.get("s")).longValue()).isBetween(24 * 3600L - 5, 24 * 3600L);
    }

    @Test
    void 체험_계정으로는_로그인할_수_없다() throws Exception {
        String token = demoToken();
        String email = body(mvc.perform(auth(get("/api/v1/auth/me"), token)).andReturn()).get("email").asString();

        mvc.perform(json(post("/api/v1/auth/login"), "{\"email\":\"" + email + "\",\"password\":\"anything-123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void 정식_사용자의_me_demo_는_false() throws Exception {
        MvcResult r = mvc.perform(json(post("/api/v1/auth/signup"),
                        "{\"email\":\"user-" + System.nanoTime() + "@example.com\",\"password\":\"password123\",\"name\":\"정식\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expires_in").value(3600))
                .andReturn();
        mvc.perform(auth(get("/api/v1/auth/me"), body(r).get("access_token").asString()))
                .andExpect(jsonPath("$.demo").value(false));
    }

    // ---------- 도우미 ----------

    String demoToken() throws Exception {
        return body(mvc.perform(post("/api/v1/auth/demo")).andExpect(status().isCreated()).andReturn())
                .get("access_token").asString();
    }

    static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    JsonNode body(MvcResult r) throws Exception {
        return mapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
