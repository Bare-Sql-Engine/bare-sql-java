package com.baresql.compiler;

public enum Dialect {
    SQLITE("\"", "\"", true, false, false),
    POSTGRES("\"", "\"", true, false, false),
    MYSQL("`", "`", false, true, false),
    SQL_SERVER("[", "]", false, false, true);

    private final String identifierQuoteOpen;
    private final String identifierQuoteClose;
    private final boolean supportsReturning;
    private final boolean usesLimitOffset;
    private final boolean usesTopFetch;

    Dialect(String identifierQuoteOpen, String identifierQuoteClose,
            boolean supportsReturning, boolean usesLimitOffset, boolean usesTopFetch) {
        this.identifierQuoteOpen = identifierQuoteOpen;
        this.identifierQuoteClose = identifierQuoteClose;
        this.supportsReturning = supportsReturning;
        this.usesLimitOffset = usesLimitOffset;
        this.usesTopFetch = usesTopFetch;
    }

    public String quoteIdentifier(String identifier) {
        return identifierQuoteOpen + identifier + identifierQuoteClose;
    }

    public boolean supportsReturning() { return supportsReturning; }
    public boolean usesLimitOffset() { return usesLimitOffset; }
    public boolean usesTopFetch() { return usesTopFetch; }
}
