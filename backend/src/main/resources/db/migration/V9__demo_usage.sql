-- 체험 사용량. 점검 건을 지워도 줄지 않게 사용자 행과 날짜별 행에 따로 센다
ALTER TABLE users
    ADD COLUMN demo_checks_used INT NOT NULL DEFAULT 0 COMMENT '체험 사용자가 접수한 점검 수(실패 포함, 다시 실행 제외)' AFTER expires_at;

CREATE TABLE demo_daily_usage
(
    day  DATE NOT NULL COMMENT 'KST 날짜',
    used INT  NOT NULL DEFAULT 0 COMMENT '그날 접수된 체험 점검 수',
    PRIMARY KEY (day)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
