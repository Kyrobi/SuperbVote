package io.minimum.minecraft.superbvote.storage;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import io.minimum.minecraft.superbvote.SuperbVote;
import io.minimum.minecraft.superbvote.configuration.StreaksConfiguration;
import io.minimum.minecraft.superbvote.util.PlayerVotes;
import io.minimum.minecraft.superbvote.votes.Vote;
import io.minimum.minecraft.superbvote.votes.VoteStreak;
import org.bukkit.entity.Player;

import java.io.File;
import java.lang.reflect.Type;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class SqliteVoteStorage implements ExtendedVoteStorage {
    private final String databaseUrl;
    private final String tableName, streaksTableName;
    private final boolean readOnly;

    private final Type servicesMapType = new TypeToken<Map<String, Long>>(){}.getType();
    private final Gson gson = new Gson();

    public SqliteVoteStorage(File databaseFile, String tableName, String streaksTableName, boolean readOnly) {
        this.databaseUrl = "jdbc:sqlite:" + databaseFile.getAbsolutePath();
        this.tableName = tableName;
        this.streaksTableName = streaksTableName;
        this.readOnly = readOnly;
    }

    public void initialize() {
        if (readOnly) return;

        try (Connection connection = getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("PRAGMA journal_mode=WAL");
            }

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tableName +
                        " (uuid TEXT PRIMARY KEY NOT NULL, last_name TEXT, votes INTEGER NOT NULL, last_vote INTEGER NOT NULL DEFAULT (strftime('%s', 'now')))");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS uuid_votes_idx ON " + tableName + " (uuid, votes)");
            }

            if (SuperbVote.getPlugin().getConfiguration().getStreaksConfiguration().isEnabled()) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + streaksTableName +
                            " (uuid TEXT PRIMARY KEY NOT NULL, streak INTEGER NOT NULL DEFAULT 1, days INTEGER NOT NULL DEFAULT 1, " +
                            "last_day TEXT NOT NULL DEFAULT (date('now')), services TEXT NOT NULL DEFAULT '{}')");
                    statement.executeUpdate("CREATE INDEX IF NOT EXISTS uuid_last_day_idx ON " + streaksTableName + " (uuid, last_day)");
                }
            }
        } catch (SQLException e) {
            SuperbVote.getPlugin().getLogger().log(Level.SEVERE, "Unable to initialize database", e);
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(databaseUrl);
    }

    @Override
    public void addVote(Vote vote) {
        if (readOnly) return;

        Preconditions.checkNotNull(vote, "vote");
        try (Connection connection = getConnection()) {
            long epochSecond = vote.getReceived().getTime() / 1000;
            if (vote.getName() != null) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO " + tableName + " (uuid, last_name, votes, last_vote) VALUES (?, ?, 1, ?)" +
                                " ON CONFLICT(uuid) DO UPDATE SET votes = votes + 1, last_name = ?, last_vote = ?")) {
                    statement.setString(1, vote.getUuid().toString());
                    statement.setString(2, vote.getName());
                    statement.setLong(3, epochSecond);
                    statement.setString(4, vote.getName());
                    statement.setLong(5, epochSecond);
                    statement.executeUpdate();
                }
            } else {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO " + tableName + " (uuid, votes, last_vote) VALUES (?, 1, ?)" +
                                " ON CONFLICT(uuid) DO UPDATE SET votes = votes + 1, last_vote = ?")) {
                    statement.setString(1, vote.getUuid().toString());
                    statement.setLong(2, epochSecond);
                    statement.setLong(3, epochSecond);
                    statement.executeUpdate();
                }
            }

            if (SuperbVote.getPlugin().getConfiguration().getStreaksConfiguration().isEnabled()) {
                VoteStreak currentStreak = getVoteStreak(vote.getUuid(), true);
                String servicesJson = gson.toJson(Collections.singletonMap(vote.getServiceName(), epochSecond));
                if (currentStreak.getCount() == 0 && currentStreak.getDays() == 0) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO " + streaksTableName + " (uuid, streak, days, services, last_day) VALUES (?, 1, 1, ?, date('now'))" +
                                    " ON CONFLICT(uuid) DO UPDATE SET streak = 1, days = 1, services = ?, last_day = date('now')")) {
                        statement.setString(1, vote.getUuid().toString());
                        statement.setString(2, servicesJson);
                        statement.setString(3, servicesJson);
                        statement.executeUpdate();
                    }
                } else {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO " + streaksTableName + " (uuid, streak, days, services, last_day) VALUES (?, 1, 1, ?, date('now'))" +
                                    " ON CONFLICT(uuid) DO UPDATE SET streak = streak + 1," +
                                    " days = days + MIN(1, CAST(julianday('now') - julianday(last_day) AS INTEGER))," +
                                    " services = ?, last_day = date('now')")) {
                        statement.setString(1, vote.getUuid().toString());
                        statement.setString(2, servicesJson);
                        statement.setString(3, servicesJson);
                        statement.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to add vote for " + vote.getUuid().toString(), e);
        }
    }

    public void updateName(Player player) {
        if (readOnly) return;

        Preconditions.checkNotNull(player, "player");
        try (Connection connection = getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + tableName + " SET last_name = ? WHERE uuid = ?")) {
                statement.setString(1, player.getName());
                statement.setString(2, player.getUniqueId().toString());
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to update name for " + player.toString(), e);
        }
    }

    @Override
    public void setVotes(UUID player, int votes, long ts) {
        if (readOnly) return;

        Preconditions.checkNotNull(player, "player");
        try (Connection connection = getConnection()) {
            long epochSecond = ts / 1000;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + tableName + " (uuid, votes, last_vote) VALUES (?, ?, ?)" +
                            " ON CONFLICT(uuid) DO UPDATE SET votes = ?, last_vote = ?")) {
                statement.setString(1, player.toString());
                statement.setInt(2, votes);
                statement.setLong(3, epochSecond);
                statement.setInt(4, votes);
                statement.setLong(5, epochSecond);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to set votes for " + player.toString(), e);
        }
    }

    @Override
    public void clearVotes() {
        if (readOnly) return;

        try (Connection connection = getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + tableName)) {
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            SuperbVote.getPlugin().getLogger().log(Level.SEVERE, "Unable to clear votes", e);
        }
    }

    @Override
    public PlayerVotes getVotes(UUID player) {
        Preconditions.checkNotNull(player, "player");
        try (Connection connection = getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT last_name, votes FROM " + tableName + " WHERE uuid = ?")) {
                statement.setString(1, player.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return new PlayerVotes(player, resultSet.getString(1), resultSet.getInt(2), PlayerVotes.Type.CURRENT);
                    }
                    return new PlayerVotes(player, null, 0, PlayerVotes.Type.CURRENT);
                }
            }
        } catch (SQLException e) {
            SuperbVote.getPlugin().getLogger().log(Level.SEVERE, "Unable to get votes for " + player.toString(), e);
            return new PlayerVotes(player, null, 0, PlayerVotes.Type.CURRENT);
        }
    }

    @Override
    public List<PlayerVotes> getTopVoters(int amount, int page) {
        int offset = page * amount;
        try (Connection connection = getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT uuid, last_name, votes FROM " + tableName + " WHERE votes > 0 ORDER BY votes DESC LIMIT ? OFFSET ?")) {
                statement.setInt(1, amount);
                statement.setInt(2, offset);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<PlayerVotes> records = new ArrayList<>();
                    while (resultSet.next()) {
                        UUID uuid = UUID.fromString(resultSet.getString(1));
                        String name = resultSet.getString(2);
                        records.add(new PlayerVotes(uuid, name, resultSet.getInt(3), PlayerVotes.Type.CURRENT));
                    }
                    return records;
                }
            }
        } catch (SQLException e) {
            SuperbVote.getPlugin().getLogger().log(Level.SEVERE, "Unable to get top votes", e);
            return Collections.emptyList();
        }
    }

    @Override
    public int getPagesAvailable(int amount) {
        try (Connection connection = getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(uuid) FROM " + tableName)) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    int count = resultSet.next() ? resultSet.getInt(1) : 0;
                    return (int) Math.ceil(count / (double) amount);
                }
            }
        } catch (SQLException e) {
            SuperbVote.getPlugin().getLogger().log(Level.SEVERE, "Unable to get top votes page count", e);
            return 0;
        }
    }

    @Override
    public boolean hasVotedToday(UUID player) {
        try (Connection connection = getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM " + tableName + " WHERE uuid = ? AND date(last_vote, 'unixepoch') = date('now')")) {
                statement.setString(1, player.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next();
                }
            }
        } catch (SQLException e) {
            SuperbVote.getPlugin().getLogger().log(Level.SEVERE, "Unable to check if voted today", e);
            return false;
        }
    }

    @Override
    public List<PlayerVotes> getAllPlayersWithNoVotesToday(List<UUID> onlinePlayers) {
        if (onlinePlayers.isEmpty()) {
            return ImmutableList.of();
        }
        List<PlayerVotes> votes = new ArrayList<>();
        try (Connection connection = getConnection()) {
            String valueStatement = Joiner.on(", ").join(Collections.nCopies(onlinePlayers.size(), "?"));
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT uuid, last_name, votes, (date(last_vote, 'unixepoch') = date('now')) AS has_voted_today FROM " +
                            tableName + " WHERE uuid IN (" + valueStatement + ")")) {
                for (int i = 0; i < onlinePlayers.size(); i++) {
                    statement.setString(i + 1, onlinePlayers.get(i).toString());
                }
                List<UUID> found = new ArrayList<>();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        UUID uuid = UUID.fromString(resultSet.getString(1));
                        found.add(uuid);
                        if (resultSet.getBoolean(4)) continue;
                        votes.add(new PlayerVotes(uuid, resultSet.getString(2), resultSet.getInt(3), PlayerVotes.Type.CURRENT));
                    }
                }
                List<UUID> missing = new ArrayList<>(onlinePlayers);
                missing.removeAll(found);
                for (UUID uuid : missing) {
                    votes.add(new PlayerVotes(uuid, null, 0, PlayerVotes.Type.CURRENT));
                }
            }
            return votes;
        } catch (SQLException e) {
            SuperbVote.getPlugin().getLogger().log(Level.SEVERE, "Unable to batch-get votes", e);
            return ImmutableList.of();
        }
    }

    @Override
    public List<Map.Entry<PlayerVotes, VoteStreak>> getAllPlayersAndStreaksWithNoVotesToday(List<UUID> onlinePlayers) {
        Map<UUID, PlayerVotes> noVotes = getAllPlayersWithNoVotesToday(onlinePlayers).stream()
                .collect(Collectors.toMap(PlayerVotes::getUuid, p -> p));
        if (noVotes.isEmpty()) {
            return ImmutableList.of();
        }

        List<Map.Entry<PlayerVotes, VoteStreak>> result = new ArrayList<>();
        try (Connection connection = getConnection()) {
            String valueStatement = Joiner.on(", ").join(Collections.nCopies(noVotes.size(), "?"));
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT uuid, streak, days FROM " + streaksTableName + " WHERE uuid IN (" + valueStatement + ")")) {
                List<PlayerVotes> noVotesList = new ArrayList<>(noVotes.values());
                for (int i = 0; i < noVotesList.size(); i++) {
                    statement.setString(i + 1, noVotesList.get(i).getUuid().toString());
                }

                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        UUID uuid = UUID.fromString(resultSet.getString(1));
                        result.add(Maps.immutableEntry(noVotes.get(uuid), new VoteStreak(uuid,
                                resultSet.getInt(2), resultSet.getInt(3), new HashMap<>())));
                    }
                }
            }
            return result;
        } catch (SQLException e) {
            SuperbVote.getPlugin().getLogger().log(Level.SEVERE, "Unable to batch-get votes", e);
            return ImmutableList.of();
        }
    }

    @Override
    public VoteStreak getVoteStreak(UUID player, boolean required) {
        Preconditions.checkNotNull(player, "player");
        StreaksConfiguration streaksConfiguration = SuperbVote.getPlugin().getConfiguration().getStreaksConfiguration();
        if (!streaksConfiguration.isEnabled() || (!required && !streaksConfiguration.isPlaceholdersEnabled())) {
            return null;
        }

        try (Connection connection = getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT streak, days, CAST(julianday('now') - julianday(last_day) AS INTEGER), services, strftime('%s', 'now') FROM " +
                            streaksTableName + " WHERE uuid = ?")) {
                statement.setString(1, player.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        int daysDifference = resultSet.getInt(3);
                        if (daysDifference > 2) {
                            try (PreparedStatement resetStatement = connection.prepareStatement(
                                    "UPDATE " + streaksTableName + " SET streak = 0, days = 0, services = '{}' WHERE uuid = ?")) {
                                resetStatement.setString(1, player.toString());
                                resetStatement.executeUpdate();
                            }
                            return new VoteStreak(player, 0, 0, Maps.newHashMap());
                        }

                        Map<String, Long> services = gson.fromJson(resultSet.getString(4), servicesMapType);
                        long unixTimestamp = resultSet.getLong(5);
                        return new VoteStreak(player, resultSet.getInt(1), resultSet.getInt(2),
                                services.entrySet().stream()
                                        .map(entry -> Maps.immutableEntry(entry.getKey(), unixTimestamp - entry.getValue()))
                                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
                    }
                    return new VoteStreak(player, 0, 0, Maps.newHashMap());
                }
            }
        } catch (SQLException e) {
            SuperbVote.getPlugin().getLogger().log(Level.SEVERE, "Unable to get or reset vote streak for " + player.toString(), e);
            return new VoteStreak(player, 0, 0, Maps.newHashMap());
        }
    }

    @Override
    public void save() {}

    @Override
    public void close() {}
}
