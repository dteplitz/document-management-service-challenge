package com.clara.ops.challenge.documentmanagement.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface DocumentJpaRepository extends JpaRepository<DocumentEntity, Long> {

  boolean existsByUserAndName(String user, String name);
}
