-- Phase 2 compatibility repairs for legacy design drafts migrated by V49.

-- A legacy draft that was already doctor-visible must have matching file
-- visibility, otherwise the version is readable but its signed URL is denied.
UPDATE file_resource f
JOIN design_draft_file ddf ON ddf.file_id = f.file_id
JOIN design_draft d ON d.design_draft_id = ddf.design_draft_id
SET f.visibility = 'DOCTOR_CS'
WHERE d.doctor_visible_at IS NOT NULL
  AND f.visibility = 'INTERNAL';

-- Legacy uploaders could be CS, ADMIN or NULL. A rejected/revision task with no
-- active WORKER assignee must return to the public pool instead of becoming
-- permanently unserviceable.
UPDATE design_task dt
LEFT JOIN system_user u ON u.user_id = dt.assigned_user_id
LEFT JOIN system_user_role ur ON ur.user_id = u.user_id
LEFT JOIN system_role r
  ON r.role_id = ur.role_id
 AND r.role_code = 'WORKER'
 AND r.status = 'ACTIVE'
SET dt.assigned_user_id = NULL,
    dt.claimed_at = NULL,
    dt.task_status = 'OPEN'
WHERE dt.task_status IN ('CLAIMED', 'INTERNAL_REJECTED', 'DOCTOR_REJECTED')
  AND (
      dt.assigned_user_id IS NULL
      OR u.status <> 'ACTIVE'
      OR r.role_id IS NULL
  );
