-- P1-8 "Hide me from the leaderboard": the user's own choice, kept apart from the admin `hidden` flag so turning
-- the toggle off never undoes a moderation hide. Leaderboard only; anonymous community counts still include them.
ALTER TABLE users ADD COLUMN self_hidden INTEGER NOT NULL DEFAULT 0;
