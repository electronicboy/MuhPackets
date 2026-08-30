package pw.valaria.muhpackets;

import io.netty.channel.Channel;
import io.papermc.paper.network.ChannelInitializeListener;
import io.papermc.paper.network.ChannelInitializeListenerHolder;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;
import pw.valaria.muhpackets.logger.LogRecord;
import pw.valaria.muhpackets.logger.LoggingSession;
import pw.valaria.muhpackets.network.PacketLoggerHandler;

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
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Stream;

public final class MuhPackets extends JavaPlugin {
  Key network_key = Key.key("muhpackets", "hook");
  private MuhPacketsConfig muhPacketsConfig = new MuhPacketsConfig(this);
  private static final int MAX_SESSION_NAME_LENGTH = 32;
  /** How long onDisable waits, in 10ms steps, for an in-flight flush before giving up on it. */
  private static final int FLUSH_WAIT_ATTEMPTS = 500;
  /** How many suffixed filenames to try before giving up on opening a log. */
  private static final int MAX_FILENAME_ATTEMPTS = 100;
  private final AtomicBoolean running = new AtomicBoolean(false);
  /** Whether handlers should still buffer records; cleared first thing on disable. */
  private volatile boolean accepting;
  private @Nullable BukkitTask pollTask;

  List<LoggingSession> sessions = new CopyOnWriteArrayList<>();

  @Override
  public void onEnable() {
    saveDefaultConfig();
    io.papermc.paper.network.ChannelInitializeListenerHolder.addListener(network_key, new ChannelInitializeListener() {
      @Override
      public void afterInitChannel(Channel channel) {
        channel.pipeline().addBefore("packet_handler", "muh_logger", new PacketLoggerHandler(MuhPackets.this, channel));
      }
    });
    this.pollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::doPoll, 20, 20);

    this.reloadConfig();

    clearOldLogs(this.getMuhPacketsConfig().getClearOldFilesDays());

