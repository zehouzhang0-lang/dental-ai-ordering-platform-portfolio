-- Synthetic identities used only by the isolated test profile.
-- Known local test passwords are intentionally kept out of main migrations.

INSERT INTO clinic (clinic_name, contact_name, status)
SELECT '作品集虚构诊所', 'Synthetic Doctor', 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM clinic WHERE clinic_name = '作品集虚构诊所'
);

INSERT INTO system_user (user_id, username, password_hash, display_name, clinic_id, user_type, status)
VALUES
    (8001, 'admin', 'pbkdf2_sha256$120000$DUAy+aGwMHsgrpHbiuoykA==$i9YpQ8n/TBZRGGVg/em/HfH1++x7A8Ub72U5LUc9tFA=', '虚构测试管理员', NULL, 'ADMIN', 'ACTIVE'),
    (8002, 'cs', 'pbkdf2_sha256$120000$3ODuOW+t81iLLLqv6izqxQ==$/DRBFRUKmQE4x9w6UX5USFGvAMJp/mXpVJ6Xz28nWIs=', '虚构测试客服', NULL, 'CS', 'ACTIVE'),
    (9601, 'worker', 'pbkdf2_sha256$120000$Rs2yTLCiwZDCen8NRGRmNg==$bi39dlchyH/bKNosCbDYs+ztJ8xAnofk4OWX2y782cs=', '虚构测试生产员', NULL, 'WORKER', 'ACTIVE'),
    (9701, 'doctor', 'pbkdf2_sha256$120000$lqdaAx81oEF+8JI6/kCdpw==$SrDw2FjWST5U6soPUDErfEOXgnvnb4EW+ApfsGIT9hY=', '虚构测试医生', (SELECT clinic_id FROM clinic WHERE clinic_name = '作品集虚构诊所'), 'DOCTOR', 'ACTIVE');

INSERT INTO system_user_role (user_id, role_id)
SELECT u.user_id, r.role_id
FROM system_user u
JOIN system_role r ON r.role_code = u.user_type
WHERE u.user_id IN (8001, 8002, 9601, 9701);
