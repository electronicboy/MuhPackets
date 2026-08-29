package pw.valaria.muhpackets.logger;

import pw.valaria.muhpackets.MuhPackets;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Buffers records produced on netty threads and drains them to disk from the plugin's flush task.
 */
public class LoggingSession {
  /**
   * Upper bound on records held in memory. Reached only if the flush task cannot keep up or the
   * log file has become unwritable; without it a stuck session grows until the server dies.
   */
  private static final int MAX_BUFFERED_RECORDS = 100_000;

  /** How many consecutive failed flushes a closed session tolerates before its records are dropped. */
  private static final int MAX_DRAIN_FAILURES = 10;

  private final MuhPackets muhPackets;
  private final String name;
  private final String safeName;
  private final ConcurrentLinkedDeque<LogRecord> records = new ConcurrentLinkedDeque<>();
  private final AtomicInteger buffered = new AtomicInteger();
  private final AtomicLong dropped = new AtomicLong();
  private final File target;
  private volatile boolean isActive = true;
  private boolean writeFailureReported;
  private int drainFailures;

  public LoggingSession(MuhPackets muhPackets, String name, String safeName, File target) {
    this.muhPackets = muhPackets;
    this.name = name;
    this.safeName = safeName;
    this.target = target;
  }

  public void log(LogRecord logRecord) {
    if (buffered.get() >= MAX_BUFFERED_RECORDS) {
      dropped.incrementAndGet();
      return;
    }
    records.add(logRecord);
    buffered.incrementAndGet();
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
      reportDropped();
      return stillNeeded();
    }

    int written = 0;
    try {
      try (Writer writer = new BufferedWriter(new FileWriter(target, StandardCharsets.UTF_8, true))) {
        // Weakly consistent iterator, head first. Only this thread removes, and others only append,
        // so the first 'written' entries are exactly the ones handed to the writer.
        for (final LogRecord record : records) {
          record.write(writer);
          written++;
        }
      }
      // Reached only once the writer has closed, and therefore flushed, cleanly. Removing before
      // this point meant a failure in that final flush discarded records that were never written.
      for (int i = 0; i < written; i++) {
        records.poll();
        buffered.decrementAndGet();
      }
      writeFailureReported = false;
      drainFailures = 0;
    } catch (IOException e) {
      drainFailures++;
      if (!writeFailureReported) {
        writeFailureReported = true;
        muhPackets.getLogger().log(Level.WARNING, "Could not write packet log " + target, e);
      }
    }
    reportDropped();
    return stillNeeded();
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
      muhPackets.getLogger().warning("Giving up on " + buffered.get() + " unwritten packet(s) for "
        + safeName + " after " + drainFailures + " failed attempts to write " + target);
      records.clear();
      buffered.set(0);
      return false;
    }
    return true;
  }

  private void reportDropped() {
    final long lost = dropped.getAndSet(0);
    if (lost > 0) {
      muhPackets.getLogger().warning("Dropped " + lost + " packet(s) for " + safeName
        + ": buffer limit of " + MAX_BUFFERED_RECORDS + " reached");
    }
  }

  @Override
  public String toString() {
    return "LoggingSession{" +
      "name='" + name + '\'' +
      ", target=" + target +
      '}';
  }
}
