package pw.valaria.muhpackets.network;

import io.netty.channel.ChannelHandlerContext;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tracks the pipelines this plugin has inserted a handler into, so they can be cleaned up again.
 *
 * <p>Paper hands out a channel when a connection is initialised but offers no way to enumerate the
 * connections that are already open, so the only reliable record of where our handlers ended up is
 * the one we keep ourselves. Contexts are captured in {@code handlerAdded} and dropped in
 * {@code handlerRemoved}, which netty also invokes when a channel closes, so entries do not
 * accumulate for connections that have gone away.</p>
 *
 * <p>Without this, a handler left in a live pipeline keeps a strong reference to the plugin, and
 * through it the plugin's classloader, for as long as that connection stays open - so every reload
 * would leak an entire plugin generation.</p>
 */
public final class HandlerRegistry {
  private final Set<ChannelHandlerContext> contexts = ConcurrentHashMap.newKeySet();

  public void register(ChannelHandlerContext ctx) {
    contexts.add(ctx);
  }

  public void unregister(ChannelHandlerContext ctx) {
    contexts.remove(ctx);
  }

  /** How many pipelines are currently believed to hold one of our handlers. */
  public int tracked() {
    return contexts.size();
  }

  /**
   * Removes every tracked handler from its pipeline.
   *
   * @return how many were actually removed
   */
  public int removeAll(Logger logger) {
    int removed = 0;
    // Snapshot: removing a handler triggers handlerRemoved, which unregisters it from this very set.
    for (final ChannelHandlerContext ctx : List.copyOf(contexts)) {
      contexts.remove(ctx);
      try {
        // Safe from any thread; netty serialises the mutation onto the channel's event loop itself.
        ctx.pipeline().remove(ctx.handler());
        removed++;
      } catch (NoSuchElementException e) {
        // Already gone - the channel closed while we were shutting down. Nothing to do.
      } catch (Throwable thrown) {
        // One unhealthy channel must not stop us cleaning up the rest.
        logger.log(Level.WARNING, "Could not remove the packet logger from " + ctx.channel(), thrown);
      }
    }
    return removed;
  }
}
