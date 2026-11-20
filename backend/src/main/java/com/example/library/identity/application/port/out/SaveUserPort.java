package com.example.library.identity.application.port.out;

import com.example.library.identity.domain.model.User;

public interface SaveUserPort {

    void save(User user);
}
