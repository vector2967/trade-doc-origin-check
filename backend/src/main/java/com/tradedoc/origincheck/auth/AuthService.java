package com.tradedoc.origincheck.auth;

import com.tradedoc.origincheck.auth.dto.LoginRequest;
import com.tradedoc.origincheck.auth.dto.MeResponse;
import com.tradedoc.origincheck.auth.dto.SignupRequest;
import com.tradedoc.origincheck.auth.dto.TokenResponse;
import com.tradedoc.origincheck.common.ApiException;
import com.tradedoc.origincheck.common.ErrorCode;
import com.tradedoc.origincheck.user.User;
import com.tradedoc.origincheck.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tradedoc.origincheck.demo.DemoProperties;
import com.tradedoc.origincheck.demo.DemoRateLimiter;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final SignupProperties signupProps;
    private final String dummyHash;

    private final DemoProperties demoProps;
    private final DemoRateLimiter demoLimiter;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtProvider jwtProvider,
                       SignupProperties signupProps, DemoProperties demoProps, DemoRateLimiter demoLimiter) {
        this.users = users;
        this.demoProps = demoProps;
        this.demoLimiter = demoLimiter;
        this.signupProps = signupProps;
        this.passwordEncoder = passwordEncoder;
        this.jwtProvider = jwtProvider;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    /** 가입 후 바로 로그인 상태로 토큰을 준다(화면명세서: 가입 성공 → 자동 로그인). */
    @Transactional
    public TokenResponse signup(SignupRequest req) {
        String email = normalize(req.email());
        if (!signupProps.allows(email)) {
            throw new ApiException(ErrorCode.SIGNUP_NOT_ALLOWED);
        }
        if (users.existsByEmail(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        User user;
        try {
            user = users.saveAndFlush(new User(email, passwordEncoder.encode(req.password()), req.name().trim(),
                    req.company() == null || req.company().isBlank() ? null : req.company().trim()));
        } catch (DataIntegrityViolationException e) { // 동시에 같은 이메일로 가입
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        return token(user);
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest req) {
        Optional<User> user = users.findByEmail(normalize(req.email()));
        // 없는 이메일도 같은 시간이 걸리게 해서 가입 여부가 드러나지 않게 한다
        boolean ok = passwordEncoder.matches(req.password(), user.map(User::getPasswordHash).orElse(dummyHash));
        if (!ok || user.isEmpty() || user.get().isDemo()) { // 체험 사용자는 로그인할 수 없다
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        return token(user.get());
    }

    @Transactional(readOnly = true)
    public MeResponse me(AuthUser authUser) {
        User user = users.findById(authUser.id())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "사용자를 찾을 수 없습니다."));
        return new MeResponse(user.getId(), user.getEmail(), user.getName(), user.getCompany(), authUser.expiresAt(),
                user.isDemo());
    }

    /**
     * 체험 사용자를 만들고 토큰을 준다(가입 허용 목록과 무관). 토큰은 {@link DemoProperties#tokenTtl()},
     * 데이터는 {@link DemoProperties#dataTtl()} 뒤에 정리 작업이 지운다.
     *
     * @throws ApiException NOT_FOUND(체험 꺼짐) · DEMO_RATE_LIMIT(IP 당 시간당 횟수 초과)
     */
    @Transactional
    public TokenResponse startDemo(String clientIp) {
        if (!demoProps.enabled()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        Instant now = Instant.now();
        if (!demoLimiter.tryAcquire(clientIp, now)) {
            throw new ApiException(ErrorCode.DEMO_RATE_LIMIT);
        }
        String email = "demo-" + UUID.randomUUID() + "@demo.invalid";
        // 아무도 모르는 비밀번호의 해시. login() 이 체험 사용자를 따로 막으므로 이중 장치다
        String unusable = passwordEncoder.encode(UUID.randomUUID() + ":" + UUID.randomUUID());
        User user = users.save(User.demo(email, unusable, now.plus(demoProps.dataTtl())));
        long ttl = demoProps.tokenTtl().toSeconds();
        return TokenResponse.bearer(jwtProvider.issue(user.getId(), user.getEmail(), true, ttl), ttl);
    }

    private TokenResponse token(User user) {
        return TokenResponse.bearer(jwtProvider.issue(user.getId(), user.getEmail()), jwtProvider.expiresInSeconds());
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
