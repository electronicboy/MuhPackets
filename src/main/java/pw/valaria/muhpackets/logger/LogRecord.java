package pw.valaria.muhpackets.logger;

import io.papermc.paper.util.ObfHelper;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.phys.HitResult;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class LogRecord {
  private final static int classIndex = "net.minecraft.network.protocol.".length();
  private final static DateTimeFormatter DEFAULT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
  private final ConnectionProtocol protocol;
  private final Packet<?> msg;

  public LogRecord(ConnectionProtocol protocol, Packet<?> msg) {

    this.protocol = protocol;
    this.msg = msg;
  }

  public void write(Writer writer, boolean writeFields, Set<String> ignoredPackets) throws IOException {
    final String deobf = ObfHelper.INSTANCE.deobfClassName(msg.getClass().getName()).substring(classIndex);
    if (!ignoredPackets.isEmpty() && ignoredPackets.contains(deobf.substring(deobf.lastIndexOf('.') + 1))) {
      return;
    }

    final Map<String, String> fields = writeFields ? populateFieldMap() : Collections.emptyMap();
    final String time = DEFAULT.format(Instant.now());
    writer.write("[%s] [%s] [%s] %s\n".formatted(time, protocol, deobf, fields));
  }

  private Map<String, String> populateFieldMap() {
    HashMap<String, String> out = new HashMap<>();

    Class<?> clazz = msg.getClass();
    while (clazz != null) {
      for (Field declaredField : clazz.getDeclaredFields()) {
        try {
          declaredField.setAccessible(true);
          if (shouldLogField(declaredField, msg)) {

            out.put(declaredField.getName(), parseValue(declaredField.get(msg)));

          }
        } catch (Throwable ignored) {
        }
      }
      clazz = clazz.getSuperclass();
    }

    return out;

  }

  private boolean shouldLogField(Field field, Packet<?> msg) {
    if (Modifier.isStatic(field.getModifiers())) {
      return false;
    }

    if (field.getType() == FriendlyByteBuf.class) {
      return false;
    }

    if (field.getType() == MessageSignature.class) {
      return false;
    }

    if (field.getType() == LastSeenMessages.Update.class) {
      return false;
    }

    return true;
  }

  private String parseValue(Object object) {
    if (object == null) return "null";

    if (object instanceof HitResult hitResult) {
      return "%s{pos=%s,type=%s}".formatted(object.getClass().getSimpleName(), hitResult.getLocation(), hitResult.getType());
    } else if (object instanceof RemoteChatSession.Data data) {
      return "%s{expiresAt=%s}".formatted("RemoteChatSession.Data", DEFAULT.format(data.profilePublicKey().expiresAt()));
    }
    return object.toString();
  }
}
