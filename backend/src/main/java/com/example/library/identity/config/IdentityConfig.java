package com.example.library.identity.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({LibrarianBootstrapProperties.class, DevMemberProperties.class})
public class IdentityConfig {
}
