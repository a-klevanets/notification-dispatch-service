package com.example.notification.persistence;

import java.util.List;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

/**
 * The list of recipients the service refuses to dispatch to. The insert is spelled out because the
 * key is the address itself: {@code save} would read a set key as an existing row and update nothing.
 */
public interface BlockedRecipientRepository extends Repository<BlockedRecipient, String> {

  /** The column width from {@code schema.sql}; a longer recipient fails the write. */
  int RECIPIENT_LENGTH = 255;

  boolean existsById(String recipient);

  List<BlockedRecipient> findAllByOrderByRecipientAsc();

  @Modifying
  @Query("INSERT INTO blocked_recipient (recipient) VALUES (:recipient)")
  void insert(String recipient);

  void deleteById(String recipient);
}
