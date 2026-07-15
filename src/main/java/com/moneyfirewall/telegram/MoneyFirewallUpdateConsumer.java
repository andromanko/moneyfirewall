package com.moneyfirewall.telegram;

import com.moneyfirewall.config.BuildInfoService;
import com.moneyfirewall.domain.AccountType;
import com.moneyfirewall.domain.Budget;
import com.moneyfirewall.domain.Category;
import com.moneyfirewall.domain.CategoryKind;
import com.moneyfirewall.domain.CategoryRule;
import com.moneyfirewall.domain.MerchantAlias;
import com.moneyfirewall.domain.ImportFileType;
import com.moneyfirewall.service.BudgetService;
import com.moneyfirewall.service.ConversationService;
import com.moneyfirewall.service.ConversationService.State;
import com.moneyfirewall.service.AccountService;
import com.moneyfirewall.service.AssetEventService;
import com.moneyfirewall.service.AssetReportService;
import com.moneyfirewall.service.CategoryRuleService;
import com.moneyfirewall.service.CategoryService;
import com.moneyfirewall.service.ImportService;
import com.moneyfirewall.service.MerchantAliasService;
import com.moneyfirewall.service.ReportService;
import com.moneyfirewall.service.TransactionService;
import com.moneyfirewall.service.TransferLinkingService;
import com.moneyfirewall.reporting.ExcelReportExporter;
import com.moneyfirewall.reporting.GoogleSheetsExporter;
import com.moneyfirewall.reporting.ReportTables;
import com.moneyfirewall.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

@Component
public class MoneyFirewallUpdateConsumer implements LongPollingUpdateConsumer {
    private static final Logger log = LoggerFactory.getLogger(MoneyFirewallUpdateConsumer.class);
    private static final DateTimeFormatter CASH_DATE_DMY = DateTimeFormatter.ofPattern("d.M.uuuu");
    private static final DateTimeFormatter CASH_DATE_DMY_PAD = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private static final Pattern TOTAL_PATTERN = Pattern.compile("(?im)^\\s*Итого\\s*[—:-]\\s*([0-9][0-9\\s.,]*)\\s*$");
    private final TelegramSender sender;
    private final UserService userService;
    private final BudgetService budgetService;
    private final ConversationService conversationService;
    private final AccountService accountService;
    private final CategoryService categoryService;
    private final TransactionService transactionService;
    private final ImportService importService;
    private final TelegramFileService telegramFileService;
    private final MerchantAliasService merchantAliasService;
    private final CategoryRuleService categoryRuleService;
    private final TransferLinkingService transferLinkingService;
    private final AssetEventService assetEventService;
    private final AssetReportService assetReportService;
    private final ReportService reportService;
    private final ExcelReportExporter excelReportExporter;
    private final GoogleSheetsExporter googleSheetsExporter;
    private final ReceiptRecognitionService receiptRecognitionService;
    private final BuildInfoService buildInfoService;
    private final ObjectMapper objectMapper;

    public MoneyFirewallUpdateConsumer(
            TelegramSender sender,
            UserService userService,
            BudgetService budgetService,
            ConversationService conversationService,
            AccountService accountService,
            CategoryService categoryService,
            TransactionService transactionService,
            ImportService importService,
            TelegramFileService telegramFileService,
            MerchantAliasService merchantAliasService,
            CategoryRuleService categoryRuleService,
            TransferLinkingService transferLinkingService,
            AssetEventService assetEventService,
            AssetReportService assetReportService,
            ReportService reportService,
            ExcelReportExporter excelReportExporter,
            GoogleSheetsExporter googleSheetsExporter,
            ReceiptRecognitionService receiptRecognitionService,
            BuildInfoService buildInfoService,
            ObjectMapper objectMapper
    ) {
        this.sender = sender;
        this.userService = userService;
        this.budgetService = budgetService;
        this.conversationService = conversationService;
        this.accountService = accountService;
        this.categoryService = categoryService;
        this.transactionService = transactionService;
        this.importService = importService;
        this.telegramFileService = telegramFileService;
        this.merchantAliasService = merchantAliasService;
        this.categoryRuleService = categoryRuleService;
        this.transferLinkingService = transferLinkingService;
        this.assetEventService = assetEventService;
        this.assetReportService = assetReportService;
        this.reportService = reportService;
        this.excelReportExporter = excelReportExporter;
        this.googleSheetsExporter = googleSheetsExporter;
        this.receiptRecognitionService = receiptRecognitionService;
        this.buildInfoService = buildInfoService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void consume(java.util.List<Update> updates) {
        for (Update update : updates) {
            try {
                consumeOne(update);
            } catch (Exception e) {
                // BotSession's poller only catches TelegramApiException around this call; anything else
                // escaping here silently cancels the scheduled long-polling task forever (no log, no crash).
                log.error("Unhandled exception processing updateId={}", update.getUpdateId(), e);
            }
        }
    }

    private void consumeOne(Update update) {
        if (update.getCallbackQuery() != null) {
            handleCallback(update.getCallbackQuery());
            return;
        }
        if (update.getMessage() == null) {
            return;
        }
        if (update.getMessage().getChat() == null) {
            return;
        }
        if (update.getMessage().getFrom() == null) {
            return;
        }

        long chatId = update.getMessage().getChatId();
        User tgUser = update.getMessage().getFrom();
        String displayName = tgUser.getUserName() != null ? tgUser.getUserName() : ((tgUser.getFirstName() == null ? "" : tgUser.getFirstName()) + " " + (tgUser.getLastName() == null ? "" : tgUser.getLastName())).trim();

        com.moneyfirewall.domain.User user = userService.getOrCreate(tgUser.getId(), chatId, displayName);

        if (update.getMessage().getDocument() != null) {
            if (handleImportDocument(chatId, user.getId(), update)) {
                return;
            }
            if (handleCategoriesImportDocument(chatId, user.getId(), update)) {
                return;
            }
            sender.sendText(chatId, "Сначала /import <bankCode>");
            return;
        }

        if (update.getMessage().getPhoto() != null && !update.getMessage().getPhoto().isEmpty()) {
            if (handleReceiptPhoto(chatId, user.getId(), tgUser.getId(), update)) {
                return;
            }
        }

        if (update.getMessage().getText() == null) {
            sender.sendText(chatId, "Понимаю только текстовые команды");
            return;
        }

        String text = update.getMessage().getText().trim();

        if (!text.startsWith("/")) {
            if (handleWizardInput(chatId, user.getId(), text)) {
                return;
            }
            sender.sendText(chatId, "Команды начинаются с /");
            return;
        }

        String[] parts = text.split("\\s+", 2);
        String cmd = parts[0];
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "/start" -> onStart(chatId, user.getId());
            case "/menu" -> onMenu(chatId, user.getId());
            case "/cancel" -> onCancel(chatId, user.getId());
            case "/budget_create" -> onBudgetCreate(chatId, user.getId(), arg);
            case "/budget_use" -> onBudgetUse(chatId, user.getId(), arg);
            case "/budget_members" -> onBudgetMembers(chatId, user.getId());
            case "/budget_add" -> onBudgetAdd(chatId, user.getId(), arg);
            case "/budget_remove" -> onBudgetRemove(chatId, user.getId(), arg);
            case "/accounts" -> onAccounts(chatId, user.getId());
            case "/account_add" -> onAccountAdd(chatId, user.getId(), arg);
            case "/categories_income" -> onCategories(chatId, user.getId(), CategoryKind.INCOME);
            case "/categories_expense" -> onCategories(chatId, user.getId(), CategoryKind.EXPENSE);
            case "/income" -> onIncome(chatId, user.getId(), arg);
            case "/expense" -> onExpense(chatId, user.getId(), arg);
            case "/transfer" -> onTransfer(chatId, user.getId(), arg);
            case "/import" -> onImport(chatId, user.getId(), arg);
            case "/import_rollback" -> onImportRollback(chatId, user.getId(), arg);
            case "/alias_add", "/nickname_add" -> onAliasAdd(chatId, user.getId(), arg);
            case "/alias_list", "/nickname_list" -> onNicknamesMenu(chatId, user.getId());
            case "/alias_delete", "/nickname_delete" -> onAliasDelete(chatId, user.getId(), arg);
            case "/catrule_add" -> onCategoryRuleAdd(chatId, user.getId(), arg);
            case "/catrule_list" -> onCategoryRuleList(chatId, user.getId());
            case "/catrule_delete" -> onCategoryRuleDelete(chatId, user.getId(), arg);
            case "/transfer_autolink" -> onTransferAutoLink(chatId, user.getId(), arg);
            case "/transfer_link" -> onTransferLink(chatId, user.getId(), arg);
            case "/transfer_unlink" -> onTransferUnlink(chatId, user.getId(), arg);
            case "/asset_event" -> onAssetEvent(chatId, user.getId(), arg);
            case "/asset_positions" -> onAssetPositions(chatId, user.getId(), arg);
            case "/report" -> onReportMenu(chatId, user.getId());
            case "/report_month" -> onReportMonth(chatId, user.getId(), arg);
            default -> sender.sendText(chatId, "Неизвестная команда");
        }
    }

    private void onStart(long chatId, UUID userId) {
        conversationService.clear(userId);
        sender.sendText(chatId, "MoneyFirewall", menuForUser(userId));
    }

    private void onMenu(long chatId, UUID userId) {
        conversationService.clear(userId);
        sender.sendText(chatId, "Меню", menuForUser(userId));
    }

    private void onCancel(long chatId, UUID userId) {
        conversationService.clear(userId);
        sender.sendText(chatId, "Ок", menuForUser(userId));
    }

    private void onBudgetCreate(long chatId, UUID userId, String name) {
        if (name == null || name.isBlank()) {
            sender.sendText(chatId, "Пример: /budget_create Семья");
            return;
        }
        Budget b = budgetService.createBudget(userId, name);
        sender.sendText(chatId, "Бюджет создан: " + b.getName(), mainMenu());
    }

    private void onBudgetCreateStart(long chatId, UUID userId) {
        conversationService.set(userId, "budget_create", new HashMap<>(Map.of("step", "name")));
        sender.sendText(chatId, "Введите название бюджета", budgetCreateMenu());
    }

    private void onBudgetSelect(long chatId, UUID userId) {
        List<Budget> budgets = budgetService.listBudgetsForUser(userId);
        if (budgets == null || budgets.isEmpty()) {
            sender.sendText(chatId, "Бюджетов пока нет", startMenuWithoutBudget());
            return;
        }
        sender.sendText(chatId, "Выберите бюджет", budgetSelectMenu(budgets));
    }

    private void onBudgetUse(long chatId, UUID userId, String budgetId) {
        try {
            UUID id = UUID.fromString(budgetId);
            budgetService.setActiveBudget(userId, id);
            sender.sendText(chatId, "Активный бюджет: " + id, mainMenu());
        } catch (Exception e) {
            sender.sendText(chatId, "Нужно UUID: /budget_use <uuid>");
        }
    }

