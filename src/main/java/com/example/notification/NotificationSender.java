package com.example.notification;

/**
 * One delivery channel. {@link #type()} is the wire value it answers to; adding a channel is a new
 * component implementing this interface, and nothing else changes.
 */
public interface NotificationSender {

  /** The normalized type this sender delivers, matching the request's {@code type}. */
  String type();

  /** Hands the notification to the channel; a failure is thrown and recorded by the caller. */
  void send(String target, String payload) throws Exception;
}
