package com.example.notification.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.example.notification.persistence.NotificationRepository;

/** What an update may attach; the attempt's own record is not part of it. */
public record NotificationUpdateRequest(
    @NotBlank(message = "Note is mandatory.")
        @Size(
            max = NotificationRepository.NOTE_LENGTH,
            message = "Note exceeds " + NotificationRepository.NOTE_LENGTH + " characters.")
        String note) {}
