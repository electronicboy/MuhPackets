package pw.valaria.muhpackets;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class MuhPacketsConfig {
  private final MuhPackets muhPackets;
  private boolean logPlayOnly = true;
  private boolean skipMovePackets;
  private Set<String> ignoredPackets = new HashSet<>();
  private int clearOldFilesDays = -1;

  public MuhPacketsConfig(MuhPackets muhPackets) {
    this.muhPackets = muhPackets;
  }

  public void reload() {
    this.logPlayOnly = muhPackets.getConfig().getBoolean("only-log-play");
    this.skipMovePackets= muhPackets.getConfig().getBoolean("skip-move-packets");
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
