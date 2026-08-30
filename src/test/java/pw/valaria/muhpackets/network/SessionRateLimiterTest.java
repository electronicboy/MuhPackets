package pw.valaria.muhpackets.network;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionRateLimiterTest {

  /** Time is driven by hand; a limiter tested against the wall clock is a flaky limiter. */
  private final AtomicLong nanos = new AtomicLong();

  private void advanceSeconds(double seconds) {
    nanos.addAndGet((long) (seconds * 1_000_000_000L));
  }

  private SessionRateLimiter limiter(double perSecond, int burst) {
    return new SessionRateLimiter(nanos::get, perSecond, burst);
  }

  @Test
  void allowsTheFullBurstImmediately() {
    // A restart reconnects everyone at once, and those are real players we want logs for.
    final SessionRateLimiter limiter = limiter(10, 200);

    for (int i = 0; i < 200; i++) {
      assertTrue(limiter.tryAcquire(), "burst permit " + i + " should be granted");
    }
  }

  @Test
  void refusesOnceTheBurstIsSpent() {
    final SessionRateLimiter limiter = limiter(10, 5);

    for (int i = 0; i < 5; i++) {
      assertTrue(limiter.tryAcquire());
    }

    assertFalse(limiter.tryAcquire());
  }

  @Test
  void refillsAtTheConfiguredRate() {
    final SessionRateLimiter limiter = limiter(10, 5);
    for (int i = 0; i < 5; i++) {
      limiter.tryAcquire();
    }
    assertFalse(limiter.tryAcquire());

    advanceSeconds(0.3); // 10/sec for 0.3s = 3 permits

    assertTrue(limiter.tryAcquire());
    assertTrue(limiter.tryAcquire());
    assertTrue(limiter.tryAcquire());
    assertFalse(limiter.tryAcquire(), "only three permits were earned");
  }

  @Test
  void doesNotBankPermitsBeyondTheBurst() {
    // Otherwise a quiet server would accumulate an unbounded allowance and the first flood after
    // it would be waved straight through.
    final SessionRateLimiter limiter = limiter(10, 5);

    advanceSeconds(3600);

    for (int i = 0; i < 5; i++) {
      assertTrue(limiter.tryAcquire());
    }
    assertFalse(limiter.tryAcquire(), "an hour idle must not buy more than the burst");
  }

  @Test
  void sustainedFloodIsHeldToTheConfiguredRate() {
    final SessionRateLimiter limiter = limiter(10, 10);
    int granted = 0;

    // Five seconds of hammering, 100 attempts per simulated 100ms.
    for (int tick = 0; tick < 50; tick++) {
      for (int attempt = 0; attempt < 100; attempt++) {
        if (limiter.tryAcquire()) {
          granted++;
        }
      }
      advanceSeconds(0.1);
    }

    // 10 burst + 10/sec for the 4.9s that elapse inside the loop.
    assertEquals(59, granted, "sustained rate must converge on the configured permits per second");
  }

  @Test
  void nonPositiveRateMeansUnlimited() {
    for (final double rate : new double[]{0, -1}) {
      final SessionRateLimiter limiter = limiter(rate, 0);
      for (int i = 0; i < 1000; i++) {
        assertTrue(limiter.tryAcquire(), "rate " + rate + " should never refuse");
      }
    }
  }

  @Test
  void reconfigureClampsBankedPermitsToTheNewBurst() {
    final SessionRateLimiter limiter = limiter(10, 500);

    limiter.reconfigure(10, 5);

    for (int i = 0; i < 5; i++) {
      assertTrue(limiter.tryAcquire());
    }
    assertFalse(limiter.tryAcquire(), "lowering the burst must take effect immediately");
  }

  @Test
  void concurrentAcquireNeverExceedsTheBurst() throws Exception {
    final int burst = 100;
    final int threads = 8;
    final SessionRateLimiter limiter = limiter(0.0001, burst);
    final AtomicInteger granted = new AtomicInteger();
    final CountDownLatch start = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(threads);

    for (int i = 0; i < threads; i++) {
      new Thread(() -> {
        try {
          start.await();
          for (int j = 0; j < burst; j++) {
            if (limiter.tryAcquire()) {
              granted.incrementAndGet();
            }
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          done.countDown();
        }
      }).start();
    }

    start.countDown();
    assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish");

    assertEquals(burst, granted.get());
  }
}
