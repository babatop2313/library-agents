package com.example.library.identity.application.port.out;

import java.util.Optional;

import com.example.library.identity.domain.model.Email;
import com.example.library.identity.domain.model.User;

public interface LoadUserByEmailPort {

    Optional<User> loadByEmail(Email email);
}
