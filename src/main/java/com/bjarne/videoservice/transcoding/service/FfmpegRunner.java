package com.bjarne.videoservice.transcoding.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Generic process runner for ffmpeg/ffprobe. FFmpeg runs as an external process
 * (CLAUDE.md 3.1), not embedded - this wrapper encapsulates timeout + destroyForcibly()
 * (CLAUDE.md 9.2: "Hard timeout per job with destroyForcibly()").
 */
@Component
public class FfmpegRunner {

    private static final Logger log = LoggerFactory.getLogger(FfmpegRunner.class);

    /**
     * Runs the command and returns the combined stdout/stderr output
     * (ffprobe writes its JSON to stdout, ffmpeg diagnostics go via stderr).
     */
    public String run(List<String> command, Duration timeout) {
        return run(command, timeout, null, null);
    }

    /**
     * Like {@link #run(List, Duration)}, but additionally reports the source timestamp already
     * processed within the run (for progress indicators, AP extension status endpoint).
     * Hooks into ffmpeg's own "-progress pipe:1" (structured key=value output), not the
     * interleaved stderr log - therefore only useful for ffmpeg, not ffprobe, commands.
     */
    public String run(List<String> command, Duration timeout, Consumer<Duration> onProgress) {
        return run(command, timeout, null, onProgress);
    }

    /**
     * Like {@link #run(List, Duration)}, but runs the process in the given working directory
     * (see {@link #run(List, Duration, Path, Consumer)}).
     */
    public String run(List<String> command, Duration timeout, Path workingDirectory) {
        return run(command, timeout, workingDirectory, null);
    }

    /**
     * Like {@link #run(List, Duration, Consumer)}, but runs the process in the given
     * working directory. Needed so that ffmpeg's own output files, which it names relatively
     * itself (e.g. "-hls_fmp4_init_filename init.mp4" - unlike "-hls_segment_filename",
     * ffmpeg writes the given name into the playlist unchanged here, instead of just taking
     * the basename), end up in the right directory and don't show up as an absolute local path
     * in the delivered manifest.
     */
    public String run(List<String> command, Duration timeout, Path workingDirectory, Consumer<Duration> onProgress) {
        List<String> actualCommand = onProgress != null ? withProgressFlag(command) : command;
        log.debug("Running: {}", String.join(" ", actualCommand));
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(actualCommand).redirectErrorStream(true);
            if (workingDirectory != null) {
                builder.directory(workingDirectory.toFile());
            }
            process = builder.start();
        } catch (IOException e) {
            throw new TranscodeProcessException("Process could not be started: " + actualCommand, e);
        }

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread outputReader = new Thread(() -> readOutput(process, captured, onProgress), "ffmpeg-output-reader");
        outputReader.setDaemon(true);
        outputReader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new TranscodeProcessException("Interrupted while waiting for process: " + command, e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new TranscodeProcessException("Process timed out after " + timeout + ": " + command);
        }

        joinQuietly(outputReader);
        int exitCode = process.exitValue();
        String output = captured.toString(StandardCharsets.UTF_8);
        if (exitCode != 0) {
            throw new TranscodeProcessException("Process exited with code " + exitCode + ": " + command
                    + "\n" + output);
        }
        return output;
    }

    private void joinQuietly(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(5).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<String> withProgressFlag(List<String> command) {
        List<String> result = new ArrayList<>(command.size() + 2);
        result.add(command.get(0));
        result.add("-progress");
        result.add("pipe:1");
        result.addAll(command.subList(1, command.size()));
        return result;
    }

    /**
     * Reads the combined stdout/stderr output line by line (instead of all at once like before),
     * so that "-progress pipe:1" lines (e.g. "out_time=00:01:23.456789") are recognized as soon
     * as ffmpeg writes them - not only after the process ends.
     */
    private void readOutput(Process process, ByteArrayOutputStream captured, Consumer<Duration> onProgress) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                captured.write(line.getBytes(StandardCharsets.UTF_8));
                captured.write('\n');
                if (onProgress != null && line.startsWith("out_time=")) {
                    parseOutTime(line.substring("out_time=".length())).ifPresent(onProgress);
                }
            }
        } catch (IOException ignored) {
            // Stream gets closed by destroyForcibly() - expected behavior in the timeout case
        }
    }

    private Optional<Duration> parseOutTime(String value) {
        String[] parts = value.split(":");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            double hours = Double.parseDouble(parts[0]);
            double minutes = Double.parseDouble(parts[1]);
            double seconds = Double.parseDouble(parts[2]);
            double totalSeconds = hours * 3600 + minutes * 60 + seconds;
            return Optional.of(Duration.ofMillis(Math.round(totalSeconds * 1000)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
