package com.moneyfirewall.service;

import com.moneyfirewall.domain.Account;
import com.moneyfirewall.domain.Category;
import com.moneyfirewall.domain.AccountType;
import com.moneyfirewall.domain.Budget;
import com.moneyfirewall.domain.ImportFileType;
import com.moneyfirewall.domain.ImportSession;
import com.moneyfirewall.domain.ImportStatus;
import com.moneyfirewall.domain.Transaction;
import com.moneyfirewall.domain.TransactionDirection;
import com.moneyfirewall.domain.TransactionSource;
import com.moneyfirewall.domain.User;
import com.moneyfirewall.importing.BankStatementParser;
import com.moneyfirewall.importing.ParsedOperation;
import com.moneyfirewall.repo.AccountRepository;
import com.moneyfirewall.repo.BudgetRepository;
import com.moneyfirewall.repo.ImportSessionRepository;
import com.moneyfirewall.repo.TransactionRepository;
import com.moneyfirewall.repo.UserRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportService {
    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final ImportSessionRepository importSessionRepository;
    private final TransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final List<BankStatementParser> parsers;
    private final MerchantAliasService merchantAliasService;
    private final TransferLinkingService transferLinkingService;
    private final CategoryRuleService categoryRuleService;
    private final CategoryService categoryService;
    private final TransactionService transactionService;

    public ImportService(
            ImportSessionRepository importSessionRepository,
            TransactionRepository transactionRepository,
            BudgetRepository budgetRepository,
            AccountRepository accountRepository,
            UserRepository userRepository,
            List<BankStatementParser> parsers,
            MerchantAliasService merchantAliasService,
            TransferLinkingService transferLinkingService,
            CategoryRuleService categoryRuleService,
            CategoryService categoryService,
            TransactionService transactionService
    ) {
        this.importSessionRepository = importSessionRepository;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.parsers = parsers;
        this.merchantAliasService = merchantAliasService;
        this.transferLinkingService = transferLinkingService;
        this.categoryRuleService = categoryRuleService;
        this.categoryService = categoryService;
        this.transactionService = transactionService;
    }

    public boolean canImport(String bankCode, ImportFileType fileType) {
        boolean ok = parsers.stream().anyMatch(p -> p.supports(bankCode, fileType));
        log.debug("canImport bankCode={} fileType={} -> {}", bankCode, fileType, ok);
        return ok;
    }

    @Transactional
    public ImportResult importFile(UUID budgetId, UUID uploadedByUserId, String bankCode, ImportFileType fileType, String telegramFileId, byte[] bytes) throws Exception {
        String sha256 = sha256Hex(bytes);
        log.debug("import start budgetId={} bankCode={} fileType={} bytes={} sha256={}", budgetId, bankCode, fileType, bytes.length, sha256);
        Optional<ImportSession> existing = importSessionRepository.findByBudgetIdAndSha256(budgetId, sha256);
        if (existing.isPresent()) {
            log.debug("import duplicate file budgetId={} existingSessionId={}", budgetId, existing.get().getId());
            return new ImportResult(existing.get().getId(), 0, true, 0);
        }

        Budget budget = budgetRepository.findById(budgetId).orElseThrow();
        User uploadedBy = userRepository.findById(uploadedByUserId).orElseThrow();
        ImportSession session = new ImportSession();
        session.setBudget(budget);
        session.setUploadedBy(uploadedBy);
        session.setBankCode(bankCode);
        session.setFileType(fileType);
        session.setTelegramFileId(telegramFileId);
        session.setSha256(sha256);
        session.setStatus(ImportStatus.RECEIVED);
        session.setCreatedAt(Instant.now());
        ImportSession saved = importSessionRepository.save(session);

        BankStatementParser parser = parsers.stream()
                .filter(p -> p.supports(bankCode, fileType))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No parser for bank=" + bankCode + " fileType=" + fileType));

        List<ParsedOperation> ops = parser.parse(bytes);
        log.debug(
                "import parsed sessionId={} parser={} operations={}",
                saved.getId(),
                parser.getClass().getSimpleName(),
                ops.size()
        );
        saved.setStatus(ImportStatus.PARSED);

        int inserted = 0;
        int skipped = 0;
        for (ParsedOperation op : ops) {
            String accountName = op.accountName() == null || op.accountName().isBlank() ? "Imported" : op.accountName();
            Account account = accountRepository.findByBudgetIdAndName(budgetId, accountName)
                    .orElseGet(() -> {
                        Account a = new Account();
                        a.setBudget(budget);
                        a.setOwnerUser(null);
                        a.setName(accountName);
                        a.setType(AccountType.BANK_ACCOUNT);
                        a.setCurrency(op.currency());
                        a.setCreatedAt(Instant.now());
                        return accountRepository.save(a);
                    });

            TransactionDirection dir = TransactionDirection.valueOf(op.direction().toUpperCase());
            BigDecimal amount = op.amount();
            String counterpartyRaw = categoryService.normalizeMtbankMinskCounterparty(op.counterpartyRaw());
            String normalizedCounterparty = merchantAliasService.normalize(budgetId, counterpartyRaw);
            // Hashed on the raw counterparty text, not the alias-normalized one: a merchant alias
            // or nickname rule added after the first import must not change the identity of an
            // already-imported transaction, or a re-import would treat it as new and duplicate it.
            String externalHash = externalHash(budgetId, op.occurredAt(), amount, op.currency(), dir, account.getName(), counterpartyRaw);

            if (transactionRepository.existsByBudgetIdAndExternalHash(budgetId, externalHash)) {
                skipped++;
                log.debug(
                        "import skip duplicate budgetId={} occurredAt={} amount={} {} {} account={} counterparty={} hash={}",
                        budgetId,
                        op.occurredAt(),
                        amount,
                        op.currency(),
                        dir,
                        account.getName(),
                        normalizedCounterparty,
                        externalHash
                );
                continue;
            }

            log.debug(
                    "import insert budgetId={} occurredAt={} amount={} {} {} account={} counterparty={}",
                    budgetId,
                    op.occurredAt(),
                    amount,
                    op.currency(),
                    dir,
                    account.getName(),
                    normalizedCounterparty
            );

            Transaction t = new Transaction();
            t.setBudget(budget);
            t.setUser(null);
            t.setDirection(dir);
            t.setOccurredAt(op.occurredAt());
            t.setAmount(amount);
            t.setCurrency(op.currency());
            t.setAccount(account);
            t.setCounterpartyRaw(counterpartyRaw);
            t.setCounterpartyNormalized(normalizedCounterparty);
            if (dir == TransactionDirection.INCOME || dir == TransactionDirection.EXPENSE) {
                Category category = categoryRuleService.matchTransaction(budgetId, t);
                if (category == null && dir == TransactionDirection.EXPENSE && categoryService.isMtbankMinskOperation(counterpartyRaw)) {
                    category = categoryService.ensureMtbankMinskCategory(budgetId);
                }
                t.setCategory(category);
            } else {
                t.setCategory(null);
            }
            t.setDescription(op.description());
            t.setBalanceAfter(op.balanceAfter());
            t.setBalanceCurrency(op.balanceCurrency());
            t.setSource(TransactionSource.IMPORT);
            t.setExternalHash(externalHash);
            t.setTransferGroup(null);
            t.setImportSession(saved);
            t.setCreatedAt(Instant.now());

            transactionRepository.save(t);
            inserted++;
        }

        log.debug(
                "import applied sessionId={} bankCode={} fileType={} parsed={} inserted={} skipped={}",
                saved.getId(),
                bankCode,
                fileType,
                ops.size(),
                inserted,
                skipped
        );

        saved.setStatus(ImportStatus.APPLIED);
        importSessionRepository.save(saved);
        int duplicatesRemoved = 0;
        if (!ops.isEmpty()) {
            Instant minOccurred = ops.stream().map(ParsedOperation::occurredAt).min(Comparator.naturalOrder()).orElseThrow();
            Instant maxOccurred = ops.stream().map(ParsedOperation::occurredAt).max(Comparator.naturalOrder()).orElseThrow();
            transferLinkingService.autoLinkAfterImport(budgetId, minOccurred, maxOccurred);

            // Same account/timestamp/amount/currency/direction twice is always a re-import bug (see
            // externalHash's comment above), so sweep the affected window and drop the uncategorized copy.
            TransactionService.DedupResult dedup = transactionService.deduplicateExactMatches(
                    budgetId, minOccurred.minusSeconds(1), maxOccurred.plusSeconds(1));
            duplicatesRemoved = dedup.deleted();
            if (!dedup.categoryConflicts().isEmpty()) {
                log.warn("import dedup collapsed groups with differing categories sessionId={} groups={}", saved.getId(), dedup.categoryConflicts());
            }
        }
        return new ImportResult(saved.getId(), inserted, false, duplicatesRemoved);
    }

    @Transactional
    public int rollback(UUID importSessionId) {
        ImportSession s = importSessionRepository.findById(importSessionId).orElseThrow();
        int deleted = transactionRepository.deleteAllByImportSessionId(importSessionId);
        s.setStatus(ImportStatus.ROLLED_BACK);
        importSessionRepository.save(s);
        return deleted;
    }

    private String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(bytes);
        return HexFormat.of().formatHex(d);
    }

    private String externalHash(UUID budgetId, Instant occurredAt, BigDecimal amount, String currency, TransactionDirection direction, String accountName, String counterpartyRaw) throws Exception {
        LocalDate d = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();
        String s = budgetId
                + "|" + d
                + "|" + amount
                + "|" + currency.toUpperCase()
                + "|" + direction
                + "|" + accountName.trim()
                + "|" + (counterpartyRaw == null ? "" : counterpartyRaw.trim().toLowerCase());
        return sha256Hex(s.getBytes(StandardCharsets.UTF_8));
    }

    public record ImportResult(UUID sessionId, int inserted, boolean alreadyImported, int duplicatesRemoved) {}
}

