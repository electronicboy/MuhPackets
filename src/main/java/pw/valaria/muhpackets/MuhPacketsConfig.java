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
  private volatile int maxBufferedRecords = 100_000;
  private volatile int maxTotalBufferedRecords = 250_000;
  private volatile int maxSessions = 200;

  public MuhPacketsConfig(MuhPackets muhPackets) {
    this.muhPackets = muhPackets;
  }

  public void reload() {
    this.logPlayOnly = muhPackets.getConfig().getBoolean("only-log-play", true);
    this.skipMovePackets = muhPackets.getConfig().getBoolean("skip-move-packets", true);
    this.ignoredPackets = Set.copyOf(muhPackets.getConfig().getStringList("ignored-packets"));
    this.clearOldFilesDays = muhPackets.getConfig().getInt("clear-old-files-days", -1);
    this.maxBufferedRecords = muhPackets.getConfig().getInt("max-buffered-records", 100_000);
    this.maxTotalBufferedRecords = muhPackets.getConfig().getInt("max-total-buffered-records", 250_000);
    this.maxSessions = muhPackets.getConfig().getInt("max-sessions", 200);
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

  /** Records one session may buffer; zero or below means unlimited. */
  public int getMaxBufferedRecords() {
    return maxBufferedRecords;
  }

  /** Records all sessions may buffer between them; zero or below means unlimited. */
  public int getMaxTotalBufferedRecords() {
    return maxTotalBufferedRecords;
  }

  /** Sessions that may exist at once; zero or below means unlimited. */
  public int getMaxSessions() {
    return maxSessions;
  }
}
