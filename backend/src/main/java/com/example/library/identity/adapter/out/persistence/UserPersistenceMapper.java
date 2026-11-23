package com.example.library.identity.adapter.out.persistence;

import com.example.library.identity.domain.model.Email;
import com.example.library.identity.domain.model.PasswordHash;
import com.example.library.identity.domain.model.User;
import com.example.library.identity.domain.model.UserId;
import org.springframework.stereotype.Component;

@Component
class UserPersistenceMapper {

    User toDomain(UserJpaEntity entity) {
        return User.restore(
                new UserId(entity.getId()),
                Email.of(entity.getEmail()),
                new PasswordHash(entity.getPasswordHash()),
                entity.isEnabled(),
                entity.getRoles(),
                entity.getCreatedAt());
    }

    UserJpaEntity toJpa(User user) {
        return new UserJpaEntity(
                user.id().value(),
                user.email().value(),
                user.passwordHash().value(),
                user.enabled(),
                user.roles(),
                user.createdAt());
    }
}
