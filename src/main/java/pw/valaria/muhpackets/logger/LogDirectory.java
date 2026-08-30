package pw.valaria.muhpackets.logger;

import org.jspecify.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Everything to do with where logs live on disk: naming, opening and expiring them.
 *
 * <p>Split out of the plugin class because none of it needs a running server, and one part of it
 * badly wants testing - {@link #sanitiseName} is the only thing standing between a client-supplied
 * name and the filesystem.</p>
 *
 * <p>The root and logger arrive as suppliers so this can be a final field on the plugin: neither
 * the data folder nor the plugin logger is available while its fields are being initialised.</p>
 */
public final class LogDirectory {
  /** Cap on a sanitised name, so a long one cannot push the path towards a filesystem limit. */
  private static final int MAX_NAME_LENGTH = 32;
  /** How many suffixed filenames to try before giving up on opening a log. */
  private static final int MAX_FILENAME_ATTEMPTS = 100;

  private final Supplier<File> root;
  private final Supplier<Logger> logger;

  public LogDirectory(Supplier<File> root, Supplier<Logger> logger) {
    this.root = root;
    this.logger = logger;
  }

  /** A log file that has been created and had its header written. */
  public record Opened(File target, String safeName) {
  }

  /**
   * Opens a log for a connection.
   *
   * @param rawName the name straight off the wire, entirely untrusted
   * @return the opened log, or null if one could not be created
   */
  public @Nullable Opened open(String rawName) {
    final String safeName = sanitiseName(rawName);
    final File targetDir = new File(root.get(), safeName);
    try {
      targetDir.mkdirs();
      final File target = createLogFile(targetDir);
      if (target == null) {
        logger.get().warning("Could not find a free log filename for " + safeName);
        return null;
      }
      writeHeader(target, rawName, safeName);
      return new Opened(target, safeName);
    } catch (IOException | SecurityException e) {
      logger.get().log(Level.WARNING, "Could not open a packet log for " + safeName, e);
      return null;
    }
  }

  /**
   * Reduces a client-supplied name to a single, safe path segment.
   *
   * <p>Anything outside {@code [A-Za-z0-9_-]} is replaced, which removes both path separators and
   * {@code .}, so {@code ..} traversal cannot survive. The result is length capped and never empty,
   * so it can always be used as a directory name.</p>
   */
  public static String sanitiseName(@Nullable String raw) {
    if (raw == null || raw.isEmpty()) {
      return "unknown";
    }
    final StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_NAME_LENGTH));
    for (int i = 0; i < raw.length() && out.length() < MAX_NAME_LENGTH; i++) {
      final char c = raw.charAt(i);
      out.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
        || c == '_' || c == '-' ? c : '_');
    }
    return out.isEmpty() ? "unknown" : out.toString();
  }

  /**
   * Creates a log file, suffixing the name until an unused one is found.
   *
   * <p>Two sessions starting in the same millisecond previously had the second silently append to
   * the first one's file, with no header of its own. Distinct client names that sanitise to the
   * same directory make that more reachable than the timestamp alone suggests.</p>
   */
  static @Nullable File createLogFile(File targetDir) throws IOException {
    final long stamp = System.currentTimeMillis();
    for (int attempt = 0; attempt < MAX_FILENAME_ATTEMPTS; attempt++) {
      final File candidate = new File(targetDir, attempt == 0 ? stamp + ".log" : stamp + "-" + attempt + ".log");
      if (candidate.createNewFile()) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * Writes a two-line preamble describing the file, so a log is interpretable on its own without
   * having to consult the source or the README.
   */
  private void writeHeader(File target, String rawName, String safeName) {
    try (Writer writer = new BufferedWriter(new FileWriter(target, StandardCharsets.UTF_8, true))) {
      // The raw name is client supplied, so it goes through the same escaping as any logged value;
      // otherwise a crafted name could forge extra log lines.
      writer.write("# muhpackets v1 player=" + LogRecord.escaped(rawName)
        + " dir=" + safeName
        + " started=" + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now()) + "\n");
      writer.write("# format: [timestamp] [protocol] [packet] key=value ...\n");
    } catch (IOException e) {
      logger.get().log(Level.WARNING, "Could not write log header to " + target, e);
    }
  }

  /**
   * Deletes logs older than {@code days}, then prunes the directories left empty behind them.
   *
   * @param days retention in days; zero or negative disables cleanup
   * @return how many files were deleted
   */
  public int prune(int days) {
    final File logsFolder = root.get();
    if (days <= 0 || !logsFolder.isDirectory()) {
      return 0;
    }
    final Instant cutoff = ZonedDateTime.now().minusDays(days).toInstant();
    final Path rootPath = logsFolder.toPath();
    final int[] deleted = {0};

    // Single pass. Collecting every stale path into a list first, then walking the whole tree again
    // to prune directories, meant holding the entire match set in memory during startup.
    try {
      Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
            try {
              Files.delete(file);
              deleted[0]++;
            } catch (IOException e) {
              logger.get().log(Level.WARNING, "Could not delete old log " + file, e);
            }
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException exc) {
          // Runs after the directory's entries, so anything emptied above is pruned here. Sessions
          // whose logs have all expired otherwise leave a directory behind forever.
          if (!dir.equals(rootPath)) {
            try (Stream<Path> entries = Files.list(dir)) {
              if (entries.findAny().isEmpty()) {
                Files.delete(dir);
              }
            } catch (IOException ignored) {
              // Best effort; a directory we cannot prune is not worth failing startup over.
            }
          }
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      logger.get().log(Level.WARNING, "Could not scan " + logsFolder + " for old logs", e);
    }
    return deleted[0];
  }
}
