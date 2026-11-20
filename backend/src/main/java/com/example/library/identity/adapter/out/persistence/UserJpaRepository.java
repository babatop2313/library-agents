package com.example.library.identity.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;

import com.example.library.identity.domain.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserJpaRepository extends JpaRepository<UserJpaEntity, UUID> {

    /** Comparaison sur lower(email) : même règle que l'index unique uk_app_user_email_lower. */
    @Query("select u from UserJpaEntity u where lower(u.email) = :email")
    Optional<UserJpaEntity> findByNormalizedEmail(@Param("email") String email);

    @Query("select count(u) > 0 from UserJpaEntity u join u.roles r where r = :role")
    boolean existsWithRole(@Param("role") Role role);
}
