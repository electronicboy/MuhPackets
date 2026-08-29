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

  private final MuhPackets muhPackets;
  private final String name;
  private final String safeName;
  private final ConcurrentLinkedDeque<LogRecord> records = new ConcurrentLinkedDeque<>();
  private final AtomicInteger buffered = new AtomicInteger();
  private final AtomicLong dropped = new AtomicLong();
  private final File target;
  private volatile boolean isActive = true;
  private boolean writeFailureReported;

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
      return this.isActive;
    }
    try (Writer writer = new BufferedWriter(new FileWriter(target, StandardCharsets.UTF_8, true))) {
      LogRecord record;
      // Peek, write, then remove. Popping first meant a mid-drain failure discarded every record
      // already taken off the deque, silently.
      while ((record = records.peek()) != null) {
        record.write(writer);
        records.poll();
        buffered.decrementAndGet();
      }
      writeFailureReported = false;
    } catch (IOException e) {
      if (!writeFailureReported) {
        writeFailureReported = true;
        muhPackets.getLogger().log(Level.WARNING, "Could not write packet log " + target, e);
      }
    }
    reportDropped();
    return this.isActive;
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
