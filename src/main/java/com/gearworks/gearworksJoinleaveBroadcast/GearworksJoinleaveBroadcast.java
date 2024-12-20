package com.gearworks.gearworksJoinleaveBroadcast;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
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

import net.kyori.adventure.text.Component;

@Plugin(id = "gearworks-joinleave-broadcast", name = "gearworks-joinleave-broadcast", version = BuildConstants.VERSION, url = "https://www.uberswe.com", authors = {"uberswe"})
public class GearworksJoinleaveBroadcast {

    private final ProxyServer server;
    private static final int MAX_BROADCASTS_PER_MINUTE = 2;
    private static final Duration TIME_WINDOW = Duration.ofSeconds(60);

    // Each player's join/leave activity is tracked here.
    // Keys: Player UUID
    // Values: ActivityRecord that tracks recent join/leave messages
    private final Map<UUID, ActivityRecord> activityRecords = new ConcurrentHashMap<>();

    @Inject
    public GearworksJoinleaveBroadcast(ProxyServer server, @DataDirectory Path dataDirectory) {
        this.server = server;
    }

    @Subscribe
    public void onPlayerJoin(PostLoginEvent event) {
        Player player = event.getPlayer();
        if (canBroadcast(player.getUniqueId(), true)) {
            // Broadcast the join message
            server.getAllPlayers().forEach(p -> {
                p.sendMessage(ComponentUtil.text(player.getUsername() + " has joined Gearworks"));
            });
            recordActivity(player.getUniqueId(), true);
        }
    }

    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        if (canBroadcast(player.getUniqueId(), false)) {
            // Broadcast the leave message
            Component message = Component.text(player.getUsername() + " has left Gearworks");
            server.getAllPlayers().forEach(p -> p.sendMessage(message));
            recordActivity(player.getUniqueId(), false);
        }
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
