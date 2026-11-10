package com.tradedoc.origincheck.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** 지울 때가 된 체험 사용자 */
    List<User> findTop50ByDemoTrueAndExpiresAtBeforeOrderByIdAsc(Instant now);
}
