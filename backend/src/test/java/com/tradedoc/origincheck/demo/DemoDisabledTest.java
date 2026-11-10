package com.tradedoc.origincheck.demo;

import com.tradedoc.origincheck.auth.AuthService;
import com.tradedoc.origincheck.auth.JwtProvider;
import com.tradedoc.origincheck.auth.SignupProperties;
import com.tradedoc.origincheck.common.ApiException;
import com.tradedoc.origincheck.common.ErrorCode;
import com.tradedoc.origincheck.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** DEMO_ENABLED=false 면 체험 API 3개가 404 */
class DemoDisabledTest {

    private final DemoProperties off = new DemoProperties(false, null, null, null, null, null, null);

    @Test
    void 체험_서류_목록과_PDF가_404() {
        DemoController c = new DemoController(new DemoSamples(JsonMapper.builder().build()), off);
        assertThatThrownBy(c::list).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> c.file("kl-mackerel", "03_인보이스.pdf")).isInstanceOf(ApiException.class);
    }

    @Test
    void 체험_토큰_발급이_404이고_사용자를_만들지_않는다() {
        UserRepository users = mock(UserRepository.class);
        AuthService auth = new AuthService(users, NoOpPasswordEncoder.getInstance(), mock(JwtProvider.class),
                new SignupProperties(null), off, new DemoRateLimiter(off));
        assertThatThrownBy(() -> auth.startDemo("1.1.1.1")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(users);
    }
}
