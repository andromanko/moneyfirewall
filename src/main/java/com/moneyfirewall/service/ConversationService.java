package com.moneyfirewall.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyfirewall.domain.UserSettings;
import com.moneyfirewall.repo.UserSettingsRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {
    private final UserSettingsRepository userSettingsRepository;
    private final ObjectMapper objectMapper;

    public ConversationService(UserSettingsRepository userSettingsRepository, ObjectMapper objectMapper) {
        this.userSettingsRepository = userSettingsRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Optional<State> get(UUID userId) {
        return userSettingsRepository.findById(userId)
                .flatMap(s -> s.getStateKey() == null ? Optional.empty() : Optional.of(new State(s.getStateKey(), parsePayload(s.getStatePayload()))));
    }

    @Transactional
    public void set(UUID userId, String key, Map<String, Object> payload) {
        UserSettings s = userSettingsRepository.findById(userId).orElseThrow();
        s.setStateKey(key);
        s.setStatePayload(writePayload(payload));
        s.setUpdatedAt(Instant.now());
        userSettingsRepository.save(s);
    }

    @Transactional
    public void clear(UUID userId) {
        UserSettings s = userSettingsRepository.findById(userId).orElseThrow();
        s.setStateKey(null);
        s.setStatePayload(null);
        s.setUpdatedAt(Instant.now());
        userSettingsRepository.save(s);
    }

    private Map<String, Object> parsePayload(JsonNode json) {
        if (json == null || json.isNull()) {
            return Map.of();
        }
        try {
            Map<?, ?> m = objectMapper.convertValue(json, Map.class);
            if (m == null || m.isEmpty()) {
                return Map.of();
            }
            Map<String, Object> res = new HashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) {
                    res.put(e.getKey().toString(), e.getValue());
                }
            }
            return res;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private JsonNode writePayload(Map<String, Object> payload) {
        return objectMapper.valueToTree(payload == null ? Map.of() : payload);
    }

    public record State(String key, Map<String, Object> payload) {}
}

