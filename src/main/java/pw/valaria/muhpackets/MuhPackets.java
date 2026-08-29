package pw.valaria.muhpackets;

import io.netty.channel.Channel;
import io.papermc.paper.network.ChannelInitializeListener;
import io.papermc.paper.network.ChannelInitializeListenerHolder;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.AgeFileFilter;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pw.valaria.muhpackets.logger.LoggingSession;
import pw.valaria.muhpackets.network.PacketLoggerHandler;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

@DefaultQualifier(NonNull.class)
public final class MuhPackets extends JavaPlugin implements Listener {
  Key network_key = Key.key("muhpackets", "hook");
  private MuhPacketsConfig muhPacketsConfig = new MuhPacketsConfig(this);
  private static final int MAX_SESSION_NAME_LENGTH = 32;
  private final AtomicBoolean running = new AtomicBoolean(false);
  /** Whether handlers should still buffer records; cleared first thing on disable. */
  private volatile boolean accepting;
  private @Nullable BukkitTask pollTask;
  private File logsFolder;

  List<LoggingSession> sessions = new CopyOnWriteArrayList<>();

  @Override
  public void onEnable() {
    logsFolder = new File(getDataFolder(), "logs/");
    saveDefaultConfig();
    io.papermc.paper.network.ChannelInitializeListenerHolder.addListener(network_key, new ChannelInitializeListener() {
      @Override
      public void afterInitChannel(@NonNull Channel channel) {
        channel.pipeline().addBefore("packet_handler", "muh_logger", new PacketLoggerHandler(MuhPackets.this, channel));
      }
    });
    this.pollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::doPoll, 20, 20);

    this.reloadConfig();

    if (this.getMuhPacketsConfig().getClearOldFilesDays() > 0) {
      if (logsFolder.exists()) {
        final Instant retentionFilePeriod = ZonedDateTime.now()
          .minusDays(this.getMuhPacketsConfig().getClearOldFilesDays()).toInstant();
        final AgeFileFilter filter = new AgeFileFilter(retentionFilePeriod.toEpochMilli());
        final Iterator<File> fileIterator = FileUtils.listFiles(logsFolder, null, true).iterator();
        fileIterator.forEachRemaining(file -> {
          if (filter.accept(file)) {
            if (file.delete()) {
              getLogger().info("Deleting " + file.toString());
            }
          }
        });
      }
    }

    this.accepting = true;
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
  public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
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

    // Wait briefly for an in-flight flush so we do not write the same file from two threads.
    for (int attempt = 0; attempt < 100 && !running.compareAndSet(false, true); attempt++) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
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

  @Nullable
  public LoggingSession createLoggingSession(String name) {
    // 'name' arrives straight off the wire in a login hello, before the player is authenticated,
    // so it is entirely attacker controlled and must never be used as a path element unfiltered.
    final String safeName = sanitiseSessionName(name);
    final File targetDir = new File(logsFolder, safeName);
    final File target = new File(targetDir, System.currentTimeMillis() + ".log");
    try {
      targetDir.mkdirs();
      target.createNewFile();
    } catch (IOException | SecurityException e) {
      getLogger().log(Level.WARNING, "Could not open a packet log for " + safeName, e);
      return null;
    }
    final LoggingSession loggingSession = new LoggingSession(this, name, safeName, target);
    this.sessions.add(loggingSession);
    return loggingSession;
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
