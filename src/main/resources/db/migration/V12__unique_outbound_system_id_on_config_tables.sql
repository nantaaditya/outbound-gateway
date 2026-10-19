-- One configuration row per outbound system.
--
-- The admin upserts are find-then-save; without a unique constraint two concurrent first-time upserts
-- both insert, and every later read (Mono finders) fails with IncorrectResultSizeDataAccessException.
--
-- This migration never deletes data. If duplicates already exist it aborts (Flyway rolls the whole
-- migration back) and names the offending tables so the operator can decide which row to keep,
-- e.g. keep the highest id per outbound_system_id, then re-run.

DO $$
DECLARE
  tbl        text;
  dup_count  bigint;
  offenders  text := '';
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
      'outbound_resilience_configurations',
      'outbound_header_configurations',
      'outbound_auth_configurations',
      'outbound_response_mapper_configuration']
  LOOP
    EXECUTE format(
        'SELECT COUNT(*) FROM (SELECT outbound_system_id FROM %I '
        || 'WHERE outbound_system_id IS NOT NULL GROUP BY outbound_system_id HAVING COUNT(*) > 1) d', tbl)
    INTO dup_count;
    IF dup_count > 0 THEN
      offenders := offenders || format(' %s (%s system ids)', tbl, dup_count);
    END IF;
  END LOOP;

  IF offenders <> '' THEN
    RAISE EXCEPTION 'V12 aborted: duplicate outbound_system_id rows exist in:%. Keep one row per outbound_system_id and re-run the migration.', offenders;
  END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_outbound_resilience_system_id
    ON outbound_resilience_configurations (outbound_system_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_outbound_header_system_id
    ON outbound_header_configurations (outbound_system_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_outbound_auth_system_id
    ON outbound_auth_configurations (outbound_system_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_outbound_response_mapper_system_id
    ON outbound_response_mapper_configuration (outbound_system_id);
