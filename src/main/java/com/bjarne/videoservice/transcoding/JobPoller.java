package com.bjarne.videoservice.transcoding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.UUID;

/**
 * Reiner Scheduler/Orchestrator - alle DB-Uebergaenge laufen ueber {@link TranscodeJobLifecycle},
 * die eigentliche Arbeit ueber {@link TranscodeService}. Worker-Concurrency=1 (CLAUDE.md 9.2)
 * ergibt sich daraus, dass pro Tick hoechstens ein Job verarbeitet wird und der naechste Tick
 * erst nach Rueckkehr dieser Methode startet - kein Thread-Pool noetig. Aktiv nur im worker-Profil
 * (WorkerConfig aktiviert @EnableScheduling nur dort).
 */
@Component
public class JobPoller {

    private static final Logger log = LoggerFactory.getLogger(JobPoller.class);

    private final TranscodeJobLifecycle lifecycle;
    private final TranscodeService transcodeService;
    private final String workerId;

    public JobPoller(TranscodeJobLifecycle lifecycle, TranscodeService transcodeService) {
        this.lifecycle = lifecycle;
        this.transcodeService = transcodeService;
        this.workerId = resolveHostname() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Scheduled(fixedDelayString = "${app.transcode.poll-interval:PT5S}")
    public void poll() {
        lifecycle.reclaimStale();

        Optional<ClaimedJob> claimed = lifecycle.claimNext(workerId);
        if (claimed.isEmpty()) {
            return;
        }
        ClaimedJob job = claimed.get();
        log.info("Job {} fuer Video {} geclaimt von {}", job.jobId(), job.videoId(), workerId);

        try {
            TranscodeOutcome outcome = transcodeService.process(job.videoId());
            lifecycle.recordSuccess(job.jobId(), job.videoId(), outcome);
            log.info("Job {} fuer Video {} erfolgreich abgeschlossen", job.jobId(), job.videoId());
        } catch (MediaValidationException e) {
            log.warn("Job {} fuer Video {} endgueltig fehlgeschlagen (Validierung): {}",
                    job.jobId(), job.videoId(), e.getMessage());
            lifecycle.recordValidationFailure(job.jobId(), job.videoId(), e.getMessage());
        } catch (Exception e) {
            log.error("Job {} fuer Video {} fehlgeschlagen, wird ggf. erneut versucht", job.jobId(), job.videoId(), e);
            lifecycle.recordTransientFailure(job.jobId(), job.videoId(), String.valueOf(e.getMessage()));
        }
    }

    private String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "worker";
        }
    }
}
