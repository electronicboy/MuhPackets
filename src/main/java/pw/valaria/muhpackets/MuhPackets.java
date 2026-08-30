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
import pw.valaria.muhpackets.logger.LogDirectory;
import pw.valaria.muhpackets.logger.LoggingSession;
import pw.valaria.muhpackets.logger.RecordBudget;
import pw.valaria.muhpackets.network.HandlerRegistry;
import pw.valaria.muhpackets.network.SessionRateLimiter;
import pw.valaria.muhpackets.network.PacketLoggerHandler;

import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class MuhPackets extends JavaPlugin {
  Key network_key = Key.key("muhpackets", "hook");
  private MuhPacketsConfig muhPacketsConfig = new MuhPacketsConfig(this);
  /** How long onDisable waits, in 10ms steps, for an in-flight flush before giving up on it. */
  private static final int FLUSH_WAIT_ATTEMPTS = 500;
  private final AtomicBoolean running = new AtomicBoolean(false);
  /**
   * Server-wide ceiling on buffered records, shared by every session. Starts unlimited and is
   * given its real capacity by the reloadConfig() call in onEnable.
   */
  private final RecordBudget recordBudget = new RecordBudget(0);
  /** Connections not logged because the session limit was already in use, pending a report. */
  private final AtomicLong refusedSessions = new AtomicLong();
  /** Connections not logged because logins were arriving too fast, pending a report. */
  private final AtomicLong rateLimitedSessions = new AtomicLong();
  /**
   * Ceiling on how fast new sessions may be opened. Starts unlimited and is given its real limits
   * by the reloadConfig() call in onEnable.
   */
  private final SessionRateLimiter sessionRateLimiter = new SessionRateLimiter(System::nanoTime, 0, 0);
  /** Every pipeline we have inserted a handler into, so onDisable can take them out again. */
  private final HandlerRegistry handlers = new HandlerRegistry();
  /** Naming, opening and expiry of the files themselves. */
  private final LogDirectory logs = new LogDirectory(this::logsFolder, this::getLogger);
  private @Nullable BukkitTask pollTask;

  List<LoggingSession> sessions = new CopyOnWriteArrayList<>();

  @Override
  public void onEnable() {
    // Startup is the mirror of shutdown, and the order is the point: everything a connection
    // depends on is in place before any connection can arrive. The listener used to go on first,
    // which left a window where handlers were installed into a plugin still reading its config.
    saveDefaultConfig();
    this.reloadConfig();

    final int deleted = logs.prune(this.getMuhPacketsConfig().getClearOldFilesDays());
    if (deleted > 0) {
      getLogger().info("Deleted " + deleted + " packet log(s) older than "
        + this.getMuhPacketsConfig().getClearOldFilesDays() + " day(s)");
    }

    this.pollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::doPoll, 20, 20);
    this.handlers.open();

    // Last: from here connections can arrive at any moment, on threads that are not this one.
    // A previous generation of this plugin may still be registered if a reload left one behind;
    // addListener would otherwise stack a second listener under the same key.
    if (ChannelInitializeListenerHolder.hasListener(network_key)) {
      ChannelInitializeListenerHolder.removeListener(network_key);
    }
    ChannelInitializeListenerHolder.addListener(network_key, new ChannelInitializeListener() {
      @Override
      public void afterInitChannel(Channel channel) {
        channel.pipeline().addBefore("packet_handler", "muh_logger", new PacketLoggerHandler(MuhPackets.this, channel));
      }
    });
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

  /** The pipelines this plugin has installed handlers into. */
  public HandlerRegistry handlers() {
    return handlers;
  }

  /**
   * Whether packet handlers should keep buffering records.
   *
   * <p>Delegated rather than duplicated. The plugin used to keep its own flag alongside the
   * registry's, and the gap between the two is where a connection accepted mid-shutdown could slip
   * a handler back into a live pipeline.</p>
   */
  public boolean isAccepting() {
    return handlers.isAccepting();
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
      reportUnloggedConnections();
    } catch (Throwable thrown) {
      // Never let a failure here kill the repeating task, but do not hide it either.
      getLogger().log(Level.WARNING, "Failed to flush packet logs", thrown);
    } finally {
      running.set(false);
    }
  }

  /**
   * Reports connections that went unlogged, once per flush rather than once per connection.
   *
   * <p>A login flood is exactly when this fires, so logging each refusal individually would turn
   * the console into the same flood. The count is also the measurement: it is the difference
   * between the login rate we recorded and the one actually arriving.</p>
   */
  private void reportUnloggedConnections() {
    final long rateLimited = rateLimitedSessions.getAndSet(0);
    if (rateLimited > 0) {
      getLogger().warning("Did not log " + rateLimited + " connection(s): logins arriving faster than "
        + getMuhPacketsConfig().getMaxSessionsPerSecond() + " per second");
    }
    final long refused = refusedSessions.getAndSet(0);
    if (refused > 0) {
      getLogger().warning("Did not log " + refused + " connection(s): already at the limit of "
        + getMuhPacketsConfig().getMaxSessions() + " concurrent sessions");
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
    // The budget outlives any single reload, so it is retuned rather than replaced; lowering it
    // below current usage simply refuses new records until the excess has drained.
    this.recordBudget.setCapacity(this.muhPacketsConfig.getMaxTotalBufferedRecords());
    this.sessionRateLimiter.reconfigure(this.muhPacketsConfig.getMaxSessionsPerSecond(),
      this.muhPacketsConfig.getMaxSessionBurst());
  }

  @Override
  public void onDisable() {
    // Shutdown in three ordered steps, and the order is load-bearing.
    // 1. Stop new connections reaching us at all.
    ChannelInitializeListenerHolder.removeListener(network_key);

    // 2. Close the gate and take our handlers back out of the pipelines they are still sitting in.
    // Both happen under one lock, so a connection accepted while it runs is refused rather than
    // slipping in behind the sweep. Each handler holds a reference to this plugin instance, and
    // through it this plugin's classloader, so leaving one behind leaks a whole plugin generation
    // per open connection every time the server reloads.
    final int removed = handlers.shutdown(getLogger());
    if (removed > 0) {
      getLogger().info("Removed the packet logger from " + removed + " open connection(s)");
    }

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
    // The same signal the handler path uses. A packet already in flight can reach here after
    // shutdown started, and opening a log for it would create a session nothing will ever drain.
    if (!isAccepting()) {
      return null;
    }

    // Both checks come before anything touches the disk. Opening a session costs a mkdirs, a
    // createNewFile and a header write on the netty thread, so under a login flood the refusal has
    // to happen before that work, not after it.
    if (!sessionRateLimiter.tryAcquire()) {
      rateLimitedSessions.incrementAndGet();
      return null;
    }
    // Off by default, and a last-resort ceiling rather than the flood defence, so concurrent
    // logins overshooting it slightly does not matter.
    final int maxSessions = getMuhPacketsConfig().getMaxSessions();
    if (maxSessions > 0 && sessions.size() >= maxSessions) {
      refusedSessions.incrementAndGet();
      return null;
    }

    // 'name' arrives straight off the wire in a login hello, before the player is authenticated,
    // so it is entirely attacker controlled; LogDirectory is what keeps it away from the path.
    final LogDirectory.Opened opened = logs.open(name);
    if (opened == null) {
      return null;
    }
    final LoggingSession loggingSession = new LoggingSession(getLogger(), name, opened.safeName(),
      opened.target(), () -> getMuhPacketsConfig().getMaxBufferedRecords(), recordBudget);
    this.sessions.add(loggingSession);
    return loggingSession;
  }
}
