package pw.valaria.muhpackets.network;

import io.netty.channel.ChannelHandlerContext;

import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
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
 *
 * <p>Registration is also the shutdown interlock. Channels are initialised on netty threads while
 * {@link #shutdown} runs on the main thread, so a connection accepted mid-sweep can register after
 * the sweep has passed it. Such a handler would be in a live pipeline and in nobody's records -
 * precisely the leak above, arrived at from the other direction - so once shut down the registry
 * refuses registrations and the caller is expected to remove itself.</p>
 *
 * <p>Guarded by a monitor rather than a concurrent set and a flag: the alternative needs a
 * double-checked add to close that window, and the untestable interleaving it leaves behind is a
 * poor trade for a lock taken once per connection, which is nothing beside accepting the socket.</p>
 */
public final class HandlerRegistry {
  private final Set<ChannelHandlerContext> contexts = new HashSet<>();
  private boolean closed;

  /**
   * Records a handler that has just been installed.
   *
   * @return whether it may stay; false means the plugin is shutting down and the caller must
   *         remove itself from the pipeline, because nothing else is going to
   */
  public synchronized boolean register(ChannelHandlerContext ctx) {
    if (closed) {
      return false;
    }
    contexts.add(ctx);
    return true;
  }

  public synchronized void unregister(ChannelHandlerContext ctx) {
    contexts.remove(ctx);
  }

  /** How many pipelines are believed to hold one of our handlers. Exists for the tests. */
  synchronized int tracked() {
    return contexts.size();
  }

  /**
   * Stops accepting registrations, then removes every handler already registered.
   *
   * @return how many were actually removed
   */
  public synchronized int shutdown(Logger logger) {
    // Set before the sweep, so anything arriving while it runs is refused rather than missed.
    closed = true;
    int removed = 0;
    // Snapshot: removing a handler triggers handlerRemoved, which unregisters it from this very
    // set. The monitor is reentrant, so that nested unregister is fine, but iterating live is not.
    for (final ChannelHandlerContext ctx : List.copyOf(contexts)) {
      try {
        // Safe from any thread; netty serialises the mutation onto the channel's event loop itself.
        ctx.pipeline().remove(ctx.handler());
        // Dropped only once the pipeline has actually let go, so what is tracked stays an honest
        // account of where our handlers still are. handlerRemoved usually gets here first.
        contexts.remove(ctx);
        removed++;
      } catch (NoSuchElementException e) {
        // Already gone - the channel closed while we were shutting down. Nothing to do.
        contexts.remove(ctx);
      } catch (Throwable thrown) {
        // One unhealthy channel must not stop us cleaning up the rest.
        logger.log(Level.WARNING, "Could not remove the packet logger from " + ctx.channel(), thrown);
      }
    }
    return removed;
  }
}
