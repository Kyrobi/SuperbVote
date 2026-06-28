package io.minimum.minecraft.superbvote.votes;

import io.minimum.minecraft.superbvote.SuperbVote;
import io.minimum.minecraft.superbvote.configuration.message.MessageContext;
import io.minimum.minecraft.superbvote.storage.VoteStorage;
import io.minimum.minecraft.superbvote.util.PlayerVotes;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class VoteReminder implements Runnable {
    private final Map<UUID, Long> lastReminded = new ConcurrentHashMap<>();
    private static final long REMINDER_COOLDOWN_MS = 3 * 60 * 60 * 1000; // ponytail: 3h cooldown between reminders

    @Override
    public void run() {
        List<UUID> onlinePlayers = Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("superbvote.notify"))
                .map(Player::getUniqueId)
                .collect(Collectors.toList());

        VoteStorage voteStorage = SuperbVote.getPlugin().getVoteStorage();
        int cooldownSeconds = SuperbVote.getPlugin().getConfig().getInt("votes.cooldown-per-service", 3600);
        long now = System.currentTimeMillis();
        List<PlayerVotes> toRemind = voteStorage.getPlayersNeedingReminder(onlinePlayers, cooldownSeconds);
        for (PlayerVotes pv : toRemind) {
            Long last = lastReminded.get(pv.getUuid());
            if (last != null && (now - last) < REMINDER_COOLDOWN_MS) continue;
            Player player = Bukkit.getPlayer(pv.getUuid());
            if (player != null) {
                lastReminded.put(pv.getUuid(), now);
                MessageContext context = new MessageContext(null, pv, player);
                SuperbVote.getPlugin().getConfiguration().getReminderMessage().sendAsReminder(player, context);
            }
        }
    }
}
