-- 체험 사용자(계정 없이 써 보기). 체험 데이터는 expires_at 이 지나면 정리 작업이 사용자째 지운다
ALTER TABLE users
    ADD COLUMN demo       BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '체험 사용자' AFTER company,
    ADD COLUMN expires_at DATETIME(6) NULL COMMENT '체험 데이터 삭제 시각' AFTER demo,
    ADD INDEX idx_users_demo_expires (demo, expires_at);
