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

@DefaultQualifier(NonNull.class)
public final class MuhPackets extends JavaPlugin implements Listener {
  Key network_key = Key.key("muhpackets", "hook");
  private MuhPacketsConfig muhPacketsConfig = new MuhPacketsConfig(this);
  private final AtomicBoolean running = new AtomicBoolean(false);
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
    Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::doPoll, 20, 20);

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
  }

  private void doPoll() {
    if (running.compareAndSet(false, true)) {
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
    } catch (Throwable ignored) {
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
    ChannelInitializeListenerHolder.removeListener(network_key);
    this.running.set(false);
  }

  @Nullable
  public LoggingSession createLoggingSession(String name) {
    final File targetDir = new File(logsFolder, name);
    final File target = new File(targetDir, System.currentTimeMillis() + ".log");
    try {
      target.getParentFile().mkdirs();
      target.createNewFile();
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
    final LoggingSession loggingSession = new LoggingSession(this, name, target);
    this.sessions.add(loggingSession);
    return loggingSession;
  }
}
