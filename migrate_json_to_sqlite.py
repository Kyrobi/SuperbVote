import json
import sqlite3
from datetime import datetime
from pathlib import Path

DB = "data.db"
VOTES_JSON = "votes.json"
QUEUED_JSON = "queued_votes.json"

con = sqlite3.connect(DB)
con.execute("PRAGMA journal_mode=WAL")

con.execute("""CREATE TABLE IF NOT EXISTS votes (
    uuid TEXT PRIMARY KEY NOT NULL,
    last_name TEXT,
    votes INTEGER NOT NULL,
    last_vote INTEGER NOT NULL
)""")
con.execute("CREATE INDEX IF NOT EXISTS uuid_votes_idx ON votes (uuid, votes)")

con.execute("""CREATE TABLE IF NOT EXISTS queued_votes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid TEXT NOT NULL,
    name TEXT,
    service_name TEXT NOT NULL,
    fake_vote INTEGER NOT NULL DEFAULT 0,
    world_name TEXT,
    received INTEGER NOT NULL
)""")
con.execute("CREATE INDEX IF NOT EXISTS queued_votes_uuid_idx ON queued_votes (uuid)")

# --- votes.json ---
try:
    with open(VOTES_JSON, "r", encoding="utf-8") as f:
        raw = json.load(f)
    records = raw.get("records") or raw  # v2 has "records" wrapper, v1 is flat uuid->int
    count = 0
    for uuid_str, data in records.items():
        if isinstance(data, dict):
            name = data.get("lastKnownUsername")
            votes = data["votes"]
            last_voted_epoch_sec = data["lastVoted"] // 1000  # millis -> seconds
        else:
            # v1 format: uuid -> int
            name = None
            votes = data
            last_voted_epoch_sec = 0
        con.execute(
            "INSERT OR REPLACE INTO votes (uuid, last_name, votes, last_vote) VALUES (?, ?, ?, ?)",
            (uuid_str, name, votes, last_voted_epoch_sec),
        )
        count += 1
    print(f"Migrated {count} records from {VOTES_JSON} -> votes")
except FileNotFoundError:
    print(f"Skipped {VOTES_JSON} (not found)")

# --- queued_votes.json ---
# date format from Gson's setDateFormat("MMM d, yyyy h:mm:ss")
FMT = "%b %d, %Y %I:%M:%S"

try:
    with open(QUEUED_JSON, "r", encoding="utf-8") as f:
        raw = json.load(f)
    count = 0
    for uuid_str, entries in raw.items():
        for entry in entries:
            received_dt = datetime.strptime(entry["received"], FMT)
            epoch_sec = int(received_dt.timestamp())
            con.execute(
                "INSERT INTO queued_votes (uuid, name, service_name, fake_vote, world_name, received) VALUES (?, ?, ?, ?, ?, ?)",
                (
                    uuid_str,
                    entry.get("name"),
                    entry["serviceName"],
                    1 if entry.get("fakeVote") else 0,
                    entry.get("worldName"),
                    epoch_sec,
                ),
            )
            count += 1
    print(f"Migrated {count} records from {QUEUED_JSON} -> queued_votes")
except FileNotFoundError:
    print(f"Skipped {QUEUED_JSON} (not found)")

con.commit()
con.close()
print("Done.")
