package com.example.library.identity.adapter.in.web;

import java.util.Collection;
import java.util.List;

import com.example.library.identity.application.port.in.CurrentUser;
import com.example.library.identity.application.port.in.LoginCommand;
import com.example.library.identity.application.port.in.LoginResult;
import com.example.library.identity.domain.model.Role;

final class AuthWebMapper {

    private AuthWebMapper() {
    }

    static LoginCommand toCommand(LoginRequest request) {
        return new LoginCommand(request.email(), request.password());
    }

    static AuthenticatedUserResponse toResponse(LoginResult result) {
        return new AuthenticatedUserResponse(
                result.userId().value().toString(),
                result.email().value(),
                sortedNames(result.roles()),
                result.accessToken().expiresAt());
    }

    static AuthenticatedUserResponse toResponse(CurrentUser user) {
        return new AuthenticatedUserResponse(user.userId(), user.email(), sortedNames(user.roles()), user.expiresAt());
    }

    private static List<String> sortedNames(Collection<Role> roles) {
        return roles.stream().map(Role::name).sorted().toList();
    }
}
