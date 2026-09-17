package dev.ledgerx.idempotency;

import dev.ledgerx.domain.AccountId;
import dev.ledgerx.domain.AccountKind;
import dev.ledgerx.domain.Entry;
import dev.ledgerx.domain.IdempotencyKey;
import dev.ledgerx.domain.JournalEvent;
import dev.ledgerx.domain.MerchantId;
import dev.ledgerx.domain.Money;
import dev.ledgerx.domain.RejectedTransactionException;
import dev.ledgerx.domain.RejectionReason;
import dev.ledgerx.domain.Transaction;
import dev.ledgerx.journal.DurableLedger;
import dev.ledgerx.journal.EventCodec;
import dev.ledgerx.wal.Corruption;
import dev.ledgerx.wal.FsyncPolicy;
import dev.ledgerx.wal.RecordType;
import dev.ledgerx.wal.UnrecoverableLogException;
import dev.ledgerx.wal.Wal;
import dev.ledgerx.wal.WalFormat;
import dev.ledgerx.wal.WalRecord;
import dev.ledgerx.wal.WalRecovery;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The idempotency contract: every claim ADR 0005 makes about a key, asserted on every build.
 *
 * <p>The ticket asked for four things specifically — the 409 that is never a 200-replay, the
 * single-record commit, the duplicate storm (one posting, ninety-nine replays), and the
 * expiry/GC/clock-skew corners — and this file holds each of them as a named check, plus the ones
 * a contract exists to pin: the record's exact bytes, the scoping, the recovery of the table from
 * the log and from a checkpoint, the refusal of a log that binds one key twice, and the
 * timing-blindness of the state hash, which is the property that keeps the boundary harness's
 * five-way hash comparison honest now that records carry wall-clock instants.
 *
 * <p>The clock is a hand-rolled mutable one, not {@code Clock.fixed}: expiry, skew and retention
 * are behaviours of time <em>passing</em>, and a test that cannot move its clock can only assert
 * the instant it froze. Everything here is deterministic — same bytes, same verdicts — and a
 * failing check keeps its directory and prints the path, in the house style.
 *
 * <p>Run by {@code ./build.sh}; exits non-zero if any check fails.
 */
public final class IdempotencyContract {

  /** Every ack forced: a crash cannot be blamed for anything this suite finds. */
  private static final FsyncPolicy POLICY = FsyncPolicy.PER_COMMIT;

  /** Retention long enough that nothing expires unless a check moves the clock past it. */
  private static final Duration RETENTION = Duration.ofHours(24);

  private static final List<String> failures = new ArrayList<>();
  private static int checksRun;

  private IdempotencyContract() {}

  /** A check returns a short detail string for the log, or throws to fail. */
  private interface Check {
    String run(Path dir) throws Exception;
  }

  public static void main(String[] args) {
    System.out.println("ledger-x idempotency contract on " + Runtime.version());
    check("a first posting under a key is one record and one receipt",
        IdempotencyContract::firstPostingBindsOnce);
    check("a retry of the same key and body replays the stored response",
        IdempotencyContract::retryReplays);
    check("the same key with a different body is a conflict, at any age",
        IdempotencyContract::differentBodyIsConflict);
    check("the keyed record is exactly these bytes", IdempotencyContract::recordIsTheseBytes);
    check("keys are scoped per merchant", IdempotencyContract::scopeIsTheMerchant);
    check("an expired key is refused, never replayed and never re-executed",
        IdempotencyContract::expiryRefuses);
    check("a clock that steps backwards extends replay, never shortens it",
        IdempotencyContract::backwardsClockExtends);
    check("the duplicate storm: one posting, ninety-nine replays",
        IdempotencyContract::duplicateStorm);
    check("a recovery folds the table back: replays after a reopen are replays",
        IdempotencyContract::recoveryRestoresTheTable);
    check("a checkpoint carries the bindings, and a reopen serves them",
        IdempotencyContract::checkpointCarriesBindings);
    check("a log that binds one key twice refuses to open",
        IdempotencyContract::doubleBindingRefusesToOpen);
    check("retention zero expires immediately, and still never re-executes",
        IdempotencyContract::retentionZero);
    check("the state hash is blind to capture timing", IdempotencyContract::hashIgnoresTiming);
    check("the fingerprint covers the body as sent: reordered entries are a different body",
        IdempotencyContract::reorderedBodyConflicts);
    check("a rejected candidate under a fresh key leaves no binding and no trace",
        IdempotencyContract::rejectionLeavesNoBinding);

    System.out.println();
    if (failures.isEmpty()) {
      System.out.println("PASS " + checksRun + "/" + checksRun + " idempotency checks");
      return;
    }
    System.out.println("FAIL " + failures.size() + " of " + checksRun + " idempotency checks");
    for (String failure : failures) {
      System.out.println("  - " + failure);
    }
    System.exit(1);
  }

