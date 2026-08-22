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
 * Legt einen festen Test-User fuer lokale Entwicklung/Frontend-Tests an, falls er noch nicht
 * existiert. Bewusst nicht ueber Flyway (V2__seed_categories.sql-Muster): Migrationen laufen
 * unveraendert auch im prod-Profil, ein Account mit bekanntem Passwort haette dort nichts
 * verloren (oeffentliche Plattform ohne Login-Pflicht, CLAUDE.md Abschnitt 1/12). @Profile("!prod")
 * stellt sicher, dass dieser Runner in Produktion nie laeuft. Idempotent + fehlertolerant bei
 * gleichzeitigem Start von API- und Worker-Container (beide fuehren diesen Runner aus).
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
            userRepository.save(new User(EMAIL, USERNAME, passwordEncoder.encode(PASSWORD)));
            log.info("Test-User angelegt: {} (Passwort siehe TestUserSeeder - nur in Nicht-prod-Profilen aktiv)",
                    EMAIL);
        } catch (DataIntegrityViolationException e) {
            log.debug("Test-User wurde parallel bereits von einem anderen Container angelegt", e);
        }
    }
}
