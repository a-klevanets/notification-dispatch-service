package com.example.notification.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One row of {@code blocked_recipient}: a recipient the service refuses to dispatch to. The key is
 * the address itself, so the list cannot hold the same recipient twice.
 */
@Table("BLOCKED_RECIPIENT")
public record BlockedRecipient(@Id String recipient) {}
