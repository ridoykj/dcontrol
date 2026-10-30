package com.dcontrol.controller.auth;

import com.dcontrol.controller.common.crud.ScopedRepository;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends ScopedRepository<User> {

    @Query("select u from User u where lower(u.email) = lower(:email) and u.active = true")
    Optional<User> findActiveByEmail(@Param("email") String email);
}
