package com.example.notification.api;

public record NotificationRequest(String type, String recipient, String payload) {}
