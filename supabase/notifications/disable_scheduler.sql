-- Preserve queued work and inbox data for recovery.
select cron.unschedule(jobid) from cron.job
where jobname in ('studyhub-notifications-dispatch','studyhub-notifications-cleanup');
