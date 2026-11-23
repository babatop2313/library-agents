package com.example.library.identity.application.port.in;

public interface LoginUseCase {

    LoginResult login(LoginCommand command);
}
