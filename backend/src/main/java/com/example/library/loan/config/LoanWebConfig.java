package com.example.library.loan.config;

import java.util.List;

import com.example.library.loan.adapter.in.web.CurrentMemberArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Enregistre la résolution de {@code @CurrentMember} (membre connecté, lu dans le JWT) pour les contrôleurs. */
@Configuration
public class LoanWebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentMemberArgumentResolver());
    }
}
