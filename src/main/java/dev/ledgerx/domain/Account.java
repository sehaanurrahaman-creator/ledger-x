package dev.ledgerx.domain;

import java.util.Objects;

/**
 * An account as the ledger knows it: an identity and a kind, both fixed at opening.
 *
 * <p>Accounts are immutable. There is no rename, no re-kind, no close-that-forgets: an
 * account that stops being used simply stops appearing in new entries, and its history
 * stays where the append-only log put it. ADR 0002 §8 explains why that immutability is
 * what makes replaying accounts-before-transactions equivalent to replaying the event log in
 * order — there is no account state for a later event to have changed.
 *
 * @param id the account's identity
 * @param kind what the account is, for reporting
 */
public record Account(AccountId id, AccountKind kind) {

  public Account {
    Objects.requireNonNull(id, "account id");
    Objects.requireNonNull(kind, "account kind");
  }
}
