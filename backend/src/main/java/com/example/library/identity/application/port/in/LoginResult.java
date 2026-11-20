package com.example.library.identity.application.port.in;

import java.util.Set;

import com.example.library.identity.domain.model.AccessToken;
import com.example.library.identity.domain.model.Email;
import com.example.library.identity.domain.model.Role;
import com.example.library.identity.domain.model.UserId;

public record LoginResult(UserId userId, Email email, Set<Role> roles, AccessToken accessToken) {
}
