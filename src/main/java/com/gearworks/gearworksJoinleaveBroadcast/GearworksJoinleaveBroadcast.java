package com.gearworks.gearworksJoinleaveBroadcast;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;

import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.logging.Logger;
import java.util.concurrent.TimeUnit;

import net.kyori.adventure.text.Component;

@Plugin(id = "gearworks-joinleave-broadcast", name = "gearworks-joinleave-broadcast", version = BuildConstants.VERSION, url = "https://www.uberswe.com", authors = {"uberswe"})
public class GearworksJoinleaveBroadcast {

    private final ProxyServer server;
    private static final int MAX_BROADCASTS_PER_MINUTE = 2;
    private static final Duration TIME_WINDOW = Duration.ofSeconds(60);
    private static final Duration WHITELIST_REJECTION_THRESHOLD = Duration.ofSeconds(5);
    // Maximum time to keep a player's join time in the map (in case disconnect event is never received)
    private static final Duration JOIN_TIME_CLEANUP_THRESHOLD = Duration.ofMinutes(30);
    // How often to run the cleanup task
    private static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(10);
    private final Map<UUID, Instant> lastJoinTime = new ConcurrentHashMap<>();
    // Track players who have joined but whose join message hasn't been broadcast yet
    private final Map<UUID, Boolean> pendingJoinBroadcasts = new ConcurrentHashMap<>();
    private final Logger logger = Logger.getLogger("GearworksJoinleaveBroadcast");

    // Each player's join/leave activity is tracked here.
    // Keys: Player UUID
    // Values: ActivityRecord that tracks recent join/leave messages
    private final Map<UUID, ActivityRecord> activityRecords = new ConcurrentHashMap<>();

    @Inject
    public GearworksJoinleaveBroadcast(ProxyServer server, @DataDirectory Path dataDirectory) {
        this.server = server;
    }

    /**
     * Called when the proxy has been initialized and the plugin is ready to start.
     * This is the appropriate place to schedule tasks that require the plugin to be registered.
     */
    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // Schedule periodic cleanup task to handle cases where disconnect events are not received
        scheduleCleanupTask();
    }

    /**
     * Schedules a periodic task to clean up stale entries in the lastJoinTime map.
     * This handles cases where a player connects but we never receive a disconnect event.
     */
    private void scheduleCleanupTask() {
        server.getScheduler()
            .buildTask(this, this::cleanupStalePlayers)
            .repeat(CLEANUP_INTERVAL.toMillis(), TimeUnit.MILLISECONDS)
            .schedule();
    }

    /**
     * Removes stale entries from the lastJoinTime and pendingJoinBroadcasts maps.
     * An entry is considered stale if it's older than JOIN_TIME_CLEANUP_THRESHOLD.
     */
    private void cleanupStalePlayers() {
        Instant cutoff = Instant.now().minus(JOIN_TIME_CLEANUP_THRESHOLD);
        int removedCount = 0;

        // Create a copy of the keys to avoid concurrent modification
        for (UUID uuid : new HashSet<>(lastJoinTime.keySet())) {
            Instant joinTime = lastJoinTime.get(uuid);
            if (joinTime != null && joinTime.isBefore(cutoff)) {
                // This player's join time is stale, they probably disconnected without us receiving the event
                lastJoinTime.remove(uuid);
                pendingJoinBroadcasts.remove(uuid);
                removedCount++;
            }
        }

        if (removedCount > 0) {
            logger.info("Cleaned up " + removedCount + " stale player entries");
        }
    }

    /**
     * Broadcasts a join message for the specified player.
     */
    private void broadcastJoinMessage(Player player) {
        UUID playerUUID = player.getUniqueId();
        if (canBroadcast(playerUUID, true)) {
            server.getAllPlayers().forEach(p -> {
                p.sendMessage(ComponentUtil.text(player.getUsername() + " has joined Gearworks"));
            });
            recordActivity(playerUUID, true);
        }
        // Remove from pending broadcasts
        pendingJoinBroadcasts.remove(playerUUID);
    }

    @Subscribe
    public void onPlayerJoin(PostLoginEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        String username = player.getUsername();

        // Record the join time for whitelist rejection detection
        lastJoinTime.put(playerUUID, Instant.now());

        // Mark this player as having a pending join broadcast
        pendingJoinBroadcasts.put(playerUUID, true);

        // Log the join attempt (this will always be logged, even if not broadcast)
        logger.info(username + " joined the server");

        // Schedule a delayed broadcast of the join message
        // This gives time to detect whitelist rejections
        server.getScheduler()
            .buildTask(this, () -> {
                // Only broadcast if the player is still online and still has a pending broadcast
                if (player.isActive() && pendingJoinBroadcasts.containsKey(playerUUID)) {
                    broadcastJoinMessage(player);
                }
            })
            .delay((int)WHITELIST_REJECTION_THRESHOLD.toMillis(), TimeUnit.MILLISECONDS)
            .schedule();
    }

    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        String username = player.getUsername();

        // Check if this player has a pending join broadcast
        boolean hasPendingJoinBroadcast = pendingJoinBroadcasts.remove(playerUUID) != null;

        // Log the disconnect (this will always be logged, even if not broadcast)
        logger.info(username + " left the server");

        if (hasPendingJoinBroadcast) {
            // Player left before their join message was broadcast
            // This is likely a whitelist rejection - don't broadcast either message
            logger.info(username + " was rejected (likely by whitelist)");
            // Still record the activity to maintain rate limiting
            recordActivity(playerUUID, false);
        } else if (canBroadcast(playerUUID, false)) {
            // Normal disconnect - broadcast the leave message
            Component message = Component.text(username + " has left Gearworks");
            server.getAllPlayers().forEach(p -> p.sendMessage(message));
            recordActivity(playerUUID, false);
        }

        // Clean up the join time entry
        lastJoinTime.remove(playerUUID);
    }

    private boolean canBroadcast(UUID uuid, boolean isJoin) {
        ActivityRecord record = activityRecords.computeIfAbsent(uuid, k -> new ActivityRecord());
        return record.canBroadcast(isJoin);
    }

    private void recordActivity(UUID uuid, boolean isJoin) {
        ActivityRecord record = activityRecords.computeIfAbsent(uuid, k -> new ActivityRecord());
        record.recordEvent(isJoin);
    }

    /**
     * Holds recent join/leave broadcast timestamps for a player.
     */
    private static class ActivityRecord {
        private final Deque<Instant> joinEvents = new ArrayDeque<>();
        private final Deque<Instant> leaveEvents = new ArrayDeque<>();

        public boolean canBroadcast(boolean isJoin) {
            cleanUp();

            Deque<Instant> queue = isJoin ? joinEvents : leaveEvents;
            return queue.size() < MAX_BROADCASTS_PER_MINUTE;
        }

        public void recordEvent(boolean isJoin) {
            Deque<Instant> queue = isJoin ? joinEvents : leaveEvents;
            queue.addLast(Instant.now());
        }

        /**
         * Remove timestamps older than TIME_WINDOW from both queues.
         */
        private void cleanUp() {
            Instant cutoff = Instant.now().minus(TIME_WINDOW);
            while (!joinEvents.isEmpty() && joinEvents.peekFirst().isBefore(cutoff)) {
                joinEvents.pollFirst();
            }
            while (!leaveEvents.isEmpty() && leaveEvents.peekFirst().isBefore(cutoff)) {
                leaveEvents.pollFirst();
            }
        }
    }
}
