package io.minimum.minecraft.superbvote.storage;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import io.minimum.minecraft.superbvote.SuperbVote;
import io.minimum.minecraft.superbvote.util.PlayerVotes;
import io.minimum.minecraft.superbvote.votes.Vote;
import org.bukkit.entity.Player;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

public class SqliteVoteStorage implements VoteStorage {
    private final String databaseUrl;
    private final String tableName;
    private final boolean readOnly;

    public SqliteVoteStorage(File databaseFile, String tableName, boolean readOnly) {
        this.databaseUrl = "jdbc:sqlite:" + databaseFile.getAbsolutePath();
        this.tableName = tableName;
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
    public List<PlayerVotes> getPlayersNeedingReminder(List<UUID> onlinePlayers, int cooldownSeconds) {
        if (onlinePlayers.isEmpty()) {
            return ImmutableList.of();
        }
        List<PlayerVotes> votes = new ArrayList<>();
        try (Connection connection = getConnection()) {
            String valueStatement = Joiner.on(", ").join(Collections.nCopies(onlinePlayers.size(), "?"));
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT uuid, last_name, votes FROM " + tableName +
                            " WHERE uuid IN (" + valueStatement + ") AND (strftime('%s', 'now') - last_vote) >= ?")) {
                for (int i = 0; i < onlinePlayers.size(); i++) {
                    statement.setString(i + 1, onlinePlayers.get(i).toString());
                }
                statement.setInt(onlinePlayers.size() + 1, cooldownSeconds);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        votes.add(new PlayerVotes(
                                UUID.fromString(resultSet.getString(1)),
                                resultSet.getString(2),
                                resultSet.getInt(3),
                                PlayerVotes.Type.CURRENT));
                    }
                }
            }
            return votes;
        } catch (SQLException e) {
            SuperbVote.getPlugin().getLogger().log(Level.SEVERE, "Unable to get players needing reminder", e);
            return ImmutableList.of();
        }
    }

    @Override
    public void save() {}

    @Override
    public void close() {}
}
