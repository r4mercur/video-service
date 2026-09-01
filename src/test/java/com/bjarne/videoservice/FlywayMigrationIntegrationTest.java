package com.bjarne.videoservice;

import com.bjarne.videoservice.catalog.*;
import com.bjarne.videoservice.identity.User;
import com.bjarne.videoservice.identity.UserRepository;
import com.bjarne.videoservice.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class FlywayMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void categoriesAreSeededByFlyway() {
        // 10 from V2, plus the age-restricted "adult" category added in V10.
        assertThat(categoryRepository.count()).isEqualTo(11);
        assertThat(categoryRepository.findBySlug("gaming")).isPresent();
        assertThat(categoryRepository.findBySlug("adult")).isPresent();
    }

    @Test
    void userAndVideoRoundtripPersistsAndReloadsCorrectly() {
        Category category = categoryRepository.findBySlug("gaming").orElseThrow();

        User user = new User("roundtrip-" + UUID.randomUUID() + "@example.com", "roundtrip-" + UUID.randomUUID(),
                "hash");
        userRepository.save(user);

        Video video = new Video(user, category, "Test Video", "test-video-" + UUID.randomUUID(), Visibility.PUBLIC);
        video.setStatus(VideoStatus.READY);
        entityManager.persist(video);
        entityManager.flush();
        entityManager.clear();

        Video reloaded = entityManager.find(Video.class, video.getId());

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getUser().getId()).isEqualTo(user.getId());
        assertThat(reloaded.getCategory().getId()).isEqualTo(category.getId());
        assertThat(reloaded.getStatus()).isEqualTo(VideoStatus.READY);
        assertThat(reloaded.getVisibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }
}
