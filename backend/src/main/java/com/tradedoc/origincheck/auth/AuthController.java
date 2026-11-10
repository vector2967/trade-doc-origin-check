package com.tradedoc.origincheck.auth;

import com.tradedoc.origincheck.auth.dto.LoginRequest;
import com.tradedoc.origincheck.auth.dto.MeResponse;
import com.tradedoc.origincheck.auth.dto.SignupRequest;
import com.tradedoc.origincheck.auth.dto.TokenResponse;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse signup(@Valid @RequestBody SignupRequest req) {
        return authService.signup(req);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    /** 체험 사용자를 만들고 토큰을 준다(인증 없음). 체험이 꺼져 있으면 404 */
    @PostMapping("/demo")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse demo(HttpServletRequest request) {
        return authService.startDemo(request.getRemoteAddr()); // 프록시 뒤에서는 X-Forwarded-For 가 반영된 값(forward-headers-strategy)
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AuthUser user) {
        return authService.me(user);
    }
}
