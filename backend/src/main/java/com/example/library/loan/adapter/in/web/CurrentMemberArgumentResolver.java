package com.example.library.loan.adapter.in.web;

import java.util.UUID;

import com.example.library.loan.domain.model.MemberId;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Résout {@code @CurrentMember MemberId} depuis le {@code sub} du JWT authentifié. Les paramètres sont résolus de
 * gauche à droite : placé en premier, il est résolu avant le corps et le chemin, donc un {@code sub} non UUID donne
 * 401 avant tout 400 ou 404.
 */
public class CurrentMemberArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentMember.class)
                && MemberId.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt) || jwt.getSubject() == null) {
            throw new InvalidMemberIdentityException();
        }
        try {
            String subject = jwt.getSubject();
            UUID uuid = UUID.fromString(subject);
            // UUID.fromString accepte des formes non canoniques ("1-2-3-4-5") : seule la forme canonique est valide
            if (!uuid.toString().equalsIgnoreCase(subject)) {
                throw new InvalidMemberIdentityException();
            }
            return new MemberId(uuid);
        } catch (IllegalArgumentException e) {
            throw new InvalidMemberIdentityException(e);
        }
    }
}
