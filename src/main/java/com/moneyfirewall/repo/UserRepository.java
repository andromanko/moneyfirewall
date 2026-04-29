package com.moneyfirewall.repo;

import com.moneyfirewall.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByTelegramUserId(long telegramUserId);
}

