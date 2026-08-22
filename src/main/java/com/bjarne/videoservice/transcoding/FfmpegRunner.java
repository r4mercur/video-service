package com.bjarne.videoservice.transcoding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Generischer Prozess-Ausfuehrer fuer ffmpeg/ffprobe. FFmpeg laeuft als externer Prozess
 * (CLAUDE.md 3.1), nicht eingebettet - dieser Wrapper kapselt Timeout + destroyForcibly()
 * (CLAUDE.md 9.2: "Harter Timeout pro Job mit destroyForcibly()").
 */
@Component
public class FfmpegRunner {

    private static final Logger log = LoggerFactory.getLogger(FfmpegRunner.class);

    /**
     * Fuehrt das Kommando aus und liefert die zusammengefuehrte stdout/stderr-Ausgabe zurueck
     * (ffprobe schreibt sein JSON auf stdout, ffmpeg-Diagnosen laufen ueber stderr).
     */
    public String run(List<String> command, Duration timeout) {
        log.debug("Fuehre aus: {}", String.join(" ", command));
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new TranscodeProcessException("Prozess konnte nicht gestartet werden: " + command, e);
        }

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread outputReader = new Thread(() -> {
            try {
                process.getInputStream().transferTo(captured);
            } catch (IOException ignored) {
                // Stream wird beim destroyForcibly() geschlossen - erwartetes Verhalten im Timeout-Fall
            }
        }, "ffmpeg-output-reader");
        outputReader.setDaemon(true);
        outputReader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new TranscodeProcessException("Warten auf Prozess unterbrochen: " + command, e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new TranscodeProcessException("Prozess-Timeout nach " + timeout + ": " + command);
        }

        joinQuietly(outputReader);
        int exitCode = process.exitValue();
        String output = captured.toString(StandardCharsets.UTF_8);
        if (exitCode != 0) {
            throw new TranscodeProcessException("Prozess beendet mit Exit-Code " + exitCode + ": " + command
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
}
