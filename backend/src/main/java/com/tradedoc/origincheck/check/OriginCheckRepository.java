package com.tradedoc.origincheck.check;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OriginCheckRepository extends JpaRepository<OriginCheck, Long> {

    Page<OriginCheck> findByUserId(Long userId, Pageable pageable);

    Page<OriginCheck> findByUserIdAndStatus(Long userId, CheckStatus status, Pageable pageable);

    Optional<OriginCheck> findByIdAndUserId(Long id, Long userId);

    List<OriginCheck> findTop20ByStatusOrderByIdAsc(CheckStatus status);

    /** 제출할 차례가 된 건(재시도 대기 중인 건은 빼고) */
    List<OriginCheck> findTop20ByStatusAndNextSubmitAtLessThanEqualOrderByIdAsc(CheckStatus status, Instant now);

    /** 제출할 차례가 된 정식 건 */
    List<OriginCheck> findTop20ByDemoFalseAndStatusAndNextSubmitAtLessThanEqualOrderByIdAsc(CheckStatus status, Instant now);

    /** 제출할 차례가 된 체험 건(빈 자리만큼) */
    List<OriginCheck> findByDemoTrueAndStatusAndNextSubmitAtLessThanEqualOrderByIdAsc(CheckStatus status, Instant now, Pageable pageable);

    long countByDemoTrueAndStatus(CheckStatus status);

    /** 체험 대기열에서 이 건보다 앞에 있는 건 수 */
    long countByDemoTrueAndStatusAndIdLessThan(CheckStatus status, Long id);

    /** 끝났는데 agent 쪽 파일이 아직 남은 건 */
    List<OriginCheck> findTop20ByStatusInAndAgentFileIdIsNotNullOrderByIdAsc(List<CheckStatus> statuses);
}
