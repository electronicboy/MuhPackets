package pw.valaria.muhpackets.logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Buffers records produced on netty threads and drains them to disk from the plugin's flush task.
 *
 * <p>Deliberately knows nothing about the plugin: it takes a logger and its limits rather than a
 * {@code MuhPackets}, which is what makes its buffering and overflow behaviour testable without a
 * running server.</p>
 */
public class LoggingSession {
  /** How many consecutive failed flushes a closed session tolerates before its records are dropped. */
  private static final int MAX_DRAIN_FAILURES = 10;

  private final Logger logger;
  private final String name;
  private final String safeName;
  private final ConcurrentLinkedDeque<LogRecord> records = new ConcurrentLinkedDeque<>();
  private final AtomicInteger buffered = new AtomicInteger();
  /** Records refused because this session was already holding its own maximum. */
  private final AtomicLong droppedSessionLimit = new AtomicLong();
  /** Records refused because every session together was holding the server-wide maximum. */
  private final AtomicLong droppedTotalLimit = new AtomicLong();
  private final File target;
  /**
   * Read per record rather than captured once, so lowering the limit during an incident takes
   * effect on connections that are already open.
   */
  private final IntSupplier maxBufferedRecords;
  private final RecordBudget budget;
  private volatile boolean isActive = true;
  private boolean writeFailureReported;
  private int drainFailures;

  public LoggingSession(Logger logger, String name, String safeName, File target,
                        IntSupplier maxBufferedRecords, RecordBudget budget) {
    this.logger = logger;
    this.name = name;
    this.safeName = safeName;
    this.target = target;
    this.maxBufferedRecords = maxBufferedRecords;
    this.budget = budget;
  }

  public void log(LogRecord logRecord) {
    final int limit = maxBufferedRecords.getAsInt();
    if (limit > 0 && buffered.get() >= limit) {
      droppedSessionLimit.incrementAndGet();
      return;
    }
    if (!budget.tryAcquire()) {
      droppedTotalLimit.incrementAndGet();
      return;
    }
    records.add(logRecord);
    buffered.incrementAndGet();
  }

  /** Records currently held in memory. Package-private: this exists for the tests. */
  int buffered() {
    return buffered.get();
  }

  public void close() {
    this.isActive = false;
  }

  /**
   * Writes everything buffered so far.
   *
   * @return whether this session should stay open; false means it is closed and fully drained
   */
  public boolean process() {
    if (records.isEmpty()) {
      // No batch means no writer, and the drop count belongs in the file. Leave it pending for the
      // next flush that does open one rather than reporting it somewhere the log cannot show it.
      return stillNeeded();
    }

    // Take the batch up front rather than iterating and then removing that many from the head.
    // A ConcurrentLinkedDeque iterator is only weakly consistent, so "the first N entries are the
    // ones just written" holds only while this is the sole thread removing - true today, but it is
    // a silent data-loss bug the moment that stops being true. Polling does not depend on
    // iteration order at all, and a failed write puts the batch back.
    final List<LogRecord> batch = new ArrayList<>();
    LogRecord record;
    while ((record = records.poll()) != null) {
      batch.add(record);
      buffered.decrementAndGet();
    }

    // Claimed inside the try so a failed write can hand them back; a marker that was never written
    // must stay owed, or the log ends up with an unexplained gap and no count for it anywhere.
    long sessionLost = 0;
    long totalLost = 0;
    try (Writer writer = new BufferedWriter(new FileWriter(target, StandardCharsets.UTF_8, true))) {
      sessionLost = droppedSessionLimit.getAndSet(0);
      totalLost = droppedTotalLimit.getAndSet(0);
      if (sessionLost > 0 || totalLost > 0) {
        writer.write(dropMarker(sessionLost, totalLost));
      }
      for (final LogRecord queued : batch) {
        queued.write(writer);
      }
      // Closing, and therefore flushing, happens on the way out of this block. Anything it throws
      // is caught below, so a failed flush restores the batch rather than dropping it.
    } catch (IOException e) {
      restore(batch);
      droppedSessionLimit.addAndGet(sessionLost);
      droppedTotalLimit.addAndGet(totalLost);
      drainFailures++;
      if (!writeFailureReported) {
        writeFailureReported = true;
        logger.log(Level.WARNING, "Could not write packet log " + target, e);
      }
      return stillNeeded();
    }

    // Only now are those records genuinely out of memory.
    budget.release(batch.size());
    writeFailureReported = false;
    drainFailures = 0;
    if (sessionLost > 0 || totalLost > 0) {
      // Also to the console: the file line is for whoever reads the log afterwards, this is for
      // whoever is watching the server while it happens.
      logger.warning("Dropped " + (sessionLost + totalLost) + " packet(s) for " + safeName
        + " (" + sessionLost + " at the per-connection limit, " + totalLost + " at the server-wide limit)");
    }
    return stillNeeded();
  }

  /**
   * Builds the in-file record of what was lost.
   *
   * <p>Written at the head of the batch that follows it, so it marks roughly where the gap is
   * rather than exactly: records are dropped from the tail while this drains from the head, so some
   * of what it counts was lost during the previous batch's write.</p>
   */
  private String dropMarker(long sessionLost, long totalLost) {
    final StringBuilder out = new StringBuilder("# dropped ")
      .append(sessionLost + totalLost)
      .append(" record(s) before this point:");
    if (sessionLost > 0) {
      out.append(' ').append(sessionLost).append(" at the per-connection buffer limit");
    }
    if (sessionLost > 0 && totalLost > 0) {
      out.append(',');
    }
    if (totalLost > 0) {
      out.append(' ').append(totalLost).append(" at the server-wide buffer limit");
    }
    return out.append('\n').toString();
  }

  /**
   * Returns an unwritten batch to the head of the deque, preserving order relative to records that
   * arrived while the write was being attempted.
   */
  private void restore(List<LogRecord> batch) {
    for (int i = batch.size() - 1; i >= 0; i--) {
      records.addFirst(batch.get(i));
      buffered.incrementAndGet();
    }
  }

  /**
   * Whether the session must be kept around.
   *
   * <p>A closed session still holding records is retained so the next flush can retry it; reporting
   * it as finished would have the poll loop drop it, discarding exactly the records the retry logic
   * was added to preserve. A file that simply cannot be written would keep it alive forever, so
   * after repeated failures the remainder is discarded loudly.</p>
   */
  private boolean stillNeeded() {
    if (this.isActive) {
      return true;
    }
    if (records.isEmpty()) {
      return false;
    }
    if (drainFailures >= MAX_DRAIN_FAILURES) {
      logger.warning("Giving up on " + buffered.get() + " unwritten packet(s) for "
        + safeName + " after " + drainFailures + " failed attempts to write " + target);
      abandon();
      return false;
    }
    return true;
  }

  /**
   * Discards whatever is still buffered and hands its share of the server-wide budget back.
   *
   * <p>Without the release, a session that dies holding records would leak budget permanently and
   * the server would slowly stop logging anything at all.</p>
   */
  void abandon() {
    records.clear();
    budget.release(buffered.getAndSet(0));
  }

  @Override
  public String toString() {
    return "LoggingSession{" +
      "name='" + name + '\'' +
      ", target=" + target +
      '}';
  }
}
