package com.moneyfirewall.service;

import com.moneyfirewall.domain.Account;
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
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportService {
    private final ImportSessionRepository importSessionRepository;
    private final TransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final List<BankStatementParser> parsers;
    private final MerchantAliasService merchantAliasService;
    private final TransferLinkingService transferLinkingService;

    public ImportService(
            ImportSessionRepository importSessionRepository,
            TransactionRepository transactionRepository,
            BudgetRepository budgetRepository,
            AccountRepository accountRepository,
            UserRepository userRepository,
            List<BankStatementParser> parsers,
            MerchantAliasService merchantAliasService,
            TransferLinkingService transferLinkingService
    ) {
        this.importSessionRepository = importSessionRepository;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.parsers = parsers;
        this.merchantAliasService = merchantAliasService;
        this.transferLinkingService = transferLinkingService;
    }

    @Transactional
    public ImportResult importFile(UUID budgetId, UUID uploadedByUserId, String bankCode, ImportFileType fileType, String telegramFileId, byte[] bytes) throws Exception {
        String sha256 = sha256Hex(bytes);
        Optional<ImportSession> existing = importSessionRepository.findByBudgetIdAndSha256(budgetId, sha256);
        if (existing.isPresent()) {
            return new ImportResult(existing.get().getId(), 0, true);
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
                .orElseThrow();

        List<ParsedOperation> ops = parser.parse(bytes);
        saved.setStatus(ImportStatus.PARSED);

        int inserted = 0;
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
            String normalizedCounterparty = merchantAliasService.normalize(budgetId, op.counterpartyRaw());
            String externalHash = externalHash(budgetId, op.occurredAt(), amount, op.currency(), dir, account.getName(), normalizedCounterparty);

            if (transactionRepository.existsByBudgetIdAndExternalHash(budgetId, externalHash)) {
                continue;
            }

            Transaction t = new Transaction();
            t.setBudget(budget);
            t.setUser(null);
            t.setDirection(dir);
            t.setOccurredAt(op.occurredAt());
            t.setAmount(amount);
            t.setCurrency(op.currency());
            t.setAccount(account);
            t.setCategory(null);
            t.setCounterpartyRaw(op.counterpartyRaw());
            t.setCounterpartyNormalized(normalizedCounterparty);
            t.setDescription(op.description());
            t.setSource(TransactionSource.IMPORT);
            t.setExternalHash(externalHash);
            t.setTransferGroup(null);
            t.setImportSession(saved);
            t.setCreatedAt(Instant.now());

            transactionRepository.save(t);
            inserted++;
        }

        saved.setStatus(ImportStatus.APPLIED);
        importSessionRepository.save(saved);
        transferLinkingService.autoLink(budgetId, Duration.ofHours(48));
        return new ImportResult(saved.getId(), inserted, false);
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

    private String externalHash(UUID budgetId, Instant occurredAt, BigDecimal amount, String currency, TransactionDirection direction, String accountName, String normalizedCounterparty) throws Exception {
        LocalDate d = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();
        String s = budgetId
                + "|" + d
                + "|" + amount
                + "|" + currency.toUpperCase()
                + "|" + direction
                + "|" + accountName.trim()
                + "|" + (normalizedCounterparty == null ? "" : normalizedCounterparty.trim().toLowerCase());
        return sha256Hex(s.getBytes(StandardCharsets.UTF_8));
    }

    public record ImportResult(UUID sessionId, int inserted, boolean alreadyImported) {}
}

