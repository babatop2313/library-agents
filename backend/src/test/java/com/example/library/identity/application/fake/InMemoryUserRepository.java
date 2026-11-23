package com.example.library.identity.application.fake;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.library.identity.application.port.out.CheckLibrarianExistsPort;
import com.example.library.identity.application.port.out.LoadUserByEmailPort;
import com.example.library.identity.application.port.out.SaveUserPort;
import com.example.library.identity.domain.model.Email;
import com.example.library.identity.domain.model.Role;
import com.example.library.identity.domain.model.User;

/** Faux en mémoire des ports de sortie de persistance du module identity. */
public class InMemoryUserRepository implements LoadUserByEmailPort, CheckLibrarianExistsPort, SaveUserPort {

    private final Map<String, User> byEmail = new LinkedHashMap<>();
    private final List<User> saved = new ArrayList<>();

    /** Pré-charge un utilisateur sans compter comme une sauvegarde du use case. */
    public void givenUser(User user) {
        byEmail.put(user.email().value(), user);
    }

    public List<User> saved() {
        return saved;
    }

    public int count() {
        return byEmail.size();
    }

    @Override
    public Optional<User> loadByEmail(Email email) {
        return Optional.ofNullable(byEmail.get(email.value()));
    }

    @Override
    public boolean librarianExists() {
        return byEmail.values().stream().anyMatch(u -> u.hasRole(Role.LIBRARIAN));
    }

    @Override
    public void save(User user) {
        saved.add(user);
        byEmail.put(user.email().value(), user);
    }
}
