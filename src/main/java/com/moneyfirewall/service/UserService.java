package com.moneyfirewall.service;

import com.moneyfirewall.domain.User;
import com.moneyfirewall.domain.UserSettings;
import com.moneyfirewall.repo.UserRepository;
import com.moneyfirewall.repo.UserSettingsRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final UserSettingsRepository userSettingsRepository;

    public UserService(UserRepository userRepository, UserSettingsRepository userSettingsRepository) {
        this.userRepository = userRepository;
        this.userSettingsRepository = userSettingsRepository;
    }

    @Transactional
    public User getOrCreate(long telegramUserId, long chatId, String displayName) {
        Optional<User> existing = userRepository.findByTelegramUserId(telegramUserId);
        if (existing.isPresent()) {
            User user = existing.get();
            if (user.getTelegramChatId() != chatId) {
                user.setTelegramChatId(chatId);
            }
            if (displayName != null && !displayName.isBlank() && !displayName.equals(user.getDisplayName())) {
                user.setDisplayName(displayName);
            }
            if (!user.isActive()) {
                user.setActive(true);
            }
            return user;
        }

        User user = new User();
        user.setTelegramUserId(telegramUserId);
        user.setTelegramChatId(chatId);
        user.setDisplayName(displayName == null || displayName.isBlank() ? "user_" + telegramUserId : displayName);
        user.setActive(true);
        user.setCreatedAt(Instant.now());

        User saved = userRepository.save(user);

        UserSettings settings = new UserSettings();
        settings.setUser(saved);
        settings.setStateKey(null);
        settings.setStatePayload(null);
        settings.setUpdatedAt(Instant.now());
        userSettingsRepository.save(settings);

        return saved;
    }
}

