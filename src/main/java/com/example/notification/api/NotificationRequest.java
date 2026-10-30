package com.example.notification.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.example.notification.persistence.NotificationRepository;

/** What a caller may ask for; a request that breaks these rules never becomes an attempt. */
public record NotificationRequest(
    @NotBlank(message = "Type is mandatory.")
        @Size(
            max = NotificationRepository.TYPE_LENGTH,
            message = "Type exceeds " + NotificationRepository.TYPE_LENGTH + " characters.")
        String type,
    @NotBlank(message = "Recipient is mandatory.")
        @Size(
            max = NotificationRepository.TARGET_LENGTH,
            message = "Recipient exceeds " + NotificationRepository.TARGET_LENGTH + " characters.")
        String recipient,
    @NotBlank(message = "Payload is mandatory.") String payload) {}