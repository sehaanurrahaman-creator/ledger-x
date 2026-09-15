package dev.ledgerx.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Shrinking: turning "some case failed" into "the smallest case I could find that fails".
 *
 * <p>A random campaign over 200-operation sequences that reports only a seed is nearly
 * useless — the interesting bug is buried under 190 operations that did not matter. These
 * are the candidate steps {@link PropertyRunner} walks, in the order it walks them, and they
 * are the part of a property-testing toolkit that is worth hand-rolling carefully because a
 * shrinker that produces a non-reproducing candidate wastes every run it is used in.
 *
 * <p>Delta debugging, not exhaustive minimization: the runner accepts the first candidate
 * that still fails and repeats, which terminates when no single step helps. That finds a
 * local minimum, not the global smallest case, and this file says so rather than implying a
 * guarantee it does not have.
 */
public final class Shrink {

  private Shrink() {}

  /** Drop one element, walking front to back: {@code n} candidates for {@code n} elements. */
  public static <T> List<List<T>> withoutOne(List<T> items) {
    List<List<T>> candidates = new ArrayList<>(items.size());
    for (int i = 0; i < items.size(); i++) {
      List<T> shorter = new ArrayList<>(items.size() - 1);
      shorter.addAll(items.subList(0, i));
      shorter.addAll(items.subList(i + 1, items.size()));
      candidates.add(shorter);
    }
    return candidates;
  }

  /** Keep the first half, then the second half: the coarse step before dropping singles. */
  public static <T> List<List<T>> halves(List<T> items) {
    List<List<T>> candidates = new ArrayList<>(2);
    int middle = items.size() / 2;
    if (middle > 0 && middle < items.size()) {
      candidates.add(new ArrayList<>(items.subList(0, middle)));
      candidates.add(new ArrayList<>(items.subList(middle, items.size())));
    }
    return candidates;
  }

  /** Replace one element by a smaller version of itself, at every position. */
  public static <T> List<List<T>> oneShrunk(List<T> items, Function<T, List<T>> shrinker) {
    List<List<T>> candidates = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      for (T smaller : shrinker.apply(items.get(i))) {
        List<T> variant = new ArrayList<>(items);
        variant.set(i, smaller);
        candidates.add(variant);
      }
    }
    return candidates;
  }

  /**
   * Numbers towards zero, then towards the small values that make arithmetic edge cases
   * legible: {@code 0}, {@code 1}, {@code -1}, halves, and the two extremes of the range. A
   * failing case with amounts in the millions is nearly always more readable as the same
   * case with amounts of 1, and this is what makes that so.
   */
  public static List<Long> towardsZero(long value) {
    List<Long> candidates = new ArrayList<>();
    if (value != 0L) {
      candidates.add(0L);
    }
    long magnitude = magnitude(value);
    for (long small : new long[] {1L, -1L, 2L, -2L}) {
      if (small != value && Math.abs(small) < magnitude) {
        candidates.add(small);
      }
    }
    long half = value / 2L;
    if (half != value && half != 0L) {
      candidates.add(half);
    }
    if (value > 0L && value != Long.MAX_VALUE) {
      candidates.add(value - 1L);
    }
    if (value < 0L && value != Long.MIN_VALUE) {
      candidates.add(value + 1L);
    }
    return candidates;
  }

  /** {@code Math.abs} lies about {@code Long.MIN_VALUE}, and that is exactly an edge case. */
  private static long magnitude(long value) {
    return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
  }
}
