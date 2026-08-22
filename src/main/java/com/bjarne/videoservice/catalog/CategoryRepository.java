package com.bjarne.videoservice.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findBySlug(String slug);

    List<Category> findAllByActiveTrueOrderBySortOrderAsc();

    List<Category> findAllByOrderBySortOrderAsc();
}
