package com.clara.ops.challenge.documentmanagement.adapter.out.persistence;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.util.List;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.jpa.domain.Specification;

final class DocumentSpecifications {

  private DocumentSpecifications() {}

  static Specification<DocumentEntity> hasUser(String user) {
    return (root, query, cb) -> cb.equal(root.get("user"), user);
  }

  static Specification<DocumentEntity> hasName(String name) {
    return (root, query, cb) -> cb.equal(root.get("name"), name);
  }

  // Checks that every requested tag is present in the document's tags array.
  // Each array_contains(tags, tag) call uses the GIN index on the tags column.
  // Semantics: tags @> ARRAY['t1'] AND tags @> ARRAY['t2'] — equivalent to @> ARRAY['t1','t2'].
  static Specification<DocumentEntity> hasAllTags(List<String> tags) {
    return (root, query, cb) -> {
      HibernateCriteriaBuilder hcb = (HibernateCriteriaBuilder) cb;
      Expression<String[]> tagsField = root.get("tags");
      Predicate[] predicates =
          tags.stream().map(tag -> hcb.arrayContains(tagsField, tag)).toArray(Predicate[]::new);
      return cb.and(predicates);
    };
  }
}
