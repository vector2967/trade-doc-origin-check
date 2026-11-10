-- 체험 사용자가 접수한 점검. 워커가 체험 건만 따로 동시 제출 수를 묶고, 대기 순번을 셀 때 쓴다
ALTER TABLE origin_checks
    ADD COLUMN demo BOOLEAN NOT NULL DEFAULT FALSE COMMENT '체험 점검' AFTER user_id,
    ADD INDEX idx_origin_checks_demo_status (demo, status, id);
