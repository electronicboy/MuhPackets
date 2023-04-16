package pw.valaria.muhpackets;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class MuhPacketsConfig {
  private final MuhPackets muhPackets;
  private boolean logPlayOnly = true;
  private boolean skipMovePackets;
  private Set<String> ignoredPackets = new HashSet<>();

  public MuhPacketsConfig(MuhPackets muhPackets) {
    this.muhPackets = muhPackets;
  }

  public void reload() {
    this.logPlayOnly = muhPackets.getConfig().getBoolean("only-log-play");
    this.skipMovePackets= muhPackets.getConfig().getBoolean("skip-move-packets");
    this.ignoredPackets = Set.copyOf(muhPackets.getConfig().getStringList("ignored-packets"));
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
}