  private static void check(String name, Check body) {
    checksRun++;
    Path dir = null;
    try {
      dir = Files.createTempDirectory("ledger-x-idempotency");
      String detail = body.run(dir);
      System.out.println("  ok   " + name + " [" + detail + "]");
      System.out.flush();
      deleteTree(dir);
    } catch (Throwable failed) {
      failures.add(name + " — " + failed);
      System.out.println("  FAIL " + name + " — " + failed);
      System.out.flush();
      if (dir != null) {
        System.out.println("       evidence kept in " + dir);
      }
    }
  }

  // --- the contract, check by check -----------------------------------------------------

  private static String firstPostingBindsOnce(Path dir) throws Exception {
    MutableClock clock = new MutableClock(1_000L);
    try (DurableLedger ledger = open(dir, clock)) {
      openTwoAccounts(ledger);
      Transaction candidate =
          Transaction.transfer(
              AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
      long bytesBefore = walSize(dir);
      IdempotentReceipt receipt =
          ledger.postIdempotent(MerchantId.of("guild"), IdempotencyKey.of("payout-1"),
              candidate);
      require(!receipt.replayed(), "the first posting called itself a replay");
      require(receipt.originalLsn() == 3L, "the receipt names lsn " + receipt.originalLsn());
      require(receipt.capturedAtMillis() == 1_000L, "the receipt did not capture the clock");
      require(receipt.transaction().equals(candidate), "the receipt is not the posting");
      require(keyedRecords(dir) == 1, "the posting did not land as exactly one keyed record");
      require(walSize(dir) > bytesBefore, "nothing was appended");
      require(
          ledger.ledger().balanceOf(AccountId.of("acct-0")).equals(Money.ofMinor(-5_000L)),
          "the money did not move");
      ledger.ledger().audit();
      return "lsn 3, captured at 1000, one IDEMPOTENT_POSTING record";
    }
  }

  private static String retryReplays(Path dir) throws Exception {
    MutableClock clock = new MutableClock(1_000L);
    try (DurableLedger ledger = open(dir, clock)) {
      openTwoAccounts(ledger);
      MerchantId merchant = MerchantId.of("guild");
      IdempotencyKey key = IdempotencyKey.of("payout-1");
      Transaction candidate =
          Transaction.transfer(
              AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
      IdempotentReceipt first = ledger.postIdempotent(merchant, key, candidate);
      long bytes = walSize(dir);
      String hash = ledger.stateHash();
      clock.advance(3_600_000L);
      IdempotentReceipt replay = ledger.postIdempotent(merchant, key, candidate);
      require(replay.replayed(), "the retry posted instead of replaying");
      require(
          replay.transaction().equals(first.transaction()),
          "the replay returned a different transaction");
      require(
          replay.originalLsn() == first.originalLsn(),
          "the replay names lsn " + replay.originalLsn() + ", not the original's "
              + first.originalLsn());
      require(
          replay.capturedAtMillis() == first.capturedAtMillis(),
          "the replay's capture instant moved");
      require(walSize(dir) == bytes, "the replay appended bytes");
      require(keyedRecords(dir) == 1, "the replay added a keyed record");
      require(ledger.stateHash().equals(hash), "the replay changed the state");
      return "same transaction, same lsn " + replay.originalLsn() + ", zero bytes appended";
    }
  }

  private static String differentBodyIsConflict(Path dir) throws Exception {
    MutableClock clock = new MutableClock(1_000L);
    try (DurableLedger ledger = open(dir, clock)) {
      openTwoAccounts(ledger);
      MerchantId merchant = MerchantId.of("guild");
      IdempotencyKey key = IdempotencyKey.of("payout-1");
      ledger.postIdempotent(
          merchant, key,
          Transaction.transfer(
              AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L)));
      long bytes = walSize(dir);
      String hash = ledger.stateHash();
      Transaction differentAmount =
          Transaction.transfer(
              AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(50_000L));
      try {
        ledger.postIdempotent(merchant, key, differentAmount);
        throw new AssertionError("a different body under a bound key was accepted");
      } catch (IdempotencyConflictException conflict) {
        require(
            !conflict.boundTo().equals(conflict.presented()),
            "the conflict did not carry two different fingerprints");
      }
      require(walSize(dir) == bytes, "the conflict appended bytes");
      require(ledger.stateHash().equals(hash), "the conflict changed the state");
      // The rule is unconditional: a clock advanced past retention turns a would-be replay into
      // a refusal, and a different body past retention is still a conflict — the binding's
      // fingerprint is permanent, which is the hole Stripe's 24-hour prune reopens.
      clock.advance(RETENTION.toMillis() * 2);
      try {
        ledger.postIdempotent(merchant, key, differentAmount);
        throw new AssertionError("a different body under an expired key was accepted");
      } catch (IdempotencyConflictException conflict) {
        require(
            !conflict.boundTo().equals(conflict.presented()),
            "the aged conflict lost its fingerprints");
      }
      require(walSize(dir) == bytes, "the aged conflict appended bytes");
      return "refused at age 0 and at age 48h, nothing appended either time";
    }
  }

  private static String recordIsTheseBytes(Path dir) throws Exception {
    MutableClock clock = new MutableClock(1_726_617_600_000L);
    MerchantId merchant = MerchantId.of("guild");
    IdempotencyKey key = IdempotencyKey.of("payout-42");
    Transaction candidate =
        Transaction.transfer(
            AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
    long capturedAt = 1_726_617_600_000L;
    try (DurableLedger ledger = open(dir, clock)) {
      openTwoAccounts(ledger);
      ledger.postIdempotent(merchant, key, candidate);
    }
    byte[] file = Files.readAllBytes(dir.resolve(Wal.FILE_NAME));
    // Found by walking the scan, not by hard-coding an offset the format owns.
    WalRecovery.Scan scan = WalRecovery.scan(dir.resolve(Wal.FILE_NAME));
    WalRecord keyed = null;
    for (WalRecord record : scan.records()) {
      if (record.type() == RecordType.IDEMPOTENT_POSTING) {
        keyed = record;
      }
    }
    require(keyed != null, "no IDEMPOTENT_POSTING frame found in the log");
    long frameStart = scan.endOf(keyed.lsn().value()) - keyed.frameLength();
    byte[] frame =
        Arrays.copyOfRange(file, (int) frameStart, (int) scan.endOf(keyed.lsn().value()));
    require(
        Arrays.equals(frame, keyed.encode()),
        "the frame in the file is not the canonical encoding of the record");
    byte[] payload =
        Arrays.copyOfRange(frame, WalFormat.FRAME_HEADER_BYTES,
            frame.length - WalFormat.CRC_BYTES);
    int p = 0;
    require((payload[p] & 0xFF) == merchant.value().length(), "the merchant length is wrong");
    p++;
    require(
        new String(
            payload, p, merchant.value().length(),
            java.nio.charset.StandardCharsets.US_ASCII)
            .equals(merchant.value()),
        "the merchant bytes are wrong");
    p += merchant.value().length();
    require((payload[p] & 0xFF) == key.value().length(), "the key length is wrong");
    p++;
    require(
        new String(
            payload, p, key.value().length(),
            java.nio.charset.StandardCharsets.US_ASCII)
            .equals(key.value()),
        "the key bytes are wrong");
    p += key.value().length();
    require(WalFormat.longAt(payload, p) == capturedAt, "the capture instant is not the clock's");
    p += 8;
    require(
        Arrays.equals(
            Arrays.copyOfRange(payload, p, p + 32),
            EventCodec.fingerprintOf(candidate).bytesUnsafe()),
        "the committed fingerprint is not the candidate's");
    p += 32;
    require(
        WalFormat.shortAt(payload, p) == 2, "the entry count is not the transaction's");
    p += 2;
    Entry first = candidate.entries().get(0);
    require((payload[p] & 0xFF) == first.account().value().length(), "entry 0's id length");
    require(
        WalFormat.longAt(payload, p + 1 + first.account().value().length() + 1)
            == first.amount().minorUnits(),
        "entry 0's amount");
    return frame.length + "-byte frame, " + payload.length + "-byte payload, fields in the"
        + " declared order";
  }

  private static String scopeIsTheMerchant(Path dir) throws Exception {
    MutableClock clock = new MutableClock(1_000L);
    try (DurableLedger ledger = open(dir, clock)) {
      openTwoAccounts(ledger);
      IdempotencyKey key = IdempotencyKey.of("payout-1");
      Transaction forGuild =
          Transaction.transfer(
              AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
      Transaction forAtelier =
          Transaction.transfer(
              AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(7_000L));
      IdempotentReceipt guild =
          ledger.postIdempotent(MerchantId.of("guild"), key, forGuild);
      IdempotentReceipt atelier =
          ledger.postIdempotent(MerchantId.of("atelier"), key, forAtelier);
      require(!guild.replayed() && !atelier.replayed(), "one of the scopes replayed");
      require(
          guild.originalLsn() != atelier.originalLsn(),
          "two scopes share one record");
      require(keyedRecords(dir) == 2, "the same key in two scopes did not post twice");
      IdempotentReceipt replay =
          ledger.postIdempotent(MerchantId.of("guild"), key, forGuild);
      require(replay.replayed(), "the guild's replay did not replay");
      require(
          replay.originalLsn() == guild.originalLsn(),
          "the guild's replay named the atelier's record");
      return "one key, two scopes, two records, two independent replays";
    }
  }

  private static String expiryRefuses(Path dir) throws Exception {
    MutableClock clock = new MutableClock(1_000L);
    try (DurableLedger ledger = open(dir, clock)) {
      openTwoAccounts(ledger);
      MerchantId merchant = MerchantId.of("guild");
      IdempotencyKey key = IdempotencyKey.of("payout-1");
      Transaction candidate =
          Transaction.transfer(
              AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
      ledger.postIdempotent(merchant, key, candidate);
      long bytes = walSize(dir);
      String hash = ledger.stateHash();
      clock.advance(RETENTION.toMillis() + 1L);
      try {
        ledger.postIdempotent(merchant, key, candidate);
        throw new AssertionError("an expired key replayed or re-executed");
      } catch (IdempotencyExpiredException expired) {
        require(expired.capturedAtMillis() == 1_000L, "the refusal misquotes the capture");
        require(
            expired.retentionMillis() == RETENTION.toMillis(),
            "the refusal misquotes the retention");
      }
      // The refusal is repeatable — expiry is a state, not an event.
      try {
        ledger.postIdempotent(merchant, key, candidate);
        throw new AssertionError("the second attempt at an expired key did something");
      } catch (IdempotencyExpiredException second) {
        // the expected outcome, and the only one
      }
      require(walSize(dir) == bytes, "a refusal appended bytes");
      require(ledger.stateHash().equals(hash), "a refusal changed the state");
      require(
          ledger.ledger().balanceOf(AccountId.of("acct-0")).equals(Money.ofMinor(-5_000L)),
          "a refusal moved money");
      // A fresh key still works — the refusal poisons the key, never the ledger.
      ledger.postIdempotent(
          merchant, IdempotencyKey.of("payout-2"), candidate);
      return "refused twice at age 24h+1ms, nothing appended, and a new key still posts";
    }
  }

  private static String backwardsClockExtends(Path dir) throws Exception {
    MutableClock clock = new MutableClock(2_000L);
    try (DurableLedger ledger = open(dir, clock)) {
      openTwoAccounts(ledger);
      MerchantId merchant = MerchantId.of("guild");
      IdempotencyKey key = IdempotencyKey.of("payout-1");
      Transaction candidate =
          Transaction.transfer(
              AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
      ledger.postIdempotent(merchant, key, candidate);
      // A step backwards makes now - capturedAt negative, which must read as "still live": the
      // safe direction for a retention floor is to over-serve replays, never to refuse them.
      clock.set(1_000L);
      IdempotentReceipt replay = ledger.postIdempotent(merchant, key, candidate);
      require(replay.replayed(), "a clock step backwards refused a live replay");
      return "negative age treated as zero: the replay was served";
    }
  }

  private static String duplicateStorm(Path dir) throws Exception {
    MutableClock clock = new MutableClock(1_000L);
    final int threads = 100;
    try (DurableLedger ledger = open(dir, clock)) {
      openTwoAccounts(ledger);
      MerchantId merchant = MerchantId.of("guild");
      IdempotencyKey key = IdempotencyKey.of("storm");
      Transaction candidate =
          Transaction.transfer(
              AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(1L));
      ConcurrentLinkedQueue<IdempotentReceipt> receipts = new ConcurrentLinkedQueue<>();
      ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
      List<Thread> storm = new ArrayList<>(threads);
      for (int i = 0; i < threads; i++) {
        storm.add(Thread.ofVirtual().name("storm-" + i).unstarted(() -> {
          try {
            receipts.add(ledger.postIdempotent(merchant, key, candidate));
          } catch (Throwable failed) {
            errors.add(failed);
          }
        }));
      }
      for (Thread thread : storm) {
        thread.start();
      }
      for (Thread thread : storm) {
        thread.join();
      }
      require(errors.isEmpty(), "the storm produced errors: " + errors.peek());
      require(receipts.size() == threads, "the storm returned " + receipts.size() + " receipts");
      int postings = 0;
      long lsn = -1L;
      for (IdempotentReceipt receipt : receipts) {
        if (!receipt.replayed()) {
          postings++;
        }
        if (lsn == -1L) {
          lsn = receipt.originalLsn();
        }
        require(
            receipt.originalLsn() == lsn,
            "a receipt named lsn " + receipt.originalLsn() + " where others named " + lsn);
      }
      require(postings == 1, postings + " postings for one key");
      require(keyedRecords(dir) == 1, "the log holds " + keyedRecords(dir) + " keyed records");
      require(
          ledger.ledger().balanceOf(AccountId.of("acct-1")).equals(Money.ofMinor(1L)),
          "the money moved more than once");
      ledger.ledger().audit();
      return threads + " concurrent retries: 1 posting, " + (threads - 1) + " replays, 0"
          + " double-charges, one lsn " + lsn;
    }
  }

  private static String recoveryRestoresTheTable(Path dir) throws Exception {
    MutableClock clock = new MutableClock(1_000L);
    MerchantId merchant = MerchantId.of("guild");
    IdempotencyKey live = IdempotencyKey.of("live");
    IdempotencyKey aged = IdempotencyKey.of("aged");
    IdempotencyKey contested = IdempotencyKey.of("contested");
    Transaction posting =
        Transaction.transfer(
            AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
    String hashBeforeClose;
    try (DurableLedger ledger = open(dir, clock)) {
      openTwoAccounts(ledger);
      ledger.postIdempotent(merchant, live, posting);
      ledger.postIdempotent(
          merchant, aged, Transaction.transfer(
              AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(1L)));
      ledger.postIdempotent(merchant, contested, posting);
      hashBeforeClose = ledger.stateHash();
    }
    // The recovery is a fold of the log: no checkpoint exists, so the table must come back from
    // the records alone.
    try (DurableLedger ledger = open(dir, clock)) {
      require(
          ledger.stateHash().equals(hashBeforeClose),
          "the recovery's state hash is not the run's");
      IdempotentReceipt replay = ledger.postIdempotent(merchant, live, posting);
      require(replay.replayed(), "a live key did not replay after recovery");
      clock.advance(RETENTION.toMillis() + 1L);
      try {
        ledger.postIdempotent(
            merchant, aged,
            Transaction.transfer(
                AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(1L)));
        throw new AssertionError("an aged key replayed or re-executed after recovery");
      } catch (IdempotencyExpiredException expired) {
        // the expected refusal
      }
      try {
        ledger.postIdempotent(
            merchant, contested,
            Transaction.transfer(
                AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(2L)));
        throw new AssertionError("a conflict did not survive recovery");
      } catch (IdempotencyConflictException conflict) {
        // the expected conflict
      }
      require(keyedRecords(dir) == 3, "the recovered log does not hold three keyed records");
      return "hash identical, live replays, aged refuses, contested still conflicts";
    }
  }

  private static String checkpointCarriesBindings(Path dir) throws Exception {
    MutableClock clock = new MutableClock(1_000L);
    MerchantId merchant = MerchantId.of("guild");
    IdempotencyKey key = IdempotencyKey.of("payout-1");
    Transaction posting =
        Transaction.transfer(
            AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
    String hashBeforeClose;
    try (DurableLedger ledger = open(dir, clock)) {
      openTwoAccounts(ledger);
      ledger.postIdempotent(merchant, key, posting);
      ledger.checkpoint();
      hashBeforeClose = ledger.stateHash();
    }
    try (DurableLedger ledger = open(dir, clock)) {
      require(ledger.checkpointLoad().used(), "the checkpoint was not used");
      require(ledger.replayedRecords() == 0, "the tail was not empty");
      require(
          ledger.stateHash().equals(hashBeforeClose),
          "the checkpointed state's hash is not the run's");
      IdempotentReceipt replay = ledger.postIdempotent(merchant, key, posting);
      require(replay.replayed(), "a binding restored from a checkpoint did not replay");
      require(
          replay.transaction().equals(posting),
          "the re-materialized response is not the original transaction");
      require(keyedRecords(dir) == 1, "the replay after the checkpoint appended a record");
      // The timing section is what lets expiry survive a checkpoint: the instant came back too.
      clock.advance(RETENTION.toMillis() + 1L);
      try {
        ledger.postIdempotent(merchant, key, posting);
        throw new AssertionError("an instant restored from a timing section did not age");
      } catch (IdempotencyExpiredException expired) {
        require(expired.capturedAtMillis() == 1_000L, "the aged instant is not the committed one");
      }
      return "checkpoint used, response re-materialized from lsn "
          + replay.originalLsn() + ", and the instant aged exactly";
    }
  }

  private static String doubleBindingRefusesToOpen(Path dir) throws Exception {
    MerchantId merchant = MerchantId.of("guild");
    IdempotencyKey key = IdempotencyKey.of("payout-1");
    Transaction posting =
        Transaction.transfer(
            AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
    // Written through the raw WAL, because the ledger itself cannot produce this log: the
    // commit path consults the index before it binds. The file is assembled with the same codec
    // so the refusal is about the duplicate, not the bytes.
    try (Wal wal = Wal.open(dir, POLICY)) {
      wal.appendSync(
          RecordType.ACCOUNT_OPENED,
          EventCodec.encode(
              new JournalEvent.AccountOpened(
                  new dev.ledgerx.domain.Account(AccountId.of("acct-0"), AccountKind.ASSET))));
      wal.appendSync(
          RecordType.ACCOUNT_OPENED,
          EventCodec.encode(
              new JournalEvent.AccountOpened(
                  new dev.ledgerx.domain.Account(AccountId.of("acct-1"), AccountKind.ASSET))));
      wal.appendSync(
          RecordType.IDEMPOTENT_POSTING,
          EventCodec.encode(
              new JournalEvent.PostedIdempotently(
                  merchant, key, EventCodec.fingerprintOf(posting), 1_000L, posting)));
      wal.appendSync(
          RecordType.IDEMPOTENT_POSTING,
          EventCodec.encode(
              new JournalEvent.PostedIdempotently(
                  merchant, key, EventCodec.fingerprintOf(posting), 2_000L, posting)));
    }
    try (DurableLedger ledger = open(dir, new MutableClock(1_000L))) {
      throw new AssertionError("a log with one key bound twice opened as " + ledger.stateHash());
    } catch (UnrecoverableLogException refused) {
      require(
          refused.corruption() == Corruption.DOMAIN_REJECTED,
          "refused as " + refused.corruption() + ", expected DOMAIN_REJECTED");
      require(
          refused.getMessage().contains("twice"),
          "the refusal does not say what the log did: " + refused.getMessage());
    }
    return "DOMAIN_REJECTED, the exactly-once theorem's one way to be false";
  }

  private static String retentionZero(Path dir) throws Exception {
    MutableClock clock = new MutableClock(1_000L);
    try (DurableLedger ledger =
        DurableLedger.open(
            dir, POLICY, true, dev.ledgerx.checkpoint.CheckpointPolicy.MANUAL,
            IdempotencyPolicy.of(clock, Duration.ZERO))) {
      openTwoAccounts(ledger);
      MerchantId merchant = MerchantId.of("guild");
      IdempotencyKey key = IdempotencyKey.of("payout-1");
      Transaction posting =
          Transaction.transfer(
              AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
      IdempotentReceipt first = ledger.postIdempotent(merchant, key, posting);
      require(!first.replayed(), "retention zero broke the first posting");
      try {
        ledger.postIdempotent(merchant, key, posting);
        throw new AssertionError("retention zero replayed the response it just stored");
      } catch (IdempotencyExpiredException expired) {
        // the degenerate floor: store, then refuse everything, forever
      }
      require(keyedRecords(dir) == 1, "the degenerate policy posted twice");
      return "stored once, refused immediately, and still never re-executed";
    }
  }

  private static String hashIgnoresTiming(Path dir) throws Exception {
    // Two runs of one history against two different clocks: their logs differ in the capture
    // bytes, and their state hashes agree — the property that keeps "byte-identical replay"
    // meaning the money state while the records carry wall-clock instants.
    Transaction posting =
        Transaction.transfer(
            AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
    byte[] logAtNoon;
    String hashAtNoon;
    try (DurableLedger ledger = open(dir, new MutableClock(1_726_617_600_000L))) {
      openTwoAccounts(ledger);
      ledger.postIdempotent(MerchantId.of("guild"), IdempotencyKey.of("payout-1"), posting);
      logAtNoon = Files.readAllBytes(dir.resolve(Wal.FILE_NAME));
      hashAtNoon = ledger.stateHash();
    }
    Path other = Files.createTempDirectory(dir, "run-two");
    byte[] logAtMidnight;
    String hashAtMidnight;
    try (DurableLedger ledger = open(other, new MutableClock(1_726_609_000_000L))) {
      openTwoAccounts(ledger);
      ledger.postIdempotent(MerchantId.of("guild"), IdempotencyKey.of("payout-1"), posting);
      logAtMidnight = Files.readAllBytes(other.resolve(Wal.FILE_NAME));
      hashAtMidnight = ledger.stateHash();
    }
    require(!Arrays.equals(logAtNoon, logAtMidnight), "two clocks wrote identical logs");
    require(
        hashAtNoon.equals(hashAtMidnight),
        "the state hash saw the capture instants: " + hashAtNoon + " vs " + hashAtMidnight);
    return "logs differ by timing bytes, hashes agree on "
        + hashAtNoon.substring(0, 16) + "...";
  }

  private static String reorderedBodyConflicts(Path dir) throws Exception {
    MutableClock clock = new MutableClock(1_000L);
    try (DurableLedger ledger = open(dir, clock)) {
      openTwoAccounts(ledger);
      MerchantId merchant = MerchantId.of("guild");
      IdempotencyKey key = IdempotencyKey.of("payout-1");
      Transaction asSent =
          Transaction.of(
              List.of(
                  Entry.debit(AccountId.of("acct-1"), Money.ofMinor(5_000L)),
                  Entry.credit(AccountId.of("acct-0"), Money.ofMinor(5_000L))));
      Transaction reordered =
          Transaction.of(
              List.of(
                  Entry.credit(AccountId.of("acct-0"), Money.ofMinor(5_000L)),
                  Entry.debit(AccountId.of("acct-1"), Money.ofMinor(5_000L))));
      ledger.postIdempotent(merchant, key, asSent);
      try {
        ledger.postIdempotent(merchant, key, reordered);
        throw new AssertionError("a reordered body was treated as the same body");
      } catch (IdempotencyConflictException conflict) {
        // the body is the entry list as sent, in the order it was sent: an array is ordered,
        // and "the bytes you sent" is the least surprising thing a fingerprint can cover
      }
      return "same entries, different order, different fingerprint, 409";
    }
  }

  private static String rejectionLeavesNoBinding(Path dir) throws Exception {
    MutableClock clock = new MutableClock(1_000L);
    try (DurableLedger ledger = open(dir, clock)) {
      openTwoAccounts(ledger);
      MerchantId merchant = MerchantId.of("guild");
      IdempotencyKey key = IdempotencyKey.of("payout-1");
      Transaction unbalanced =
          Transaction.of(
              List.of(
                  Entry.debit(AccountId.of("acct-1"), Money.ofMinor(5_000L)),
                  Entry.credit(AccountId.of("acct-0"), Money.ofMinor(4_999L))));
      long bytes = walSize(dir);
      try {
        ledger.postIdempotent(merchant, key, unbalanced);
        throw new AssertionError("an unbalanced candidate was accepted under a key");
      } catch (RejectedTransactionException refused) {
        require(
            refused.reason() == RejectionReason.UNBALANCED,
            "refused as " + refused.reason());
      }
      require(walSize(dir) == bytes, "a rejected candidate appended bytes");
      // The refusal left no binding: the key is still usable for the intent the client meant.
      Transaction corrected =
          Transaction.transfer(
              AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
      IdempotentReceipt receipt = ledger.postIdempotent(merchant, key, corrected);
      require(!receipt.replayed(), "the corrected posting replayed something");
      return "rejected with nothing bound, and the key still usable";
    }
  }

  // --- the harness ----------------------------------------------------------------------

  /** A clock a check can move: the one dependency expiry has that cannot be faked with a seed. */
  static final class MutableClock extends Clock {

    private volatile long millis;
    private final ZoneOffset zone = ZoneOffset.UTC;

    MutableClock(long millis) {
      this.millis = millis;
    }

    void advance(long byMillis) {
      millis += byMillis;
    }

    void set(long toMillis) {
      millis = toMillis;
    }

    /** The clock's reading — the capture instant a receipt must quote back. */
    @Override
    public long millis() {
      return millis;
    }

    @Override
    public ZoneOffset getZone() {
      return zone;
    }

    @Override
    public Clock withZone(java.time.ZoneId ignored) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis);
    }
  }

  private static DurableLedger open(Path dir, MutableClock clock) throws IOException {
    return DurableLedger.open(
        dir,
        POLICY,
        true,
        dev.ledgerx.checkpoint.CheckpointPolicy.MANUAL,
        IdempotencyPolicy.of(clock, RETENTION));
  }

  private static void openTwoAccounts(DurableLedger ledger) throws IOException {
    ledger.openAccount(AccountId.of("acct-0"), AccountKind.ASSET);
    ledger.openAccount(AccountId.of("acct-1"), AccountKind.ASSET);
  }

  private static long walSize(Path dir) throws IOException {
    return Files.size(dir.resolve(Wal.FILE_NAME));
  }

  /** How many {@code IDEMPOTENT_POSTING} records the log holds, by scanning it cold. */
  private static int keyedRecords(Path dir) throws IOException {
    int keyed = 0;
    for (WalRecord record : WalRecovery.scan(dir.resolve(Wal.FILE_NAME)).records()) {
      if (record.type() == RecordType.IDEMPOTENT_POSTING) {
        keyed++;
      }
    }
    return keyed;
  }

  private static void require(boolean condition, String what) {
    if (!condition) {
      throw new AssertionError(what);
    }
  }

  private static void deleteTree(Path root) throws IOException {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (IOException ignored) {
          // a temp directory that cannot be fully cleaned is not a failed check
        }
      });
    }
  }
}
