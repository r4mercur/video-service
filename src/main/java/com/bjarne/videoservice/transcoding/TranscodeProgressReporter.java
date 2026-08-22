package com.bjarne.videoservice.transcoding;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Einziger Schreibzugriff, den {@link TranscodeService} selbst ausfuehrt - bewusst getrennt von
 * {@link TranscodeJobLifecycle}, die die massgeblichen Zustandsuebergaenge (PENDING/RUNNING/DONE/
 * FAILED) besitzt. Jeder Aufruf ist eine eigene kurze Transaktion, passend zu den vielen, haeufigen
 * Zwischen-Updates waehrend eines lange laufenden ffmpeg-Jobs.
 */
@Service
public class TranscodeProgressReporter {

    private final TranscodeJobRepository jobRepository;

    public TranscodeProgressReporter(TranscodeJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Transactional
    public void report(Long jobId, int progressPercent, String currentStep) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setProgressPercent(progressPercent);
            job.setCurrentStep(currentStep);
            jobRepository.save(job);
        });
    }
}
