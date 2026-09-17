package dev.ledgerx.checkpoint;

import dev.ledgerx.domain.Account;
import dev.ledgerx.domain.AccountId;
import dev.ledgerx.domain.AccountKind;
import dev.ledgerx.domain.AccountState;
import dev.ledgerx.domain.Entry;
import dev.ledgerx.domain.InMemoryLedger;
import dev.ledgerx.domain.Money;
import dev.ledgerx.domain.Transaction;
import dev.ledgerx.journal.DurableLedger;
import dev.ledgerx.journal.Replay;
import dev.ledgerx.testing.RandomSource;
import dev.ledgerx.wal.Corruption;
import dev.ledgerx.wal.FsyncPolicy;
import dev.ledgerx.wal.UnrecoverableLogException;
import dev.ledgerx.wal.Wal;
import dev.ledgerx.wal.WalFormat;
import dev.ledgerx.wal.WalRecovery;
import dev.ledgerx.wal.WalRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The checkpoint-and-replay contract: every claim ADR 0004 makes about the state hash, the
 * checkpoint format, the atomic swap and recovery, asserted on every build.
 *
 * <p><strong>The headline is check 5, and the ticket asked for it by name.</strong> Take a random
 * history. For <em>every</em> prefix of it — every LSN boundary, which is every point a crash can
 * land — cut the log there, recover, and hash the state. Then recover again from the checkpoint
 * that first recovery wrote. Then delete the checkpoint and recover a third time, from byte 0. All
 * three digests must equal the digest of the same prefix folded in memory, with no files involved.
 * That is "byte-identical" made into a number: not two ledgers that look equivalent, but two
 * 32-byte digests that are equal, on every prefix of every history, with zero tolerance.
 *
 * <p><strong>Why a contract suite and not only a random harness.</strong> A random crash finds the
 * case nobody thought of; it cannot be relied on to hit the case that matters, and the cases that
 * matter here are exact: a checkpoint truncated at byte <em>n</em> for every <em>n</em>, a single
 * flipped bit in each of the fields a stale snapshot hides behind, a log cut and then regrown past
 * the LSN the snapshot names. Those are enumerated, not sampled, so a green run means each of them
 * was walked and not that none of them was hit.
 *
 * <p><strong>Hostile inputs are built from raw bytes.</strong> Every damaged checkpoint here is
 * written by hand — CRC recomputed where the check being tested is the SHA-256 rather than the
 * CRC — because a corrupted file produced by the code under test proves nothing about it.
 *
 * <p>Run by {@code ./build.sh}; exits non-zero if any check fails. Campaign size without editing
 * anything: {@code -Dledgerx.checkpoint.histories=400 -Dledgerx.checkpoint.ops=60}.
 */
public final class CheckpointContract {

  /** Random histories for the boundary harness. Every one is walked at every LSN boundary. */
  private static final int HISTORIES = Integer.getInteger("ledgerx.checkpoint.histories", 40);

  /** Ops per history: enough for a checkpoint to be worth writing, few enough to walk. */
  private static final int OPS = Integer.getInteger("ledgerx.checkpoint.ops", 24);

  private static final long BASE_SEED = 20260917L;

  private static final List<String> failures = new ArrayList<>();
  private static int checksRun;

  private CheckpointContract() {}

  /** A check returns a short detail string for the log, or throws to fail. */
  private interface Check {
    String run(Path dir) throws Exception;
  }

  public static void main(String[] args) {
    System.out.println(
        "ledger-x checkpoint-and-replay contract on "
            + Runtime.version()
            + " ("
            + HISTORIES
            + " histories x "
            + OPS
            + " ops)");
    check("the state hash is a canonical encoding, not an ordering", CheckpointContract::canonical);
    check("the state hash is exactly these bytes", CheckpointContract::hashIsTheseBytes);
    check("a checkpoint file is exactly these bytes", CheckpointContract::fileIsTheseBytes);
    check(
        "a checkpoint names the log it belongs to, exactly",
        CheckpointContract::watermarkMatchesTheLog);
    check(
        "a crash at every lsn boundary reconstructs byte-identical state",
        CheckpointContract::crashAtEveryBoundary);
    check(
        "a checkpoint is written when the fold is long enough, and replayed as nothing",
        CheckpointContract::checkpointAdvances);
    check(
        "a checkpoint torn at any byte is a cache miss, never a wrong state",
        CheckpointContract::tornCheckpointIsAMiss);
    check("a checkpoint that lies is discarded, not believed", CheckpointContract::lyingCheckpoint);
    check(
        "a baseline that breaks Σ balances = 0 is refused",
        CheckpointContract::impossibleBaseline);
    check(
        "a log cut and regrown cannot resurrect through a stale checkpoint",
        CheckpointContract::noResurrectionThroughACheckpoint);
    check(
        "a damaged log head still refuses, checkpoint or not",
        CheckpointContract::headStillRefuses);
    check("the log is never truncated for a checkpoint, and orphans are collected",
        CheckpointContract::retentionIsHarmless);
    check("no-fsync refuses to snapshot records the log has not made durable",
        CheckpointContract::noFsyncNeverSnapshotsAhead);

    System.out.println();
    if (failures.isEmpty()) {
      System.out.println("PASS " + checksRun + "/" + checksRun + " checkpoint checks");
      return;
    }
    System.out.println("FAIL " + failures.size() + " of " + checksRun + " checkpoint checks");
    for (String failure : failures) {
      System.out.println("  - " + failure);
    }
    System.exit(1);
  }

