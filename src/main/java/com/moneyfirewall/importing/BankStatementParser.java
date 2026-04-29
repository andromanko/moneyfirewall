package com.moneyfirewall.importing;

import com.moneyfirewall.domain.ImportFileType;
import java.util.List;

public interface BankStatementParser {
    boolean supports(String bankCode, ImportFileType fileType);

    List<ParsedOperation> parse(byte[] bytes) throws Exception;
}

