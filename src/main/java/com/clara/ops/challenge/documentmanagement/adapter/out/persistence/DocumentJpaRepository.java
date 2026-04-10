package com.clara.ops.challenge.documentmanagement.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface DocumentJpaRepository
    extends JpaRepository<DocumentEntity, Long>, JpaSpecificationExecutor<DocumentEntity> {

  boolean existsByUserAndName(String user, String name);
}
