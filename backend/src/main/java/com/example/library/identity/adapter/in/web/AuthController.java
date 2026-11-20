package com.example.library.identity.adapter.in.web;

import com.example.library.config.security.JwtClaims;
import com.example.library.identity.application.port.in.CurrentUser;
import com.example.library.identity.application.port.in.GetCurrentUserQuery;
import com.example.library.identity.application.port.in.GetCurrentUserUseCase;
import com.example.library.identity.application.port.in.LoginResult;
import com.example.library.identity.application.port.in.LoginUseCase;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentification : connexion, identité courante, déconnexion.
 * login et logout sont publics (voir SecurityConfig) ; ils ne modifient aucune donnée.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final LoginUseCase loginUseCase;
    private final GetCurrentUserUseCase getCurrentUserUseCase;
    private final AccessTokenCookies cookies;

    AuthController(LoginUseCase loginUseCase, GetCurrentUserUseCase getCurrentUserUseCase,
                   AccessTokenCookies cookies) {
        this.loginUseCase = loginUseCase;
        this.getCurrentUserUseCase = getCurrentUserUseCase;
        this.cookies = cookies;
    }

    @PostMapping("/login")
    ResponseEntity<AuthenticatedUserResponse> login(@Valid @RequestBody LoginRequest request,
                                                    HttpServletResponse response) {
        LoginResult result = loginUseCase.login(AuthWebMapper.toCommand(request));
        // addHeader et non ResponseEntity.header : on conserve le Set-Cookie XSRF-TOKEN déjà posé par CsrfCookieFilter
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.issue(result.accessToken()).toString());
        return ResponseEntity.ok(AuthWebMapper.toResponse(result));
    }

    @GetMapping("/me")
    AuthenticatedUserResponse me(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser current = getCurrentUserUseCase.current(new GetCurrentUserQuery(
                jwt.getSubject(),
                jwt.getClaimAsString(JwtClaims.EMAIL),
                jwt.getClaimAsStringList(JwtClaims.ROLES),
                jwt.getExpiresAt()));
        return AuthWebMapper.toResponse(current);
    }

    /** Pur transport HTTP : vide le cookie. Le JWT n'est pas révoqué côté serveur (RM-AUTH-27). */
    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear().toString());
        return ResponseEntity.noContent().build();
    }
}
