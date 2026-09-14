package dev.ledgerx.substrate;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The substrate contract: the platform facts ADR 0001 rests on, asserted on every build.
 *
 * <p>These are not ledger tests. Each one pins a claim the ADR makes about Java 21, so
 * that a future JDK upgrade or a changed filesystem that quietly breaks one of them fails
 * the build instead of corrupting a ledger.
 *
 * <p>Run by {@code ./build.sh}; exits non-zero if any check fails.
 */
public final class SubstrateContract {

  private static final int VIRTUAL_THREADS = 10_000;
  private static final int PLATFORM_THREAD_CEILING = 1_000;
  private static final int CONTESTED_WRITERS = 64;
  private static final int WRITES_PER_WRITER = 50;
  private static final int AWAIT_SECONDS = 60;

  private static final List<String> failures = new ArrayList<>();
  private static int checksRun;

  private SubstrateContract() {}

  /** A check returns a short detail string for the log, or throws to fail. */
  private interface Check {
    String run() throws Exception;
  }

  public static void main(String[] args) {
    System.out.println("ledger-x substrate contract on " + Runtime.version());
    check("runtime is Java 21 or newer", SubstrateContract::runtimeIsAtLeast21);
    check(
        VIRTUAL_THREADS + " parked virtual threads cost a handful of platform threads",
        SubstrateContract::virtualThreadsAreCheap);
    check(
        "fdatasync and fsync are both reachable and the bytes survive",
        SubstrateContract::bothForceVariantsWork);
    check("the WAL directory itself can be fsynced", SubstrateContract::directoryFsyncIsAvailable);
    check(
        "a per-account ReentrantLock serializes contending virtual threads",
        SubstrateContract::perAccountLockSerializes);

    System.out.println();
    if (failures.isEmpty()) {
      System.out.println("PASS " + checksRun + "/" + checksRun + " substrate checks");
      return;
    }
    System.out.println("FAIL " + failures.size() + " of " + checksRun + " substrate checks");
    for (String failure : failures) {
      System.out.println("  - " + failure);
    }
    System.exit(1);
  }

  private static void check(String name, Check body) {
    checksRun++;
    try {
      String detail = body.run();
      System.out.println("  ok   " + name + " [" + detail + "]");
    } catch (Throwable failed) {
      failures.add(name + " — " + failed);
      System.out.println("  FAIL " + name + " — " + failed);
    }
  }

  private static String runtimeIsAtLeast21() {
    int feature = Runtime.version().feature();
    if (feature < 21) {
      throw new AssertionError("feature version is " + feature + ", virtual threads need 21");
    }
    return "Runtime.version()=" + Runtime.version();
  }

  private static String virtualThreadsAreCheap() throws InterruptedException {
    CountDownLatch allParked = new CountDownLatch(VIRTUAL_THREADS);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger sawVirtual = new AtomicInteger();
    List<Thread> threads = new ArrayList<>(VIRTUAL_THREADS);

    for (int i = 0; i < VIRTUAL_THREADS; i++) {
      threads.add(
          Thread.ofVirtual()
              .start(
                  () -> {
                    if (Thread.currentThread().isVirtual()) {
                      sawVirtual.incrementAndGet();
                    }
                    allParked.countDown();
                    await(release);
                  }));
    }

    if (!allParked.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
      throw new AssertionError("only " + (VIRTUAL_THREADS - allParked.getCount()) + " started");
    }
    int platformThreads = ManagementFactory.getThreadMXBean().getThreadCount();
    release.countDown();
    for (Thread thread : threads) {
      thread.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
      if (thread.isAlive()) {
        throw new AssertionError("a parked virtual thread did not finish after release");
      }
    }
    if (sawVirtual.get() != VIRTUAL_THREADS) {
      throw new AssertionError(sawVirtual.get() + " of " + VIRTUAL_THREADS + " were virtual");
    }
    if (platformThreads >= PLATFORM_THREAD_CEILING) {
      throw new AssertionError("platform threads = " + platformThreads);
    }
    return "virtual=" + sawVirtual.get() + " platformThreads=" + platformThreads;
  }

  private static String bothForceVariantsWork() throws IOException {
    Path dir = Files.createTempDirectory("ledger-x-substrate");
    try {
      Path wal = dir.resolve("wal.log");
      byte[] record = "ledger-x substrate probe\n".getBytes(StandardCharsets.UTF_8);
      long size;
      try (DurableChannel channel = DurableChannel.openForAppend(wal)) {
        channel.write(ByteBuffer.wrap(record));
        channel.forceData();
        channel.write(ByteBuffer.wrap(record));
        channel.forceAll();
        size = channel.size();
      }
      byte[] readBack = Files.readAllBytes(wal);
      byte[] expected = new byte[record.length * 2];
      System.arraycopy(record, 0, expected, 0, record.length);
      System.arraycopy(record, 0, expected, record.length, record.length);
      if (!Arrays.equals(expected, readBack)) {
        throw new AssertionError(
            "read back " + readBack.length + " bytes, expected " + expected.length);
      }
      if (size != expected.length) {
        throw new AssertionError("channel size " + size + " != " + expected.length);
      }
      return "bytes=" + readBack.length + " after force(false)+force(true)";
    } finally {
      deleteTree(dir);
    }
  }

  private static String directoryFsyncIsAvailable() throws IOException {
    Path dir = Files.createTempDirectory("ledger-x-substrate-dir");
    try {
      Files.writeString(dir.resolve("wal.log"), "x", StandardCharsets.UTF_8);
      DurableChannel.syncDirectory(dir);
      return "fsync(dirfd) returned for " + dir.getFileName();
    } finally {
      deleteTree(dir);
    }
  }

  private static String perAccountLockSerializes() throws InterruptedException {
    ReentrantLock account = new ReentrantLock();
    AtomicInteger inside = new AtomicInteger();
    AtomicInteger maxConcurrent = new AtomicInteger();
    CountDownLatch finished = new CountDownLatch(CONTESTED_WRITERS);

    for (int w = 0; w < CONTESTED_WRITERS; w++) {
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  for (int i = 0; i < WRITES_PER_WRITER; i++) {
                    account.lock();
                    try {
                      maxConcurrent.accumulateAndGet(inside.incrementAndGet(), Math::max);
                    } finally {
                      inside.decrementAndGet();
                      account.unlock();
                    }
                  }
                } finally {
                  finished.countDown();
                }
              });
    }

    if (!finished.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
      throw new AssertionError(finished.getCount() + " writers never finished");
    }
    if (maxConcurrent.get() != 1) {
      throw new AssertionError(
          "max concurrent holders of the account lock = " + maxConcurrent.get());
    }
    return CONTESTED_WRITERS + " writers x " + WRITES_PER_WRITER + " writes, maxInsideLock=1";
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException ignored) {
      // A parked virtual thread that is interrupted still has to leave the latch wait, and the
      // caller's own latch join() decides whether that mattered; restoring the flag is enough.
      Thread.currentThread().interrupt();
    }
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    List<Path> paths = new ArrayList<>();
    try (var walked = Files.walk(root)) {
      walked.sorted(Comparator.reverseOrder()).forEach(paths::add);
    }
    for (Path path : paths) {
      Files.deleteIfExists(path);
    }
  }
}
