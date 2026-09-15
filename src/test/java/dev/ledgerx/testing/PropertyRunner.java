package dev.ledgerx.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Runs random cases until one fails, shrinks the failure, and reports it reproducibly.
 *
 * <p>This is the property-testing toolkit ADR 0001's dependency rule leaves room for: no
 * JUnit, no jqwik, no registry, about a hundred lines. What it gives up is written in ADR
 * 0002 §9 and is not nothing — there is no curated edge-case database, no exhaustive
 * combinatorial mode, and the shrinker finds a local minimum rather than the smallest
 * possible case. What it keeps is the two things that make a property suite worth running in
 * CI at all: every case is reproducible from a printed seed, and a failure arrives already
 * shrunk to something a human can read.
 *
 * <p>The runner is single-threaded and its cases must be re-runnable: shrinking re-executes
 * the body against fresh state, so a body that mutates anything shared will report a shrink
 * that cannot be reproduced. {@code OpApplier} builds a new ledger per case for exactly this
 * reason.
 */
public final class PropertyRunner {

  /** Produces one random case from a seeded source. */
  public interface Generator<T> {
    T next(RandomSource rnd);
  }

  /** Candidate smaller versions of a failing case, best first. */
  public interface Shrinker<T> {
    List<T> smaller(T failing);
  }

  /**
   * Asserts a property of one case, throwing anything on breach. Returns a short detail
   * string for the summary line — usually a cumulative count, so the last case's return
   * value describes the whole campaign.
   */
  public interface Body<T> {
    String run(T value) throws Exception;
  }

  /** Never shrink forever: a shrinker that always finds a smaller candidate is a bug. */
  private static final int MAX_SHRINK_STEPS = 4_000;

  /** How much of a shrunk case to print. Long enough to read, short enough to stay in a log. */
  private static final int MAX_REPORTED_CASE = 1_200;

  private final int trials;
  private final long baseSeed;
  private final List<String> failures = new ArrayList<>();
  private final Supplier<String> campaignDetail;

  private int checksRun;
  private long casesRun;

  public PropertyRunner(int trials, long baseSeed, Supplier<String> campaignDetail) {
    this.trials = trials;
    this.baseSeed = baseSeed;
    this.campaignDetail = campaignDetail;
  }

  public int trials() {
    return trials;
  }

  public long baseSeed() {
    return baseSeed;
  }

  public long casesRun() {
    return casesRun;
  }

  public boolean passed() {
    return failures.isEmpty();
  }

  public int checksRun() {
    return checksRun;
  }

  public List<String> failures() {
    return List.copyOf(failures);
  }

  /**
   * Runs {@code trials} cases of one property. The first failure stops that property — a
   * second failure of the same property is almost always the same bug wearing a different
   * seed, and reporting both costs the reader time.
   */
  public <T> void check(
      String name, Generator<T> generator, Shrinker<T> shrinker, Body<T> body) {
    checksRun++;
    String detail = "";
    for (int trial = 0; trial < trials; trial++) {
      long seed = baseSeed + trial;
      T value = generator.next(new RandomSource(seed));
      try {
        detail = body.run(value);
        casesRun++;
      } catch (Throwable failed) {
        Shrunk<T> minimal = shrink(value, shrinker, body);
        String report =
            name
                + " — seed "
                + seed
                + " (reproduce: -Dledgerx.property.seed="
                + seed
                + ")\n"
                + "       minimal failing case: "
                + truncate(String.valueOf(minimal.value()))
                + "\n"
                + "       "
                + describe(minimal.failure());
        failures.add(report);
        System.out.println("  FAIL " + report);
        return;
      }
    }
    System.out.println(
        "  ok   " + name + " [" + trials + " cases, seed base " + baseSeed
            + (detail.isEmpty() ? "" : ", " + detail) + "]");
  }

  /** A property with no randomness to shrink: one case, asserted once. */
  public void checkOnce(String name, ThrowingRunnable body) {
    checksRun++;
    try {
      String detail = body.run();
      casesRun++;
      System.out.println(
          "  ok   " + name + (detail == null || detail.isEmpty() ? "" : " [" + detail + "]"));
    } catch (Throwable failed) {
      String report = name + " — " + describe(failed);
      failures.add(report);
      System.out.println("  FAIL " + report);
    }
  }

  /** A targeted assertion that is not a random case at all, but belongs in the same report. */
  public interface ThrowingRunnable {
    String run() throws Exception;
  }

  private record Shrunk<T>(T value, Throwable failure) {}

  private <T> Shrunk<T> shrink(T value, Shrinker<T> shrinker, Body<T> body) {
    T best = value;
    Throwable failure = null;
    for (int step = 0; step < MAX_SHRINK_STEPS; step++) {
      T next = null;
      for (T candidate : shrinker.smaller(best)) {
        try {
          body.run(candidate);
        } catch (Throwable stillFails) {
          next = candidate;
          failure = stillFails;
          break;
        }
      }
      if (next == null) {
        break;
      }
      best = next;
    }
    if (failure == null) {
      // Every shrink candidate passed, so the original failure message is the one to show.
      try {
        body.run(best);
        failure = new AssertionError("the failing case passed on re-run — the body is not pure");
      } catch (Throwable original) {
        failure = original;
      }
    }
    return new Shrunk<>(best, failure);
  }

  /**
   * The failure plus the top of its stack. A property failure names a seed and a minimal case,
   * which says what broke but not where; four frames is enough to find the line without
   * turning the report into a dump.
   */
  private static String describe(Throwable failure) {
    StringBuilder text = new StringBuilder(String.valueOf(failure));
    StackTraceElement[] frames = failure.getStackTrace();
    for (int i = 0; i < frames.length && i < 4; i++) {
      text.append("\n         at ").append(frames[i]);
    }
    return text.toString();
  }

  private static String truncate(String text) {
    if (text.length() <= MAX_REPORTED_CASE) {
      return text;
    }
    return text.substring(0, MAX_REPORTED_CASE) + " …(" + text.length() + " characters)";
  }

  /** The summary line the build publishes as a run annotation. */
  public String verdict() {
    if (passed()) {
      return "PASS "
          + checksRun
          + "/"
          + checksRun
          + " property checks — "
          + casesRun
          + " random cases, "
          + campaignDetail.get();
    }
    return "FAIL " + failures.size() + " of " + checksRun + " property checks";
  }
}
