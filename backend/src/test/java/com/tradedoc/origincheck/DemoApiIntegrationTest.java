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
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    // ---------- 2. 체험용 서류 ----------

    @Test
    void 체험용_서류_목록은_인증_없이_4세트() throws Exception {
        mvc.perform(get("/api/v1/demo/samples"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sets.length()").value(4))
                .andExpect(jsonPath("$.sets[0].id").value("kl-mackerel"))
                .andExpect(jsonPath("$.sets[0].name").value("말레이시아 · 냉동 고등어"))
                .andExpect(jsonPath("$.sets[0].files.length()").value(6))
                .andExpect(jsonPath("$.sets[0].files[0].name").value("01_원산지증명서.pdf"))
                .andExpect(jsonPath("$.sets[0].files[0].hint_type").value("원산지증명서"))
                .andExpect(jsonPath("$.sets[0].files[0].size").value(172065))
                .andExpect(jsonPath("$.sets[0].files[0].sha256").doesNotExist())
                .andExpect(jsonPath("$.sets[3].id").value("de-led-bulbs"))
                .andExpect(jsonPath("$.sets[3].files.length()").value(5));
    }

    @Test
    void 체험용_PDF는_하루_캐시하고_없는_이름은_404() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/demo/samples/{set}/{name}", "kl-mackerel", "03_인보이스.pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=86400, public"))
                .andReturn();
        assertThat(r.getResponse().getContentAsByteArray()).hasSize(249285).startsWith("%PDF".getBytes());

        // 자모가 풀린(NFD) 이름도 같은 파일
        mvc.perform(get("/api/v1/demo/samples/{set}/{name}", "kl-mackerel",
                        java.text.Normalizer.normalize("03_인보이스.pdf", java.text.Normalizer.Form.NFD)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/demo/samples/{set}/{name}", "kl-mackerel", "99_없는파일.pdf"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/demo/samples/{set}/{name}", "nope", "03_인보이스.pdf"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/demo/samples/{set}/{name}", "kl-mackerel", "..%2Fmanifest.json"))
                .andExpect(status().is4xxClientError()); // 요청 방화벽이 400 으로 먼저 막는다
        mvc.perform(get("/api/v1/demo/samples/{set}/{name}", "kl-mackerel", "manifest.json"))
                .andExpect(status().isNotFound());
    }

    // ---------- 3. 체험 접수 검사 ----------

    @Test
    void 세트_파일을_전부_올리면_접수되고_워커가_제출한다() throws Exception {
        String token = demoToken();
        long id = upload(token, sample("kl-mackerel", "01_원산지증명서.pdf"), sample("kl-mackerel", "02_수출신고필증.pdf"),
                sample("kl-mackerel", "03_인보이스.pdf"), sample("kl-mackerel", "04_원산지확인서.pdf"),
                sample("kl-mackerel", "05_BL.pdf"), sample("kl-mackerel", "06_패킹리스트.pdf"));

        assertThat(jdbc.queryForObject("select demo from origin_checks where id = ?", Boolean.class, id)).isTrue();
        worker.tick();
        assertThat(jdbc.queryForObject("select status from origin_checks where id = ?", String.class, id)).isEqualTo("IN_PROGRESS");
        assertThat(AGENT.lastJobId).isNotNull();
    }

    @Test
    void 세트_일부만_올려도_접수되고_이름을_바꿔도_내용으로_본다() throws Exception {
        String token = demoToken();
        upload(token, sample("de-led-bulbs", "01_인보이스.pdf"), sample("de-led-bulbs", "03_BL.pdf"));
        upload(token, renamed(sample("th-led-monitor", "01_인보이스.pdf"), "invoice.pdf"));
    }

    @Test
    void 체험용이_아닌_PDF나_두_세트를_섞으면_403() throws Exception {
        String token = demoToken();
        mvc.perform(multipartUpload(token, pdf("mine.pdf", pdfBytes())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEMO_SAMPLES_ONLY"));
        mvc.perform(multipartUpload(token, sample("kl-mackerel", "03_인보이스.pdf"), sample("vn-led-monitor", "01_인보이스.pdf")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEMO_SAMPLES_ONLY"));
        // 체험용 파일 사이에 자기 PDF 를 하나 끼워도 막는다
        mvc.perform(multipartUpload(token, sample("kl-mackerel", "03_인보이스.pdf"), pdf("03_인보이스.pdf", pdfBytes())))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from origin_checks c join users u on u.id = c.user_id where u.email = ?",
                Integer.class, email(token))).isZero();
    }

    @Test
    void 정식_사용자는_체험_검사를_받지_않는다() throws Exception {
        String token = signup();
        long id = upload(token, pdf("mine.pdf", pdfBytes()));
        assertThat(jdbc.queryForObject("select demo from origin_checks where id = ?", Boolean.class, id)).isFalse();
    }

    // ---------- 도우미 ----------

    String signup() throws Exception {
        MvcResult r = mvc.perform(json(post("/api/v1/auth/signup"),
                        "{\"email\":\"user-" + System.nanoTime() + "@example.com\",\"password\":\"password123\",\"name\":\"정식\"}"))
                .andExpect(status().isCreated()).andReturn();
        return body(r).get("access_token").asString();
    }

    String email(String token) throws Exception {
        return body(mvc.perform(auth(get("/api/v1/auth/me"), token)).andReturn()).get("email").asString();
    }

    static MockMultipartFile sample(String setId, String name) throws Exception {
        try (var in = new ClassPathResource("demo/" + setId + "/" + name).getInputStream()) {
            return pdf(name, in.readAllBytes());
        }
    }

    static MockMultipartFile pdf(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/pdf", bytes);
    }

    static MockMultipartFile renamed(MockMultipartFile f, String name) throws Exception {
        return pdf(name, f.getBytes());
    }

    static byte[] pdfBytes() throws Exception {
        try (var d = new org.apache.pdfbox.pdmodel.PDDocument(); var out = new java.io.ByteArrayOutputStream()) {
            d.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            d.save(out);
            return out.toByteArray();
        }
    }

    org.springframework.test.web.servlet.RequestBuilder multipartUpload(String token, MockMultipartFile... files) {
        var b = multipart("/api/v1/origin-checks");
        for (MockMultipartFile f : files) {
            b.file(f);
        }
        b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return b;
    }

    long upload(String token, MockMultipartFile... files) throws Exception {
        MvcResult r = mvc.perform(multipartUpload(token, files)).andExpect(status().isAccepted()).andReturn();
        return body(r).get("id").asLong();
    }

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
