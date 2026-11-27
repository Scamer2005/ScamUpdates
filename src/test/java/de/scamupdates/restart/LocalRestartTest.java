package de.scamupdates.restart;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalRestartTest {
    @TempDir Path root;

    @Test
    void missingDefaultScriptMeansNoWorkingMechanism() {
        LocalRestart.Status st = LocalRestart.evaluateScript(root);
        assertFalse(st.ok());
        assertTrue(st.detail().contains("start.sh"));
    }

    @Test
    void existingDefaultScriptIsAccepted() throws Exception {
        Files.writeString(root.resolve("start.sh"), "#!/bin/sh\njava -jar paper.jar\n");
        LocalRestart.Status st = LocalRestart.evaluateScript(root);
        assertTrue(st.ok(), st.detail());
    }

    @Test
    void customScriptFromSpigotYmlIsUsed() throws Exception {
        Files.writeString(root.resolve("spigot.yml"), "settings:\n  restart-script: ./run.sh\n");
        assertFalse(LocalRestart.evaluateScript(root).ok());
        Files.writeString(root.resolve("run.sh"), "#!/bin/sh\n");
        assertTrue(LocalRestart.evaluateScript(root).ok());
    }

    @Test
    void blankOrInterpreterOnlyScriptIsRejected() throws Exception {
        Files.writeString(root.resolve("spigot.yml"), "settings:\n  restart-script: ''\n");
        assertFalse(LocalRestart.evaluateScript(root).ok());
        Files.writeString(root.resolve("spigot.yml"), "settings:\n  restart-script: sh start.sh\n");
        assertFalse(LocalRestart.evaluateScript(root).ok(), "Spigot prueft das erste Token als Datei");
    }
}
