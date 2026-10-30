package com.example.notification.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.notification.dispatch.Blacklist;

/** The list the dispatch flow consults: adding an entry refuses that recipient from then on. */
@RestController
@RequestMapping("/api/v1/blocklist")
public class BlocklistController {

  private final Blacklist blacklist;

  public BlocklistController(Blacklist blacklist) {
    this.blacklist = blacklist;
  }

  @GetMapping
  public List<String> entries() {
    return blacklist.entries();
  }

  @PostMapping
  public ResponseEntity<Void> add(@Valid @RequestBody BlockedRecipientRequest request) {
    blacklist.add(request.recipient());
    return ResponseEntity.status(HttpStatus.CREATED).build();
  }

  @DeleteMapping
  public ResponseEntity<Void> remove(@RequestParam String recipient) {
    blacklist.remove(recipient);
    return ResponseEntity.noContent().build();
  }
}
