package com.moneyfirewall.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.springframework.stereotype.Service;

@Service
public class BuildInfoService {
    private final String buildTime;

    public BuildInfoService() {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/build-info.properties")) {
            if (in != null) {
                props.load(in);
                buildTime = props.getProperty("build.time", "unknown");
            } else {
                buildTime = "dev";
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read build-info.properties", e);
        }
    }

    public String buildTime() {
        return buildTime;
    }
}
