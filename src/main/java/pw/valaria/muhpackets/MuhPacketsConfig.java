package pw.valaria.muhpackets;

import java.util.Set;

/**
 * Typed view of config.yml.
 *
 * <p>Reloaded from the main thread while netty threads read it, so every field is volatile: without
 * that there is no guarantee a reload is ever visible to the threads doing the logging.</p>
 */
public class MuhPacketsConfig {
  private final MuhPackets muhPackets;
  private volatile boolean logPlayOnly = true;
  private volatile boolean skipMovePackets;
  private volatile Set<String> ignoredPackets = Set.of();
  private volatile int clearOldFilesDays = -1;

  public MuhPacketsConfig(MuhPackets muhPackets) {
    this.muhPackets = muhPackets;
  }

  public void reload() {
    this.logPlayOnly = muhPackets.getConfig().getBoolean("only-log-play", true);
    this.skipMovePackets = muhPackets.getConfig().getBoolean("skip-move-packets", true);
    this.ignoredPackets = Set.copyOf(muhPackets.getConfig().getStringList("ignored-packets"));
    this.clearOldFilesDays = muhPackets.getConfig().getInt("clear-old-files-days", -1);
  }

  public boolean isLogPlayOnly() {
    return logPlayOnly;
  }

  public boolean isSkipMovePackets() {
    return skipMovePackets;
  }

  public Set<String> getIgnoredPackets() {
    return ignoredPackets;
  }

  public int getClearOldFilesDays() {
    return clearOldFilesDays;
  }
}
