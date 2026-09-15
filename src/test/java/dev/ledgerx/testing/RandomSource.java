package dev.ledgerx.testing;

/**
 * A reproducible source of randomness, and the reason a failing property can always be
 * re-run.
 *
 * <p>The generator is SplitMix64, implemented here rather than taken from
 * {@link java.util.SplittableRandom} or {@link java.util.Random} on purpose: a property
 * suite whose failure message says "reproduce with seed 1234" is only telling the truth if
 * seed 1234 still produces that case after a JDK upgrade. The JDK's generators are not
 * contractually frozen across releases, and a property that stops reproducing is worse than
 * one that was never found. Twelve lines of specified arithmetic buys that guarantee, and
 * this class has no other job.
 *
 * <p>Bounded draws are modulo a 63-bit sample, so they are very slightly biased towards
 * small results: the bias is at most {@code bound / 2^63}, which for every bound this suite
 * uses (all below 2^20) is below 2^-43. Unbiased rejection sampling was considered and
 * dropped — uniformity of the generator is not the property under test, and a hand-rolled
 * Lemire multiply-shift is a place for a subtle bug to hide in the code that is supposed to
 * be obviously right.
 */
public final class RandomSource {

  /** SplitMix64's increment: the 64-bit golden ratio. */
  private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

  private static final long MIX_A = 0xBF58476D1CE4E5B9L;
  private static final long MIX_B = 0x94D049BB133111EBL;

  private final long seed;
  private long state;
  private long draws;

  public RandomSource(long seed) {
    this.seed = seed;
    this.state = seed;
  }

  public long seed() {
    return seed;
  }

  /** How many draws this source has served, for a failure report that can be replayed. */
  public long draws() {
    return draws;
  }

  public long nextLong() {
    draws++;
    state += GOLDEN_GAMMA;
    long mixed = state;
    mixed = (mixed ^ (mixed >>> 30)) * MIX_A;
    mixed = (mixed ^ (mixed >>> 27)) * MIX_B;
    return mixed ^ (mixed >>> 31);
  }

  /** Uniform over {@code [0, bound)}, modulo the negligible bias documented above. */
  public int nextInt(int bound) {
    if (bound <= 0) {
      throw new IllegalArgumentException("bound must be positive: " + bound);
    }
    return (int) ((nextLong() >>> 1) % bound);
  }

  /** Uniform over {@code [origin, bound)}. */
  public int nextInt(int origin, int bound) {
    if (origin >= bound) {
      throw new IllegalArgumentException("empty range: [" + origin + ", " + bound + ")");
    }
    return origin + nextInt(bound - origin);
  }

  public long nextLong(long bound) {
    if (bound <= 0L) {
      throw new IllegalArgumentException("bound must be positive: " + bound);
    }
    return (nextLong() >>> 1) % bound;
  }

  public long nextLong(long origin, long bound) {
    if (origin >= bound) {
      throw new IllegalArgumentException("empty range: [" + origin + ", " + bound + ")");
    }
    return origin + nextLong(bound - origin);
  }

  public boolean nextBoolean() {
    return (nextLong() & 1L) != 0L;
  }

  /** True with the given probability in percent. */
  public boolean chance(int percent) {
    return nextInt(100) < percent;
  }

  public <T> T pick(T[] choices) {
    if (choices.length == 0) {
      throw new IllegalArgumentException("nothing to pick from");
    }
    return choices[nextInt(choices.length)];
  }

  /**
   * A child source, deterministically derived from this one's current state. Two generators
   * that need to be independent of each other — and of everything drawn so far — each take a
   * fork rather than sharing this source and coupling their sequences.
   */
  public RandomSource fork() {
    return new RandomSource(nextLong());
  }
}
