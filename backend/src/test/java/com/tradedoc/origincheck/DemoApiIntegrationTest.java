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
        "app.demo.cleanup-initial-delay-ms=3600000",
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
    @Autowired
    com.tradedoc.origincheck.demo.DemoCleanup cleanup;
    @Autowired
    com.tradedoc.origincheck.check.storage.FileStorage storage;

    @BeforeEach
    void reset() {
        AGENT.reset();
        // 앞 테스트가 남긴 체험 건이 동시 제출 자리·대기 순번을 차지하지 않게 끝난 것으로 둔다
        jdbc.update("UPDATE origin_checks SET status = 'FAILED' WHERE demo AND status IN ('QUEUED', 'IN_PROGRESS')");
        jdbc.update("DELETE FROM demo_daily_usage");
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

    // ---------- 4. 사용량·부하 관리 ----------

    @Test
    void 체험_사용자는_3건까지_실패한_건도_세고_다시_실행은_세지_않는다() throws Exception {
        String token = demoToken();
        long first = upload(token, sample("kl-mackerel", "03_인보이스.pdf"));
        upload(token, sample("kl-mackerel", "05_BL.pdf"));

        // 첫 건을 실패시킨 뒤 다시 실행해도 사용량은 그대로(2건)
        jdbc.update("UPDATE origin_checks SET status = 'FAILED' WHERE id = ?", first);
        mvc.perform(auth(post("/api/v1/origin-checks/" + first + "/retry"), token)).andExpect(status().isAccepted());
        assertThat(used(token)).isEqualTo(2);

        upload(token, sample("kl-mackerel", "06_패킹리스트.pdf"));
        mvc.perform(multipartUpload(token, sample("kl-mackerel", "01_원산지증명서.pdf")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("DEMO_LIMIT"));
        assertThat(used(token)).isEqualTo(3);

        // 건을 지워도 사용량은 줄지 않는다
        jdbc.update("UPDATE origin_checks SET status = 'COMPLETED' WHERE id = ?", first);
        mvc.perform(auth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/origin-checks/" + first), token))
                .andExpect(status().isNoContent());
        mvc.perform(multipartUpload(token, sample("kl-mackerel", "01_원산지증명서.pdf")))
                .andExpect(jsonPath("$.code").value("DEMO_LIMIT"));
    }

    @Test
    void 하루_상한에_닿으면_DEMO_DAILY_LIMIT_이고_개인_사용량은_되돌린다() throws Exception {
        String token = demoToken();
        jdbc.update("INSERT INTO demo_daily_usage (day, used) VALUES (?, 50)",
                java.sql.Date.valueOf(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))));

        mvc.perform(multipartUpload(token, sample("kl-mackerel", "03_인보이스.pdf")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("DEMO_DAILY_LIMIT"));
        assertThat(used(token)).isZero();

        // 둘러보기(토큰 발급)는 막지 않는다
        mvc.perform(post("/api/v1/auth/demo")).andExpect(status().isCreated());
        // 정식 사용자는 상관없다
        upload(signup(), pdf("mine.pdf", pdfBytes()));
    }

    @Test
    void 체험_건_대기_순번과_동시_제출_제한() throws Exception {
        long a = upload(demoToken(), sample("vn-led-monitor", "01_인보이스.pdf"));
        long b = upload(demoToken(), sample("vn-led-monitor", "03_BL.pdf"));
        long c = upload(demoToken(), sample("vn-led-monitor", "06_수출신고필증.pdf"));
        String user = signup();
        long regular = upload(user, pdf("mine.pdf", pdfBytes()));

        String tc = tokenOf(c);
        mvc.perform(auth(get("/api/v1/origin-checks/" + c), tc)).andExpect(jsonPath("$.queue_position").value(3));
        mvc.perform(auth(get("/api/v1/origin-checks/" + regular), user)).andExpect(jsonPath("$.queue_position").doesNotExist());

        worker.tick(); // 체험 2건 + 정식 건 제출, 체험 1건은 대기
        assertThat(statusOf(a)).isEqualTo("IN_PROGRESS");
        assertThat(statusOf(b)).isEqualTo("IN_PROGRESS");
        assertThat(statusOf(c)).isEqualTo("QUEUED");
        assertThat(statusOf(regular)).isEqualTo("IN_PROGRESS");
        mvc.perform(auth(get("/api/v1/origin-checks/" + c), tc)).andExpect(jsonPath("$.queue_position").value(1));
    }

    // ---------- 5. 정리 작업 ----------

    @Test
    void 만료된_체험_사용자는_점검_건_원본_파일_agent_파일까지_지운다() throws Exception {
        String token = demoToken();
        String email = email(token);
        long done = upload(token, sample("th-led-monitor", "01_인보이스.pdf"), sample("th-led-monitor", "03_BL.pdf"));
        worker.tick(); // 제출 → agent 파일이 생긴다
        String agentFile = jdbc.queryForObject("select agent_file_id from origin_checks where id = ?", String.class, done);
        assertThat(agentFile).isNotNull();
        java.util.List<String> keys = jdbc.queryForList(
                "select file_key from origin_checks where id = ? union select file_key from origin_check_files where check_id = ?",
                String.class, done, done);
        assertThat(keys).hasSize(3); // 합친 PDF + 원본 2개
        keys.forEach(k -> assertThat(Files.exists(storage.path(k))).isTrue());

        String other = demoToken(); // 아직 만료 안 된 체험 사용자는 그대로
        long kept = upload(other, sample("th-led-monitor", "02_패킹리스트.pdf"));

        expire(email);
        cleanup.run();

        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from origin_checks where id = ?", Integer.class, done)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from origin_check_files where check_id = ?", Integer.class, done)).isZero();
        keys.forEach(k -> assertThat(Files.exists(storage.path(k))).isFalse());
        assertThat(AGENT.deleted).contains(agentFile);
        assertThat(jdbc.queryForObject("select count(*) from origin_checks where id = ?", Integer.class, kept)).isOne();
        // 지운 사용자의 토큰은 더는 쓸 수 없다
        mvc.perform(auth(get("/api/v1/auth/me"), token)).andExpect(status().isUnauthorized());
    }

    @Test
    void agent_파일을_못_지우면_그_사용자는_다음_차례로_미룬다() throws Exception {
        String token = demoToken();
        long id = upload(token, sample("de-led-bulbs", "01_인보이스.pdf"));
        worker.tick();
        expire(email(token));

        AGENT.deleteError = new com.tradedoc.origincheck.agent.AgentException("Upstage 파일 삭제 실패 (HTTP 503)");
        cleanup.run();
        assertThat(jdbc.queryForObject("select count(*) from origin_checks where id = ?", Integer.class, id)).isOne();

        AGENT.deleteError = null;
        cleanup.run();
        assertThat(jdbc.queryForObject("select count(*) from origin_checks where id = ?", Integer.class, id)).isZero();
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

    /** 앱은 UTC 로 저장하므로 DB 의 UTC 시계로 1분 전을 넣는다(Timestamp 는 JVM 시간대로 바뀐다) */
    void expire(String email) {
        jdbc.update("UPDATE users SET expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE WHERE email = ?", email);
    }

    int used(String token) throws Exception {
        return jdbc.queryForObject("select demo_checks_used from users where email = ?", Integer.class, email(token));
    }

    String statusOf(long id) {
        return jdbc.queryForObject("select status from origin_checks where id = ?", String.class, id);
    }

    /** 점검 건 주인의 토큰(테스트용으로 같은 비밀 키로 만든다) */
    String tokenOf(long checkId) {
        var row = jdbc.queryForMap("select u.id, u.email from users u join origin_checks c on c.user_id = u.id where c.id = ?", checkId);
        return io.jsonwebtoken.Jwts.builder().subject(String.valueOf(row.get("id"))).claim("email", row.get("email")).claim("demo", true)
                .issuer("fta-origin-check-api").issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 600_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
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
