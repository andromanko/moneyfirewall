package com.moneyfirewall.telegram;

import com.moneyfirewall.domain.AccountType;
import com.moneyfirewall.domain.Budget;
import com.moneyfirewall.domain.CategoryKind;
import com.moneyfirewall.domain.ImportFileType;
import com.moneyfirewall.service.BudgetService;
import com.moneyfirewall.service.ConversationService;
import com.moneyfirewall.service.ConversationService.State;
import com.moneyfirewall.service.AccountService;
import com.moneyfirewall.service.AssetEventService;
import com.moneyfirewall.service.AssetReportService;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
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
    private final TransferLinkingService transferLinkingService;
    private final AssetEventService assetEventService;
    private final AssetReportService assetReportService;
    private final ReportService reportService;
    private final ExcelReportExporter excelReportExporter;
    private final GoogleSheetsExporter googleSheetsExporter;
    private final ReceiptRecognitionService receiptRecognitionService;

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
            TransferLinkingService transferLinkingService,
            AssetEventService assetEventService,
            AssetReportService assetReportService,
            ReportService reportService,
            ExcelReportExporter excelReportExporter,
            GoogleSheetsExporter googleSheetsExporter,
            ReceiptRecognitionService receiptRecognitionService
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
        this.transferLinkingService = transferLinkingService;
        this.assetEventService = assetEventService;
        this.assetReportService = assetReportService;
        this.reportService = reportService;
        this.excelReportExporter = excelReportExporter;
        this.googleSheetsExporter = googleSheetsExporter;
        this.receiptRecognitionService = receiptRecognitionService;
    }

    @Override
    public void consume(java.util.List<Update> updates) {
        for (Update update : updates) {
            consumeOne(update);
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
            case "/alias_add" -> onAliasAdd(chatId, user.getId(), arg);
            case "/alias_list" -> onAliasList(chatId, user.getId());
            case "/alias_delete" -> onAliasDelete(chatId, user.getId(), arg);
            case "/transfer_autolink" -> onTransferAutoLink(chatId, user.getId(), arg);
            case "/transfer_link" -> onTransferLink(chatId, user.getId(), arg);
            case "/transfer_unlink" -> onTransferUnlink(chatId, user.getId(), arg);
            case "/asset_event" -> onAssetEvent(chatId, user.getId(), arg);
            case "/asset_positions" -> onAssetPositions(chatId, user.getId(), arg);
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
        conversationService.set(userId, "income", new HashMap<>(Map.of("step", "amount")));
        sender.sendText(chatId, "Выбери сумму дохода", amountMenu("income"));
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
        conversationService.set(userId, "expense_manual", new HashMap<>(Map.of("step", "text", "telegramUserId", telegramUserId)));
        sender.sendText(chatId, "Наберите сумму и наименование категории или магазина через пробел", budgetCreateMenu());
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
        sender.sendText(chatId, "Выберите категорию или укажите магазин", receiptSaveMenu());
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
            case "mtbank" -> "Пришли PDF-выписку МТБанка.";
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
            ImportService.ImportResult res = importService.importFile(budgetId, userId, bank, type, fileId, bytes);
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
            sender.sendText(chatId, "Пример: /alias_add SANTA => магазин Санта");
            return;
        }
        String pattern = parts[0].trim();
        String name = parts[1].trim();
        merchantAliasService.add(budgetId, pattern, name, 100, false);
        sender.sendText(chatId, "Ок");
    }

    private void onAliasList(long chatId, UUID userId) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Алиасы\n");
        merchantAliasService.list(budgetId).forEach(a -> sb.append(a.getId()).append(" ").append(a.getPattern()).append(" => ").append(a.getNormalizedName()).append("\n"));
        sender.sendText(chatId, sb.toString().trim());
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
            sender.sendText(chatId, "Пример: /alias_delete <uuid>");
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

    private void onReportMonth(long chatId, UUID userId, String arg) {
        UUID budgetId = budgetService.getActiveBudgetId(userId);
        if (budgetId == null) {
            sender.sendText(chatId, "Сначала выбери бюджет: /budget_use <uuid>");
            return;
        }
        java.time.YearMonth ym;
        try {
            ym = (arg == null || arg.isBlank()) ? java.time.YearMonth.now() : java.time.YearMonth.parse(arg.trim());
        } catch (Exception e) {
            sender.sendText(chatId, "Формат: /report_month YYYY-MM");
            return;
        }
        Instant from = ym.atDay(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        Instant to = ym.plusMonths(1).atDay(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);

        ReportTables tables = reportService.build(budgetId, from, to);

        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        for (List<Object> row : tables.summary()) {
            if (row == null || row.size() < 2) {
                continue;
            }
            Object metricObj = row.getFirst();
            Object valueObj = row.get(1);
            if (!(metricObj instanceof String metric)) {
                continue;
            }
            if (!(valueObj instanceof BigDecimal val)) {
                continue;
            }
            if ("income".equals(metric)) {
                income = val;
            } else if ("expense".equals(metric)) {
                expense = val;
            }
        }
        sender.sendText(chatId, "Предпросмотр " + ym + "\n" +
                "Доход: " + income.toPlainString() + "\n" +
                "Расход: " + expense.toPlainString());

        byte[] xlsx = excelReportExporter.export(tables);
        String fileName = "moneyfirewall-" + ym + ".xlsx";
        sender.sendDocument(chatId, xlsx, fileName, "Отчёт " + ym);

        try {
            String title = "MoneyFirewall " + ym + " " + budgetId;
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

        if ("expense_manual".equals(st.key())) {
            UUID budgetId = budgetService.getActiveBudgetId(userId);
            if (budgetId == null) {
                conversationService.clear(userId);
                sender.sendText(chatId, "Сначала выбери бюджет", menuForUser(userId));
                return true;
            }
            String raw = text == null ? "" : text.trim();
            String[] p = raw.split("\\s+", 2);
            if (p.length < 2) {
                sender.sendText(chatId, "Наберите сумму и наименование категории или магазина через пробел", budgetCreateMenu());
                return true;
            }
            BigDecimal amount;
            try {
                amount = new BigDecimal(p[0].replace(",", "."));
            } catch (Exception e) {
                sender.sendText(chatId, "Наберите сумму и наименование категории или магазина через пробел", budgetCreateMenu());
                return true;
            }
            String categoryOrShop = p[1].trim();
            if (categoryOrShop.isBlank()) {
                sender.sendText(chatId, "Наберите сумму и наименование категории или магазина через пробел", budgetCreateMenu());
                return true;
            }

            String accountName = "Cash:" + st.payload().getOrDefault("telegramUserId", "").toString();
            if ("Cash:".equals(accountName)) {
                sender.sendText(chatId, "Ошибка", menuForUser(userId));
                conversationService.clear(userId);
                return true;
            }

            String categoryName = findExpenseCategoryName(budgetId, categoryOrShop);
            String counterparty = categoryName == null ? categoryOrShop : null;
            if (categoryName == null) {
                categoryName = "Прочее";
            }
            transactionService.createExpense(budgetId, userId, Instant.now(), amount, "BYN", accountName, categoryName, counterparty, null);
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
            transactionService.createExpense(budgetId, userId, Instant.now(), amount, "BYN", accountName, "Прочее", shop, null);
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
        if (data.startsWith("wiz:")) {
            handleWizardCallback(chatId, user.getId(), data);
            return;
        }

        if (data.startsWith("mf:budget_use:")) {
            String budgetId = data.substring("mf:budget_use:".length());
            onBudgetUse(chatId, user.getId(), budgetId);
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
            case "mf:report" -> onReportMonth(chatId, user.getId(), "");
            case "mf:accounts" -> onAccounts(chatId, user.getId());
            case "mf:accounts_list" -> onAccountsList(chatId, user.getId());
            case "mf:accounts_add" -> onAccountsAddStart(chatId, user.getId());
            case "mf:accounts_rename" -> onAccountsRenameSelect(chatId, user.getId());
            case "mf:accounts_delete" -> onAccountsDeleteSelect(chatId, user.getId());
            case "mf:budget_members" -> onBudgetMembers(chatId, user.getId());
            case "mf:cancel" -> onCancel(chatId, user.getId());
            case "mf:help" -> sender.sendText(chatId, "Помощь\n\n" +
                    "Доход — добавить поступление\n" +
                    "Трата — добавить расход\n" +
                    "Перевод — перевод между счетами/наличными\n" +
                    "Импорт — меню выбора банка (PDF/JSON), затем файл\n" +
                    "Отчёт (месяц) — выгрузить отчёт за месяц\n" +
                    "Счета / Участники — управление в рамках активного бюджета\n" +
                    "Сброс — вернуться в главное меню", menuForUser(user.getId()));
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
            payload.put("step", "currency");
            conversationService.set(userId, key, payload);
            sender.sendText(chatId, "Выбери валюту", currencyMenu(key));
            return;
        }
        if ("currency".equals(part) && p.length >= 4) {
            payload.put("currency", p[3]);
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
            payload.put("step", "category");
            conversationService.set(userId, key, payload);
            CategoryKind kind = "income".equals(key) ? CategoryKind.INCOME : CategoryKind.EXPENSE;
            sender.sendText(chatId, "Выбери категорию", categoryMenu(budgetId, kind));
            return;
        }
        if ("category".equals(part) && p.length >= 4) {
            String categoryName = categoryNameById(budgetId, p[3]);
            if (categoryName == null) {
                sender.sendText(chatId, "Категория не найдена", mainMenu());
                conversationService.clear(userId);
                return;
            }
            payload.put("category", categoryName);
            payload.put("step", "counterparty");
            conversationService.set(userId, key, payload);
            sender.sendText(chatId, "Выбери контрагента", counterpartyMenu());
            return;
        }
        if ("counterparty".equals(part) && p.length >= 4) {
            String cp = "none".equals(p[3]) ? null : p[3];
            payload.put("counterparty", cp);
            payload.put("step", "confirm");
            conversationService.set(userId, key, payload);
            sender.sendText(chatId, "Подтвердить операцию?", confirmMenu(key));
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
                Instant now = Instant.now();
                if ("income".equals(key)) {
                    transactionService.createIncome(
                            budgetId,
                            userId,
                            now,
                            amount,
                            currency,
                            payload.get("account").toString(),
                            payload.get("category").toString(),
                            (String) payload.get("counterparty"),
                            null
                    );
                } else if ("expense".equals(key)) {
                    transactionService.createExpense(
                            budgetId,
                            userId,
                            now,
                            amount,
                            currency,
                            payload.get("account").toString(),
                            payload.get("category").toString(),
                            (String) payload.get("counterparty"),
                            null
                    );
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

    private InlineKeyboardMarkup receiptSaveMenu() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("📂 Категория", "mf:expense_scan_pick_category")),
                        new InlineKeyboardRow(btn("🏪 Магазин", "mf:expense_scan_pick_shop")),
                        new InlineKeyboardRow(btn("🏠 Меню", "mf:cancel"))
                ))
                .build();
    }

    private InlineKeyboardMarkup receiptCategoryMenu(UUID budgetId) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (com.moneyfirewall.domain.Category c : categoryService.list(budgetId, CategoryKind.EXPENSE)) {
            rows.add(new InlineKeyboardRow(btn(c.getName(), "mf:expense_scan_cat:" + c.getId())));
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

    private InlineKeyboardMarkup importBankMenu() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("PDF — МТБанк", "mf:import:set:mtbank")),
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
                        new InlineKeyboardRow(btn("🔁 Перевод", "mf:transfer"), btn("📥 Импорт", "mf:import")),
                        new InlineKeyboardRow(btn("📊 Отчёт (месяц)", "mf:report")),
                        new InlineKeyboardRow(btn("💳 Счета", "mf:accounts"), btn("👥 Участники", "mf:budget_members")),
                        new InlineKeyboardRow(btn("❓ Помощь", "mf:help"), btn("🏠 Меню", "mf:cancel"))
                ))
                .build();
    }

    private InlineKeyboardButton btn(String text, String data) {
        return InlineKeyboardButton.builder().text(text).callbackData(data).build();
    }

    private String findExpenseCategoryName(UUID budgetId, String categoryOrShop) {
        String q = categoryOrShop == null ? "" : categoryOrShop.trim();
        if (q.isBlank()) {
            return null;
        }
        for (com.moneyfirewall.domain.Category c : categoryService.list(budgetId, CategoryKind.EXPENSE)) {
            if (c.getName() != null && c.getName().equalsIgnoreCase(q)) {
                return c.getName();
            }
        }
        return null;
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
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(btn("BYN", "wiz:currency:" + key + ":BYN"), btn("USD", "wiz:currency:" + key + ":USD"), btn("EUR", "wiz:currency:" + key + ":EUR")),
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
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (com.moneyfirewall.domain.Category c : categoryService.list(budgetId, kind)) {
            rows.add(new InlineKeyboardRow(btn(c.getName(), "wiz:category:" + kind.name().toLowerCase() + ":" + c.getId())));
        }
        rows.add(new InlineKeyboardRow(btn("Прочее", "wiz:category:" + kind.name().toLowerCase() + ":misc")));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
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
        transactionService.createExpense(budgetId, userId, Instant.now(), amount, "BYN", accountName, categoryName, null, null);
        conversationService.clear(userId);
        sender.sendText(chatId, "✅ Трата добавлена", mainMenu());
    }

}

