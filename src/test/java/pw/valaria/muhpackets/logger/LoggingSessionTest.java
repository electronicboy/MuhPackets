package pw.valaria.muhpackets.logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingSessionTest {

  @TempDir
  Path dir;

  private Logger logger;

  @BeforeEach
  void quietLogger() {
    logger = Logger.getAnonymousLogger();
    logger.setUseParentHandlers(false);
    logger.setLevel(Level.OFF);
  }

  private LoggingSession session(File target) {
    return session(target, 0);
  }

  private LoggingSession session(File target, int limit) {
    return new LoggingSession(logger, "player", "player", target, () -> limit);
  }

  private static LogRecord record(String name) {
    final Map<String, String> fields = new LinkedHashMap<>();
    fields.put("id", name);
    return new LogRecord(null, "game.Serverbound" + name, fields);
  }

  private List<String> lines(File target) throws IOException {
    return Files.readAllLines(target.toPath(), StandardCharsets.UTF_8);
  }

  @Test
  void writesBufferedRecords() throws IOException {
    final File target = dir.resolve("out.log").toFile();
    final LoggingSession session = session(target);

    session.log(record("A"));
    session.log(record("B"));
    assertTrue(session.process());

    final List<String> lines = lines(target);
    assertEquals(2, lines.size());
    assertTrue(lines.get(0).contains("game.ServerboundA"), lines.get(0));
    assertTrue(lines.get(1).contains("game.ServerboundB"), lines.get(1));
  }

  @Test
  void appendsAcrossFlushes() throws IOException {
    final File target = dir.resolve("out.log").toFile();
    final LoggingSession session = session(target);

    session.log(record("A"));
    session.process();
    session.log(record("B"));
    session.process();

    assertEquals(2, lines(target).size(), "a second flush must not truncate the first");
  }

  @Test
  void stopsBufferingAtTheLimit() {
    final LoggingSession session = session(dir.resolve("out.log").toFile(), 2);

    for (int i = 0; i < 5; i++) {
      session.log(record("P" + i));
    }

    assertEquals(2, session.buffered(), "the limit is what stands between a flood and the heap");
  }

  @Test
  void aLimitOfZeroMeansUnlimited() {
    final LoggingSession session = session(dir.resolve("out.log").toFile(), 0);

    for (int i = 0; i < 50; i++) {
      session.log(record("P" + i));
    }

    assertEquals(50, session.buffered());
  }

  @Test
  void recordsWhatWasDroppedInTheLogFile() throws IOException {
    final File target = dir.resolve("out.log").toFile();
    final LoggingSession session = session(target, 2);

    for (int i = 0; i < 5; i++) {
      session.log(record("P" + i));
    }
    session.process();

    final List<String> lines = lines(target);
    assertEquals("# dropped 3 record(s) before this point: buffer limit reached", lines.get(0),
      "a log has to say what is missing from it; the console may be long gone");
    assertEquals(3, lines.size(), "marker plus the two records that fit");
  }

  @Test
  void noMarkerWhenNothingWasDropped() throws IOException {
    final File target = dir.resolve("out.log").toFile();
    final LoggingSession session = session(target, 10);

    session.log(record("A"));
    session.process();

    assertTrue(lines(target).stream().noneMatch(line -> line.startsWith("# dropped")));
  }

  @Test
  void aFailedWriteStillOwesTheDropCount() throws IOException {
    // A directory cannot be opened as a file, which is the cheapest real IOException available.
    final File target = dir.resolve("unwritable").toFile();
    assertTrue(target.mkdir());
    final LoggingSession session = session(target, 1);

    session.log(record("A"));
    session.log(record("B"));
    session.log(record("C"));
    assertTrue(session.process(), "an open session survives a failed write");

    // The count must not have been consumed by a write that never happened: once the file becomes
    // writable the marker still has to appear, or the gap is silent.
    assertTrue(target.delete());
    assertTrue(session.process());

    final List<String> lines = lines(dir.resolve("unwritable").toFile());
    assertEquals("# dropped 2 record(s) before this point: buffer limit reached", lines.get(0));
    assertEquals(2, lines.size(), "marker plus the record that was held back");
  }

  @Test
  void closedAndDrainedSessionReportsItselfFinished() {
    final File target = dir.resolve("out.log").toFile();
    final LoggingSession session = session(target);

    session.log(record("A"));
    assertTrue(session.process());

    session.close();
    assertFalse(session.process(), "closed and empty means the poll loop can drop it");
  }

  @Test
  void closedSessionWithUnwritableTargetIsRetainedForRetry() {
    // A directory cannot be opened as a file, which is the cheapest real IOException available.
    final File target = dir.resolve("unwritable").toFile();
    assertTrue(target.mkdir());
    final LoggingSession session = session(target);

    session.log(record("A"));
    session.close();

    assertTrue(session.process(), "records must not be discarded on the first failed write");
  }
}
