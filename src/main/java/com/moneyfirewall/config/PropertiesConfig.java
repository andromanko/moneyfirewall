package com.moneyfirewall.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MoneyFirewallProperties.class)
public class PropertiesConfig {}

