package com.example.notification.dispatch;

import java.util.List;

import org.springframework.stereotype.Component;

import com.example.notification.persistence.BlockedRecipient;
import com.example.notification.persistence.BlockedRecipientRepository;

/**
 * The recipients the service refuses to dispatch to. Membership is the whole rule: an entry blocks
 * that exact address and nothing else, so what the list says is what happens.
 */
@Component
public class Blacklist {

  private final BlockedRecipientRepository blocked;

  public Blacklist(BlockedRecipientRepository blocked) {
    this.blocked = blocked;
  }

  public boolean blocks(String target) {
    return blocked.existsById(target);
  }

  public List<String> entries() {
    return blocked.findAllByOrderByRecipientAsc().stream().map(BlockedRecipient::recipient).toList();
  }

  public void add(String recipient) {
    String entry = recipient.trim();
    if (!blocked.existsById(entry)) {
      blocked.insert(entry);
    }
  }

  public void remove(String recipient) {
    blocked.deleteById(recipient.trim());
  }
}