    this.accepting = true;
  }

  /**
   * Deletes logs older than {@code days}, then prunes the directories left empty behind them.
   *
   * @param days retention in days; zero or negative disables cleanup
   */
  private void clearOldLogs(int days) {
    final File logsFolder = logsFolder();
    if (days <= 0 || !logsFolder.isDirectory()) {
      return;
    }
    final Instant cutoff = ZonedDateTime.now().minusDays(days).toInstant();
    final Path root = logsFolder.toPath();
    final int[] deleted = {0};

    // Single pass. Collecting every stale path into a list first, then walking the whole tree again
    // to prune directories, meant holding the entire match set in memory during startup.
    try {
      Files.walkFileTree(root, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
            try {
              Files.delete(file);
              deleted[0]++;
            } catch (IOException e) {
              getLogger().log(Level.WARNING, "Could not delete old log " + file, e);
            }
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException exc) {
          // Runs after the directory's entries, so anything emptied above is pruned here. Sessions
          // whose logs have all expired otherwise leave a directory behind forever.
          if (!dir.equals(root)) {
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
      getLogger().log(Level.WARNING, "Could not scan " + logsFolder + " for old logs", e);
      return;
    }

    if (deleted[0] > 0) {
      getLogger().info("Deleted " + deleted[0] + " packet log(s) older than " + days + " day(s)");
    }
  }

  /**
   * Where session logs live.
   *
   * <p>Derived on demand rather than cached in a field: a {@link File} is just a path wrapper, and a
   * field would be null until onEnable ran, which is a lifecycle hole the package's null-marking
   * would otherwise have to lie about.</p>
   */
  private File logsFolder() {
    return new File(getDataFolder(), "logs/");
  }

  /** Whether packet handlers should keep buffering records. */
  public boolean isAccepting() {
    return accepting;
  }

  private void doPoll() {
    // Bail out if a previous flush is somehow still running. compareAndSet returns true when it
    // *acquired* the flag, so the early return has to be on the failure case.
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      final Iterator<LoggingSession> iterator = sessions.iterator();
      while (iterator.hasNext()) {
        final LoggingSession session = iterator.next();
        if (!session.process()) {
          sessions.remove(session);
          getLogger().info("Closing session: " + session.toString());
        }
      }
    } catch (Throwable thrown) {
      // Never let a failure here kill the repeating task, but do not hide it either.
      getLogger().log(Level.WARNING, "Failed to flush packet logs", thrown);
    } finally {
      running.set(false);
    }
  }

  public MuhPacketsConfig getMuhPacketsConfig() {
    return muhPacketsConfig;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    reloadConfig();
    sender.sendMessage(Component.text("Reloaded config!", NamedTextColor.GREEN));
    return true;
  }

  @Override
  public void reloadConfig() {
    super.reloadConfig();
    this.muhPacketsConfig.reload();
  }

  @Override
  public void onDisable() {
    // Stop accepting first: handlers left in live pipelines must not keep buffering into a plugin
    // that is going away, and they outlive us because we cannot remove them from open connections.
    this.accepting = false;
    ChannelInitializeListenerHolder.removeListener(network_key);
    if (this.pollTask != null) {
      this.pollTask.cancel();
      this.pollTask = null;
    }

    // Wait for an in-flight flush. The poll task is already cancelled, so at most one can be
    // running and it should finish quickly.
    boolean acquired = false;
    for (int attempt = 0; attempt < FLUSH_WAIT_ATTEMPTS; attempt++) {
      if (running.compareAndSet(false, true)) {
        acquired = true;
        break;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    if (!acquired) {
      // Draining anyway would race the flush still in progress: both threads would append to the
      // same files and both would remove from the same deques.
      getLogger().warning("A packet log flush is still in progress after "
        + (FLUSH_WAIT_ATTEMPTS / 100) + "s; skipping the final drain rather than writing the same "
        + "logs from two threads. Some buffered packets may not have been written.");
      sessions.clear();
      return;
    }

    try {
      // Anything still buffered is only in memory; without this final drain it is simply lost.
      for (final LoggingSession session : sessions) {
        session.close();
        session.process();
      }
    } catch (Throwable thrown) {
      getLogger().log(Level.WARNING, "Failed to flush packet logs on shutdown", thrown);
    } finally {
      sessions.clear();
      running.set(false);
    }
  }

  public @Nullable LoggingSession createLoggingSession(String name) {
    // 'name' arrives straight off the wire in a login hello, before the player is authenticated,
    // so it is entirely attacker controlled and must never be used as a path element unfiltered.
    final String safeName = sanitiseSessionName(name);
    final File targetDir = new File(logsFolder(), safeName);
    final File target;
    try {
      targetDir.mkdirs();
      final File created = createLogFile(targetDir);
      if (created == null) {
        getLogger().warning("Could not find a free log filename for " + safeName);
        return null;
      }
      target = created;
      writeHeader(target, name, safeName);
    } catch (IOException | SecurityException e) {
      getLogger().log(Level.WARNING, "Could not open a packet log for " + safeName, e);
      return null;
    }
    final LoggingSession loggingSession = new LoggingSession(getLogger(), name, safeName, target);
    this.sessions.add(loggingSession);
    return loggingSession;
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
      getLogger().log(Level.WARNING, "Could not write log header to " + target, e);
    }
  }

  /**
   * Reduces a client-supplied name to a single, safe path segment.
   *
   * <p>Anything outside {@code [A-Za-z0-9_-]} is replaced, which removes both path separators and
   * {@code .}, so {@code ..} traversal cannot survive. The result is length capped and never empty,
   * so it can always be used as a directory name.</p>
   */
  static String sanitiseSessionName(@Nullable String raw) {
    if (raw == null || raw.isEmpty()) {
      return "unknown";
    }
    final StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_SESSION_NAME_LENGTH));
    for (int i = 0; i < raw.length() && out.length() < MAX_SESSION_NAME_LENGTH; i++) {
      final char c = raw.charAt(i);
      out.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
        || c == '_' || c == '-' ? c : '_');
    }
    return out.isEmpty() ? "unknown" : out.toString();
  }
}
