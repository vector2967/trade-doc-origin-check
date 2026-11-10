package com.tradedoc.origincheck.demo;

import com.tradedoc.origincheck.common.ApiException;
import com.tradedoc.origincheck.common.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 체험 사용량 상한. 점검 접수 트랜잭션 안에서 부른다 → 접수가 실패(롤백)하면 센 것도 되돌아간다.
 * 한도 검사와 증가를 조건부 UPDATE 한 문장으로 해서 동시에 여러 건이 와도 넘치지 않는다.
 * <ul>
 *   <li>체험 사용자당 {@link DemoProperties#perUserLimit()} 건 → 429 DEMO_LIMIT</li>
 *   <li>서비스 전체 하루(KST 자정 초기화) {@link DemoProperties#dailyLimit()} 건 → 429 DEMO_DAILY_LIMIT</li>
 * </ul>
 * 실패한 건도 접수 때 센 그대로 남는다(1건). 다시 실행은 이 메서드를 부르지 않는다.
 */
@Component
public class DemoQuota {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;
    private final DemoProperties props;

    public DemoQuota(JdbcTemplate jdbc, DemoProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(Long userId) {
        int mine = jdbc.update(
                "UPDATE users SET demo_checks_used = demo_checks_used + 1 WHERE id = ? AND demo AND demo_checks_used < ?",
                userId, props.perUserLimit());
        if (mine == 0) {
            throw new ApiException(ErrorCode.DEMO_LIMIT);
        }
        Date today = Date.valueOf(LocalDate.now(KST));
        jdbc.update("INSERT IGNORE INTO demo_daily_usage (day, used) VALUES (?, 0)", today);
        int all = jdbc.update("UPDATE demo_daily_usage SET used = used + 1 WHERE day = ? AND used < ?",
                today, props.dailyLimit());
        if (all == 0) {
            throw new ApiException(ErrorCode.DEMO_DAILY_LIMIT); // 롤백되며 위의 사용자 증가도 되돌아간다
        }
    }
}
