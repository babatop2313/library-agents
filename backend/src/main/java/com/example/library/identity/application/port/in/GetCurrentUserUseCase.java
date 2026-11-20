package com.example.library.identity.application.port.in;

public interface GetCurrentUserUseCase {

    CurrentUser current(GetCurrentUserQuery query);
}
