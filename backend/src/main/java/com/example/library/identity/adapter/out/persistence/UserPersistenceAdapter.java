package com.example.library.identity.adapter.out.persistence;

import java.util.Optional;

import com.example.library.identity.application.port.out.CheckLibrarianExistsPort;
import com.example.library.identity.application.port.out.LoadUserByEmailPort;
import com.example.library.identity.application.port.out.SaveUserPort;
import com.example.library.identity.domain.model.Email;
import com.example.library.identity.domain.model.Role;
import com.example.library.identity.domain.model.User;
import org.springframework.stereotype.Component;

@Component
class UserPersistenceAdapter implements LoadUserByEmailPort, CheckLibrarianExistsPort, SaveUserPort {

    private final UserJpaRepository repository;
    private final UserPersistenceMapper mapper;

    UserPersistenceAdapter(UserJpaRepository repository, UserPersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Optional<User> loadByEmail(Email email) {
        return repository.findByNormalizedEmail(email.value()).map(mapper::toDomain);
    }

    @Override
    public boolean librarianExists() {
        return repository.existsWithRole(Role.LIBRARIAN);
    }

    @Override
    public void save(User user) {
        // flush immédiat : une violation d'unicité (création concurrente) doit remonter ici
        repository.saveAndFlush(mapper.toJpa(user));
    }
}
