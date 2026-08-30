package pw.valaria.muhpackets.logger;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A cap on how many records may be buffered across every session at once.
 *
 * <p>Each {@link LoggingSession} already bounds itself, but that bound is per connection: a login
 * flood opens a session per connection, so the memory actually at risk is the per-session limit
 * multiplied by the number of connections. This is the limit that does not scale with attacker
 * effort.</p>
 *
 * <p>Acquired from netty threads and released from the flush task, so every operation is atomic.</p>
 */
public final class RecordBudget {
  /** Records permitted in memory at once; zero or negative means unlimited. */
  private volatile int capacity;
  private final AtomicInteger used = new AtomicInteger();

  public RecordBudget(int capacity) {
    this.capacity = capacity;
  }

  /**
   * Re-reads the limit after a config reload.
   *
   * <p>Lowering it below current usage does not evict anything: the excess drains away normally and
   * acquires are refused until usage falls back under the new cap.</p>
   */
  public void setCapacity(int capacity) {
    this.capacity = capacity;
  }

  /**
   * Reserves room for one record.
   *
   * @return whether the record may be buffered; false means the caller must drop it
   */
  public boolean tryAcquire() {
    // Read once: a second read of the volatile field could see a reload in between and compare
    // against a different limit than the one just tested.
    final int limit = this.capacity;
    if (limit <= 0) {
      used.incrementAndGet();
      return true;
    }
    int current;
    do {
      current = used.get();
      if (current >= limit) {
        return false;
      }
      // CAS rather than incrementAndGet-then-correct: backing out an overshoot would let a
      // concurrent acquire observe usage above the cap and refuse a record that should have fit.
    } while (!used.compareAndSet(current, current + 1));
    return true;
  }

  /** Returns capacity for records that are no longer held in memory. */
  public void release(int count) {
    if (count <= 0) {
      return;
    }
    // Clamped at zero. Records are released on several paths - a successful write, giving up on an
    // unwritable file, a session going away - and a double release that drove this negative would
    // quietly raise the effective cap instead of failing visibly.
    used.updateAndGet(current -> Math.max(0, current - count));
  }

  /** Records currently accounted for. Tracked even when unlimited, so it is usable for diagnostics. */
  public int used() {
    return used.get();
  }
}
