package com.recsys.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @NonNull
    private final LoginInterceptor loginInterceptor;

    @NonNull
    private final SlowRequestInterceptor slowRequestInterceptor;

    public WebConfig(@NonNull LoginInterceptor loginInterceptor,
                     @NonNull SlowRequestInterceptor slowRequestInterceptor) {
        this.loginInterceptor = loginInterceptor;
        this.slowRequestInterceptor = slowRequestInterceptor;
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        // Registered first so its preHandle runs before LoginInterceptor's and its
        // afterCompletion runs last — a request rejected by auth still gets timed.
        registry.addInterceptor(slowRequestInterceptor);
        registry.addInterceptor(loginInterceptor);
    }
}
