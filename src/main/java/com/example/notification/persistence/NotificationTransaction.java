package com.example.notification.persistence;

import java.sql.Timestamp;

/** One row of {@code ntf_tx}. The column names are kept verbatim until the schema is revised. */
public record NotificationTransaction(
    long id,
    String tp,
    String tgt,
    String pld,
    String st,
    int fc,
    int dc,
    String bl,
    String lm,
    Timestamp crt,
    Timestamp upd) {}
