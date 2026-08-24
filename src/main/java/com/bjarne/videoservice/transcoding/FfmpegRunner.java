package com.bjarne.videoservice.transcoding;

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
        return run(command, timeout, null, null);
    }

    /**
     * Wie {@link #run(List, Duration)}, meldet aber zusaetzlich den innerhalb des Laufs bereits
     * verarbeiteten Zeitpunkt der Quelle (fuer Fortschrittsanzeigen, AP-Erweiterung Status-Endpunkt).
     * Haengt an ffmpegs eigenem "-progress pipe:1" (structured key=value output), nicht am
     * interleaved stderr-Log - deshalb nur fuer ffmpeg-, nicht ffprobe-Kommandos sinnvoll.
     */
    public String run(List<String> command, Duration timeout, Consumer<Duration> onProgress) {
        return run(command, timeout, null, onProgress);
    }

    /**
     * Wie {@link #run(List, Duration)}, fuehrt den Prozess aber im angegebenen Working Directory
     * aus (siehe {@link #run(List, Duration, Path, Consumer)}).
     */
    public String run(List<String> command, Duration timeout, Path workingDirectory) {
        return run(command, timeout, workingDirectory, null);
    }

    /**
     * Wie {@link #run(List, Duration, Consumer)}, fuehrt den Prozess aber im angegebenen
     * Working Directory aus. Noetig, damit ffmpeg-eigene Ausgabedateien, die es selbst relativ
     * benennt (z.B. "-hls_fmp4_init_filename init.mp4" - anders als "-hls_segment_filename"
     * schreibt ffmpeg hier den uebergebenen Namen unveraendert in die Playlist, statt nur den
     * Basename zu nehmen), im richtigen Verzeichnis landen und nicht als absoluter lokaler Pfad
     * im ausgelieferten Manifest auftauchen.
     */
    public String run(List<String> command, Duration timeout, Path workingDirectory, Consumer<Duration> onProgress) {
        List<String> actualCommand = onProgress != null ? withProgressFlag(command) : command;
        log.debug("Fuehre aus: {}", String.join(" ", actualCommand));
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(actualCommand).redirectErrorStream(true);
            if (workingDirectory != null) {
                builder.directory(workingDirectory.toFile());
            }
            process = builder.start();
        } catch (IOException e) {
            throw new TranscodeProcessException("Prozess konnte nicht gestartet werden: " + actualCommand, e);
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

    private List<String> withProgressFlag(List<String> command) {
        List<String> result = new ArrayList<>(command.size() + 2);
        result.add(command.get(0));
        result.add("-progress");
        result.add("pipe:1");
        result.addAll(command.subList(1, command.size()));
        return result;
    }

    /**
     * Liest die kombinierte stdout/stderr-Ausgabe zeilenweise (statt in einem Rutsch wie vorher),
     * damit "-progress pipe:1"-Zeilen (z.B. "out_time=00:01:23.456789") erkannt werden, sobald
     * ffmpeg sie schreibt - nicht erst nach Prozessende.
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
            // Stream wird beim destroyForcibly() geschlossen - erwartetes Verhalten im Timeout-Fall
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