    private void onBudgetMembers(long chatId, UUID userId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid> или создай /budget_create <name>");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Участники ").append(budgetId).append("\n");
        budgetService.listMembers(budgetId).forEach(m -> sb.append(m.getRole()).append(" ").append(m.getUser().getTelegramUserId()).append(" ").append(m.getUser().getDisplayName()).append("\n"));
        sender.sendText(chatId, sb.toString().trim());
    }

    private void onBudgetAdd(long chatId, UUID userId, String telegramUserId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        if (!budgetService.isAdmin(budgetId, userId)) {
            sender.sendText(chatId, "Нужна роль ADMIN");
            return;
        }
        try {
            long tId = Long.parseLong(telegramUserId);
            budgetService.addMemberByTelegramUserId(budgetId, tId, com.moneyfirewall.domain.BudgetRole.MEMBER);
            sender.sendText(chatId, "Добавлен участник " + tId);
        } catch (Exception e) {
            sender.sendText(chatId, "Пример: /budget_add 123456789");
        }
    }

    private void onBudgetRemove(long chatId, UUID userId, String telegramUserId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        if (!budgetService.isAdmin(budgetId, userId)) {
            sender.sendText(chatId, "Нужна роль ADMIN");
            return;
        }
        try {
            long tId = Long.parseLong(telegramUserId);
            budgetService.removeMemberByTelegramUserId(budgetId, tId);
            sender.sendText(chatId, "Удалён участник " + tId);
        } catch (Exception e) {
            sender.sendText(chatId, "Пример: /budget_remove 123456789");
        }
    }

    private void onAccounts(long chatId, UUID userId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        sender.sendText(chatId, "💳 Счета", accountsMenu());
    }

    private void onAccountsList(long chatId, UUID userId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Счета\n");
        accountService.list(budgetId).forEach(a -> sb.append(a.getType()).append(" ").append(a.getCurrency()).append(" ").append(a.getName()).append("\n"));
        sender.sendText(chatId, sb.toString().trim(), accountsMenu());
    }

    private void onAccountsAddStart(long chatId, UUID userId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        conversationService.set(userId, "account_add", new HashMap<>(Map.of("step", "text")));
        sender.sendText(chatId, "Формат: TYPE CURRENCY NAME", budgetCreateMenu());
    }

    private void onAccountsRenameSelect(long chatId, UUID userId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        sender.sendText(chatId, "Выберите счёт для переименования", accountsSelectMenu(budgetId, "mf:acc_ren:"));
    }

    private void onAccountsDeleteSelect(long chatId, UUID userId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        sender.sendText(chatId, "Выберите счёт для удаления", accountsSelectMenu(budgetId, "mf:acc_del:"));
    }

    private void onAccountRenameChosen(long chatId, UUID userId, String accountId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        conversationService.set(userId, "account_rename", new HashMap<>(Map.of("accountId", accountId)));
        sender.sendText(chatId, "Введите новое имя счёта", budgetCreateMenu());
    }

    private void onAccountDeleteConfirm(long chatId, UUID userId, String accountId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        sender.sendText(chatId, "Удалить счёт?", confirmDeleteMenu(accountId));
    }

    private void onAccountDeleteDo(long chatId, UUID userId, String accountId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        try {
            accountService.delete(budgetId, UUID.fromString(accountId));
            sender.sendText(chatId, "Ок", accountsMenu());
        } catch (Exception e) {
            sender.sendText(chatId, "Ошибка", accountsMenu());
        }
    }

    private void onAccountAdd(long chatId, UUID userId, String arg) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        try {
            String[] p = arg.split("\\s+", 3);
            AccountType type = AccountType.valueOf(p[0].trim().toUpperCase());
            String currency = p[1].trim().toUpperCase();
            String name = p[2].trim();
            accountService.create(budgetId, userId, name, type, currency);
            sender.sendText(chatId, "Ок");
        } catch (Exception e) {
            sender.sendText(chatId, "Пример: /account_add CASH BYN Наличные");
        }
    }

    private void onCategories(long chatId, UUID userId, CategoryKind kind) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Категории ").append(kind).append("\n");
        categoryService.list(budgetId, kind).forEach(c -> sb.append(c.getName()).append("\n"));
        sender.sendText(chatId, sb.toString().trim());
    }

    private void onIncome(long chatId, UUID userId, String arg) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", mainMenu());
            return;
        }
        categoryService.ensureStandardIncomeCategories(budgetId);
        conversationService.set(userId, "income", new HashMap<>(Map.of("step", "category")));
        sender.sendText(chatId, "Выбери категорию дохода", incomeCategoryMenuByUsage(budgetId, "income"));
    }

    private void onExpense(long chatId, UUID userId, String arg) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", mainMenu());
            return;
        }
        conversationService.set(userId, "expense", new HashMap<>(Map.of("step", "amount")));
        sender.sendText(chatId, "Выбери сумму траты", amountMenu("expense"));
    }

    private void onExpenseManual(long chatId, UUID userId, long telegramUserId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        budgetService.ensureUserCashAccount(budgetId, userId);
        conversationService.set(userId, "expense_manual", new HashMap<>(Map.of(
                "step", "category",
                "telegramUserId", telegramUserId
        )));
        sender.sendText(chatId, "Выбери категорию", expenseCategoryMenuByUsage(budgetId, "expense_manual"));
    }

    private void onExpenseScan(long chatId, UUID userId, long telegramUserId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        conversationService.set(userId, "expense_scan", new HashMap<>(Map.of("step", "photo", "telegramUserId", telegramUserId)));
        sender.sendText(chatId, "Пришлите фотографию чека", budgetCreateMenu());
    }

    private void onExpenseScanSave(long chatId, UUID userId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            conversationService.clear(userId);
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        State st = conversationService.get(userId).orElse(null);
        if (st == null || !"expense_scan_result".equals(st.key())) {
            sender.sendText(chatId, "Нет данных чека", menuForUser(userId));
            return;
        }
        conversationService.set(userId, "expense_scan_save", new HashMap<>(st.payload()));
        sender.sendText(chatId, "Выберите валюту, категорию или магазин", receiptSaveMenu(st.payload()));
    }

    private void onExpenseScanSelectCategory(long chatId, UUID userId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            conversationService.clear(userId);
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        State st = conversationService.get(userId).orElse(null);
        if (st == null || !"expense_scan_save".equals(st.key())) {
            sender.sendText(chatId, "Нет данных чека", menuForUser(userId));
            return;
        }
        sender.sendText(chatId, "Категория", receiptCategoryMenu(budgetId));
    }

    private void onExpenseScanEnterShop(long chatId, UUID userId) {
        State st = conversationService.get(userId).orElse(null);
        if (st == null || !"expense_scan_save".equals(st.key())) {
            sender.sendText(chatId, "Нет данных чека", menuForUser(userId));
            return;
        }
        conversationService.set(userId, "expense_scan_shop", new HashMap<>(st.payload()));
        sender.sendText(chatId, "Введите магазин", budgetCreateMenu());
    }

    private void onTransfer(long chatId, UUID userId, String arg) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", mainMenu());
            return;
        }
        conversationService.set(userId, "transfer", new HashMap<>(Map.of("step", "amount")));
        sender.sendText(chatId, "Выбери сумму перевода", amountMenu("transfer"));
    }

    private void onImport(long chatId, UUID userId, String arg) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", mainMenu());
            return;
        }
        if (arg != null && !arg.isBlank()) {
            onImportWithBank(chatId, userId, arg.trim());
            return;
        }
        onImportMenu(chatId, userId);
    }

    private void onImportMenu(long chatId, UUID userId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", mainMenu());
            return;
        }
        conversationService.clear(userId);
        sender.sendText(chatId, "Импорт: выбери банк или формат, затем пришли файл.", importBankMenu());
    }

    private void onImportWithBank(long chatId, UUID userId, String bank) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", mainMenu());
            return;
        }
        conversationService.set(userId, "import", new HashMap<>(Map.of("bank", bank)));
        String hint = switch (bank.toLowerCase(Locale.ROOT)) {
            case "alfajson" -> "Пришли JSON (экспорт Альфа-Банка, поле items).";
            case "mtbank" -> "Пришли PDF-выписку МТБанка или JSON-выгрузку операций.";
            case "oplati" -> "Пришли PDF-выписку ОПЛАТИ / Белинвестбанк.";
            default -> "Пришли PDF или JSON в общем формате.";
        };
        sender.sendText(chatId, hint, importPendingMenu());
    }

    private void onImportRollback(long chatId, UUID userId, String arg) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        if (!budgetService.isAdmin(budgetId, userId)) {
            sender.sendText(chatId, "Нужна роль ADMIN");
            return;
        }
        try {
            UUID sessionId = UUID.fromString(arg.trim());
            int deleted = importService.rollback(sessionId);
            sender.sendText(chatId, "Удалено: " + deleted);
        } catch (Exception e) {
            sender.sendText(chatId, "Пример: /import_rollback <uuid>");
        }
    }

    private boolean handleImportDocument(long chatId, UUID userId, Update update) {
        State st = conversationService.get(userId).orElse(null);
        if (st == null || !"import".equals(st.key())) {
            return false;
        }
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            conversationService.clear(userId);
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return true;
        }
        String bank = st.payload().getOrDefault("bank", "generic").toString();
        String fileId = update.getMessage().getDocument().getFileId();
        String fileName = update.getMessage().getDocument().getFileName();
        ImportFileType type = fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf") ? ImportFileType.PDF : ImportFileType.JSON;
        if (!importService.canImport(bank, type)) {
            conversationService.clear(userId);
            sender.sendText(chatId, "Тип файла не подходит выбранному варианту. Выбери снова.", importBankMenu());
            return true;
        }
        try {
            byte[] bytes = telegramFileService.downloadByFileId(fileId);
            log.debug("import document budgetId={} bank={} fileName={} fileType={} size={}", budgetId, bank, fileName, type, bytes.length);
            ImportService.ImportResult res = importService.importFile(budgetId, userId, bank, type, fileId, bytes);
            log.debug("import result sessionId={} inserted={} alreadyImported={}", res.sessionId(), res.inserted(), res.alreadyImported());
            conversationService.clear(userId);
            if (res.alreadyImported()) {
                sender.sendText(chatId, "Уже импортировано: " + res.sessionId());
            } else {
                sender.sendText(chatId, "Импорт: " + res.sessionId() + ", добавлено: " + res.inserted());
            }
        } catch (Exception e) {
            conversationService.clear(userId);
            log.warn("import failed bank={} fileName={}", bank, fileName, e);
            sender.sendText(chatId, "Ошибка импорта");
        }
        return true;
    }

    private boolean handleReceiptPhoto(long chatId, UUID userId, long telegramUserId, Update update) {
        State st = conversationService.get(userId).orElse(null);
        if (st == null || !"expense_scan".equals(st.key())) {
            return false;
        }
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            conversationService.clear(userId);
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return true;
        }

        List<PhotoSize> photos = update.getMessage().getPhoto();
        PhotoSize best = photos.stream().max(Comparator.comparingInt(p -> p.getFileSize() == null ? 0 : p.getFileSize())).orElse(null);
        if (best == null || best.getFileId() == null) {
            sender.sendText(chatId, "Пришлите фотографию чека", budgetCreateMenu());
            return true;
        }

        try {
            byte[] bytes = telegramFileService.downloadByFileId(best.getFileId());
            ReceiptRecognitionService.Result res = receiptRecognitionService.recognize(bytes);
            BigDecimal total = extractReceiptTotal(res.text());
            if (total != null) {
                conversationService.set(userId, "expense_scan_result", new HashMap<>(Map.of(
                        "amount", total,
                        "currency", extractReceiptCurrency(res.text()),
                        "telegramUserId", telegramUserId,
                        "text", res.text()
                )));
                sender.sendText(chatId, res.text(), receiptResultMenu());
            } else {
                conversationService.clear(userId);
                sender.sendText(chatId, res.text(), menuForUser(userId));
            }
        } catch (Exception e) {
            conversationService.clear(userId);
            sender.sendText(chatId, "Ошибка распознавания", menuForUser(userId));
        }
        return true;
    }

    private void onAliasAdd(long chatId, UUID userId, String arg) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        if (!budgetService.isAdmin(budgetId, userId)) {
            sender.sendText(chatId, "Нужна роль ADMIN");
            return;
        }
        String[] parts = arg.split("\\s*=>\\s*", 2);
        if (parts.length != 2) {
            sender.sendText(chatId, "Пример: /nickname_add SANTA => магазин Санта");
            return;
        }
        String pattern = parts[0].trim();
        String name = parts[1].trim();
        merchantAliasService.add(budgetId, pattern, name, 100, false);
        sender.sendText(chatId, "Ок");
    }

    private void onAliasDelete(long chatId, UUID userId, String arg) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        if (!budgetService.isAdmin(budgetId, userId)) {
            sender.sendText(chatId, "Нужна роль ADMIN");
            return;
        }
        try {
            merchantAliasService.delete(UUID.fromString(arg.trim()));
            sender.sendText(chatId, "Ок");
        } catch (Exception e) {
            sender.sendText(chatId, "Пример: /nickname_delete <uuid>");
        }
    }

    private void onNicknamesMenu(long chatId, UUID userId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        sender.sendText(chatId, "Никнеймы контрагентов для отчётов", nicknamesMenu());
    }

    private void onNicknamesAddStart(long chatId, UUID userId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        if (!budgetService.isAdmin(budgetId, userId)) {
            sender.sendText(chatId, "Нужна роль ADMIN", menuForUser(userId));
            return;
        }
        conversationService.set(userId, "nickname_add", new HashMap<>(Map.of("step", "pattern")));
        sender.sendText(chatId, "Введите фразу из выписки (контрагент)", nicknamesBackMenu());
    }

    private void onNicknamesList(long chatId, UUID userId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        List<MerchantAlias> aliases = merchantAliasService.list(budgetId);
        if (aliases.isEmpty()) {
            sender.sendText(chatId, "Нет никнеймов", nicknamesMenu());
            return;
        }
        sender.sendText(chatId, "Никнеймы (нажми для удаления)", nicknamesListMenu(aliases));
    }

    private void onNicknameDeleteConfirm(long chatId, UUID userId, String aliasId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        if (!budgetService.isAdmin(budgetId, userId)) {
            sender.sendText(chatId, "Нужна роль ADMIN", menuForUser(userId));
            return;
        }
        try {
            UUID id = UUID.fromString(aliasId);
            MerchantAlias a = merchantAliasService.find(budgetId, id).orElse(null);
            if (a == null) {
                sender.sendText(chatId, "Не найдено", nicknamesMenu());
                return;
            }
            sender.sendText(chatId, "Удалить?\n" + a.getPattern() + " → " + a.getNormalizedName(), nicknameDeleteConfirmMenu(aliasId));
        } catch (Exception e) {
            sender.sendText(chatId, "Ошибка", nicknamesMenu());
        }
    }

    private void onNicknameDeleteDo(long chatId, UUID userId, String aliasId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        if (!budgetService.isAdmin(budgetId, userId)) {
            sender.sendText(chatId, "Нужна роль ADMIN", menuForUser(userId));
            return;
        }
        try {
            UUID id = UUID.fromString(aliasId);
            if (merchantAliasService.find(budgetId, id).isEmpty()) {
                sender.sendText(chatId, "Не найдено", nicknamesMenu());
                return;
            }
            merchantAliasService.delete(id);
            onNicknamesList(chatId, userId);
        } catch (Exception e) {
            sender.sendText(chatId, "Ошибка", nicknamesMenu());
        }
    }

    private void onCategoryRuleAdd(long chatId, UUID userId, String arg) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        if (!budgetService.isAdmin(budgetId, userId)) {
            sender.sendText(chatId, "Нужна роль ADMIN");
            return;
        }

        String[] leftParts = arg.split("\\s+", 3);
        if (leftParts.length != 3) {
            sender.sendText(chatId, """
                    Примеры:
                    /catrule_add EXPENSE Продукты SANTA
                    /catrule_add INCOME Работа account:Alfa min:4000 prio:10
                    /catrule_add INCOME Работа account:Alfa amount:500 once_month prio:20""");
            return;
        }

        com.moneyfirewall.domain.CategoryKind kind;
        try {
            kind = com.moneyfirewall.domain.CategoryKind.valueOf(leftParts[0].trim().toUpperCase());
        } catch (Exception e) {
            sender.sendText(chatId, "kind: INCOME или EXPENSE");
            return;
        }
        String categoryName = leftParts[1].trim();
        String match = leftParts[2].trim();
        com.moneyfirewall.service.CategoryRuleConditions conditions = com.moneyfirewall.service.CategoryRuleConditions.fromMatchText(match);
        if (!conditions.hasConstraints()) {
            sender.sendText(chatId, "Укажите фразу или account:/min:/amount:");
            return;
        }
        try {
            categoryRuleService.add(budgetId, kind, categoryName, conditions, false);
            sender.sendText(chatId, "Ок");
        } catch (Exception e) {
            sender.sendText(chatId, "Ошибка: " + e.getMessage());
        }
    }

    private void onCategoryRuleList(long chatId, UUID userId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Правила категорий\n");
        categoryRuleService.list(budgetId).forEach(r -> sb.append(r.getId())
                .append(" ")
                .append(r.getCategory().getKind())
                .append(" ")
                .append(r.getCategory().getName())
                .append(" <= ")
                .append(r.getPattern())
                .append("\n"));
        sender.sendText(chatId, sb.toString().trim());
    }

    private void onCategoryRuleDelete(long chatId, UUID userId, String arg) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        if (!budgetService.isAdmin(budgetId, userId)) {
            sender.sendText(chatId, "Нужна роль ADMIN");
            return;
        }
        try {
            categoryRuleService.delete(UUID.fromString(arg.trim()));
            sender.sendText(chatId, "Ок");
        } catch (Exception e) {
            sender.sendText(chatId, "Пример: /catrule_delete <uuid>");
        }
    }

    private void onTransferAutoLink(long chatId, UUID userId, String arg) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        if (!budgetService.isAdmin(budgetId, userId)) {
            sender.sendText(chatId, "Нужна роль ADMIN");
            return;
        }
        int linked = transferLinkingService.autoLink(budgetId, java.time.Duration.ofHours(48));
        sender.sendText(chatId, "Связано: " + linked);
    }

    private void onTransferLink(long chatId, UUID userId, String arg) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        if (!budgetService.isAdmin(budgetId, userId)) {
            sender.sendText(chatId, "Нужна роль ADMIN");
            return;
        }
        try {
            String[] p = arg.trim().split("\\s+");
            UUID outId = UUID.fromString(p[0]);
            UUID inId = UUID.fromString(p[1]);
            UUID groupId = transferLinkingService.linkManual(budgetId, outId, inId);
            sender.sendText(chatId, "Группа: " + groupId);
        } catch (Exception e) {
            sender.sendText(chatId, "Пример: /transfer_link <outTxId> <inTxId>");
        }
    }

    private void onTransferUnlink(long chatId, UUID userId, String arg) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        if (!budgetService.isAdmin(budgetId, userId)) {
            sender.sendText(chatId, "Нужна роль ADMIN");
            return;
        }
        try {
            UUID groupId = UUID.fromString(arg.trim());
            int n = transferLinkingService.unlinkByGroupId(groupId);
            sender.sendText(chatId, "Развязано: " + n);
        } catch (Exception e) {
            sender.sendText(chatId, "Пример: /transfer_unlink <groupId>");
        }
    }

    private void onAssetEvent(long chatId, UUID userId, String arg) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        try {
            String[] p = arg.trim().split("\\s+");
            com.moneyfirewall.domain.AssetEventType type = com.moneyfirewall.domain.AssetEventType.valueOf(p[0].toUpperCase());
            String asset = p[1].toUpperCase();
            BigDecimal qty = new BigDecimal(p[2].replace(',', '.'));
            BigDecimal feeAmount = null;
            String feeCur = null;
            String feeAccount = null;
            if (p.length >= 6) {
                feeAmount = new BigDecimal(p[3].replace(',', '.'));
                feeCur = p[4].toUpperCase();
                feeAccount = p[5];
            }
            assetEventService.addEvent(budgetId, userId, Instant.now(), type, asset, qty, null, null, feeAmount, feeCur, null, feeAccount);
            sender.sendText(chatId, "Ок");
        } catch (Exception e) {
            sender.sendText(chatId, "Пример: /asset_event BUY BTC 0.01 3.00 USDT Binance");
        }
    }

    private void onAssetPositions(long chatId, UUID userId, String arg) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        Map<String, BigDecimal> pos = assetReportService.positions(budgetId, Instant.EPOCH, Instant.now());
        StringBuilder sb = new StringBuilder();
        sb.append("Позиции\n");
        pos.forEach((k, v) -> sb.append(k).append(" ").append(v.toPlainString()).append("\n"));
        sender.sendText(chatId, sb.toString().trim());
    }

    private void onReportMenu(long chatId, UUID userId) {
        if (budgetService.getActiveBudgetId(userId) == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        sender.sendText(chatId, "Период отчёта", reportPeriodMenu());
    }

    private void onReportMonth(long chatId, UUID userId, String arg) {
        if (budgetService.getActiveBudgetId(userId) == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        YearMonth ym;
        try {
            ym = (arg == null || arg.isBlank()) ? YearMonth.now(ZoneOffset.UTC) : YearMonth.parse(arg.trim());
        } catch (Exception e) {
            sender.sendText(chatId, "Формат: /report_month YYYY-MM");
            return;
        }
        Instant from = ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = ym.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        runReport(chatId, userId, from, to, ym.toString());
    }

    private void onReportPreset(long chatId, UUID userId, String preset) {
        if (budgetService.getActiveBudgetId(userId) == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant from;
        Instant to;
        String label;
        switch (preset) {
            case "current_month" -> {
                YearMonth ym = YearMonth.from(today);
                from = ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                to = ym.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                label = ym.toString();
            }
            case "last_month" -> {
                YearMonth ym = YearMonth.from(today).minusMonths(1);
                from = ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                to = ym.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                label = ym.toString();
            }
            case "current_year" -> {
                int y = today.getYear();
                from = LocalDate.of(y, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
                to = LocalDate.of(y + 1, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
                label = String.valueOf(y);
            }
            case "last_year" -> {
                int y = today.getYear() - 1;
                from = LocalDate.of(y, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
                to = LocalDate.of(y + 1, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
                label = String.valueOf(y);
            }
            default -> {
                sender.sendText(chatId, "Неизвестный период", reportPeriodMenu());
                return;
            }
        }
        runReport(chatId, userId, from, to, label);
    }

    private String formatByCurrency(Map<String, BigDecimal> byCurrency) {
        if (byCurrency.isEmpty()) {
            return "0";
        }
        return byCurrency.entrySet().stream()
                .map(e -> e.getValue().toPlainString() + " " + e.getKey())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private void onReportManualStart(long chatId, UUID userId) {
        if (budgetService.getActiveBudgetId(userId) == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        conversationService.set(userId, "report_period", new HashMap<>(Map.of("step", "from")));
        sender.sendText(chatId, "Дата начала (YYYY-MM-DD)", reportCancelMenu());
    }

    private void runReport(long chatId, UUID userId, Instant from, Instant to, String label) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        int recategorized = categoryRuleService.recategorizeExpenses(budgetId, from, to);
        int nicknamed = merchantAliasService.reapplyNicknames(budgetId, from, to);
        ReportTables tables = reportService.build(budgetId, from, to);

        Map<String, BigDecimal> incomeByCurrency = new TreeMap<>();
        Map<String, BigDecimal> expenseByCurrency = new TreeMap<>();
        for (List<Object> row : tables.summary()) {
            if (row == null || row.size() < 3) {
                continue;
            }
            Object metricObj = row.get(0);
            Object currencyObj = row.get(1);
            Object valueObj = row.get(2);
            if (!(metricObj instanceof String metric) || !(currencyObj instanceof String currency)) {
                continue;
            }
            if (!(valueObj instanceof BigDecimal val)) {
                continue;
            }
            if ("income".equals(metric)) {
                incomeByCurrency.put(currency, val);
            } else if ("expense".equals(metric)) {
                expenseByCurrency.put(currency, val);
            }
        }
        sender.sendText(chatId, "Предпросмотр " + label + "\n" +
                "Доход: " + formatByCurrency(incomeByCurrency) + "\n" +
                "Расход: " + formatByCurrency(expenseByCurrency) +
                (recategorized > 0 ? "\nКатегории проставлены: " + recategorized : "") +
                (nicknamed > 0 ? "\nНикнеймы обновлены: " + nicknamed : ""));

        byte[] xlsx = excelReportExporter.export(tables);
        String safeLabel = label.replace(" ", "_").replace("—", "-");
        String fileName = "moneyfirewall-" + safeLabel + ".xlsx";
        sender.sendDocument(chatId, xlsx, fileName, "Отчёт " + label);

        try {
            String title = "MoneyFirewall " + label + " " + budgetId;
            GoogleSheetsExporter.ExportResult res = googleSheetsExporter.export(title, tables);
            sender.sendText(chatId, res.url());
        } catch (Exception e) {
            sender.sendText(chatId, "Google Sheets: ошибка");
        }
    }

    private boolean handleWizardInput(long chatId, UUID userId, String text) {
        State st = conversationService.get(userId).orElse(null);
        if (st == null) {
            return false;
        }
        if ("catrule_add".equals(st.key())) {
            UUID budgetId = budgetService.getActiveBudgetId(userId);
            if (budgetId == null) {
                conversationService.clear(userId);
                sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
                return true;
            }
            if (!budgetService.isAdmin(budgetId, userId)) {
                conversationService.clear(userId);
                sender.sendText(chatId, "Нужна роль ADMIN", menuForUser(userId));
                return true;
            }
            String step = st.payload().getOrDefault("step", "").toString();
            if ("newCategoryName".equals(step)) {
                String name = text == null ? "" : text.trim();
                if (name.isBlank()) {
                    sender.sendText(chatId, "Введите название категории", catRulesBackMenu());
                    return true;
                }
                Map<String, Object> p = new HashMap<>(st.payload());
                p.put("categoryName", name);
                p.put("step", "pattern");
                conversationService.set(userId, "catrule_add", p);
                sender.sendText(chatId, "Введите фразу для поиска контрагента", catRulesBackMenu());
                return true;
            }
            if ("pattern".equals(step)) {
                String pattern = text == null ? "" : text.trim();
                if (pattern.isBlank()) {
                    sender.sendText(chatId, "Введите фразу для поиска контрагента", catRulesBackMenu());
                    return true;
                }
                String kindRaw = st.payload().getOrDefault("kind", "").toString();
                CategoryKind kind;
                try {
                    kind = CategoryKind.valueOf(kindRaw);
                } catch (Exception e) {
                    conversationService.clear(userId);
                    sender.sendText(chatId, "Ошибка", menuForUser(userId));
                    return true;
                }
                String categoryId = st.payload().getOrDefault("categoryId", "").toString();
                if (!categoryId.isBlank()) {
                    categoryRuleService.add(budgetId, UUID.fromString(categoryId), pattern, 100, false);
                } else {
                    String categoryName = st.payload().getOrDefault("categoryName", "").toString();
                    if (categoryName.isBlank()) {
                        sender.sendText(chatId, "Категория не выбрана", catRulesBackMenu());
                        return true;
                    }
                    categoryRuleService.add(budgetId, kind, categoryName, pattern, 100, false);
                }
                conversationService.clear(userId);
                sender.sendText(chatId, "Ок", catRulesMenu());
                return true;
            }
            return true;
        }

        if ("nickname_add".equals(st.key())) {
            UUID budgetId = budgetService.getActiveBudgetId(userId);
            if (budgetId == null) {
                conversationService.clear(userId);
                sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
                return true;
            }
            if (!budgetService.isAdmin(budgetId, userId)) {
                conversationService.clear(userId);
                sender.sendText(chatId, "Нужна роль ADMIN", menuForUser(userId));
                return true;
            }
            String step = st.payload().getOrDefault("step", "").toString();
            if ("pattern".equals(step)) {
                String pattern = text == null ? "" : text.trim();
                if (pattern.isBlank()) {
                    sender.sendText(chatId, "Введите фразу из выписки (контрагент)", nicknamesBackMenu());
                    return true;
                }
                Map<String, Object> p = new HashMap<>(st.payload());
                p.put("pattern", pattern);
                p.put("step", "nickname");
                conversationService.set(userId, "nickname_add", p);
                sender.sendText(chatId, "Введите никнейм для отчётов", nicknamesBackMenu());
                return true;
            }
            if ("nickname".equals(step)) {
                String nickname = text == null ? "" : text.trim();
                if (nickname.isBlank()) {
                    sender.sendText(chatId, "Введите никнейм", nicknamesBackMenu());
                    return true;
                }
                String pattern = st.payload().getOrDefault("pattern", "").toString();
                merchantAliasService.add(budgetId, pattern, nickname, 100, false);
                conversationService.clear(userId);
                sender.sendText(chatId, "Ок", nicknamesMenu());
                return true;
            }
            return true;
        }

        if ("budget_create".equals(st.key())) {
            String name = text == null ? "" : text.trim();
            if (name.isBlank()) {
                sender.sendText(chatId, "Введите название бюджета", budgetCreateMenu());
                return true;
            }
            Budget b = budgetService.createBudget(userId, name);
            conversationService.clear(userId);
            sender.sendText(chatId, "Бюджет создан: " + b.getName(), mainMenu());
            return true;
        }

        if (supportsDateStep(st.key()) && "dateText".equals(st.payload().getOrDefault("step", "").toString())) {
            LocalDate date = parseCashDate(text);
            if (date == null) {
                sender.sendText(chatId, "Формат: YYYY-MM-DD или DD.MM.YYYY", cashDateBackMenu(st.key()));
                return true;
            }
            Map<String, Object> p = new HashMap<>(st.payload());
            p.put("occurredAt", date.atStartOfDay().toInstant(ZoneOffset.UTC).toString());
            sendDatedConfirm(chatId, userId, st.key(), p);
            return true;
        }

        if (("income".equals(st.key()) || "cash_income".equals(st.key()))
                && "newCategoryName".equals(st.payload().getOrDefault("step", "").toString())) {
            UUID budgetId = budgetService.getActiveBudgetId(userId);
            if (budgetId == null) {
                conversationService.clear(userId);
                sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
                return true;
            }
            String name = text == null ? "" : text.trim();
            if (name.isBlank()) {
                sender.sendText(chatId, "Введите название категории дохода", incomeCategoryBackMenu(st.key()));
                return true;
            }
            Category c = categoryService.ensure(budgetId, CategoryKind.INCOME, name);
            Map<String, Object> p = new HashMap<>(st.payload());
            p.put("category", categoryService.displayName(c));
            p.put("categoryId", c.getId().toString());
            p.put("step", "amount");
            conversationService.set(userId, st.key(), p);
            sender.sendText(chatId, "Выбери сумму", amountMenu(st.key()));
            return true;
        }

        if ("expense_manual".equals(st.key()) && "counterparty".equals(st.payload().getOrDefault("step", "").toString())) {
            UUID budgetId = budgetService.getActiveBudgetId(userId);
            if (budgetId == null) {
                conversationService.clear(userId);
                sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
                return true;
            }
            String shop = text == null ? "" : text.trim();
            if (shop.isBlank()) {
                sender.sendText(chatId, "Введите магазин или нажмите Пропустить", expenseManualShopMenu("expense_manual"));
                return true;
            }
            saveExpenseManual(budgetId, userId, st.payload(), shop);
            conversationService.clear(userId);
            sender.sendText(chatId, "✅ Трата добавлена", mainMenu());
            return true;
        }

        if ("expense_scan_shop".equals(st.key())) {
            UUID budgetId = budgetService.getActiveBudgetId(userId);
            if (budgetId == null) {
                conversationService.clear(userId);
                sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
                return true;
            }
            String shop = text == null ? "" : text.trim();
            if (shop.isBlank()) {
                sender.sendText(chatId, "Введите магазин", budgetCreateMenu());
                return true;
            }
            BigDecimal amount;
            try {
                amount = new BigDecimal(st.payload().get("amount").toString());
            } catch (Exception e) {
                conversationService.clear(userId);
                sender.sendText(chatId, "Ошибка", menuForUser(userId));
                return true;
            }
            String accountName = "Cash:" + st.payload().getOrDefault("telegramUserId", "").toString();
            if ("Cash:".equals(accountName)) {
                conversationService.clear(userId);
                sender.sendText(chatId, "Ошибка", menuForUser(userId));
                return true;
            }
            transactionService.createExpense(budgetId, userId, Instant.now(), amount, currencyFromPayload(st.payload()), accountName, "Прочее", shop, null);
            conversationService.clear(userId);
            sender.sendText(chatId, "✅ Трата добавлена", mainMenu());
            return true;
        }

        if ("account_add".equals(st.key())) {
            UUID budgetId = budgetService.getActiveBudgetId(userId);
            if (budgetId == null) {
                conversationService.clear(userId);
                sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
                return true;
            }
            String raw = text == null ? "" : text.trim();
            try {
                String[] p = raw.split("\\s+", 3);
                AccountType type = AccountType.valueOf(p[0].trim().toUpperCase());
                String currency = p[1].trim().toUpperCase();
                String name = p[2].trim();
                accountService.create(budgetId, userId, name, type, currency);
                conversationService.clear(userId);
                sender.sendText(chatId, "Ок", accountsMenu());
            } catch (Exception e) {
                sender.sendText(chatId, "Формат: TYPE CURRENCY NAME", budgetCreateMenu());
            }
            return true;
        }

        if ("account_rename".equals(st.key())) {
            UUID budgetId = budgetService.getActiveBudgetId(userId);
            if (budgetId == null) {
                conversationService.clear(userId);
                sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
                return true;
            }
            String newName = text == null ? "" : text.trim();
            if (newName.isBlank()) {
                sender.sendText(chatId, "Введите новое имя счёта", budgetCreateMenu());
                return true;
            }
            try {
                String accountId = st.payload().get("accountId").toString();
                accountService.rename(budgetId, UUID.fromString(accountId), newName);
                conversationService.clear(userId);
                sender.sendText(chatId, "Ок", accountsMenu());
            } catch (Exception e) {
                conversationService.clear(userId);
                sender.sendText(chatId, "Ошибка", accountsMenu());
            }
            return true;
        }

        if ("report_period".equals(st.key())) {
            if (budgetService.getActiveBudgetId(userId) == null) {
                conversationService.clear(userId);
                sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
                return true;
            }
            String step = st.payload().getOrDefault("step", "").toString();
            String raw = text == null ? "" : text.trim();
            if ("from".equals(step)) {
                LocalDate fromDate;
                try {
                    fromDate = LocalDate.parse(raw);
                } catch (Exception e) {
                    sender.sendText(chatId, "Формат: YYYY-MM-DD", reportCancelMenu());
                    return true;
                }
                Map<String, Object> payload = new HashMap<>(st.payload());
                payload.put("step", "to");
                payload.put("from", fromDate.toString());
                conversationService.set(userId, "report_period", payload);
                sender.sendText(chatId, "Дата конца (YYYY-MM-DD), включительно", reportCancelMenu());
                return true;
            }
            if ("to".equals(step)) {
                LocalDate toDate;
                try {
                    toDate = LocalDate.parse(raw);
                } catch (Exception e) {
                    sender.sendText(chatId, "Формат: YYYY-MM-DD", reportCancelMenu());
                    return true;
                }
                LocalDate fromDate = LocalDate.parse(st.payload().get("from").toString());
                if (toDate.isBefore(fromDate)) {
                    sender.sendText(chatId, "Конец не может быть раньше начала", reportCancelMenu());
                    return true;
                }
                conversationService.clear(userId);
                Instant from = fromDate.atStartOfDay().toInstant(ZoneOffset.UTC);
                Instant to = toDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                String label = fromDate + " — " + toDate;
                runReport(chatId, userId, from, to, label);
                return true;
            }
        }

        sender.sendText(chatId, "Используй кнопки в меню", menuForUser(userId));
        return true;
    }

    private void handleCallback(CallbackQuery cq) {
        if (cq.getMessage() == null || cq.getMessage().getChat() == null || cq.getFrom() == null) {
            return;
        }
        long chatId = cq.getMessage().getChatId();
        User tgUser = cq.getFrom();
        String displayName = tgUser.getUserName() != null ? tgUser.getUserName() : ((tgUser.getFirstName() == null ? "" : tgUser.getFirstName()) + " " + (tgUser.getLastName() == null ? "" : tgUser.getLastName())).trim();
        com.moneyfirewall.domain.User user = userService.getOrCreate(tgUser.getId(), chatId, displayName);

        String data = cq.getData() == null ? "" : cq.getData();
        log.debug("callback chatId={} data={}", chatId, data);
        try {
            handleCallbackData(chatId, user, tgUser, data);
        } catch (Exception e) {
            log.error("callback failed chatId={} data={}", chatId, data, e);
            sender.sendText(chatId, "Ошибка", menuForUser(user.getId()));
        } finally {
            if (cq.getId() != null) {
                sender.answerCallback(cq.getId());
            }
        }
    }

    private void handleCallbackData(long chatId, com.moneyfirewall.domain.User user, User tgUser, String data) {
        if (data.startsWith("wiz:")) {
            handleWizardCallback(chatId, user.getId(), data);
            return;
        }

        if (data.startsWith("mf:budget_use:")) {
            String budgetId = data.substring("mf:budget_use:".length());
            onBudgetUse(chatId, user.getId(), budgetId);
            return;
        }

        if (data.startsWith("mf:expense_scan_cur:")) {
            String currency = data.substring("mf:expense_scan_cur:".length()).trim().toUpperCase(Locale.ROOT);
            onExpenseScanCurrencySelected(chatId, user.getId(), currency);
            return;
        }

        if (data.startsWith("mf:expense_scan_cat:")) {
            String categoryId = data.substring("mf:expense_scan_cat:".length());
            onExpenseScanCategorySelected(chatId, user.getId(), categoryId);
            return;
        }

        if (data.startsWith("mf:acc_ren:")) {
            String accountId = data.substring("mf:acc_ren:".length());
            onAccountRenameChosen(chatId, user.getId(), accountId);
            return;
        }

        if (data.startsWith("mf:acc_del:")) {
            String accountId = data.substring("mf:acc_del:".length());
            onAccountDeleteConfirm(chatId, user.getId(), accountId);
            return;
        }

        if (data.startsWith("mf:acc_del_do:")) {
            String accountId = data.substring("mf:acc_del_do:".length());
            onAccountDeleteDo(chatId, user.getId(), accountId);
            return;
        }

        if (data.startsWith("mf:import:set:")) {
            String bank = data.substring("mf:import:set:".length());
            onImportWithBank(chatId, user.getId(), bank);
            return;
        }

        if (data.startsWith("mf:report:p:")) {
            String preset = data.substring("mf:report:p:".length());
            onReportPreset(chatId, user.getId(), preset);
            return;
        }

        if (data.startsWith("mf:catrules:add_kind:")) {
            String kind = data.substring("mf:catrules:add_kind:".length());
            onCategoryRuleAddKind(chatId, user.getId(), kind);
            return;
        }

        if (data.startsWith("mf:catrules:add_cat:")) {
            String categoryId = data.substring("mf:catrules:add_cat:".length());
            onCategoryRuleAddCategory(chatId, user.getId(), categoryId);
            return;
        }

        if (data.startsWith("mf:catrules:del_do:")) {
            String ruleId = data.substring("mf:catrules:del_do:".length());
            onCategoryRuleDeleteDo(chatId, user.getId(), ruleId);
            return;
        }

        if (data.startsWith("mf:catrules:del:")) {
            String ruleId = data.substring("mf:catrules:del:".length());
            onCategoryRuleDeleteConfirm(chatId, user.getId(), ruleId);
            return;
        }

        if (data.startsWith("mf:aliases:del_do:")) {
            String aliasId = data.substring("mf:aliases:del_do:".length());
            onNicknameDeleteDo(chatId, user.getId(), aliasId);
            return;
        }

        if (data.startsWith("mf:aliases:del:")) {
            String aliasId = data.substring("mf:aliases:del:".length());
            onNicknameDeleteConfirm(chatId, user.getId(), aliasId);
            return;
        }

        switch (data) {
            case "mf:menu" -> sender.sendText(chatId, "Меню", menuForUser(user.getId()));
            case "mf:budget_create" -> onBudgetCreateStart(chatId, user.getId());
            case "mf:budget_select" -> onBudgetSelect(chatId, user.getId());
            case "mf:income" -> onIncome(chatId, user.getId(), "");
            case "mf:expense_scan" -> onExpenseScan(chatId, user.getId(), tgUser.getId());
            case "mf:expense_manual" -> onExpenseManual(chatId, user.getId(), tgUser.getId());
            case "mf:expense_scan_save" -> onExpenseScanSave(chatId, user.getId());
            case "mf:expense_scan_pick_category" -> onExpenseScanSelectCategory(chatId, user.getId());
            case "mf:expense_scan_pick_shop" -> onExpenseScanEnterShop(chatId, user.getId());
            case "mf:transfer" -> onTransfer(chatId, user.getId(), "");
            case "mf:import" -> onImportMenu(chatId, user.getId());
            case "mf:report" -> onReportMenu(chatId, user.getId());
            case "mf:report:manual" -> onReportManualStart(chatId, user.getId());
            case "mf:accounts" -> onAccounts(chatId, user.getId());
            case "mf:accounts_list" -> onAccountsList(chatId, user.getId());
            case "mf:accounts_add" -> onAccountsAddStart(chatId, user.getId());
            case "mf:accounts_rename" -> onAccountsRenameSelect(chatId, user.getId());
            case "mf:accounts_delete" -> onAccountsDeleteSelect(chatId, user.getId());
            case "mf:budget_members" -> onBudgetMembers(chatId, user.getId());
            case "mf:catrules" -> onCategoryRulesMenu(chatId, user.getId());
            case "mf:catrules:add" -> onCategoryRulesAddStart(chatId, user.getId());
            case "mf:catrules:list" -> onCategoryRulesList(chatId, user.getId());
            case "mf:catrules:add_newcat" -> onCategoryRuleAddNewCategory(chatId, user.getId());
            case "mf:cat_export" -> onCategoriesExport(chatId, user.getId());
            case "mf:cat_import" -> onCategoriesImportStart(chatId, user.getId());
            case "mf:aliases" -> onNicknamesMenu(chatId, user.getId());
            case "mf:aliases:add" -> onNicknamesAddStart(chatId, user.getId());
            case "mf:aliases:list" -> onNicknamesList(chatId, user.getId());
            case "mf:cash" -> onCashMenu(chatId, user.getId());
            case "mf:cash:income" -> onCashIncomeStart(chatId, user.getId(), tgUser.getId());
            case "mf:cash:expense", "mf:cash:spend" -> onCashExpenseStart(chatId, user.getId(), tgUser.getId());
            case "mf:cash:withdraw" -> onCashWithdrawStart(chatId, user.getId());
            case "mf:cancel" -> onCancel(chatId, user.getId());
            case "mf:help" -> sender.sendText(chatId, helpText(), menuForUser(user.getId()));
            default -> sender.sendText(chatId, "Неизвестно", menuForUser(user.getId()));
        }
    }

    private void handleWizardCallback(long chatId, UUID userId, String data) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            conversationService.clear(userId);
            sender.sendText(chatId, "Сначала выбери бюджет", mainMenu());
            return;
        }
        State st = conversationService.get(userId).orElse(null);
        if (st == null) {
            sender.sendText(chatId, "Нет активного визарда", mainMenu());
            return;
        }
        String key = st.key();
        Map<String, Object> payload = new HashMap<>(st.payload());
        String[] p = data.split(":");
        if (p.length < 3) {
            sender.sendText(chatId, "Ошибка шага", mainMenu());
            return;
        }
        String part = p[1];
        if ("amount".equals(part) && p.length >= 4) {
            payload.put("amount", new BigDecimal(p[3]));
            if ("expense_manual".equals(key)) {
                payload.put("step", "currency");
                conversationService.set(userId, key, payload);
                sender.sendText(chatId, "Выбери валюту", expenseCurrencyMenu(key));
                return;
            }
            payload.put("step", "currency");
            conversationService.set(userId, key, payload);
            sender.sendText(chatId, "Выбери валюту", currencyMenu(key));
            return;
        }
        if ("currency".equals(part) && p.length >= 4) {
            payload.put("currency", p[3]);
            if ("expense_manual".equals(key)) {
                payload.put("step", "counterparty");
                conversationService.set(userId, key, payload);
                sender.sendText(chatId, "Магазин (опционально)", expenseManualShopMenu(key));
                return;
            }
            if ("cash_income".equals(key) || "cash_expense".equals(key)) {
                String accountName = cashAccountName(payload);
                if (accountName == null) {
                    conversationService.clear(userId);
                    sender.sendText(chatId, "Ошибка", menuForUser(userId));
                    return;
                }
                payload.put("account", accountName);
                payload.put("step", "date");
                conversationService.set(userId, key, payload);
                sender.sendText(chatId, "Дата операции", cashDateMenu(key));
                return;
            }
            if ("cash_withdraw".equals(key)) {
                payload.put("step", "account");
                conversationService.set(userId, key, payload);
                sender.sendText(chatId, "Выбери карту/счёт", cardAccountMenu("wiz:account", budgetId));
                return;
            }
            if ("transfer".equals(key)) {
                payload.put("step", "fromAccount");
                conversationService.set(userId, key, payload);
                sender.sendText(chatId, "С какого счёта?", accountMenu("wiz:from", budgetId, null));
            } else {
                payload.put("step", "account");
                conversationService.set(userId, key, payload);
                sender.sendText(chatId, "Выбери счёт", accountMenu("wiz:account", budgetId, null));
            }
            return;
        }
        if ("from".equals(part) && p.length >= 3) {
            String accountId = p[p.length - 1];
            String accountName = accountNameById(budgetId, accountId);
            if (accountName == null) {
                sender.sendText(chatId, "Счёт не найден", mainMenu());
                conversationService.clear(userId);
                return;
            }
            payload.put("fromAccount", accountName);
            payload.put("step", "toAccount");
            conversationService.set(userId, key, payload);
            sender.sendText(chatId, "На какой счёт?", accountMenu("wiz:to", budgetId, accountName));
            return;
        }
        if ("to".equals(part) && p.length >= 3) {
            String accountId = p[p.length - 1];
            String accountName = accountNameById(budgetId, accountId);
            if (accountName == null) {
                sender.sendText(chatId, "Счёт не найден", mainMenu());
                conversationService.clear(userId);
                return;
            }
            payload.put("toAccount", accountName);
            payload.put("step", "confirm");
            conversationService.set(userId, key, payload);
            sender.sendText(chatId, "Подтвердить перевод?", confirmMenu(key));
            return;
        }
        if ("account".equals(part) && p.length >= 3) {
            String accountId = p[p.length - 1];
            String accountName = accountNameById(budgetId, accountId);
            if (accountName == null) {
                sender.sendText(chatId, "Счёт не найден", mainMenu());
                conversationService.clear(userId);
                return;
            }
            payload.put("account", accountName);
            if ("cash_withdraw".equals(key)) {
                payload.put("step", "date");
                conversationService.set(userId, key, payload);
                sender.sendText(chatId, "Дата операции", cashDateMenu(key));
                return;
            }
            if ("income".equals(key) && payload.get("categoryId") != null) {
                payload.put("step", "counterparty");
                conversationService.set(userId, key, payload);
                sender.sendText(chatId, "Выбери контрагента", counterpartyMenu());
                return;
            }
            payload.put("step", "category");
            conversationService.set(userId, key, payload);
            CategoryKind kind = "income".equals(key) ? CategoryKind.INCOME : CategoryKind.EXPENSE;
            sender.sendText(chatId, "Выбери категорию", categoryMenu(budgetId, kind));
            return;
        }
        if ("category".equals(part) && p.length >= 4) {
            if ("back".equals(p[3])) {
                if ("income".equals(key) || "cash_income".equals(key)) {
                    categoryService.ensureStandardIncomeCategories(budgetId);
                    payload.put("step", "category");
                    conversationService.set(userId, key, payload);
                    sender.sendText(chatId, "Выбери категорию дохода", incomeCategoryMenuByUsage(budgetId, key));
                    return;
                }
            }
            if ("expense".equals(p[3])) {
                if (!"income".equals(key) && !"cash_income".equals(key)) {
                    sender.sendText(chatId, "Ошибка шага", menuForUser(userId));
                    return;
                }
                payload.put("step", "category");
                conversationService.set(userId, key, payload);
                sender.sendText(chatId, "Компенсация расхода: выбери категорию", incomeExpenseCategoryMenu(budgetId, key));
                return;
            }
            if ("newcat".equals(p[3])) {
                if (!"income".equals(key) && !"cash_income".equals(key)) {
                    sender.sendText(chatId, "Ошибка шага", menuForUser(userId));
                    return;
                }
                payload.put("step", "newCategoryName");
                conversationService.set(userId, key, payload);
                sender.sendText(chatId, "Введите название категории дохода", incomeCategoryBackMenu(key));
                return;
            }
            String categoryName = categoryNamePathById(budgetId, p[3]);
            if (categoryName == null) {
                sender.sendText(chatId, "Категория не найдена", mainMenu());
                conversationService.clear(userId);
                return;
            }
            payload.put("category", categoryName);
            payload.put("categoryId", p[3]);
            if ("cash_income".equals(key) || "cash_expense".equals(key) || "expense_manual".equals(key) || "income".equals(key)) {
                payload.put("step", "amount");
                conversationService.set(userId, key, payload);
                sender.sendText(chatId, "Выбери сумму", amountMenu(key));
                return;
            }
            payload.put("step", "counterparty");
            conversationService.set(userId, key, payload);
            sender.sendText(chatId, "Выбери контрагента", counterpartyMenu());
            return;
        }
        if ("counterparty".equals(part) && p.length >= 4) {
            String cp = "none".equals(p[3]) ? null : p[3];
            if ("expense_manual".equals(key)) {
                saveExpenseManual(budgetId, userId, payload, cp);
                conversationService.clear(userId);
                sender.sendText(chatId, "✅ Трата добавлена", mainMenu());
                return;
            }
            payload.put("counterparty", cp);
            if ("income".equals(key) && isIncomeCompensation(budgetId, payload)) {
                payload.put("step", "date");
                conversationService.set(userId, key, payload);
                sender.sendText(chatId, "Дата операции", cashDateMenu(key));
                return;
            }
            payload.put("step", "confirm");
            conversationService.set(userId, key, payload);
            sender.sendText(chatId, "Подтвердить операцию?", confirmMenu(key));
            return;
        }
        if ("date".equals(part) && p.length >= 4) {
            if (!supportsDateStep(key)) {
                sender.sendText(chatId, "Ошибка шага", menuForUser(userId));
                return;
            }
            if ("back".equals(p[3])) {
                payload.put("step", "date");
                conversationService.set(userId, key, payload);
                sender.sendText(chatId, "Дата операции", cashDateMenu(key));
                return;
            }
            if ("custom".equals(p[3])) {
                payload.put("step", "dateText");
                conversationService.set(userId, key, payload);
                sender.sendText(chatId, "Дата операции (YYYY-MM-DD или DD.MM.YYYY)", cashDateBackMenu(key));
                return;
            }
            LocalDate date = null;
            if ("today".equals(p[3])) {
                date = LocalDate.now(ZoneOffset.UTC);
            } else if ("yesterday".equals(p[3])) {
                date = LocalDate.now(ZoneOffset.UTC).minusDays(1);
            }
            if (date == null) {
                sender.sendText(chatId, "Ошибка шага", menuForUser(userId));
                return;
            }
            payload.put("occurredAt", date.atStartOfDay().toInstant(ZoneOffset.UTC).toString());
            sendDatedConfirm(chatId, userId, key, payload);
            return;
        }
        if ("confirm".equals(part) && p.length >= 4) {
            if ("cancel".equals(p[3])) {
                conversationService.clear(userId);
                sender.sendText(chatId, "Отменено", mainMenu());
                return;
            }
            try {
                BigDecimal amount = new BigDecimal(payload.get("amount").toString());
                String currency = payload.get("currency").toString();
                Instant now = occurredAtFromPayload(payload);
                if ("income".equals(key)) {
                    String account = payload.get("account").toString();
                    String categoryId = payload.get("categoryId") == null ? null : payload.get("categoryId").toString();
                    if (categoryId != null && !"misc".equals(categoryId)) {
                        transactionService.createIncomeByCategoryId(
                                budgetId,
                                userId,
                                now,
                                amount,
                                currency,
                                account,
                                UUID.fromString(categoryId),
                                (String) payload.get("counterparty"),
                                null
                        );
                    } else {
                        transactionService.createIncome(
                                budgetId,
                                userId,
                                now,
                                amount,
                                currency,
                                account,
                                payload.get("category").toString(),
                                (String) payload.get("counterparty"),
                                null
                        );
                    }
                } else if ("expense".equals(key)) {
                    String account = payload.get("account").toString();
                    String categoryId = payload.get("categoryId") == null ? null : payload.get("categoryId").toString();
                    if (categoryId != null && !"misc".equals(categoryId)) {
                        transactionService.createExpenseByCategoryId(
                                budgetId,
                                userId,
                                now,
                                amount,
                                currency,
                                account,
                                UUID.fromString(categoryId),
                                (String) payload.get("counterparty"),
                                null
                        );
                    } else {
                        transactionService.createExpense(
                                budgetId,
                                userId,
                                now,
                                amount,
                                currency,
                                account,
                                payload.get("category").toString(),
                                (String) payload.get("counterparty"),
                                null
                        );
                    }
                } else if ("cash_withdraw".equals(key)) {
                    categoryService.ensureCash(budgetId);
                    transactionService.createExpense(
                            budgetId,
                            userId,
                            now,
                            amount,
                            currency,
                            payload.get("account").toString(),
                            CategoryService.CASH,
                            null,
                            null
                    );
                } else if ("cash_income".equals(key)) {
                    createCashIncome(budgetId, userId, now, amount, currency, payload);
                } else if ("cash_expense".equals(key)) {
                    createCashExpense(budgetId, userId, now, amount, currency, payload);
                } else if ("transfer".equals(key)) {
                    transactionService.createTransfer(
                            budgetId,
                            userId,
                            now,
                            amount,
                            currency,
                            payload.get("fromAccount").toString(),
                            payload.get("toAccount").toString(),
                            null
                    );
                }
                conversationService.clear(userId);
                sender.sendText(chatId, "Готово", mainMenu());
            } catch (Exception e) {
                conversationService.clear(userId);
                sender.sendText(chatId, "Ошибка", mainMenu());
            }
        }
    }

    private String helpText() {
        return "Помощь\n\n" +
                "Доход — добавить поступление\n" +
                "Трата — добавить расход\n" +
                "Перевод — перевод между счетами/наличными\n" +
                "Импорт — меню выбора банка (PDF/JSON), затем файл\n" +
                "Отчёт — период кнопками или свой (даты YYYY-MM-DD)\n" +
                "Счета / Участники — управление в рамках активного бюджета\n" +
                "Никнеймы — короткие имена контрагентов в отчётах (фраза из выписки → никнейм)\n" +
                "Категории — правила автопроставления категории по контрагенту\n" +
                "Сброс — вернуться в главное меню\n\n" +
                "Сборка: " + buildInfoService.buildTime();
    }

    private InlineKeyboardMarkup menuForUser(UUID userId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        return budgetId == null ? startMenuWithoutBudget() : mainMenu();
    }

    private InlineKeyboardMarkup startMenuWithoutBudget() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("🆕 Создать бюджет", "mf:budget_create")),
                        new InlineKeyboardRow(btn("📂 Выбрать бюджет", "mf:budget_select")),
                        new InlineKeyboardRow(btn("❓ Помощь", "mf:help"), btn("🏠 Меню", "mf:cancel"))
                ))
                .build();
    }

    private InlineKeyboardMarkup budgetSelectMenu(List<Budget> budgets) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Budget b : budgets) {
            rows.add(new InlineKeyboardRow(btn("📌 " + b.getName(), "mf:budget_use:" + b.getId())));
        }
        rows.add(new InlineKeyboardRow(btn("⬅️ Назад", "mf:cancel")));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardMarkup budgetCreateMenu() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("✖️ Отмена", "mf:cancel"))
                ))
                .build();
    }

    private InlineKeyboardMarkup receiptResultMenu() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("✅ Сохранить Итого", "mf:expense_scan_save")),
                        new InlineKeyboardRow(btn("🏠 Меню", "mf:cancel"))
                ))
                .build();
    }

    private InlineKeyboardMarkup receiptSaveMenu(Map<String, Object> payload) {
        String cur = currencyFromPayload(payload);
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(
                                btn("BYN" + ("BYN".equals(cur) ? " ✓" : ""), "mf:expense_scan_cur:BYN"),
                                btn("EUR" + ("EUR".equals(cur) ? " ✓" : ""), "mf:expense_scan_cur:EUR"),
                                btn("USD" + ("USD".equals(cur) ? " ✓" : ""), "mf:expense_scan_cur:USD")
                        ),
                        new InlineKeyboardRow(btn("📂 Категория", "mf:expense_scan_pick_category")),
                        new InlineKeyboardRow(btn("🏪 Магазин", "mf:expense_scan_pick_shop")),
                        new InlineKeyboardRow(btn("🏠 Меню", "mf:cancel"))
                ))
                .build();
    }

    private InlineKeyboardMarkup receiptCategoryMenu(UUID budgetId) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Category c : categoryService.listExpenseByUsage(budgetId)) {
            rows.add(new InlineKeyboardRow(btn(categoryService.displayName(c), "mf:expense_scan_cat:" + c.getId())));
        }
        rows.add(new InlineKeyboardRow(btn("Прочее", "mf:expense_scan_cat:misc")));
        rows.add(new InlineKeyboardRow(btn("⬅️ Назад", "mf:expense_scan_save")));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardMarkup accountsMenu() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("📋 Список", "mf:accounts_list")),
                        new InlineKeyboardRow(btn("➕ Добавить", "mf:accounts_add")),
                        new InlineKeyboardRow(btn("✏️ Переименовать", "mf:accounts_rename")),
                        new InlineKeyboardRow(btn("🗑️ Удалить", "mf:accounts_delete")),
                        new InlineKeyboardRow(btn("🏠 Меню", "mf:cancel"))
                ))
                .build();
    }

    private InlineKeyboardMarkup accountsSelectMenu(UUID budgetId, String prefix) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (com.moneyfirewall.domain.Account a : accountService.list(budgetId)) {
            rows.add(new InlineKeyboardRow(btn(a.getName(), prefix + a.getId())));
        }
        rows.add(new InlineKeyboardRow(btn("⬅️ Назад", "mf:accounts")));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardMarkup confirmDeleteMenu(String accountId) {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("✅ Удалить", "mf:acc_del_do:" + accountId)),
                        new InlineKeyboardRow(btn("⬅️ Назад", "mf:accounts"))
                ))
                .build();
    }

    private InlineKeyboardMarkup reportPeriodMenu() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("Текущий месяц", "mf:report:p:current_month")),
                        new InlineKeyboardRow(btn("Прошлый месяц", "mf:report:p:last_month")),
                        new InlineKeyboardRow(btn("Текущий год", "mf:report:p:current_year")),
                        new InlineKeyboardRow(btn("Прошлый год", "mf:report:p:last_year")),
                        new InlineKeyboardRow(btn("✏️ Свой период", "mf:report:manual")),
                        new InlineKeyboardRow(btn("⬅️ Меню", "mf:cancel"))
                ))
                .build();
    }

    private InlineKeyboardMarkup reportCancelMenu() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("⬅️ Отмена", "mf:cancel"))
                ))
                .build();
    }

    private InlineKeyboardMarkup importBankMenu() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("PDF / JSON — МТБанк", "mf:import:set:mtbank")),
                        new InlineKeyboardRow(btn("PDF — ОПЛАТИ / Белинвест", "mf:import:set:oplati")),
                        new InlineKeyboardRow(btn("PDF / JSON — универсально", "mf:import:set:generic")),
                        new InlineKeyboardRow(btn("JSON — Альфа-Банк", "mf:import:set:AlfaJson")),
                        new InlineKeyboardRow(btn("⬅️ Меню", "mf:cancel"))
                ))
                .build();
    }

    private InlineKeyboardMarkup importPendingMenu() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("🔁 Другой банк", "mf:import")),
                        new InlineKeyboardRow(btn("🏠 Меню", "mf:cancel"))
                ))
                .build();
    }

    private InlineKeyboardMarkup mainMenu() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("💰 Доход", "mf:income")),
                        new InlineKeyboardRow(btn("🧾 Трата (скан чек)", "mf:expense_scan")),
                        new InlineKeyboardRow(btn("✍️ Трата (вручную)", "mf:expense_manual")),
                        new InlineKeyboardRow(btn("💵 Наличные", "mf:cash")),
                        new InlineKeyboardRow(btn("🔁 Перевод", "mf:transfer"), btn("📥 Импорт", "mf:import")),
                        new InlineKeyboardRow(btn("📊 Отчёт", "mf:report")),
                        new InlineKeyboardRow(btn("💳 Счета", "mf:accounts"), btn("👥 Участники", "mf:budget_members")),
                        new InlineKeyboardRow(btn("🏷 Категории", "mf:catrules"), btn("👤 Никнеймы", "mf:aliases")),
                        new InlineKeyboardRow(btn("❓ Помощь", "mf:help"), btn("🏠 Меню", "mf:cancel"))
                ))
                .build();
    }

    private InlineKeyboardMarkup catRulesMenu() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("➕ Добавить правило", "mf:catrules:add")),
                        new InlineKeyboardRow(btn("📋 Список правил", "mf:catrules:list")),
                        new InlineKeyboardRow(btn("📤 Экспорт категорий", "mf:cat_export"), btn("📥 Импорт категорий", "mf:cat_import")),
                        new InlineKeyboardRow(btn("⬅️ Меню", "mf:cancel"))
                ))
                .build();
    }

    private InlineKeyboardMarkup catImportPendingMenu() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("✖️ Отмена", "mf:cancel"))
                ))
                .build();
    }

    private InlineKeyboardMarkup catRulesKindMenu() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("🧾 Расход", "mf:catrules:add_kind:EXPENSE")),
                        new InlineKeyboardRow(btn("💰 Доход", "mf:catrules:add_kind:INCOME")),
                        new InlineKeyboardRow(btn("⬅️ Назад", "mf:catrules"))
                ))
                .build();
    }

    private InlineKeyboardMarkup catRulesBackMenu() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("⬅️ Назад", "mf:catrules")),
                        new InlineKeyboardRow(btn("🏠 Меню", "mf:cancel"))
                ))
                .build();
    }

    private InlineKeyboardMarkup catRulesPickCategoryMenu(UUID budgetId, CategoryKind kind) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (com.moneyfirewall.domain.Category p : categoryService.listParents(budgetId, kind)) {
            if (CategoryService.CASH.equalsIgnoreCase(p.getName())) {
                continue;
            }
            rows.add(new InlineKeyboardRow(btn(p.getName(), "mf:catrules:add_cat:" + p.getId())));
            for (com.moneyfirewall.domain.Category c : categoryService.listChildren(budgetId, kind, p.getId())) {
                rows.add(new InlineKeyboardRow(btn("— " + c.getName(), "mf:catrules:add_cat:" + c.getId())));
            }
        }
        rows.add(new InlineKeyboardRow(btn("➕ Новая категория", "mf:catrules:add_newcat")));
        rows.add(new InlineKeyboardRow(btn("⬅️ Назад", "mf:catrules:add")));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardMarkup catRulesListMenu(List<CategoryRule> rules) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            CategoryRule r = rules.get(i);
            String label = truncate((i + 1) + ". " + categoryRuleLabel(r), 64);
            rows.add(new InlineKeyboardRow(btn(label, "mf:catrules:del:" + r.getId())));
        }
        rows.add(new InlineKeyboardRow(btn("⬅️ Назад", "mf:catrules")));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardMarkup catRuleDeleteConfirmMenu(String ruleId) {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("✅ Удалить", "mf:catrules:del_do:" + ruleId)),
                        new InlineKeyboardRow(btn("⬅️ Назад", "mf:catrules:list"))
                ))
                .build();
    }

    private InlineKeyboardMarkup nicknamesMenu() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("➕ Добавить", "mf:aliases:add")),
                        new InlineKeyboardRow(btn("📋 Список", "mf:aliases:list")),
                        new InlineKeyboardRow(btn("⬅️ Меню", "mf:cancel"))
                ))
                .build();
    }

    private InlineKeyboardMarkup nicknamesBackMenu() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("⬅️ Назад", "mf:aliases")),
                        new InlineKeyboardRow(btn("🏠 Меню", "mf:cancel"))
                ))
                .build();
    }

    private InlineKeyboardMarkup nicknamesListMenu(List<MerchantAlias> aliases) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < aliases.size(); i++) {
            MerchantAlias a = aliases.get(i);
            String label = truncate((i + 1) + ". " + a.getPattern() + " → " + a.getNormalizedName(), 64);
            rows.add(new InlineKeyboardRow(btn(label, "mf:aliases:del:" + a.getId())));
        }
        rows.add(new InlineKeyboardRow(btn("⬅️ Назад", "mf:aliases")));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardMarkup nicknameDeleteConfirmMenu(String aliasId) {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("✅ Удалить", "mf:aliases:del_do:" + aliasId)),
                        new InlineKeyboardRow(btn("⬅️ Назад", "mf:aliases:list"))
                ))
                .build();
    }

    private InlineKeyboardMarkup cashMenu() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("💰 Доход", "mf:cash:income"), btn("🧾 Расход", "mf:cash:expense")),
                        new InlineKeyboardRow(btn("🏧 Снять с карты → CASH", "mf:cash:withdraw")),
                        new InlineKeyboardRow(btn("⬅️ Меню", "mf:cancel"))
                ))
                .build();
    }

    private String categoryRuleLabel(CategoryRule r) {
        Category c = r.getCategory();
        String categoryName = c.getParentCategory() != null
                ? c.getParentCategory().getName() + " / " + c.getName()
                : c.getName();
        StringBuilder cond = new StringBuilder();
        if (r.getAccountName() != null && !r.getAccountName().isBlank()) {
            cond.append("account:").append(r.getAccountName()).append(' ');
        }
        if (r.getMinAmount() != null) {
            cond.append("min:").append(r.getMinAmount().toPlainString()).append(' ');
        }
        if (r.getExactAmount() != null) {
            cond.append("amount:").append(r.getExactAmount().toPlainString()).append(' ');
        }
        if (r.isOncePerMonth()) {
            cond.append("once_month ");
        }
        if (r.getPattern() != null && !r.getPattern().isBlank()) {
            cond.append(r.getPattern());
        }
        String match = cond.toString().trim();
        if (match.isEmpty()) {
            match = "*";
        }
        return c.getKind() + " " + categoryName + " <= " + match;
    }

    private String categoryRulesListText(List<CategoryRule> rules) {
        if (rules.isEmpty()) {
            return "Правил пока нет";
        }
        StringBuilder sb = new StringBuilder("Правила категорий:\n");
        for (int i = 0; i < rules.size(); i++) {
            sb.append(i + 1).append(". ").append(categoryRuleLabel(rules.get(i))).append('\n');
        }
        return sb.toString().trim();
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 1) + "…";
    }

    private void onCategoryRulesMenu(long chatId, UUID userId) {
        sender.sendText(chatId, "Категории: правила автопроставления", catRulesMenu());
    }

    private void onCategoriesExport(long chatId, UUID userId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>", menuForUser(userId));
            return;
        }
        List<CategoryService.CategoryTransferEntry> categories = categoryService.exportAll(budgetId);
        List<CategoryRuleService.CategoryRuleTransferEntry> rules = categoryRuleService.exportAll(budgetId);
        try {
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(new CategoryBundleExport(categories, rules));
            sender.sendDocument(chatId, bytes, "categories.json", "Категории: " + categories.size() + ", правила: " + rules.size());
        } catch (Exception e) {
            log.warn("categories export failed budgetId={}", budgetId, e);
            sender.sendText(chatId, "Не удалось сформировать файл", menuForUser(userId));
        }
    }

    private void onCategoriesImportStart(long chatId, UUID userId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>", menuForUser(userId));
            return;
        }
        if (!budgetService.isAdmin(budgetId, userId)) {
            sender.sendText(chatId, "Нужна роль ADMIN", menuForUser(userId));
            return;
        }
        conversationService.set(userId, "cat_import", new HashMap<>());
        sender.sendText(chatId, "Пришли JSON-файл с категориями и правилами (как из экспорта). Совпадающее будет пропущено.", catImportPendingMenu());
    }

    private boolean handleCategoriesImportDocument(long chatId, UUID userId, Update update) {
        State st = conversationService.get(userId).orElse(null);
        if (st == null || !"cat_import".equals(st.key())) {
            return false;
        }
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            conversationService.clear(userId);
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>", menuForUser(userId));
            return true;
        }
        String fileId = update.getMessage().getDocument().getFileId();
        String fileName = update.getMessage().getDocument().getFileName();
        try {
            byte[] bytes = telegramFileService.downloadByFileId(fileId);
            CategoryBundleExport bundle = objectMapper.readValue(bytes, CategoryBundleExport.class);
            List<CategoryService.CategoryTransferEntry> categories = bundle.categories() == null ? List.of() : bundle.categories();
            List<CategoryRuleService.CategoryRuleTransferEntry> rules = bundle.rules() == null ? List.of() : bundle.rules();

            CategoryService.CategoryImportResult catResult = categoryService.importAll(budgetId, categories);
            CategoryRuleService.CategoryRuleImportResult ruleResult = categoryRuleService.importAll(budgetId, rules);

            conversationService.clear(userId);
            sender.sendText(chatId, "Категории: создано " + catResult.created() + ", пропущено " + catResult.skipped()
                    + "\nПравила: создано " + ruleResult.created() + ", пропущено " + ruleResult.skipped(), menuForUser(userId));
        } catch (Exception e) {
            conversationService.clear(userId);
            log.warn("categories import failed budgetId={} fileName={}", budgetId, fileName, e);
            sender.sendText(chatId, "Не удалось разобрать файл. Ожидается JSON как из экспорта.", menuForUser(userId));
        }
        return true;
    }

    private record CategoryBundleExport(
            List<CategoryService.CategoryTransferEntry> categories,
            List<CategoryRuleService.CategoryRuleTransferEntry> rules
    ) {
    }

    private void onCategoryRulesAddStart(long chatId, UUID userId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        if (!budgetService.isAdmin(budgetId, userId)) {
            sender.sendText(chatId, "Нужна роль ADMIN", menuForUser(userId));
            return;
        }
        conversationService.set(userId, "catrule_add", new HashMap<>(Map.of("step", "kind")));
        sender.sendText(chatId, "Выбери тип операции", catRulesKindMenu());
    }

    private void onCategoryRuleAddKind(long chatId, UUID userId, String kindRaw) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            conversationService.clear(userId);
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        State st = conversationService.get(userId).orElse(null);
        if (st == null || !"catrule_add".equals(st.key())) {
            sender.sendText(chatId, "Нет активного визарда", menuForUser(userId));
            return;
        }
        CategoryKind kind;
        try {
            kind = CategoryKind.valueOf(kindRaw);
        } catch (Exception e) {
            conversationService.clear(userId);
            sender.sendText(chatId, "Ошибка", menuForUser(userId));
            return;
        }
        Map<String, Object> p = new HashMap<>(st.payload());
        p.put("kind", kind.name());
        p.put("step", "category");
        conversationService.set(userId, "catrule_add", p);
        sender.sendText(chatId, "Выбери категорию", catRulesPickCategoryMenu(budgetId, kind));
    }

    private void onCategoryRuleAddCategory(long chatId, UUID userId, String categoryId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            conversationService.clear(userId);
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        State st = conversationService.get(userId).orElse(null);
        if (st == null || !"catrule_add".equals(st.key())) {
            sender.sendText(chatId, "Нет активного визарда", menuForUser(userId));
            return;
        }
        Map<String, Object> p = new HashMap<>(st.payload());
        p.put("categoryId", categoryId);
        p.put("step", "pattern");
        conversationService.set(userId, "catrule_add", p);
        sender.sendText(chatId, "Введите фразу для поиска контрагента", catRulesBackMenu());
    }

    private void onCategoryRuleAddNewCategory(long chatId, UUID userId) {
        State st = conversationService.get(userId).orElse(null);
        if (st == null || !"catrule_add".equals(st.key())) {
            sender.sendText(chatId, "Нет активного визарда", menuForUser(userId));
            return;
        }
        Map<String, Object> p = new HashMap<>(st.payload());
        p.put("step", "newCategoryName");
        p.remove("categoryId");
        p.remove("categoryName");
        conversationService.set(userId, "catrule_add", p);
        sender.sendText(chatId, "Введите название категории", catRulesBackMenu());
    }

    private void onCategoryRulesList(long chatId, UUID userId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        List<CategoryRule> rules = categoryRuleService.list(budgetId);
        sender.sendText(chatId, categoryRulesListText(rules), catRulesListMenu(rules));
    }

    private void onCategoryRuleDeleteConfirm(long chatId, UUID userId, String ruleId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(ruleId);
        } catch (Exception e) {
            sender.sendText(chatId, "Ошибка", menuForUser(userId));
            return;
        }
        CategoryRule rule = categoryRuleService.find(budgetId, id).orElse(null);
        if (rule == null) {
            sender.sendText(chatId, "Правило не найдено", catRulesMenu());
            return;
        }
        sender.sendText(chatId, "Удалить правило?\n" + categoryRuleLabel(rule), catRuleDeleteConfirmMenu(ruleId));
    }

    private void onCategoryRuleDeleteDo(long chatId, UUID userId, String ruleId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        if (!budgetService.isAdmin(budgetId, userId)) {
            sender.sendText(chatId, "Нужна роль ADMIN", menuForUser(userId));
            return;
        }
        try {
            UUID id = UUID.fromString(ruleId);
            if (categoryRuleService.find(budgetId, id).isEmpty()) {
                sender.sendText(chatId, "Правило не найдено", catRulesMenu());
                return;
            }
            categoryRuleService.delete(id);
        } catch (Exception e) {
            sender.sendText(chatId, "Ошибка", menuForUser(userId));
            return;
        }
        List<CategoryRule> rules = categoryRuleService.list(budgetId);
        sender.sendText(chatId, "Удалено\n\n" + categoryRulesListText(rules), catRulesListMenu(rules));
    }

    private void onCashMenu(long chatId, UUID userId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        categoryService.ensureCash(budgetId);
        sender.sendText(chatId, "Наличные (CASH)", cashMenu());
    }

    private void onCashWithdrawStart(long chatId, UUID userId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        categoryService.ensureCash(budgetId);
        conversationService.set(userId, "cash_withdraw", new HashMap<>(Map.of("step", "amount")));
        sender.sendText(chatId, "Сумма снятия с карты", amountMenu("cash_withdraw"));
    }

    private void onCashIncomeStart(long chatId, UUID userId, long telegramUserId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        budgetService.ensureUserCashAccount(budgetId, userId);
        categoryService.ensureStandardIncomeCategories(budgetId);
        conversationService.set(userId, "cash_income", new HashMap<>(Map.of(
                "step", "category",
                "telegramUserId", telegramUserId
        )));
        sender.sendText(chatId, "Доход наличными: выбери категорию", cashCategoryMenu(budgetId, CategoryKind.INCOME, "cash_income"));
    }

    private void onCashExpenseStart(long chatId, UUID userId, long telegramUserId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        categoryService.ensureCash(budgetId);
        budgetService.ensureUserCashAccount(budgetId, userId);
        conversationService.set(userId, "cash_expense", new HashMap<>(Map.of(
                "step", "category",
                "telegramUserId", telegramUserId
        )));
        sender.sendText(chatId, "Расход наличными: выбери категорию", cashCategoryMenu(budgetId, CategoryKind.EXPENSE, "cash_expense"));
    }

    private String cashAccountName(Map<String, Object> payload) {
        String accountName = "Cash:" + payload.getOrDefault("telegramUserId", "").toString();
        if ("Cash:".equals(accountName)) {
            return null;
        }
        return accountName;
    }

    private void createCashIncome(UUID budgetId, UUID userId, Instant now, BigDecimal amount, String currency, Map<String, Object> payload) {
        String account = payload.get("account").toString();
        String categoryId = payload.get("categoryId").toString();
        if ("misc".equals(categoryId)) {
            transactionService.createIncome(budgetId, userId, now, amount, currency, account, "Прочее", null, null);
            return;
        }
        transactionService.createIncomeByCategoryId(
                budgetId,
                userId,
                now,
                amount,
                currency,
                account,
                UUID.fromString(categoryId),
                null,
                null
        );
    }

    private void createCashExpense(UUID budgetId, UUID userId, Instant now, BigDecimal amount, String currency, Map<String, Object> payload) {
        String account = payload.get("account").toString();
        String categoryId = payload.get("categoryId").toString();
        if ("misc".equals(categoryId)) {
            transactionService.createExpense(budgetId, userId, now, amount, currency, account, "Прочее", null, null);
            return;
        }
        transactionService.createExpenseByCategoryId(
                budgetId,
                userId,
                now,
                amount,
                currency,
                account,
                UUID.fromString(categoryId),
                null,
                null
        );
    }

    private InlineKeyboardButton btn(String text, String data) {
        return InlineKeyboardButton.builder().text(text).callbackData(data).build();
    }

    private InlineKeyboardMarkup cardAccountMenu(String prefix, UUID budgetId) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (com.moneyfirewall.domain.Account a : accountService.list(budgetId)) {
            if (a.getType() == AccountType.CASH) {
                continue;
            }
            rows.add(new InlineKeyboardRow(btn(a.getName(), prefix + ":" + a.getId())));
        }
        rows.add(new InlineKeyboardRow(btn("Отмена", "wiz:confirm:cash_withdraw:cancel")));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardMarkup cashCategoryMenu(UUID budgetId, CategoryKind kind, String wizardKey) {
        if (kind == CategoryKind.EXPENSE) {
            return expenseCategoryMenuByUsage(budgetId, wizardKey);
        }
        return incomeCategoryMenuByUsage(budgetId, wizardKey);
    }

    private InlineKeyboardMarkup incomeCategoryMenuByUsage(UUID budgetId, String wizardKey) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Category c : categoryService.listIncomeByUsage(budgetId)) {
            rows.add(new InlineKeyboardRow(btn(
                    categoryService.displayName(c),
                    "wiz:category:" + wizardKey + ":" + c.getId()
            )));
        }
        rows.add(new InlineKeyboardRow(btn("💸 Расходные (компенсация)", "wiz:category:" + wizardKey + ":expense")));
        rows.add(new InlineKeyboardRow(btn("➕ Новая категория", "wiz:category:" + wizardKey + ":newcat")));
        rows.add(new InlineKeyboardRow(btn("Отмена", "wiz:confirm:" + wizardKey + ":cancel")));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardMarkup incomeExpenseCategoryMenu(UUID budgetId, String wizardKey) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Category c : categoryService.listExpenseByUsage(budgetId)) {
            rows.add(new InlineKeyboardRow(btn(
                    categoryService.displayName(c),
                    "wiz:category:" + wizardKey + ":" + c.getId()
            )));
        }
        rows.add(new InlineKeyboardRow(btn("⬅️ Назад", "wiz:category:" + wizardKey + ":back")));
        rows.add(new InlineKeyboardRow(btn("Отмена", "wiz:confirm:" + wizardKey + ":cancel")));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardMarkup incomeCategoryBackMenu(String wizardKey) {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("⬅️ Назад", "wiz:category:" + wizardKey + ":back")),
                        new InlineKeyboardRow(btn("Отмена", "wiz:confirm:" + wizardKey + ":cancel"))
                ))
                .build();
    }

    private InlineKeyboardMarkup expenseCategoryMenuByUsage(UUID budgetId, String wizardKey) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Category c : categoryService.listExpenseByUsage(budgetId)) {
            rows.add(new InlineKeyboardRow(btn(
                    categoryService.displayName(c),
                    "wiz:category:" + wizardKey + ":" + c.getId()
            )));
        }
        rows.add(new InlineKeyboardRow(btn("Прочее", "wiz:category:" + wizardKey + ":misc")));
        rows.add(new InlineKeyboardRow(btn("Отмена", "wiz:confirm:" + wizardKey + ":cancel")));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardMarkup expenseManualShopMenu(String wizardKey) {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("Пропустить", "wiz:counterparty:" + wizardKey + ":none")),
                        new InlineKeyboardRow(btn("Отмена", "wiz:confirm:" + wizardKey + ":cancel"))
                ))
                .build();
    }

    private void saveExpenseManual(UUID budgetId, UUID userId, Map<String, Object> payload, String counterparty) {
        BigDecimal amount = new BigDecimal(payload.get("amount").toString());
        String currency = currencyFromPayload(payload);
        String accountName = "Cash:" + payload.getOrDefault("telegramUserId", "").toString();
        String categoryId = payload.get("categoryId").toString();
        if ("misc".equals(categoryId)) {
            transactionService.createExpense(
                    budgetId,
                    userId,
                    Instant.now(),
                    amount,
                    currency,
                    accountName,
                    "Прочее",
                    counterparty,
                    null
            );
            return;
        }
        transactionService.createExpenseByCategoryId(
                budgetId,
                userId,
                Instant.now(),
                amount,
                currency,
                accountName,
                UUID.fromString(categoryId),
                counterparty,
                null
        );
    }

    private String currencyFromPayload(Map<String, Object> payload) {
        Object raw = payload.get("currency");
        if (raw == null || raw.toString().isBlank()) {
            return "BYN";
        }
        return raw.toString().trim().toUpperCase(Locale.ROOT);
    }

    private String extractReceiptCurrency(String text) {
        if (text == null || text.isBlank()) {
            return "BYN";
        }
        String u = text.toUpperCase(Locale.ROOT);
        if (u.contains(" EUR") || u.contains("€") || u.contains("EURO")) {
            return "EUR";
        }
        if (u.contains(" USD") || u.contains("$")) {
            return "USD";
        }
        return "BYN";
    }

    private BigDecimal extractReceiptTotal(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = TOTAL_PATTERN.matcher(text);
        if (!m.find()) {
            return null;
        }
        String raw = m.group(1);
        if (raw == null) {
            return null;
        }
        String normalized = raw.replace(" ", "").replace(",", ".");
        try {
            return new BigDecimal(normalized);
        } catch (Exception e) {
            return null;
        }
    }

    private InlineKeyboardMarkup amountMenu(String key) {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("10", "wiz:amount:" + key + ":10"), btn("20", "wiz:amount:" + key + ":20"), btn("50", "wiz:amount:" + key + ":50")),
                        new InlineKeyboardRow(btn("100", "wiz:amount:" + key + ":100"), btn("200", "wiz:amount:" + key + ":200"), btn("500", "wiz:amount:" + key + ":500")),
                        new InlineKeyboardRow(btn("1000", "wiz:amount:" + key + ":1000"), btn("Отмена", "wiz:confirm:" + key + ":cancel"))
                ))
                .build();
    }

    private InlineKeyboardMarkup currencyMenu(String key) {
        return expenseCurrencyMenu(key);
    }

    private InlineKeyboardMarkup expenseCurrencyMenu(String key) {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("BYN", "wiz:currency:" + key + ":BYN"), btn("EUR", "wiz:currency:" + key + ":EUR"), btn("USD", "wiz:currency:" + key + ":USD")),
                        new InlineKeyboardRow(btn("Отмена", "wiz:confirm:" + key + ":cancel"))
                ))
                .build();
    }

    private InlineKeyboardMarkup accountMenu(String prefix, UUID budgetId, String skipName) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (com.moneyfirewall.domain.Account a : accountService.list(budgetId)) {
            if (skipName != null && skipName.equals(a.getName())) {
                continue;
            }
            rows.add(new InlineKeyboardRow(btn(a.getName(), prefix + ":" + a.getId())));
        }
        rows.add(new InlineKeyboardRow(btn("Отмена", "wiz:confirm:transfer:cancel")));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardMarkup categoryMenu(UUID budgetId, CategoryKind kind) {
        if (kind == CategoryKind.EXPENSE) {
            return expenseCategoryMenuByUsage(budgetId, kind.name().toLowerCase(Locale.ROOT));
        }
        return incomeCategoryMenuByUsage(budgetId, kind.name().toLowerCase(Locale.ROOT));
    }

    private InlineKeyboardMarkup counterpartyMenu() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("Без контрагента", "wiz:counterparty:any:none")),
                        new InlineKeyboardRow(btn("Зарплата", "wiz:counterparty:any:Зарплата"), btn("Магазин", "wiz:counterparty:any:Магазин"))
                ))
                .build();
    }

    private InlineKeyboardMarkup confirmMenu(String key) {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("Подтвердить", "wiz:confirm:" + key + ":ok"), btn("Отмена", "wiz:confirm:" + key + ":cancel"))
                ))
                .build();
    }

    private InlineKeyboardMarkup cashDateMenu(String wizardKey) {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("Сегодня", "wiz:date:" + wizardKey + ":today"), btn("Вчера", "wiz:date:" + wizardKey + ":yesterday")),
                        new InlineKeyboardRow(btn("Другая дата", "wiz:date:" + wizardKey + ":custom")),
                        new InlineKeyboardRow(btn("Отмена", "wiz:confirm:" + wizardKey + ":cancel"))
                ))
                .build();
    }

    private InlineKeyboardMarkup cashDateBackMenu(String wizardKey) {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("⬅️ Назад", "wiz:date:" + wizardKey + ":back")),
                        new InlineKeyboardRow(btn("Отмена", "wiz:confirm:" + wizardKey + ":cancel"))
                ))
                .build();
    }

    private boolean isCashKey(String key) {
        return "cash_income".equals(key) || "cash_expense".equals(key) || "cash_withdraw".equals(key);
    }

    private boolean supportsDateStep(String key) {
        return isCashKey(key) || "income".equals(key);
    }

    private boolean isIncomeCompensation(UUID budgetId, Map<String, Object> payload) {
        Object categoryIdRaw = payload.get("categoryId");
        if (categoryIdRaw == null || "misc".equals(categoryIdRaw.toString())) {
            return false;
        }
        try {
            UUID categoryId = UUID.fromString(categoryIdRaw.toString());
            return categoryService.findById(budgetId, categoryId)
                    .map(c -> c.getKind() == CategoryKind.EXPENSE)
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    private void sendDatedConfirm(long chatId, UUID userId, String key, Map<String, Object> payload) {
        payload.put("step", "confirm");
        conversationService.set(userId, key, payload);
        String dateLabel = formatCashDate(payload);
        String label = switch (key) {
            case "cash_income" -> "Подтвердить доход наличными на " + dateLabel + "?";
            case "cash_expense" -> "Подтвердить расход наличными на " + dateLabel + "?";
            case "cash_withdraw" -> "Снять на наличные (CASH) на " + dateLabel + "?";
            case "income" -> "Подтвердить компенсацию расхода на " + dateLabel + "?";
            default -> "Подтвердить операцию на " + dateLabel + "?";
        };
        sender.sendText(chatId, label, confirmMenu(key));
    }

    private Instant occurredAtFromPayload(Map<String, Object> payload) {
        Object raw = payload.get("occurredAt");
        if (raw == null) {
            return Instant.now();
        }
        return Instant.parse(raw.toString());
    }

    private String formatCashDate(Map<String, Object> payload) {
        return occurredAtFromPayload(payload).atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    private LocalDate parseCashDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        try {
            return LocalDate.parse(s);
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(s, CASH_DATE_DMY);
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(s, CASH_DATE_DMY_PAD);
        } catch (Exception ignored) {
        }
        return null;
    }

    private String accountNameById(UUID budgetId, String accountId) {
        try {
            UUID id = UUID.fromString(accountId);
            for (com.moneyfirewall.domain.Account a : accountService.list(budgetId)) {
                if (a.getId().equals(id)) {
                    return a.getName();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String categoryNameById(UUID budgetId, String categoryId) {
        if ("misc".equals(categoryId)) {
            return "Прочее";
        }
        try {
            UUID id = UUID.fromString(categoryId);
            for (com.moneyfirewall.domain.Category c : categoryService.list(budgetId, CategoryKind.INCOME)) {
                if (c.getId().equals(id)) {
                    return c.getName();
                }
            }
            for (com.moneyfirewall.domain.Category c : categoryService.list(budgetId, CategoryKind.EXPENSE)) {
                if (c.getId().equals(id)) {
                    return c.getName();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String categoryNamePathById(UUID budgetId, String categoryId) {
        if ("misc".equals(categoryId)) {
            return "Прочее";
        }
        try {
            UUID id = UUID.fromString(categoryId);
            for (com.moneyfirewall.domain.Category c : categoryService.list(budgetId, CategoryKind.INCOME)) {
                if (c.getId().equals(id)) {
                    return c.getParentCategory() == null ? c.getName() : c.getParentCategory().getName() + " / " + c.getName();
                }
            }
            for (com.moneyfirewall.domain.Category c : categoryService.list(budgetId, CategoryKind.EXPENSE)) {
                if (c.getId().equals(id)) {
                    return c.getParentCategory() == null ? c.getName() : c.getParentCategory().getName() + " / " + c.getName();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private void onExpenseScanCategorySelected(long chatId, UUID userId, String categoryId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            conversationService.clear(userId);
            sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
            return;
        }
        State st = conversationService.get(userId).orElse(null);
        if (st == null || !"expense_scan_save".equals(st.key())) {
            sender.sendText(chatId, "Нет данных чека", menuForUser(userId));
            return;
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(st.payload().get("amount").toString());
        } catch (Exception e) {
            conversationService.clear(userId);
            sender.sendText(chatId, "Ошибка", menuForUser(userId));
            return;
        }
        String categoryName = categoryNameById(budgetId, categoryId);
        if (categoryName == null) {
            sender.sendText(chatId, "Категория не найдена", menuForUser(userId));
            conversationService.clear(userId);
            return;
        }
        String accountName = "Cash:" + st.payload().getOrDefault("telegramUserId", "").toString();
        if ("Cash:".equals(accountName)) {
            sender.sendText(chatId, "Ошибка", menuForUser(userId));
            conversationService.clear(userId);
            return;
        }
        if ("misc".equals(categoryId)) {
            transactionService.createExpense(budgetId, userId, Instant.now(), amount, currencyFromPayload(st.payload()), accountName, "Прочее", null, null);
        } else {
            transactionService.createExpenseByCategoryId(budgetId, userId, Instant.now(), amount, currencyFromPayload(st.payload()), accountName, UUID.fromString(categoryId), null, null);
        }
        conversationService.clear(userId);
        sender.sendText(chatId, "✅ Трата добавлена", mainMenu());
    }

    private void onExpenseScanCurrencySelected(long chatId, UUID userId, String currency) {
        State st = conversationService.get(userId).orElse(null);
        if (st == null || !"expense_scan_save".equals(st.key())) {
            sender.sendText(chatId, "Нет данных чека", menuForUser(userId));
            return;
        }
        Map<String, Object> p = new HashMap<>(st.payload());
        p.put("currency", currency);
        conversationService.set(userId, "expense_scan_save", p);
        sender.sendText(chatId, "Валюта: " + currency, receiptSaveMenu(p));
    }

}

