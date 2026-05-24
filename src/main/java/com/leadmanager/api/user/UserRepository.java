package com.leadmanager.api.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence port for {@link User}.
 * <p>
 * Email-based lookups MUST go through {@link #findByEmailIgnoreCase(String)};
 * the {@code users_email_lower_uq} functional unique index in
 * {@code V3__create_users.sql} matches {@code LOWER(email)}, so any query that
 * compares {@code email = :email} directly would do a sequential scan and
 * also fail to detect "Foo@bar.com" vs "foo@bar.com" duplicates.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    @Query("SELECT u FROM User u WHERE LOWER(u.email) = LOWER(:email)")
    Optional<User> findByEmailIgnoreCase(@Param("email") String email);

    @Query("SELECT (COUNT(u) > 0) FROM User u WHERE LOWER(u.email) = LOWER(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);
}
