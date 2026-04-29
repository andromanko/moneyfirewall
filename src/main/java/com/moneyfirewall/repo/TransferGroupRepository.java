package com.moneyfirewall.repo;

import com.moneyfirewall.domain.TransferGroup;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferGroupRepository extends JpaRepository<TransferGroup, UUID> {}

