package pw.valaria.muhpackets.logger;

import pw.valaria.muhpackets.CompressionSchema;
import pw.valaria.muhpackets.MuhPackets;

import java.io.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.zip.GZIPOutputStream;

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
    return "LoggingSession{" + "name='" + name + '\'' + ", target=" + target + '}';
  }

  public void compress(CompressionSchema compressionSchema) {
    if (compressionSchema == CompressionSchema.GZIP) {
      final File compressedTarget = new File(target.getPath() + ".gz");
      try {
        compressedTarget.createNewFile();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      try (FileInputStream fir = new FileInputStream(target);
           FileOutputStream fos = new FileOutputStream(compressedTarget);
           GZIPOutputStream gos = new GZIPOutputStream(fos)) {
        byte[] buffer = new byte[1024];
        int len;
        while ((len = fir.read(buffer)) != -1) {
          gos.write(buffer, 0, len);
        }


      } catch (FileNotFoundException e) {
        throw new RuntimeException(e);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
