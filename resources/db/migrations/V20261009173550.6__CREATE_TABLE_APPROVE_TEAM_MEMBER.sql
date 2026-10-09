CREATE TABLE IF NOT EXISTS approve_team_member (user_id VARCHAR(20) NOT NULL, since TIMESTAMP NOT NULL, deleted BOOLEAN DEFAULT FALSE NOT NULL);
COMMENT ON COLUMN approve_team_member.user_id IS 'The user id of the member';
COMMENT ON COLUMN approve_team_member.since IS 'The timestamp when the member was added';
COMMENT ON COLUMN approve_team_member.deleted IS 'Whether the member has been deleted';