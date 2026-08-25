package com.bjarne.videoservice.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates a fixed test user for local development/frontend tests, if it doesn't already
 * exist. Deliberately not done via Flyway (the V2__seed_categories.sql pattern): migrations
 * run unchanged in the prod profile too, and an account with a known password has no
 * business being there (public platform with no login requirement, CLAUDE.md section 1/12).
 * @Profile("!prod") ensures this runner never runs in production. Idempotent and fault-tolerant
 * when the API and worker containers start at the same time (both run this runner).
 */
@Component
@Profile("!prod")
public class TestUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TestUserSeeder.class);

    public static final String EMAIL = "test@video-service.local";
    public static final String USERNAME = "testuser";
    public static final String PASSWORD = "Test1234!";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public TestUserSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.findByEmail(EMAIL).isPresent()) {
            return;
        }
        try {
            User user = new User(EMAIL, USERNAME, passwordEncoder.encode(PASSWORD));
            user.setRole(Role.ADMIN);
            userRepository.save(user);
            log.info("Test user created: {} (role ADMIN, password see TestUserSeeder - only active in non-prod profiles)",
                    EMAIL);
        } catch (DataIntegrityViolationException e) {
            log.debug("Test user was already created concurrently by another container", e);
        }
    }
}
