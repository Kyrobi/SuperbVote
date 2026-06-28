package io.minimum.minecraft.superbvote.storage;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import io.minimum.minecraft.superbvote.SuperbVote;
import io.minimum.minecraft.superbvote.votes.Vote;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class QueuedVotesStorage {
    private final String databaseUrl;

    public QueuedVotesStorage(File databaseFile) {
        this.databaseUrl = "jdbc:sqlite:" + databaseFile.getAbsolutePath();
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS queued_votes (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "uuid TEXT NOT NULL," +
                    "name TEXT," +
                    "service_name TEXT NOT NULL," +
                    "fake_vote INTEGER NOT NULL DEFAULT 0," +
                    "world_name TEXT," +
                    "received INTEGER NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS queued_votes_uuid_idx ON queued_votes (uuid)");
        } catch (SQLException e) {
            SuperbVote.getPlugin().getLogger().log(Level.SEVERE, "Unable to initialize queued votes table", e);
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(databaseUrl);
    }

    public void addVote(Vote vote) {
        Preconditions.checkNotNull(vote, "vote");
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO queued_votes (uuid, name, service_name, fake_vote, world_name, received) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, vote.getUuid().toString());
            statement.setString(2, vote.getName());
            statement.setString(3, vote.getServiceName());
            statement.setInt(4, vote.isFakeVote() ? 1 : 0);
            statement.setString(5, vote.getWorldName());
            statement.setLong(6, vote.getReceived().getTime() / 1000);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Unable to queue vote for " + vote.getUuid(), e);
        }
    }

    public void clearVotes() {
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM queued_votes")) {
            statement.executeUpdate();
        } catch (SQLException e) {
            SuperbVote.getPlugin().getLogger().log(Level.SEVERE, "Unable to clear queued votes", e);
        }
    }

    public List<Vote> getAndRemoveVotes(UUID player) {
        Preconditions.checkNotNull(player, "player");
        List<Vote> votes = new ArrayList<>();
        try (Connection connection = getConnection()) {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, name, service_name, fake_vote, world_name, received FROM queued_votes WHERE uuid = ?")) {
                select.setString(1, player.toString());
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        votes.add(new Vote(
                                rs.getString(2),
                                player,
                                rs.getString(3),
                                rs.getInt(4) != 0,
                                rs.getString(5),
                                new Date(rs.getLong(6) * 1000)
                        ));
                    }
                }
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM queued_votes WHERE uuid = ?")) {
                delete.setString(1, player.toString());
                delete.executeUpdate();
            }
        } catch (SQLException e) {
            SuperbVote.getPlugin().getLogger().log(Level.SEVERE, "Unable to get/remove queued votes for " + player, e);
            return ImmutableList.of();
        }
        return votes;
    }

    public void save() {}
}
