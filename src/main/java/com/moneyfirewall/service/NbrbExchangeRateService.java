package com.moneyfirewall.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Official NBRB (National Bank of the Republic of Belarus) exchange rates, via the public
 * api.nbrb.by REST API. Rates for a past date never change, so results are cached for the
 * lifetime of the process.
 */
@Service
public class NbrbExchangeRateService {
    private static final Logger log = LoggerFactory.getLogger(NbrbExchangeRateService.class);
    private static final int MAX_DAYS_BACK = 7;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ConcurrentMap<String, BigDecimal> cache = new ConcurrentHashMap<>();

    public NbrbExchangeRateService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    /** BYN per 1 unit of {@code currency} on {@code date} (or the closest earlier published date). */
    public BigDecimal rateToByn(String currency, LocalDate date) {
        String cur = normalize(currency);
        if ("BYN".equals(cur)) {
            return BigDecimal.ONE;
        }
        String key = cur + "|" + date;
        return cache.computeIfAbsent(key, k -> fetchWithFallback(cur, date));
    }

    /** Rate to convert 1 unit of {@code from} into {@code to}, via BYN as pivot. */
    public BigDecimal rate(String from, String to, LocalDate date) {
        String f = normalize(from);
        String t = normalize(to);
        if (f.equals(t)) {
            return BigDecimal.ONE;
        }
        BigDecimal fromToByn = rateToByn(f, date);
        BigDecimal toToByn = rateToByn(t, date);
        return fromToByn.divide(toToByn, 8, RoundingMode.HALF_UP);
    }

    private String normalize(String currency) {
        return currency == null || currency.isBlank() ? "BYN" : currency.trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal fetchWithFallback(String cur, LocalDate date) {
        LocalDate d = date;
        Exception lastError = null;
        for (int i = 0; i <= MAX_DAYS_BACK; i++) {
            try {
                BigDecimal rate = fetchExact(cur, d);
                if (rate != null) {
                    return rate;
                }
            } catch (Exception e) {
                lastError = e;
            }
            d = d.minusDays(1);
        }
        log.warn("No NBRB rate found for {} within {} days of {}", cur, MAX_DAYS_BACK, date, lastError);
        throw new IllegalStateException("No NBRB rate for " + cur + " near " + date, lastError);
    }

    private BigDecimal fetchExact(String cur, LocalDate date) throws Exception {
        String url = "https://api.nbrb.by/exrates/rates/" + cur + "?parammode=2&ondate=" + date + "&periodicity=0";
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("NBRB HTTP " + resp.statusCode() + " for " + url);
        }
        String body = resp.body();
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonNode node = objectMapper.readTree(body);
        if (node.isMissingNode() || node.isNull() || node.isArray()) {
            return null;
        }
        if (!node.hasNonNull("Cur_OfficialRate") || !node.hasNonNull("Cur_Scale")) {
            return null;
        }
        BigDecimal officialRate = node.get("Cur_OfficialRate").decimalValue();
        BigDecimal scale = node.get("Cur_Scale").decimalValue();
        return officialRate.divide(scale, 8, RoundingMode.HALF_UP);
    }
}
