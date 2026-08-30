package pw.valaria.muhpackets.logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogDirectoryTest {

  @TempDir
  Path root;

  private LogDirectory logs;

  @BeforeEach
  void setUp() {
    final Logger logger = Logger.getAnonymousLogger();
    logger.setUseParentHandlers(false);
    logger.setLevel(Level.OFF);
    logs = new LogDirectory(root::toFile, () -> logger);
  }

  // --- sanitiseName is the only thing between an unauthenticated name and the filesystem ---

  @Test
  void pathSeparatorsAndDotsAreReplaced() {
    // '.' goes too, so ".." cannot survive in any form.
    assertEquals("_________etc_passwd", LogDirectory.sanitiseName("../../../etc/passwd"));
    assertEquals("__", LogDirectory.sanitiseName(".."));
    assertEquals("_", LogDirectory.sanitiseName("/"));
  }

  @Test
  void sanitisedNameIsAlwaysAUsableSingleSegment() {
    final List<String> hostile = List.of("../x", "a/b", "a\\b", "..", ".", " evil", "con:", "a b");
    for (final String raw : hostile) {
      final String safe = LogDirectory.sanitiseName(raw);
      assertFalse(safe.isEmpty(), raw);
      assertFalse(safe.contains("/"), raw);
      assertFalse(safe.contains("\\"), raw);
      assertFalse(safe.contains("."), raw);
      assertEquals(1, Path.of(safe).getNameCount(), "must stay one path segment: " + raw);
    }
  }

  @Test
  void emptyAndNullBecomeUnknown() {
    assertEquals("unknown", LogDirectory.sanitiseName(null));
    assertEquals("unknown", LogDirectory.sanitiseName(""));
  }

  @Test
  void ordinaryNamesSurviveUnchanged() {
    assertEquals("Notch", LogDirectory.sanitiseName("Notch"));
    assertEquals("some_player-1", LogDirectory.sanitiseName("some_player-1"));
  }

  @Test
  void longNamesAreCapped() {
    assertEquals(32, LogDirectory.sanitiseName("x".repeat(500)).length());
  }

  @Test
  void openKeepsTheLogInsideTheRoot() throws IOException {
    final LogDirectory.Opened opened = logs.open("../../escape");

    assertNotNull(opened);
    assertTrue(opened.target().getCanonicalPath().startsWith(root.toFile().getCanonicalPath()),
      "a crafted name must not place a log outside the logs folder: " + opened.target());
  }

  // --- opening ---

  @Test
  void openWritesAHeaderNamingThePlayer() throws IOException {
    final LogDirectory.Opened opened = logs.open("Notch");
    assertNotNull(opened);

    final List<String> lines = Files.readAllLines(opened.target().toPath(), StandardCharsets.UTF_8);
    assertEquals(2, lines.size());
    assertTrue(lines.get(0).startsWith("# muhpackets v1 player=Notch dir=Notch started="), lines.get(0));
    assertEquals("# format: [timestamp] [protocol] [packet] key=value ...", lines.get(1));
  }

  @Test
  void aCraftedNameCannotForgeLinesInTheHeader() throws IOException {
    final LogDirectory.Opened opened = logs.open("evil\nnot-a-real-line");
    assertNotNull(opened);

    final List<String> lines = Files.readAllLines(opened.target().toPath(), StandardCharsets.UTF_8);
    assertEquals(2, lines.size(), "a newline in the name must not become a new line in the log");
  }

  @Test
  void twoSessionsInTheSameMillisecondGetSeparateFiles() throws IOException {
    final File dir = new File(root.toFile(), "Same");
    assertTrue(dir.mkdirs());

    final File first = LogDirectory.createLogFile(dir);
    final File second = LogDirectory.createLogFile(dir);

    assertNotNull(first);
    assertNotNull(second);
    assertNotEquals(first, second, "the second session must not append to the first one's file");
  }

  @Test
  void namesThatSanitiseAlikeShareADirectoryWithoutClobbering() {
    final LogDirectory.Opened a = logs.open("a.b");
    final LogDirectory.Opened b = logs.open("a/b");

    assertNotNull(a);
    assertNotNull(b);
    assertEquals(a.safeName(), b.safeName(), "these collide by design");
    assertNotEquals(a.target(), b.target(), "but must not share a file");
  }

  // --- pruning ---

  private File aged(String dir, String name, int daysOld) throws IOException {
    final File d = new File(root.toFile(), dir);
    assertTrue(d.isDirectory() || d.mkdirs());
    final File f = new File(d, name);
    assertTrue(f.createNewFile());
    Files.setLastModifiedTime(f.toPath(), FileTime.from(Instant.now().minus(daysOld, ChronoUnit.DAYS)));
    return f;
  }

  @Test
  void pruneDeletesOnlyExpiredLogs() throws IOException {
    final File old = aged("Player", "old.log", 10);
    final File recent = aged("Player", "recent.log", 1);

    assertEquals(1, logs.prune(5));

    assertFalse(old.exists());
    assertTrue(recent.exists());
  }

  @Test
  void pruneRemovesDirectoriesItEmpties() throws IOException {
    aged("Gone", "old.log", 10);
    aged("Stays", "recent.log", 1);

    logs.prune(5);

    assertFalse(new File(root.toFile(), "Gone").exists(), "an emptied directory should not linger");
    assertTrue(new File(root.toFile(), "Stays").isDirectory());
    assertTrue(root.toFile().isDirectory(), "the root itself must survive");
  }

  @Test
  void pruneIsDisabledByANonPositiveRetention() throws IOException {
    final File old = aged("Player", "old.log", 100);

    assertEquals(0, logs.prune(0));
    assertEquals(0, logs.prune(-1));

    assertTrue(old.exists());
  }

  @Test
  void pruneToleratesAMissingLogsFolder() {
    final LogDirectory missing =
      new LogDirectory(() -> new File(root.toFile(), "nope"), Logger::getAnonymousLogger);

    assertEquals(0, missing.prune(5));
  }
}
