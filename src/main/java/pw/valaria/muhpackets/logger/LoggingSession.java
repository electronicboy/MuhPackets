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
 * <p>Deliberately knows nothing about the plugin: it takes a logger rather than a
 * {@code MuhPackets}, which is what makes its buffering behaviour testable without a running
 * server.</p>
 */
public class LoggingSession {
  /** How many consecutive failed flushes a closed session tolerates before its records are dropped. */
  private static final int MAX_DRAIN_FAILURES = 10;

  private final Logger logger;
  private final String name;
  private final String safeName;
  private final ConcurrentLinkedDeque<LogRecord> records = new ConcurrentLinkedDeque<>();
  private final AtomicInteger buffered = new AtomicInteger();
  private final AtomicLong dropped = new AtomicLong();
  private final File target;
  /**
   * Upper bound on records held in memory; zero or below means unlimited. Reached only if the
   * flush task cannot keep up or the log file has become unwritable; without it a stuck session
   * grows until the server dies.
   *
   * <p>Read per record rather than captured once, so lowering the limit during an incident takes
   * effect on connections that are already open.</p>
   */
  private final IntSupplier maxBufferedRecords;
  private volatile boolean isActive = true;
  private boolean writeFailureReported;
  private int drainFailures;

  public LoggingSession(Logger logger, String name, String safeName, File target,
                        IntSupplier maxBufferedRecords) {
    this.logger = logger;
    this.name = name;
    this.safeName = safeName;
    this.target = target;
    this.maxBufferedRecords = maxBufferedRecords;
  }

  public void log(LogRecord logRecord) {
    final int limit = maxBufferedRecords.getAsInt();
    if (limit > 0 && buffered.get() >= limit) {
      dropped.incrementAndGet();
      return;
    }
    records.add(logRecord);
    buffered.incrementAndGet();
  }

  /** Records currently held in memory. */
  public int buffered() {
    return buffered.get();
  }

  public void close() {
    this.isActive = false;
  }

  public String safeName() {
    return safeName;
  }

  public File target() {
    return target;
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

    // Claimed inside the try so a failed write can hand it back; a marker that was never written
    // must stay owed, or the log ends up with an unexplained gap and no count for it anywhere.
    long lost = 0;
    try (Writer writer = new BufferedWriter(new FileWriter(target, StandardCharsets.UTF_8, true))) {
      lost = dropped.getAndSet(0);
      if (lost > 0) {
        writer.write(dropMarker(lost));
      }
      for (final LogRecord queued : batch) {
        queued.write(writer);
      }
      // Closing, and therefore flushing, happens on the way out of this block. Anything it throws
      // is caught below, so a failed flush restores the batch rather than dropping it.
    } catch (IOException e) {
      restore(batch);
      dropped.addAndGet(lost);
      drainFailures++;
      if (!writeFailureReported) {
        writeFailureReported = true;
        logger.log(Level.WARNING, "Could not write packet log " + target, e);
      }
      return stillNeeded();
    }

    writeFailureReported = false;
    drainFailures = 0;
    if (lost > 0) {
      // Also to the console: the file line is for whoever reads the log afterwards, this is for
      // whoever is watching the server while it happens.
      logger.warning("Dropped " + lost + " packet(s) for " + safeName
        + ": buffer limit of " + maxBufferedRecords.getAsInt() + " reached");
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
  private String dropMarker(long lost) {
    return "# dropped " + lost + " record(s) before this point: buffer limit reached\n";
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
      records.clear();
      buffered.set(0);
      return false;
    }
    return true;
  }

  @Override
  public String toString() {
    return "LoggingSession{" +
      "name='" + name + '\'' +
      ", target=" + target +
      '}';
  }
}
