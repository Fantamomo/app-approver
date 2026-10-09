CREATE TABLE IF NOT EXISTS restricted_scopes (scope VARCHAR(40) NOT NULL, restricted VARCHAR(22) NOT NULL, scope_type VARCHAR(4) NOT NULL, review BOOLEAN NOT NULL);
COMMENT ON COLUMN restricted_scopes.scope IS 'The scope which is restricted';
COMMENT ON COLUMN restricted_scopes.restricted IS 'How restricted the scope is';
COMMENT ON COLUMN restricted_scopes.scope_type IS 'The type of scope';
COMMENT ON COLUMN restricted_scopes.review IS 'Whether the scope requires review or is directly declined';