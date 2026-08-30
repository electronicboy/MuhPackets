package pw.valaria.muhpackets.network;

import java.util.function.LongSupplier;

/**
 * Rate limit on how often a new logging session may be opened.
 *
 * <p>A login flood is a different problem from a chatty connection, and capping how many sessions
 * exist at once is the wrong answer to it: sessions live as long as their connections, so a
 * concurrency cap is really a cap on concurrent players and refuses logging for legitimate ones on
 * a busy server. What distinguishes a flood is the <em>rate</em> of logins, so that is what this
 * limits.</p>
 *
 * <p>A token bucket rather than a fixed window, because the burst allowance is the point: a server
 * restart reconnects everybody at once, and those are real players whose logs are wanted. The
 * sustained rate is what separates that from an attack.</p>
 *
 * <p>Permits are only taken when a connection reaches the login stage, so this is called orders of
 * magnitude less often than anything on the packet path; a lock is cheaper to reason about here
 * than a lock-free bucket and costs nothing at these rates.</p>
 */
public final class SessionRateLimiter {
  private final LongSupplier nanoTime;
  private double permitsPerSecond;
  private double burst;
  private double tokens;
  private long lastRefillNanos;

  public SessionRateLimiter(LongSupplier nanoTime, double permitsPerSecond, int burst) {
    this.nanoTime = nanoTime;
    this.permitsPerSecond = permitsPerSecond;
    this.burst = burst;
    this.tokens = burst;
    this.lastRefillNanos = nanoTime.getAsLong();
  }

  /** Re-reads the limits after a config reload, without discarding the current bucket. */
  public synchronized void reconfigure(double permitsPerSecond, int burst) {
    this.permitsPerSecond = permitsPerSecond;
    this.burst = burst;
    // No need to clamp the banked tokens here: the refill in tryAcquire caps them at the burst
    // before every decision, so a lowered burst already bites on the very next acquire.
  }

  /**
   * Takes a permit for one new session.
   *
   * @return whether a session may be opened; false means this connection goes unlogged
   */
  public synchronized boolean tryAcquire() {
    if (permitsPerSecond <= 0) {
      return true;
    }

    final long now = nanoTime.getAsLong();
    final double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
    lastRefillNanos = now;
    // Capped at the burst: without this a quiet server banks an unbounded allowance and waves the
    // next flood straight through.
    tokens = Math.min(burst, tokens + elapsedSeconds * permitsPerSecond);

    if (tokens >= 1.0) {
      tokens -= 1.0;
      return true;
    }
    return false;
  }
}
