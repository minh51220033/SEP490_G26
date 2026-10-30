package com.cvdcms.reception;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;
public interface ReceptionCheckInKeyRepository extends JpaRepository<ReceptionCheckInKey, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) Optional<ReceptionCheckInKey> findByRequestKey(String requestKey);
}
