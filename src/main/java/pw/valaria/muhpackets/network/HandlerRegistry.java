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
 * <p>This also holds the plugin's one shutdown signal, and it lives here rather than on the plugin
 * for a specific reason: admitting a handler has to be indivisible from the shutdown sweep.
 * Channels are initialised on netty threads while {@link #shutdown} runs on the main thread, so a
 * connection accepted mid-sweep can otherwise register after the sweep has passed it, leaving a
 * handler in a live pipeline and in nobody's records - precisely the leak above, arrived at from
 * the other direction. A flag anywhere else is read outside this monitor and reopens that window,
 * which is what a second, separate "accepting" flag on the plugin used to do.</p>
 *
 * <p>One field, three states, so the ordering is in the type rather than in the order some
 * statements happen to appear in: nothing is admitted before {@link #open} or after
 * {@link #shutdown}, and shutdown is terminal.</p>
 *
 * <p>Guarded by a monitor rather than a concurrent set and a flag: the alternative needs a
 * double-checked add to close that window, and the untestable interleaving it leaves behind is a
 * poor trade for a lock taken once per connection, which is nothing beside accepting the socket.</p>
 */
public final class HandlerRegistry {
  /** Lifecycle of the plugin's network side, and the only shutdown signal there is. */
  private enum State {
    /** Enabling: the plugin is not ready to take connections yet. */
    NEW,
    /** Enabled and taking connections. */
    OPEN,
    /** Disabled. Terminal - a reload gets a new plugin instance and a new registry. */
    CLOSED
  }

  private final Set<ChannelHandlerContext> contexts = new HashSet<>();
  /**
   * Volatile so the packet path can read it without contending for the monitor. Registration and
   * shutdown read it under the monitor instead, which is what makes those two indivisible.
   */
  private volatile State state = State.NEW;

  /**
   * Whether the plugin is up and work should still be accepted.
   *
   * <p>The cheap read, for the packet path. Registration must not use this: checking here and
   * acting afterwards is exactly the race the monitor exists to prevent.</p>
   */
  public boolean isAccepting() {
    return state == State.OPEN;
  }

  /** Opens the gate once the plugin has finished enabling. */
  public synchronized void open() {
    if (state == State.NEW) {
      state = State.OPEN;
    }
  }

  /**
   * Records a handler that has just been installed.
   *
   * @return whether it may stay; false means the plugin is shutting down and the caller must
   *         remove itself from the pipeline, because nothing else is going to
   */
  public synchronized boolean register(ChannelHandlerContext ctx) {
    if (state != State.OPEN) {
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
    // Moving this below the loop reopens the window this class exists to close.
    state = State.CLOSED;
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
