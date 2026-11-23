package com.example.library.identity.application.usecase;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.example.library.identity.application.port.in.CurrentUser;
import com.example.library.identity.application.port.in.GetCurrentUserQuery;
import com.example.library.identity.application.port.in.GetCurrentUserUseCase;
import com.example.library.identity.domain.model.Role;
import com.example.library.shared.NonTransactionalUseCase;

/**
 * RM-AUTH-26 : identité lue dans les claims du JWT, sans accès à la base.
 * Aucune transaction ({@link NonTransactionalUseCase}) : on n'emprunte pas de connexion.
 */
@NonTransactionalUseCase
public class GetCurrentUserService implements GetCurrentUserUseCase {

    @Override
    public CurrentUser current(GetCurrentUserQuery query) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        List<String> claims = query.roles() == null ? List.of() : query.roles();
        for (String claim : claims) {
            Role.fromClaim(claim).ifPresent(roles::add);   // rôles inconnus ignorés
        }
        return new CurrentUser(query.subject(), query.email(), roles, query.expiresAt());
    }
}
