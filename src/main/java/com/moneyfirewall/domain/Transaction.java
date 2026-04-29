package com.moneyfirewall.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "mf_transactions")
public class Transaction {
    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "budget_id", nullable = false)
    private Budget budget;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private TransactionDirection direction;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(name = "counterparty_raw")
    private String counterpartyRaw;

    @Column(name = "counterparty_normalized")
    private String counterpartyNormalized;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private TransactionSource source;

    @Column(name = "external_hash")
    private String externalHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_group_id")
    private TransferGroup transferGroup;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_session_id")
    private ImportSession importSession;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Budget getBudget() {
        return budget;
    }

    public void setBudget(Budget budget) {
        this.budget = budget;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public TransactionDirection getDirection() {
        return direction;
    }

    public void setDirection(TransactionDirection direction) {
        this.direction = direction;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Account getAccount() {
        return account;
    }

    public void setAccount(Account account) {
        this.account = account;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public String getCounterpartyRaw() {
        return counterpartyRaw;
    }

    public void setCounterpartyRaw(String counterpartyRaw) {
        this.counterpartyRaw = counterpartyRaw;
    }

    public String getCounterpartyNormalized() {
        return counterpartyNormalized;
    }

    public void setCounterpartyNormalized(String counterpartyNormalized) {
        this.counterpartyNormalized = counterpartyNormalized;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public TransactionSource getSource() {
        return source;
    }

    public void setSource(TransactionSource source) {
        this.source = source;
    }

    public String getExternalHash() {
        return externalHash;
    }

    public void setExternalHash(String externalHash) {
        this.externalHash = externalHash;
    }

    public TransferGroup getTransferGroup() {
        return transferGroup;
    }

    public void setTransferGroup(TransferGroup transferGroup) {
        this.transferGroup = transferGroup;
    }

    public ImportSession getImportSession() {
        return importSession;
    }

    public void setImportSession(ImportSession importSession) {
        this.importSession = importSession;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

