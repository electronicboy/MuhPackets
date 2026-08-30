package pw.valaria.muhpackets.logger;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordBudgetTest {

  @Test
  void acquiresUpToCapacity() {
    final RecordBudget budget = new RecordBudget(3);

    assertTrue(budget.tryAcquire());
    assertTrue(budget.tryAcquire());
    assertTrue(budget.tryAcquire());

    assertEquals(3, budget.used());
  }

  @Test
  void refusesOnceFull() {
    final RecordBudget budget = new RecordBudget(2);
    budget.tryAcquire();
    budget.tryAcquire();

    assertFalse(budget.tryAcquire());
    assertEquals(2, budget.used(), "a refused acquire must not consume budget");
  }

  @Test
  void releaseRestoresCapacity() {
    final RecordBudget budget = new RecordBudget(2);
    budget.tryAcquire();
    budget.tryAcquire();
    assertFalse(budget.tryAcquire());

    budget.release(2);

    assertEquals(0, budget.used());
    assertTrue(budget.tryAcquire());
  }

  @Test
  void overReleaseDoesNotCreateCapacity() {
    // Sessions release on several paths (successful write, give-up, shutdown). If a double release
    // could drive the counter negative, the cap would silently stop being a cap.
    final RecordBudget budget = new RecordBudget(2);
    budget.tryAcquire();

    budget.release(50);

    assertEquals(0, budget.used());
    assertTrue(budget.tryAcquire());
    assertTrue(budget.tryAcquire());
    assertFalse(budget.tryAcquire());
  }

  @Test
  void releaseOfNothingIsIgnored() {
    final RecordBudget budget = new RecordBudget(2);
    budget.tryAcquire();

    budget.release(0);
    budget.release(-5);

    assertEquals(1, budget.used());
  }

  @Test
  void nonPositiveCapacityMeansUnlimited() {
    for (final int capacity : new int[]{0, -1}) {
      final RecordBudget budget = new RecordBudget(capacity);
      for (int i = 0; i < 1000; i++) {
        assertTrue(budget.tryAcquire(), "capacity " + capacity + " should never refuse");
      }
      assertEquals(1000, budget.used(), "usage is still tracked when unlimited");
    }
  }

  @Test
  void capacityCanBeChangedByReload() {
    final RecordBudget budget = new RecordBudget(1);
    budget.tryAcquire();
    assertFalse(budget.tryAcquire());

    budget.setCapacity(3);

    assertTrue(budget.tryAcquire());
    assertTrue(budget.tryAcquire());
    assertFalse(budget.tryAcquire());
  }

  @Test
  void loweringCapacityBelowUsageRefusesUntilDrained() {
    final RecordBudget budget = new RecordBudget(10);
    for (int i = 0; i < 8; i++) {
      budget.tryAcquire();
    }

    budget.setCapacity(4);

    assertFalse(budget.tryAcquire(), "already over the new cap");
    budget.release(5);
    assertTrue(budget.tryAcquire());
  }

  @Test
  void concurrentAcquireNeverExceedsCapacity() throws Exception {
    // The whole point of the budget is that netty threads hit it simultaneously; a read-then-write
    // implementation would hand out more than the cap here.
    final int capacity = 500;
    final int threads = 8;
    final RecordBudget budget = new RecordBudget(capacity);
    final AtomicInteger granted = new AtomicInteger();
    final CountDownLatch start = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(threads);

    for (int i = 0; i < threads; i++) {
      new Thread(() -> {
        try {
          start.await();
          for (int j = 0; j < capacity; j++) {
            if (budget.tryAcquire()) {
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

    assertEquals(capacity, granted.get());
    assertEquals(capacity, budget.used());
  }
}
