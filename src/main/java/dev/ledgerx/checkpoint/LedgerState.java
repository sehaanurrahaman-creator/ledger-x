package dev.ledgerx.checkpoint;

import dev.ledgerx.domain.Account;
import dev.ledgerx.domain.AccountId;
import dev.ledgerx.domain.AccountKind;
import dev.ledgerx.domain.IdempotencyKey;
import dev.ledgerx.domain.InMemoryLedger;
import dev.ledgerx.domain.KeyBinding;
import dev.ledgerx.domain.MerchantId;
import dev.ledgerx.domain.Money;
import dev.ledgerx.domain.RequestFingerprint;
import dev.ledgerx.wal.WalFormat;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The full materialized state of a ledger, in <strong>one canonical byte order</strong>, and the
 * hash of it: ADR 0004 §3, extended by ADR 0005 §6.
 *
 * <p>This is the answer to "what does <em>byte-identical</em> mean here". State is
 * {@code (accounts in opening order, each with its balance, the key bindings in binding order,
 * and the position in the log that produced them)}, serialized once, in one place, with no
 * options, no maps in the path and nothing that reads a clock or an identity hash.
 * {@link #stateHash()} hashes exactly those bytes and nothing else, so two runs agree on the hash
 * when and only when they agree on those bytes.
 *
 * <pre>{@code
 * u8  canonical version (2)
 * u64 lastLsn                     the LSN of the last journal record the state includes, 0 if none
 * u32 accountCount
 * accountCount x {
 *   u8  kindNameLength | kindName bytes (ASCII, AccountKind.name())
 *   u8  idLength       | id bytes (7-bit ASCII, AccountId's alphabet)
 *   i64 balanceMinorUnits (signed, debit-positive)
 * }
 * u32 bindingCount
 * bindingCount x {                in binding order — the order their records appear in the log
 *   u8  merchantLength | merchant bytes (7-bit ASCII)
 *   u8  keyLength       | key bytes (7-bit ASCII)
 *   u8[32] requestFingerprint
 *   u64 responseLsn               the record that made this binding, and where its response lives
 * }
 * }</pre>
 *
 * <p><strong>Why the account's kind is a name here and a number in the WAL.</strong> ADR 0003's
 * payload writes {@code AccountKind.ordinal()} because the log's reader is versioned with its
 * writer and a re-ordering of the enum is already a format change there. A state hash is a
 * different animal: it is the number a human compares between two builds and a value an operator
 * writes down, so it says {@code LIABILITY} rather than {@code 1} and it survives a constant being
 * moved. The two encodings never have to agree, because a checkpoint's bytes are never read as a
 * WAL record and vice versa; what they do have to agree on is the <em>ordering</em> rule below.
 *
 * <p><strong>One list, one order, and the order is the log's.</strong> Accounts are emitted in
 * opening order — the order their {@code ACCOUNT_OPENED} records appear in the log, which is the
 * order a {@code LinkedHashMap} built by a fold holds them in and the order a checkpoint restored
 * from a previous checkpoint preserves. Bindings follow the same rule with their own records, in
 * the section after the accounts. Balances are not a second section: each account carries the
 * balance it had at the watermark, so the canonical form contains no cross-reference between two
 * collections that could disagree, and no iteration over a map. This is the rule that makes
 * {@code HashMap}, {@code Set}, {@code Stream.parallel} and every other order-varying construct
 * banned from the path (ADR 0004 §4) — because the bytes are pinned by a test, not by review.
 *
 * <p><strong>What is not in it.</strong> No event list: the log <em>is</em> the history, and a
 * checkpoint that carried the history would be a copy of the log rather than a cache of its fold
 * (ADR 0004 §6). No byte offsets: {@code lastLsn} is the money position, and the coverage offsets
 * live in the checkpoint's header where they belong to a file rather than to a ledger. No
 * timestamp, no random, no version of the JVM, no wall clock — and the binding rows carry no
 * capture instant, which is the one rule ADR 0004 §9 handed down that this record had to argue
 * its way around: the hash says <em>what</em> was bound, never <em>when</em>, so two runs of one
 * history hash the same even though their clocks wrote different instants into the log. The
 * instants ride in the checkpoint's timing section, beside the hashed state, paired by count and
 * covered by the file's CRC (ADR 0005 §6).
 *
 * <p>The {@code lastLsn} in the state is deliberately <em>inside</em> the hash. Two ledgers with
 * the same balances at different points in their lives are not the same state, and the position is
 * what lets "checkpoint + tail" and "replay from scratch" be compared as one equality instead of
 * two checks that could each pass while disagreeing.
 *
 * @param accounts the accounts, in opening order; never null, never empty-named
 * @param balances one balance per account, in the same order — never a map
 * @param bindings the key bindings, in binding (log) order — the order their records appear; the
 *     identity rows only, with no capture instant, which lives in the timing section beside the
 *     hashed state rather than inside it (ADR 0005 §6)
 * @param lastLsn the LSN of the last journal record whose effect this state includes, 0 if none
 */
public record LedgerState(
    List<Account> accounts, List<Money> balances, List<KeyBinding> bindings, long lastLsn) {

  public LedgerState {
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(balances, "balances");
    Objects.requireNonNull(bindings, "bindings");
    accounts = List.copyOf(accounts);
    balances = List.copyOf(balances);
    bindings = List.copyOf(bindings);
    if (accounts.size() != balances.size()) {
      throw new IllegalArgumentException(
          accounts.size() + " accounts and " + balances.size() + " balances is not a state");
    }
    if (lastLsn < 0L) {
      throw new IllegalArgumentException("lastLsn is a position and cannot be " + lastLsn);
    }
  }

  /**
   * Captures a ledger as state at a watermark.
   *
   * <p>{@code lastLsn} is not read from the ledger because the domain does not know about LSNs —
   * deliberately, per ADR 0002 — so the caller that owns the log supplies the position. That is
   * the one piece of this record that is not derived from the ledger, and it is why every caller
   * of this method is a class that holds both. The bindings <em>are</em> read from the ledger,
   * identity rows in log order — the index's own order, which the audit holds to the log.
   */
  public static LedgerState of(InMemoryLedger ledger, long lastLsn) {
    Objects.requireNonNull(ledger, "ledger");
    List<Account> accounts = ledger.accounts();
    List<Money> balances = new ArrayList<>(accounts.size());
    for (Account account : accounts) {
      balances.add(ledger.balanceOf(account.id()));
    }
    return new LedgerState(accounts, balances, ledger.bindings(), lastLsn);
  }

  /** Accounts in the state, which is also the number of balances. */
  public int accountCount() {
    return accounts.size();
  }

  /** Key bindings in the state — which is also the number of instants the timing section
   * holds.  */
  public int bindingCount() {
    return bindings.size();
  }

  /**
   * The canonical bytes: the one serialization, in the order above. Two equal states produce byte
   * arrays that are equal, and two unequal states produce byte arrays that differ — which is the
   * whole requirement, and the reason nothing in this method consults a map, a locale, a default
   * charset or a clock.
   */
  public byte[] canonicalBytes() {
    int size = CheckpointFormat.STATE_PREFIX_BYTES;
    byte[][] kinds = new byte[accounts.size()][];
    byte[][] ids = new byte[accounts.size()][];
    for (int i = 0; i < accounts.size(); i++) {
      kinds[i] = ascii(accounts.get(i).kind().name());
      ids[i] = ascii(accounts.get(i).id().value());
      size += CheckpointFormat.ACCOUNT_FIXED_BYTES + kinds[i].length + ids[i].length;
    }
    byte[][] merchants = new byte[bindings.size()][];
    byte[][] keys = new byte[bindings.size()][];
    size += CheckpointFormat.BINDING_COUNT_BYTES;
    for (int i = 0; i < bindings.size(); i++) {
      merchants[i] = ascii(bindings.get(i).merchant().value());
      keys[i] = ascii(bindings.get(i).key().value());
      size += CheckpointFormat.BINDING_FIXED_BYTES + merchants[i].length + keys[i].length;
    }
    byte[] bytes = new byte[size];
    bytes[0] = CheckpointFormat.CANONICAL_VERSION;
    WalFormat.putLong(bytes, 1, lastLsn);
    WalFormat.putInt(bytes, 9, accounts.size());
    int at = CheckpointFormat.STATE_PREFIX_BYTES;
    for (int i = 0; i < accounts.size(); i++) {
      bytes[at++] = (byte) kinds[i].length;
      System.arraycopy(kinds[i], 0, bytes, at, kinds[i].length);
      at += kinds[i].length;
      bytes[at++] = (byte) ids[i].length;
      System.arraycopy(ids[i], 0, bytes, at, ids[i].length);
      at += ids[i].length;
      WalFormat.putLong(bytes, at, balances.get(i).minorUnits());
      at += 8;
    }
    WalFormat.putInt(bytes, at, bindings.size());
    at += CheckpointFormat.BINDING_COUNT_BYTES;
    for (int i = 0; i < bindings.size(); i++) {
      KeyBinding binding = bindings.get(i);
      bytes[at++] = (byte) merchants[i].length;
      System.arraycopy(merchants[i], 0, bytes, at, merchants[i].length);
      at += merchants[i].length;
      bytes[at++] = (byte) keys[i].length;
      System.arraycopy(keys[i], 0, bytes, at, keys[i].length);
      at += keys[i].length;
      System.arraycopy(
          binding.fingerprint().bytesUnsafe(), 0, bytes, at, RequestFingerprint.BYTES);
      at += RequestFingerprint.BYTES;
      WalFormat.putLong(bytes, at, binding.responseLsn());
      at += 8;
    }
    return bytes;
  }

  /** SHA-256 over {@link #canonicalBytes()}, as 64 lowercase hex digits. */
  public String stateHash() {
    return Sha256.hex(stateHashBytes());
  }

  /** The same digest, undecoded, for the file trailer that stores it. */
  public byte[] stateHashBytes() {
    return Sha256.of(canonicalBytes());
  }

  /**
   * Seeds a fresh ledger with this state: accounts, kinds, order and balances, and nothing else —
   * bindings deliberately excepted.
   *
   * <p>The events that produced these balances are in the log <em>before</em> the watermark and are
   * not in this ledger's journal, which is the whole point of a checkpoint — and it is why the
   * domain gained a base-balance notion for the fold to start from (ADR 0004 §9). The returned
   * ledger is a valid {@link InMemoryLedger} in the sense of {@code audit()}: its index equals its
   * base plus a fold of whatever events are applied to it next.
   *
   * <p>Bindings are the <em>caller's</em> to restore, one {@code InMemoryLedger.restoreBinding}
   * per row, because a binding's two other halves — its capture instant and its stored response
   * —
   * live outside this record: the instant in the checkpoint's timing section, the response in the
   * log record the row's {@code responseLsn} names. A caller that holds all three is a caller that
   * holds the log, which is the only caller that reaches this method.
   */
  public InMemoryLedger restore() {
    InMemoryLedger ledger = new InMemoryLedger();
    for (int i = 0; i < accounts.size(); i++) {
      ledger.restore(accounts.get(i), balances.get(i));
    }
    return ledger;
  }

  /**
   * Reads a canonical state section, bounded by the frame it was handed: a section that runs out
   * mid-account is a refusal, never a read past the buffer (the same rule
   * {@code EventCodec.decode} follows).
   *
   * @param file the whole checkpoint file
   * @param offset where the section starts
   * @param length how long the header says the section is
   * @throws CorruptCheckpointException if the bytes are not a canonical state of this version
   */
  public static LedgerState decode(byte[] file, int offset, int length)
      throws CorruptCheckpointException {
    if (length < CheckpointFormat.STATE_PREFIX_BYTES) {
      throw malformed("a state section is at least "
          + CheckpointFormat.STATE_PREFIX_BYTES + " bytes and this one is " + length);
    }
    int at = offset;
    int end = offset + length;
    int version = file[at++] & 0xFF;
    if (version != CheckpointFormat.CANONICAL_VERSION) {
      throw malformed("canonical version " + version + ", this build writes "
          + CheckpointFormat.CANONICAL_VERSION + (version == 1
              ? " — a version-1 state predates key bindings and is a cache the log can"
                  + " still reproduce, so it is skipped rather than upgraded"
              : ""));
    }
    long lastLsn = WalFormat.longAt(file, at);
    at += 8;
    long declared = Integer.toUnsignedLong(WalFormat.intAt(file, at));
    at += 4;
    if (declared > Integer.MAX_VALUE) {
      throw malformed("an account count of " + declared + " is not a count");
    }
    int count = (int) declared;
    if (count > (end - at) / CheckpointFormat.ACCOUNT_FIXED_BYTES) {
      throw malformed(count + " accounts do not fit in " + (end - at) + " bytes");
    }
    List<Account> accounts = new ArrayList<>(count);
    List<Money> balances = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      if (at >= end) {
        throw malformed("account " + i + " of " + count + " is not there");
      }
      int kindLength = file[at++] & 0xFF;
      if (at + kindLength > end) {
        throw malformed("account " + i + "'s kind runs past the end of the section");
      }
      String kindName = new String(file, at, kindLength, StandardCharsets.US_ASCII);
      at += kindLength;
      if (at >= end) {
        throw malformed("account " + i + " has no id");
      }
      int idLength = file[at++] & 0xFF;
      if (at + idLength + 8 > end) {
        throw malformed("account " + i + "'s id or balance runs past the end of the section");
      }
      String idText = new String(file, at, idLength, StandardCharsets.US_ASCII);
      at += idLength;
      long minorUnits = WalFormat.longAt(file, at);
      at += 8;
      AccountKind kind = kindOf(kindName, i);
      AccountId id;
      try {
        id = AccountId.of(idText);
      } catch (IllegalArgumentException outsideTheAlphabet) {
        throw malformed("account " + i + "'s id is outside the alphabet: "
            + outsideTheAlphabet.getMessage());
      }
      accounts.add(new Account(id, kind));
      balances.add(Money.ofMinor(minorUnits));
    }
    List<KeyBinding> bindings = decodeBindings(file, at, end);
    return new LedgerState(accounts, balances, bindings, lastLsn);
  }

  /**
   * The binding rows after the accounts: a count, then that many bounded rows, consumed exactly to
   * the section's end. The same rules as the account loop — a row that runs out mid-field is a
   * refusal, a leftover byte is a refusal — with one addition the accounts do not need: a
   * duplicate {@code (merchant, key)} inside one state section is a section that no fold of any
   * log could have produced, because the log's own fold would have refused the second record.
   */
  private static List<KeyBinding> decodeBindings(byte[] file, int at, int end)
      throws CorruptCheckpointException {
    if (end - at < CheckpointFormat.BINDING_COUNT_BYTES) {
      throw malformed("a state section of this version carries a binding count, and "
          + (end - at) + " bytes are left after the accounts");
    }
    long declared = Integer.toUnsignedLong(WalFormat.intAt(file, at));
    at += CheckpointFormat.BINDING_COUNT_BYTES;
    if (declared > Integer.MAX_VALUE) {
      throw malformed("a binding count of " + declared + " is not a count");
    }
    int count = (int) declared;
    if (count > (end - at) / CheckpointFormat.BINDING_FIXED_BYTES) {
      throw malformed(count + " bindings do not fit in " + (end - at) + " bytes");
    }
    List<KeyBinding> bindings = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      if (at >= end) {
        throw malformed("binding " + i + " of " + count + " is not there");
      }
      int merchantLength = file[at++] & 0xFF;
      if (at + merchantLength > end) {
        throw malformed("binding " + i + "'s merchant runs past the end of the section");
      }
      String merchantText = new String(file, at, merchantLength, StandardCharsets.US_ASCII);
      at += merchantLength;
      if (at >= end) {
        throw malformed("binding " + i + " has no key");
      }
      int keyLength = file[at++] & 0xFF;
      if (at + keyLength + RequestFingerprint.BYTES + 8 > end) {
        throw malformed(
            "binding " + i + "'s key, fingerprint or responseLsn runs past the section");
      }
      String keyText = new String(file, at, keyLength, StandardCharsets.US_ASCII);
      at += keyLength;
      RequestFingerprint fingerprint =
          RequestFingerprint.of(
              Arrays.copyOfRange(file, at, at + RequestFingerprint.BYTES));
      at += RequestFingerprint.BYTES;
      long responseLsn = WalFormat.longAt(file, at);
      at += 8;
      KeyBinding binding;
      try {
        binding =
            new KeyBinding(
                MerchantId.of(merchantText), IdempotencyKey.of(keyText), fingerprint,
                responseLsn);
      } catch (IllegalArgumentException outsideTheRules) {
        throw malformed("binding " + i + " is not a binding this domain would hold: "
            + outsideTheRules.getMessage());
      }
      for (KeyBinding earlier : bindings) {
        if (earlier.merchant().equals(binding.merchant())
            && earlier.key().equals(binding.key())) {
          throw malformed(
              "the state binds (" + binding.merchant() + ", " + binding.key() + ") twice");
        }
      }
      bindings.add(binding);
    }
    if (at != end) {
      throw malformed((end - at) + " bytes are left over after " + count + " bindings");
    }
    return bindings;
  }

  private static AccountKind kindOf(String name, int index) throws CorruptCheckpointException {
    for (AccountKind kind : AccountKind.values()) {
      if (kind.name().equals(name)) {
        return kind;
      }
    }
    throw malformed("account " + index + " has kind '" + name + "', which is not a kind");
  }

  private static byte[] ascii(String text) {
    byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
    if (bytes.length > 255) {
      throw new IllegalArgumentException("'" + text + "' does not fit a one-byte length");
    }
    return bytes;
  }

  private static CorruptCheckpointException malformed(String detail) {
    return new CorruptCheckpointException(CheckpointRefusal.STATE_MALFORMED, detail);
  }

  /** A one-line summary for a log: the watermark is what identifies a checkpoint in practice. */
  @Override
  public String toString() {
    return "LedgerState["
        + accounts.size()
        + " accounts, "
        + bindings.size()
        + " bindings, lsn "
        + lastLsn
        + ", hash "
        + stateHash().substring(0, 16)
        + "...]";
  }
}
