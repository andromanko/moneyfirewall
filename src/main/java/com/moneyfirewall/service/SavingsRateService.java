package com.moneyfirewall.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyfirewall.config.MoneyFirewallProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Converts a savings balance held in some asset (crypto, or a fiat currency) into the budget's
 * report currency.
 *
 * <p>Plain fiat goes through NBRB, which is authoritative locally and already cached. Anything NBRB
 * doesn't publish (crypto) is priced against a configurable exchange — dzengi.com by default — and
 * then bridged into the report currency via NBRB on the quote currency. The exchange's URL, symbol
 * format, price field and quote currency all come from config, so pointing this at a different
 * exchange needs no code change.
 *
 * <p>Every failure path returns empty rather than throwing: a missing rate must degrade to "н/д" in
 * one report cell, never fail the whole report.
 */
@Service
public class SavingsRateService {
    private static final Logger log = LoggerFactory.getLogger(SavingsRateService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** dzengi.com is only partially Binance-compatible: it has no /ticker/price, so the 24h
     * ticker's lastPrice is the current-price source. Symbols are slash-separated ("BTC/USD"). */
    private static final String DEFAULT_PRICE_URL =
            "https://api-adapter.dzengi.com/api/v2/ticker/24hr?symbol={symbol}";
    private static final String DEFAULT_SYMBOL = "{base}/{quote}";
    private static final String DEFAULT_PRICE_FIELD = "lastPrice";
    private static final String DEFAULT_QUOTE_CURRENCY = "USD";

    private final ObjectMapper objectMapper;
    private final NbrbExchangeRateService nbrb;
    private final MoneyFirewallProperties props;
    private final HttpClient httpClient;
    /** Current prices only, so cached per asset+quote for a short while rather than forever. */
    private final ConcurrentMap<String, CachedPrice> priceCache = new ConcurrentHashMap<>();

    public SavingsRateService(ObjectMapper objectMapper, NbrbExchangeRateService nbrb, MoneyFirewallProperties props) {
        this.objectMapper = objectMapper;
        this.nbrb = nbrb;
        this.props = props;
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /**
     * How much of {@code asset} the given amount of {@code reportCurrency} is worth right now,
     * i.e. the savings balance restated in the unit it is actually held in.
     */
    public Optional<BigDecimal> convertToAsset(BigDecimal amountInReportCurrency, String asset, String reportCurrency) {
        if (amountInReportCurrency == null || asset == null || asset.isBlank()) {
            return Optional.empty();
        }
        return unitPriceInReportCurrency(asset, reportCurrency)
                .filter(price -> price.signum() != 0)
                .map(price -> amountInReportCurrency.divide(price, 8, RoundingMode.HALF_UP));
    }

    /** Price of one unit of {@code asset} in {@code reportCurrency}. */
    public Optional<BigDecimal> unitPriceInReportCurrency(String asset, String reportCurrency) {
        String a = normalize(asset);
        String report = normalize(reportCurrency);
        if (a.isEmpty() || report.isEmpty()) {
            return Optional.empty();
        }
        if (a.equals(report)) {
            return Optional.of(BigDecimal.ONE);
        }
        Optional<BigDecimal> viaNbrb = fiatRate(a, report);
        if (viaNbrb.isPresent()) {
            return viaNbrb;
        }
        // dzengi lists direct pairs against BYN for the common assets (BTC/BYN, ETH/BYN, ...);
        // using them avoids bridging through a second rate and losing precision twice.
        Optional<BigDecimal> direct = exchangePrice(a, report);
        if (direct.isPresent()) {
            return direct;
        }
        String quote = configured(cfg -> cfg.quoteCurrency(), DEFAULT_QUOTE_CURRENCY);
        if (quote.equalsIgnoreCase(report)) {
            return Optional.empty();
        }
        return exchangePrice(a, quote).flatMap(priceInQuote -> fiatRate(quote, report).map(priceInQuote::multiply));
    }

    private Optional<BigDecimal> fiatRate(String from, String to) {
        try {
            return Optional.of(nbrb.rate(from, to, LocalDate.now()));
        } catch (Exception e) {
            log.debug("No NBRB rate for {}->{}: {}", from, to, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<BigDecimal> exchangePrice(String base, String quote) {
        String cacheKey = base + "|" + quote;
        CachedPrice cached = priceCache.get(cacheKey);
        if (cached != null && !cached.isStale()) {
            return Optional.ofNullable(cached.price());
        }
        BigDecimal price;
        try {
            price = fetchExchangePrice(base, quote);
        } catch (Exception e) {
            log.warn("Savings price lookup failed for {}/{}: {}", base, quote, e.getMessage());
            price = null;
        }
        if (price != null && price.signum() <= 0) {
            price = null;
        }
        // Misses are cached too: an asset with no pair on the exchange (very common, since a direct
        // report-currency pair is tried first) must not re-hit the API for every report.
        priceCache.put(cacheKey, new CachedPrice(price, System.nanoTime()));
        return Optional.ofNullable(price);
    }

    private BigDecimal fetchExchangePrice(String base, String quote) throws Exception {
        String symbol = configured(cfg -> cfg.symbol(), DEFAULT_SYMBOL)
                .replace("{base}", base)
                .replace("{quote}", quote);
        String url = configured(cfg -> cfg.priceUrl(), DEFAULT_PRICE_URL)
                .replace("{symbol}", URLEncoder.encode(symbol, StandardCharsets.UTF_8))
                .replace("{base}", base)
                .replace("{quote}", quote);

        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(TIMEOUT).GET().build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 400 || resp.statusCode() == 404) {
            // No such trading pair — expected while probing for a direct report-currency pair.
            log.debug("No savings price pair {}/{} ({})", base, quote, resp.statusCode());
            return null;
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            log.warn("Savings price HTTP {} for {}", resp.statusCode(), url);
            return null;
        }
        String body = resp.body();
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonNode node = objectMapper.readTree(body);
        if (node.isArray()) {
            node = node.isEmpty() ? null : node.get(0);
        }
        if (node == null) {
            return null;
        }
        String field = configured(cfg -> cfg.priceField(), DEFAULT_PRICE_FIELD);
        JsonNode priceNode = node.get(field);
        if (priceNode == null || priceNode.isNull()) {
            log.warn("Savings price field '{}' missing in response from {}", field, url);
            return null;
        }
        return new BigDecimal(priceNode.asText());
    }

    private String configured(java.util.function.Function<MoneyFirewallProperties.Savings, String> get, String fallback) {
        MoneyFirewallProperties.Rates rates = props == null ? null : props.rates();
        MoneyFirewallProperties.Savings savings = rates == null ? null : rates.savings();
        String value = savings == null ? null : get.apply(savings);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private record CachedPrice(BigDecimal price, long fetchedAtNanos) {
        private static final long TTL_NANOS = Duration.ofMinutes(10).toNanos();

        boolean isStale() {
            return System.nanoTime() - fetchedAtNanos > TTL_NANOS;
        }
    }
}
