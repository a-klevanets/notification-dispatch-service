package com.example.notification;

/**
 * One delivery channel. {@link #type()} is the channel it answers to; adding a channel is a new
 * component implementing this interface, and nothing else changes.
 */
public interface NotificationSender {

  NotificationType type();

  /** Throws on delivery failure; the caller records it. */
  void send(String target, String payload) throws Exception;
}
