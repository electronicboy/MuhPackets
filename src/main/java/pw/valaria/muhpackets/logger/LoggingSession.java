package pw.valaria.muhpackets.logger;

import pw.valaria.muhpackets.MuhPackets;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedDeque;

public class LoggingSession {
  private final MuhPackets muhPackets;
  private final String name;
  private final ConcurrentLinkedDeque<LogRecord> records = new ConcurrentLinkedDeque<>();
  private final File target;
  private boolean isActive = true;

  public LoggingSession(MuhPackets muhPackets, String name, File target) {
    this.muhPackets = muhPackets;

    this.name = name;
    this.target = target;
  }

  public void log(LogRecord logRecord) {
    records.add(logRecord);
  }

  public void close() {
    this.isActive = false;
  }

  public boolean process() {
    if (records.isEmpty()) return this.isActive;
    try (FileWriter writer = new FileWriter(target, true)) {
      while (records.peek() != null) {
        records.pop().write(writer, true, muhPackets.getMuhPacketsConfig().getIgnoredPackets());
      }
    } catch (IOException e) {
    }
    return this.isActive;
  }

  @Override
  public String toString() {
    return "LoggingSession{" +
      "name='" + name + '\'' +
      ", target=" + target +
      '}';
  }
}
