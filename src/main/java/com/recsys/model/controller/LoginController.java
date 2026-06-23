package com.recsys.model.controller;

import com.recsys.config.NeedLogin;
import com.recsys.config.LoginProperties;
import com.recsys.config.RequestScopeData;
import com.recsys.model.dto.ApiResponse;
import com.recsys.exception.UnauthorizedException;
import com.recsys.model.request.LoginRequest;
import com.recsys.model.response.LoginResponse;
import com.recsys.model.service.LoginTokenService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/auth")
public class LoginController {

    private final LoginTokenService loginTokenService;
    private final LoginProperties loginProperties;
    private final RequestScopeData requestScopeData;

    public LoginController(LoginTokenService loginTokenService,
                           LoginProperties loginProperties,
                           RequestScopeData requestScopeData) {
        this.loginTokenService = loginTokenService;
        this.loginProperties = loginProperties;
        this.requestScopeData = requestScopeData;
    }

    @PostMapping(
            value = "/login",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        if (!loginProperties.isEnabled()) {
            throw new UnauthorizedException("login is disabled on this server");
        }
        boolean matched = loginProperties.parsedApiKeys().stream()
                .anyMatch(key -> constantTimeEquals(key, request.getApiKey()));
        if (!matched) {
            throw new UnauthorizedException("invalid API key");
        }
        String token = loginTokenService.create(request.getApiKey());
        return new LoginResponse(token, LoginTokenService.TTL_SECONDS);
    }

    @NeedLogin
    @PostMapping(value = "/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Void> logout() {
        loginTokenService.invalidate(requestScopeData.getToken());
        return ApiResponse.success();
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
