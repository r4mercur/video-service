package com.bjarne.videoservice.transcoding.service;

import com.bjarne.videoservice.transcoding.repository.TranscodeJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only write access that {@link TranscodeService} performs itself - deliberately separated
 * from {@link TranscodeJobLifecycle}, which owns the authoritative state transitions (PENDING/
 * RUNNING/DONE/FAILED). Each call is its own short transaction, matching the many, frequent
 * intermediate updates during a long-running ffmpeg job.
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