  private static void check(String name, Check body) {
    checksRun++;
    Path dir = null;
    try {
      dir = Files.createTempDirectory("ledger-x-ckpt");
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

  // --- the state hash ---------------------------------------------------------------------

  /**
   * The determinism rules, tested rather than stated: two ledgers holding the same money reach the
   * same canonical bytes however they got there, and the encoding survives a round trip.
   */
  private static String canonical(Path dir) throws IOException {
    // One history: open a, b, c in that order, then move money.
    InMemoryLedger first = new InMemoryLedger();
    first.openAccount(new AccountId("a"), AccountKind.ASSET);
    first.openAccount(new AccountId("b"), AccountKind.LIABILITY);
    first.openAccount(new AccountId("c"), AccountKind.EQUITY);
    first.post(Transaction.transfer(new AccountId("a"), new AccountId("b"), Money.ofMinor(500L)));
    first.post(Transaction.transfer(new AccountId("b"), new AccountId("c"), Money.ofMinor(200L)));

    // The other history: the same accounts opened in reverse, the same two transfers reversed in
    // order and expressed with the entries in the other sequence. Same state, different route.
    InMemoryLedger second = new InMemoryLedger();
    second.openAccount(new AccountId("c"), AccountKind.EQUITY);
    second.openAccount(new AccountId("b"), AccountKind.LIABILITY);
    second.openAccount(new AccountId("a"), AccountKind.ASSET);
    // Same two transfers, opposite order, and each with its entries listed the other way round:
    // entry order inside a transaction is not part of the state either.
    second.post(
        Transaction.of(
            Entry.credit(new AccountId("b"), Money.ofMinor(200L)),
            Entry.debit(new AccountId("c"), Money.ofMinor(200L))));
    second.post(
        Transaction.of(
            Entry.credit(new AccountId("a"), Money.ofMinor(500L)),
            Entry.debit(new AccountId("b"), Money.ofMinor(500L))));

    byte[] one = StateHash.canonical(first);
    byte[] two = StateHash.canonical(second);
    if (!java.util.Arrays.equals(one, two)) {
      throw new AssertionError(
          "the same money hashed differently by route\n  first:  " + StateHash.hex(one)
              + "\n  second: " + StateHash.hex(two));
    }
    if (!java.util.Arrays.equals(StateHash.digest(one), StateHash.digest(two))) {
      throw new AssertionError("equal canonical bytes produced different digests");
    }
    // The order really is canonical: rows come out sorted, and re-encoding what was decoded
    // reproduces the bytes, so the encoding is injective rather than merely self-consistent.
    List<AccountState> rows = StateHash.decode(one);
    for (int i = 1; i < rows.size(); i++) {
      if (AccountState.compareIds(rows.get(i - 1).id(), rows.get(i).id()) >= 0) {
        throw new AssertionError(
            "the canonical order is not ascending at " + i + ": " + rows.get(i - 1).id() + " then "
                + rows.get(i).id());
      }
    }
    if (!java.util.Arrays.equals(StateHash.canonical(rows), one)) {
      throw new AssertionError("decoding and re-encoding the canonical form changed it");
    }
    // Nothing positional: a ledger restored from a checkpoint of itself hashes the same, which is
    // the property the whole recovery path rests on.
    InMemoryLedger restored = new InMemoryLedger(first.state(), first.journalEvents());
    if (!java.util.Arrays.equals(StateHash.digest(restored), StateHash.digest(first))) {
      throw new AssertionError("a baseline of a state does not hash like the state");
    }
    return one.length
        + " canonical bytes for "
        + rows.size()
        + " accounts, "
        + StateHash.hex(one).substring(0, 16)
        + "…, identical by two routes and stable through decode";
  }

  /**
   * A golden digest, pinned. Everything else in this suite compares one digest with another, so a
   * change to the encoding that moved both sides together would be invisible; this is the check
   * that cannot move with it. Changing the canonical form is a format change, and it must fail
   * here loudly and be re-pinned deliberately.
   */
  private static String hashIsTheseBytes(Path dir) {
    InMemoryLedger ledger = new InMemoryLedger();
    ledger.openAccount(new AccountId("alpha"), AccountKind.ASSET);
    ledger.openAccount(new AccountId("beta"), AccountKind.LIABILITY);
    ledger.post(
        Transaction.transfer(new AccountId("alpha"), new AccountId("beta"), Money.ofMinor(12345L)));
    byte[] canonicalBytes = StateHash.canonical(ledger);
    String canonicalHex = StateHash.hex(canonicalBytes);
    String digest = StateHash.hex(StateHash.digest(canonicalBytes));
    if (!canonicalHex.equals(PINNED_CANONICAL_HEX)) {
      throw new AssertionError(
          "the canonical encoding changed\n  want " + PINNED_CANONICAL_HEX + "\n  got  "
              + canonicalHex);
    }
    if (!digest.equals(PINNED_DIGEST_HEX)) {
      throw new AssertionError(
          "the state hash changed\n  want " + PINNED_DIGEST_HEX + "\n  got  " + digest);
    }
    return canonicalBytes.length + " bytes, sha256 " + digest.substring(0, 24) + "…";
  }

  /**
   * The canonical bytes of {@code alpha ASSET −12345, beta LIABILITY +12345}: version, tag,
   * reserved, count, then one row per account sorted by id. Pinned by hand from the layout in
   * {@link StateHash}, and checked against the code rather than the other way round.
   */
  private static final String PINNED_CANONICAL_HEX =
      "0153000000000002"
          + "05616c706861"
          + "00"
          + "ffffffffffffcfc7"
          + "0462657461"
          + "01"
          + "0000000000003039";

  /**
   * SHA-256 of {@link #PINNED_CANONICAL_HEX}, verified independently of this codebase: the same 37
   * bytes through {@code hashlib.sha256} give the same 64 characters, so the pin is a fact about
   * the encoding rather than a transcript of what the code happened to print.
   */
  private static final String PINNED_DIGEST_HEX =
      "0a4815c507a759141b878e5e9640e7d4a340b37e1199e07d3bc2ad0ecbc4ff19";

  // --- the checkpoint file ----------------------------------------------------------------

  private static String fileIsTheseBytes(Path dir) throws IOException {
    InMemoryLedger ledger = new InMemoryLedger();
    ledger.openAccount(new AccountId("one"), AccountKind.ASSET);
    ledger.openAccount(new AccountId("two"), AccountKind.LIABILITY);
    ledger.post(
        Transaction.transfer(new AccountId("one"), new AccountId("two"), Money.ofMinor(700L)));
    Checkpoint checkpoint = Checkpoint.of(9L, 348L, ledger.journalEvents(), ledger.state());

    byte[] file = checkpoint.encode();
    if (WalFormat.intAt(file, 0) != Checkpoint.MAGIC) {
      throw new AssertionError("the magic is not \"LCKP\"");
    }
    if (file[4] != Checkpoint.VERSION || file[5] != 0) {
      throw new AssertionError("the version and flags bytes are not 1 and 0");
    }
    if (WalFormat.shortAt(file, 6) != Checkpoint.HEADER_BYTES) {
      throw new AssertionError("the header size field does not say " + Checkpoint.HEADER_BYTES);
    }
    if (WalFormat.longAt(file, 8) != 9L || WalFormat.longAt(file, 16) != 348L) {
      throw new AssertionError("the watermark fields are not the ones given");
    }
    if (WalFormat.longAt(file, 24) != ledger.journalEvents()) {
      throw new AssertionError("the journal-event count is wrong");
    }
    byte[] payload = checkpoint.payload();
    if (WalFormat.intAt(file, 32) != payload.length) {
      throw new AssertionError("the payload length does not match the canonical state");
    }
    if (file.length != Checkpoint.HEADER_BYTES + payload.length + Checkpoint.CRC_BYTES) {
      throw new AssertionError("the file is not header + payload + crc");
    }
    for (int i = 0; i < payload.length; i++) {
      if (file[Checkpoint.HEADER_BYTES + i] != payload[i]) {
        throw new AssertionError("the payload is not the canonical state, byte for byte");
      }
    }
    byte[] stored = new byte[StateHash.DIGEST_BYTES];
    System.arraycopy(file, 36, stored, 0, StateHash.DIGEST_BYTES);
    if (!java.util.Arrays.equals(stored, StateHash.digest(payload))) {
      throw new AssertionError("the header's digest is not SHA-256 of the payload");
    }
    int crcAt = file.length - Checkpoint.CRC_BYTES;
    if (WalFormat.intAt(file, crcAt) != WalFormat.crc32c(file, crcAt)) {
      throw new AssertionError("the trailing crc does not cover the file");
    }
    // Round trip, and byte-stability: the same state always produces the same file, which is what
    // lets a harness compare two runs' checkpoints rather than only their hashes.
    Checkpoint.Decode decoded = Checkpoint.decode(file);
    if (!(decoded instanceof Checkpoint.Decoded)) {
      throw new AssertionError("a file this class just wrote did not decode: " + decoded);
    }
    Checkpoint back = ((Checkpoint.Decoded) decoded).checkpoint();
    if (!back.equals(checkpoint)) {
      throw new AssertionError("a checkpoint did not survive its own encoding");
    }
    Path written = CheckpointWriter.write(dir, checkpoint);
    Path again = CheckpointWriter.write(dir, checkpoint);
    if (!java.util.Arrays.equals(Files.readAllBytes(written), Files.readAllBytes(again))) {
      throw new AssertionError("writing the same checkpoint twice produced different bytes");
    }
    return file.length
        + " bytes = "
        + Checkpoint.HEADER_BYTES
        + " header + "
        + payload.length
        + " payload + "
        + Checkpoint.CRC_BYTES
        + " crc, stable across writes";
  }

  private static String watermarkMatchesTheLog(Path dir) throws IOException {
    Path ledger = dir.resolve("ledger");
    try (DurableLedger open = build(ledger, FsyncPolicy.PER_COMMIT, 12)) {
      Checkpoint written = open.checkpoint();
      WalRecovery.Scan scan = WalRecovery.scan(ledger.resolve(Wal.FILE_NAME));
      if (written.lastLsn() != scan.report().lastLsn()) {
        throw new AssertionError(
            "the checkpoint claims LSN " + written.lastLsn() + " and the log ends at "
                + scan.report().lastLsn());
      }
      if (written.walBytes() != scan.report().cleanBytes()) {
        throw new AssertionError(
            "the checkpoint claims byte " + written.walBytes() + " and the clean prefix ends at "
                + scan.report().cleanBytes());
      }
      if (scan.endOffsetOf(written.lastLsn()) != written.walBytes()) {
        throw new AssertionError("the claimed offset is not where that record ends");
      }
      if (written.journalEvents() != open.ledger().journalEvents()) {
        throw new AssertionError("the checkpoint's event count is not the ledger's");
      }
      if (!java.util.Arrays.equals(
          Files.readAllBytes(CheckpointWriter.liveFile(ledger)), written.encode())) {
        throw new AssertionError("the file on disk is not the checkpoint that was returned");
      }
      return "lsn<=" + written.lastLsn() + " at byte " + written.walBytes() + ", "
          + written.journalEvents() + " events, " + written.accounts() + " accounts";
    }
  }

  // --- the headline: crash at every lsn boundary -------------------------------------------

  /**
   * For every prefix of a random history: cut, recover, hash — and demand the same digest from
   * three recoveries and from a fold that never touched a file.
   */
  private static String crashAtEveryBoundary(Path dir) throws IOException {
    long boundaries = 0L;
    long usedCheckpoints = 0L;
    long discarded = 0L;
    long absent = 0L;
    long tailRecords = 0L;
    for (int history = 0; history < HISTORIES; history++) {
      Path ledger = dir.resolve("h" + history);
      long seed = BASE_SEED + history;
      Path wal = ledger.resolve(Wal.FILE_NAME);
      long[] ends;
      List<WalRecord> records;
      try (DurableLedger open = build(ledger, FsyncPolicy.PER_COMMIT, OPS, seed)) {
        WalRecovery.Scan scan = WalRecovery.scan(wal);
        ends = scan.endOffsets();
        records = scan.records();
        if (records.size() != scan.report().lastLsn()) {
          throw new AssertionError("the history has a marker in it; this check wants a clean log");
        }
      }
      // The whole log, kept: a truncation cannot grow a file back, so every boundary has to start
      // from the intact log rather than from the previous, shorter cut.
      byte[] whole = Files.readAllBytes(wal);
      CheckpointWriter.delete(ledger);

      // The reference: the same prefix folded in memory, no files, no checkpoint, no recovery.
      List<String> expected = new ArrayList<>();
      for (int k = 0; k <= records.size(); k++) {
        expected.add(StateHash.hex(StateHash.digest(Replay.foldEvents(records.subList(0, k)))));
      }

      for (int k = 0; k <= records.size(); k++) {
        // The crash: the log ends where record k ended, and everything after it is gone.
        Files.write(wal, whole);
        WalRecovery.truncateTo(wal, k == 0 ? 16L : ends[k - 1]);
        if (Files.size(wal) != (k == 0 ? 16L : ends[k - 1])) {
          throw new AssertionError(
              "history " + history + ": the cut to boundary " + k + " did not take");
        }
        String recovered;
        try (DurableLedger afterCrash = DurableLedger.open(ledger, FsyncPolicy.PER_COMMIT)) {
          recovered = afterCrash.stateHashHex();
          switch (afterCrash.checkpointDecision().outcome()) {
            case USED -> usedCheckpoints++;
            case DISCARDED -> discarded++;
            case ABSENT -> absent++;
          }
        }
        String second;
        int tail;
        try (DurableLedger reopened = DurableLedger.open(ledger, FsyncPolicy.PER_COMMIT)) {
          second = reopened.stateHashHex();
          tail = reopened.checkpointDecision().tail().size();
        }
        String fromScratch;
        CheckpointWriter.delete(ledger);
        try (DurableLedger noCheckpoint = DurableLedger.open(ledger, FsyncPolicy.PER_COMMIT)) {
          if (noCheckpoint.checkpointDecision().outcome()
              != CheckpointRecovery.Outcome.ABSENT) {
            throw new AssertionError(
                "history " + history + " boundary " + k + ": the checkpoint was deleted and the"
                    + " recovery says " + noCheckpoint.checkpointDecision());
          }
          fromScratch = noCheckpoint.stateHashHex();
        }
        if (!recovered.equals(expected.get(k))
            || !second.equals(recovered)
            || !fromScratch.equals(recovered)) {
          throw new AssertionError(
              "history " + history + " (seed " + seed + ") boundary " + k + " of "
                  + records.size() + " is not byte-identical\n"
                  + "  folded in memory " + expected.get(k) + "\n"
                  + "  after the crash  " + recovered + "\n"
                  + "  from checkpoint  " + second + "\n"
                  + "  from byte zero   " + fromScratch);
        }
        boundaries++;
        tailRecords += tail;
      }
    }
    return boundaries
        + " lsn boundaries across "
        + HISTORIES
        + " histories, 3 recoveries each, 100% byte-identical ("
        + usedCheckpoints
        + " from a checkpoint, "
        + discarded
        + " discarded, "
        + absent
        + " absent, "
        + tailRecords
        + " tail records replayed)";
  }

  private static String checkpointAdvances(Path dir) throws IOException {
    Path ledger = dir.resolve("ledger");
    // Four account openings are journal events as well, so the threshold is on the total.
    int shortOps = DurableLedger.MIN_CHECKPOINT_EVENTS - 5;
    try (DurableLedger open = build(ledger, FsyncPolicy.PER_COMMIT, shortOps)) {
      // Nothing on disk yet, and an open this small must not write one: a checkpoint that saves
      // fewer records than it costs in fsyncs is a write, not an optimisation.
    }
    try (DurableLedger reopened = DurableLedger.open(ledger, FsyncPolicy.PER_COMMIT)) {
      if (reopened.checkpointDecision().outcome() != CheckpointRecovery.Outcome.ABSENT) {
        throw new AssertionError(
            "a log of " + (4 + shortOps) + " events got a checkpoint: "
                + reopened.checkpointDecision());
      }
    }
    if (Files.exists(CheckpointWriter.liveFile(ledger))) {
      throw new AssertionError("an open below the threshold wrote a checkpoint file");
    }
    try (DurableLedger grown = DurableLedger.open(ledger, FsyncPolicy.PER_COMMIT)) {
      grow(grown, 12, 4242L);
    }
    // The open after the growth folds a log long enough to be worth saving, so it leaves one
    // behind — and it is that open, not the writing one, that pays for the checkpoint.
    try (DurableLedger open = DurableLedger.open(ledger, FsyncPolicy.PER_COMMIT)) {
      if (open.checkpointDecision().outcome() != CheckpointRecovery.Outcome.ABSENT) {
        throw new AssertionError(
            "the first open of the grown log found a checkpoint: "
                + open.checkpointDecision());
      }
      if (!Files.exists(CheckpointWriter.liveFile(ledger))) {
        throw new AssertionError("folding " + open.ledger().journalEvents()
            + " events left no checkpoint behind");
      }
    }
    String first;
    int tail;
    try (DurableLedger open = DurableLedger.open(ledger, FsyncPolicy.PER_COMMIT)) {
      if (!open.checkpointDecision().used()) {
        throw new AssertionError(
            "the checkpoint on disk was not used: " + open.checkpointDecision());
      }
      first = open.stateHashHex();
      tail = open.checkpointDecision().tail().size();
    }
    if (tail != 0) {
      throw new AssertionError(
          "a checkpoint at the end of the log left " + tail + " records to replay");
    }
    try (DurableLedger open = DurableLedger.open(ledger, FsyncPolicy.PER_COMMIT)) {
      if (!first.equals(open.stateHashHex())) {
        throw new AssertionError("two opens from the same checkpoint disagreed");
      }
    }
    return "no checkpoint at "
        + (4 + shortOps)
        + " events, one once the fold passed "
        + DurableLedger.MIN_CHECKPOINT_EVENTS
        + ", and the next open replays 0 records";
  }

  // --- damaged checkpoints ----------------------------------------------------------------

  /** Every truncation of a checkpoint file, exhaustively: all of them must be a cache miss. */
  private static String tornCheckpointIsAMiss(Path dir) throws IOException {
    Path ledger = dir.resolve("ledger");
    String expected;
    byte[] whole;
    try (DurableLedger open = build(ledger, FsyncPolicy.PER_COMMIT, 14)) {
      open.checkpoint();
      expected = open.stateHashHex();
      whole = Files.readAllBytes(CheckpointWriter.liveFile(ledger));
    }
    int checked = 0;
    for (int cut = 0; cut <= whole.length; cut++) {
      Files.write(CheckpointWriter.liveFile(ledger), java.util.Arrays.copyOf(whole, cut));
      try (DurableLedger open = DurableLedger.open(ledger, FsyncPolicy.PER_COMMIT)) {
        CheckpointRecovery.Decision decision = open.checkpointDecision();
        if (cut == whole.length) {
          if (!decision.used()) {
            throw new AssertionError("the intact file was not used: " + decision);
          }
        } else {
          if (decision.outcome() != CheckpointRecovery.Outcome.DISCARDED) {
            throw new AssertionError(
                "a checkpoint cut to " + cut + " of " + whole.length + " bytes was "
                    + decision.outcome());
          }
          if (!decision.cause().damaged()) {
            throw new AssertionError(
                "a checkpoint cut to " + cut + " bytes was rejected as " + decision.cause()
                    + ", which is not damage");
          }
        }
        if (!expected.equals(open.stateHashHex())) {
          throw new AssertionError(
              "a checkpoint cut to " + cut + " bytes changed the recovered state");
        }
      }
      checked++;
    }
    Files.write(CheckpointWriter.liveFile(ledger), whole);
    return checked + " truncations of a " + whole.length + "-byte checkpoint, all cache misses";
  }

  /**
   * Single-bit and single-field lies, each with the CRC repaired so the check being tested is the
   * one behind it: a wrong state (caught by SHA-256), a wrong watermark (caught against the log).
   */
  private static String lyingCheckpoint(Path dir) throws IOException {
    Path ledger = dir.resolve("ledger");
    String expected;
    byte[] whole;
    try (DurableLedger open = build(ledger, FsyncPolicy.PER_COMMIT, 14)) {
      open.checkpoint();
      expected = open.stateHashHex();
      whole = Files.readAllBytes(CheckpointWriter.liveFile(ledger));
    }
    List<String> caught = new ArrayList<>();
    // A balance changed by one minor unit, CRC recomputed: the file is internally consistent and
    // only the state hash can see it.
    byte[] money = whole.clone();
    int lastRow = Checkpoint.HEADER_BYTES + WalFormat.intAt(whole, 32) - 8;
    WalFormat.putLong(money, lastRow, WalFormat.longAt(money, lastRow) + 1L);
    caught.add(lie(ledger, money, expected, CheckpointRejection.HASH_MISMATCH));
    // The digest field itself scribbled, CRC recomputed.
    byte[] digest = whole.clone();
    digest[36] ^= 0x01;
    caught.add(lie(ledger, digest, expected, CheckpointRejection.HASH_MISMATCH));
    // A watermark one record short: readable, self-consistent, and not where that record ends.
    byte[] shortLsn = whole.clone();
    WalFormat.putLong(shortLsn, 8, WalFormat.longAt(whole, 8) - 1L);
    caught.add(lie(ledger, shortLsn, expected, CheckpointRejection.NOT_A_RECORD_BOUNDARY));
    // A byte offset one byte past the log: the snapshot is ahead of the file it belongs to.
    byte[] pastEnd = whole.clone();
    WalFormat.putLong(pastEnd, 16, WalFormat.longAt(whole, 16) + 1L);
    caught.add(lie(ledger, pastEnd, expected, CheckpointRejection.STALE_WAL_BYTES));
    // A byte offset one byte short: inside the log, but not where any record ends.
    byte[] midFrame = whole.clone();
    WalFormat.putLong(midFrame, 16, WalFormat.longAt(whole, 16) - 1L);
    caught.add(lie(ledger, midFrame, expected, CheckpointRejection.NOT_A_RECORD_BOUNDARY));
    // A watermark past the end of the log.
    byte[] future = whole.clone();
    WalFormat.putLong(future, 8, WalFormat.longAt(whole, 8) + 50L);
    WalFormat.putLong(future, 16, WalFormat.longAt(whole, 16) + 5_000L);
    caught.add(lie(ledger, future, expected, CheckpointRejection.STALE_WAL_BYTES));
    Files.write(CheckpointWriter.liveFile(ledger), whole);
    return caught.size() + " lying checkpoints discarded: " + String.join(", ", caught);
  }

  /** Writes a doctored file, opens, and returns the cause it was caught by. */
  private static String lie(
      Path ledger, byte[] doctored, String expected, CheckpointRejection want) throws IOException {
    int crcAt = doctored.length - Checkpoint.CRC_BYTES;
    WalFormat.putInt(doctored, crcAt, WalFormat.crc32c(doctored, crcAt));
    Files.write(CheckpointWriter.liveFile(ledger), doctored);
    try (DurableLedger open = DurableLedger.open(ledger, FsyncPolicy.PER_COMMIT)) {
      CheckpointRecovery.Decision decision = open.checkpointDecision();
      if (decision.outcome() != CheckpointRecovery.Outcome.DISCARDED
          || decision.cause() != want) {
        throw new AssertionError("wanted " + want + " and got " + decision);
      }
      if (!expected.equals(open.stateHashHex())) {
        throw new AssertionError("a discarded checkpoint changed the recovered state");
      }
      return String.valueOf(want);
    }
  }

  /** A baseline whose balances do not sum to zero describes a ledger that never existed. */
  private static String impossibleBaseline(Path dir) throws IOException {
    Path ledger = dir.resolve("ledger");
    String expected;
    try (DurableLedger open = build(ledger, FsyncPolicy.PER_COMMIT, 14)) {
      expected = open.stateHashHex();
      List<AccountState> rows = new ArrayList<>();
      for (AccountState row : open.ledger().state()) {
        rows.add(row);
      }
      // Move one minor unit out of thin air: still a well-formed, self-consistent checkpoint.
      rows.set(
          0,
          new AccountState(
              rows.get(0).id(), rows.get(0).kind(), rows.get(0).balance().plus(Money.ofMinor(1L))));
      Checkpoint impossible = Checkpoint.of(
          open.ledger().journalEvents(), 400L, open.ledger().journalEvents(), rows);
      CheckpointWriter.write(ledger, impossible);
    }
    try (DurableLedger open = DurableLedger.open(ledger, FsyncPolicy.PER_COMMIT)) {
      CheckpointRecovery.Decision decision = open.checkpointDecision();
      if (decision.cause() != CheckpointRejection.IMPOSSIBLE_STATE) {
        throw new AssertionError("wanted IMPOSSIBLE_STATE and got " + decision);
      }
      if (!expected.equals(open.stateHashHex())) {
        throw new AssertionError("an impossible baseline changed the recovered state");
      }
      return "a baseline summing to 1 minor unit was refused and the log folded instead";
    }
  }

  /**
   * The case ADR 0003 §2 makes possible and ADR 0004 has to survive: a log cut below a
   * checkpoint's watermark and then regrown past it, so the LSNs come back and the bytes behind
   * them do not.
   */
  private static String noResurrectionThroughACheckpoint(Path dir) throws IOException {
    Path ledger = dir.resolve("ledger");
    byte[] before;
    long watermark;
    try (DurableLedger open = build(ledger, FsyncPolicy.PER_COMMIT, 16)) {
      Checkpoint written = open.checkpoint();
      before = written.encode();
      watermark = written.lastLsn();
    }
    // Cut in the middle of a record below the watermark, so recovery has to repair.
    WalRecovery.Scan scan = WalRecovery.scan(ledger.resolve(Wal.FILE_NAME));
    long cutAt = scan.endOffsets()[5] - 3L;
    WalRecovery.truncateTo(ledger.resolve(Wal.FILE_NAME), cutAt);
    // Regrow past the old watermark with different transactions. The LSNs are reused.
    try (DurableLedger regrown = DurableLedger.open(ledger, FsyncPolicy.PER_COMMIT)) {
      RandomSource rnd = new RandomSource(99L);
      List<AccountId> accounts = new ArrayList<>();
      for (Account account : regrown.ledger().accounts()) {
        accounts.add(account.id());
      }
      for (int i = 0; i < 20; i++) {
        regrown.post(
            Transaction.transfer(
                accounts.get(rnd.nextInt(accounts.size())),
                accounts.get(rnd.nextInt(accounts.size())),
                Money.ofMinor(1L + rnd.nextLong(9_000L))));
      }
      WalRecovery.Scan after = WalRecovery.scan(ledger.resolve(Wal.FILE_NAME));
      if (after.report().lastLsn() <= watermark) {
        throw new AssertionError("the log did not regrow past LSN " + watermark);
      }
      Files.write(CheckpointWriter.liveFile(ledger), before);
    }
    String fromScratch;
    WalRecovery.Scan clean = WalRecovery.scan(ledger.resolve(Wal.FILE_NAME));
    CheckpointWriter.delete(ledger);
    try (DurableLedger open = DurableLedger.open(ledger, FsyncPolicy.PER_COMMIT)) {
      fromScratch = open.stateHashHex();
    }
    Files.write(CheckpointWriter.liveFile(ledger), before);
    try (DurableLedger open = DurableLedger.open(ledger, FsyncPolicy.PER_COMMIT)) {
      CheckpointRecovery.Decision decision = open.checkpointDecision();
      if (decision.cause() != CheckpointRejection.NOT_A_RECORD_BOUNDARY) {
        throw new AssertionError(
            "a stale checkpoint over a regrown log was " + decision.outcome() + " ("
                + decision.cause() + "); it must be discarded, not believed");
      }
      if (!fromScratch.equals(open.stateHashHex())) {
        throw new AssertionError(
            "the stale checkpoint resurrected transactions recovery had cut\n"
                + "  from the log  " + fromScratch + "\n"
                + "  with the snap " + open.stateHashHex());
      }
      return "cut below LSN "
          + watermark
          + ", regrown to LSN "
          + clean.report().lastLsn()
          + ": the stale snapshot was discarded and nothing came back";
    }
  }

  /** A checkpoint must not soften ADR 0003: a damaged head is still a refusal, file untouched. */
  private static String headStillRefuses(Path dir) throws IOException {
    Path ledger = dir.resolve("ledger");
    try (DurableLedger open = build(ledger, FsyncPolicy.PER_COMMIT, 12)) {
      open.checkpoint();
    }
    Path wal = ledger.resolve(Wal.FILE_NAME);
    byte[] original = Files.readAllBytes(wal);
    byte[] damaged = original.clone();
    damaged[4] = (byte) (damaged[4] ^ 0x01);
    Files.write(wal, damaged);
    try (DurableLedger open = DurableLedger.open(ledger, FsyncPolicy.PER_COMMIT)) {
      throw new AssertionError("a damaged segment header opened, checkpoint or not");
    } catch (UnrecoverableLogException refused) {
      if (refused.corruption() != Corruption.BAD_SEGMENT_HEADER) {
        throw new AssertionError("refused for the wrong reason: " + refused.corruption());
      }
    }
    if (!java.util.Arrays.equals(Files.readAllBytes(wal), damaged)) {
      throw new AssertionError("a refusal modified the file it refused");
    }
    return "BAD_SEGMENT_HEADER with a valid checkpoint beside it: refused, file untouched";
  }

  /** Retention, and the fact that a checkpoint is disposable. */
  private static String retentionIsHarmless(Path dir) throws IOException {
    Path ledger = dir.resolve("ledger");
    Path wal = ledger.resolve(Wal.FILE_NAME);
    String expected;
    long sizeBefore;
    try (DurableLedger open = build(ledger, FsyncPolicy.PER_COMMIT, 14)) {
      expected = open.stateHashHex();
      sizeBefore = Files.size(wal);
      open.checkpoint();
      open.checkpoint();
      open.checkpoint();
      if (Files.size(wal) != sizeBefore) {
        throw new AssertionError("checkpointing changed the log's size");
      }
      Files.writeString(CheckpointWriter.scratchFile(ledger, 3L), "an orphan from a crashed write");
      Files.writeString(CheckpointWriter.scratchFile(ledger, 7L), "another one");
    }
    if (CheckpointWriter.collectScratchFiles(ledger) != 2) {
      throw new AssertionError("the two orphan scratch files were not collected");
    }
    try (java.util.stream.Stream<Path> entries = Files.list(ledger)) {
      for (Path entry : entries.toList()) {
        String name = entry.getFileName().toString();
        if (name.endsWith(CheckpointWriter.SCRATCH_SUFFIX)) {
          throw new AssertionError("a scratch file survived collection: " + name);
        }
      }
    }
    // The checkpoint is a cache: removing it costs time and nothing else.
    if (!CheckpointWriter.delete(ledger)) {
      throw new AssertionError("there was no checkpoint to delete");
    }
    try (DurableLedger open = DurableLedger.open(ledger, FsyncPolicy.PER_COMMIT)) {
      if (!expected.equals(open.stateHashHex())) {
        throw new AssertionError("deleting the checkpoint changed the state");
      }
      if (Files.size(wal) != sizeBefore) {
        throw new AssertionError("recovery changed the log's size");
      }
      return "3 checkpoints replaced one file, 2 orphans collected, the log stayed at "
          + sizeBefore + " bytes, and deleting the checkpoint changed nothing";
    }
  }

  /**
   * The soundness condition, from the side that can break it. Under {@code NO_FSYNC} an append is
   * acknowledged when {@code write()} returns, so memory runs ahead of anything the device has; a
   * snapshot of that state would claim a watermark the log cannot honour, and a recovery that
   * believed it would hand back money the log had already lost.
   *
   * <p>The interesting half is what is <em>not</em> refused: {@code Wal.open} forces the prefix it
   * recovered, so after a reopen that prefix genuinely is durable and snapshotting it is sound. The
   * rule is not "NO_FSYNC never checkpoints" — it is "never past the last completed force".
   */
  private static String noFsyncNeverSnapshotsAhead(Path dir) throws IOException {
    Path ledger = dir.resolve("ledger");
    long unforced;
    try (DurableLedger open = build(ledger, FsyncPolicy.NO_FSYNC, 12)) {
      unforced = open.ledger().journalEvents();
      if (open.lastAck() != null && open.lastAck().forced()) {
        throw new AssertionError("a NO_FSYNC ack claims to have forced");
      }
      try {
        open.checkpoint();
        throw new AssertionError("NO_FSYNC snapshotted records the log has not made durable");
      } catch (IOException refused) {
        if (!refused.getMessage().contains("durable")) {
          throw new AssertionError("refused for an unexpected reason: " + refused.getMessage());
        }
      }
      if (Files.exists(CheckpointWriter.liveFile(ledger))) {
        throw new AssertionError("the refused snapshot wrote a file anyway");
      }
    }
    long watermark;
    try (DurableLedger reopened = DurableLedger.open(ledger, FsyncPolicy.NO_FSYNC)) {
      // Wal.open forced the recovered prefix, so this checkpoint is sound and gets written.
      if (!Files.exists(CheckpointWriter.liveFile(ledger))) {
        throw new AssertionError(
            "a reopen whose prefix open() had forced wrote no checkpoint");
      }
      watermark = reopened.checkpointDecision().used()
          ? reopened.checkpointDecision().checkpoint().lastLsn()
          : -1L;
      if (watermark != -1L) {
        throw new AssertionError("the checkpoint written at open was then used by it");
      }
      try (DurableLedger again = DurableLedger.open(ledger, FsyncPolicy.NO_FSYNC)) {
        if (!again.checkpointDecision().used()) {
          throw new AssertionError(
              "the checkpoint the forced open wrote was not used: "
                  + again.checkpointDecision());
        }
        watermark = again.checkpointDecision().checkpoint().lastLsn();
      }
    }
    WalRecovery.Scan scan = WalRecovery.scan(ledger.resolve(Wal.FILE_NAME));
    if (watermark != scan.report().lastLsn()) {
      throw new AssertionError(
          "the checkpoint covers LSN " + watermark + " and the log ends at "
              + scan.report().lastLsn());
    }
    return unforced
        + " unforced records refused a snapshot; after a reopen, whose prefix open() forces,"
        + " a checkpoint at LSN " + watermark + " is sound and is used";
  }

  // --- fixtures ---------------------------------------------------------------------------

  /** Builds a ledger with a deterministic workload and returns it open. */
  private static DurableLedger build(Path ledger, FsyncPolicy policy, int ops) throws IOException {
    return build(ledger, policy, ops, BASE_SEED);
  }

  private static DurableLedger build(Path ledger, FsyncPolicy policy, int ops, long seed)
      throws IOException {
    DurableLedger open = DurableLedger.open(ledger, policy);
    AccountKind[] kinds = AccountKind.values();
    for (int i = 0; i < 4; i++) {
      open.openAccount(new AccountId("acct-" + i), kinds[i % kinds.length]);
    }
    grow(open, ops, seed);
    return open;
  }

  /** Posts {@code ops} transfers between the accounts an open ledger already has. */
  private static void grow(DurableLedger open, int ops, long seed) throws IOException {
    RandomSource rnd = new RandomSource(seed);
    List<AccountId> accounts = new ArrayList<>();
    for (Account account : open.ledger().accounts()) {
      accounts.add(account.id());
    }
    for (int op = 0; op < ops; op++) {
      open.post(
          Transaction.transfer(
              accounts.get(rnd.nextInt(accounts.size())),
              accounts.get(rnd.nextInt(accounts.size())),
              Money.ofMinor(1L + rnd.nextLong(100_000L))));
    }
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    List<Path> paths = new ArrayList<>();
    try (java.util.stream.Stream<Path> walked = Files.walk(root)) {
      walked.sorted(Comparator.reverseOrder()).forEach(paths::add);
    }
    for (Path path : paths) {
      Files.deleteIfExists(path);
    }
  }
}
