# --- MapRoulette Scheme

# --- !Ups
-- Remove unique index on Groups
DROP INDEX IF EXISTS idx_groups_name;
SELECT create_index_if_not_exists('groups', 'project_id_group_type', '(project_id, group_type)');;

# --- !Downs
