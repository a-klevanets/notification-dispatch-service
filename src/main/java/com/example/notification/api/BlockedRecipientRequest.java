package com.example.notification.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.example.notification.persistence.BlockedRecipientRepository;

/** A recipient to refuse; the list holds addresses, so an entry names one exactly. */
public record BlockedRecipientRequest(
    @NotBlank(message = "Recipient is mandatory.")
        @Size(
            max = BlockedRecipientRepository.RECIPIENT_LENGTH,
            message = "Recipient exceeds " + BlockedRecipientRepository.RECIPIENT_LENGTH + " characters.")
        String recipient) {}
