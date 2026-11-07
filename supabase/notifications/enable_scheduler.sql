-- Run only on the authorized target after migration/integration verification.
begin;
create extension if not exists pg_cron;
select cron.schedule('studyhub-notifications-dispatch','5 seconds',
  $$set statement_timeout='4s'; select studyhub_private.dispatch_notifications();$$);
select cron.schedule('studyhub-notifications-cleanup','17 * * * *',
  $$set statement_timeout='10s'; select studyhub_private.cleanup_notification_jobs();$$);
commit;
