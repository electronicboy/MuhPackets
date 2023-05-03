package pw.valaria.muhpackets;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class MuhPacketsConfig {
  private final MuhPackets muhPackets;
  private boolean logPlayOnly = true;
  private boolean skipMovePackets;
  private Set<String> ignoredPackets = new HashSet<>();
  private int clearOldFilesDays = -1;

  CompressionSchema compressionSchema = CompressionSchema.NONE;

  public MuhPacketsConfig(MuhPackets muhPackets) {
    this.muhPackets = muhPackets;
  }

  public void reload() {
    this.logPlayOnly = muhPackets.getConfig().getBoolean("only-log-play");
    this.skipMovePackets = muhPackets.getConfig().getBoolean("skip-move-packets");
    this.ignoredPackets = Set.copyOf(muhPackets.getConfig().getStringList("ignored-packets"));
    final String compressionSchema = muhPackets.getConfig().getString("compress-old-files-schema");
    CompressionSchema schema = CompressionSchema.NONE;
    try {
      if (compressionSchema != null) {
        schema = CompressionSchema.valueOf(compressionSchema.toUpperCase(Locale.ROOT));
      }
    } catch (IllegalArgumentException ex) {
      ex.printStackTrace();
    }
    this.compressionSchema = schema;


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

  public CompressionSchema getCompressionSchema() {
    return compressionSchema;
  }

  public int getClearOldFilesDays() {
    return clearOldFilesDays;
  }
}
