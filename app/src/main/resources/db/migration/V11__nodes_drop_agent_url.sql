-- agent_url on nodes was a snapshot of servers.agent_url taken at AddNodeUseCase time — nothing
-- read it, and it could go stale after a server re-enroll. Resolve the agent through server_id
-- instead.
ALTER TABLE nodes DROP COLUMN agent_url;
