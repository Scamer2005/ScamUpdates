package de.scamupdates.net;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaperDownloadsTest {

    @Test
    void versionsAreFlattenedAndSortedNewestFirstIncludingNewScheme() throws Exception {
        String json = "{\"project\":{\"id\":\"paper\"},\"versions\":{\"1.20\":[\"1.20.4\",\"1.20.6\"],"
                + "\"1.21\":[\"1.21.10\",\"1.21.11\"],\"26.1\":[\"26.1\",\"26.1.2\"],"
                + "\"26.2\":[\"26.2\"],\"26.3\":[\"26.3\"]}}";
        List<String> v = PaperDownloads.parseVersions(JsonParser.parseString(json));
        assertEquals(List.of("26.3", "26.2", "26.1.2", "26.1", "1.21.11", "1.21.10", "1.20.6", "1.20.4"), v);
    }

    @Test
    void buildsAreParsedWithChecksumAndSortedByIdDescendingForLegacyAnd26x() throws Exception {
        String json = "[{\"id\":264,\"channel\":\"STABLE\",\"downloads\":{\"server:default\":{\"name\":\"paper-26.3-264.jar\","
                + "\"url\":\"https://fill-data.papermc.io/v1/objects/cc/paper-26.3-264.jar\",\"checksums\":{\"sha256\":\"" + "c".repeat(64) + "\"},\"size\":6000}}},"
                + "{\"id\":100,\"channel\":\"ALPHA\",\"downloads\":{\"server:default\":{\"name\":\"paper-1.21.11-100.jar\","
                + "\"url\":\"https://fill-data.papermc.io/v1/objects/aa/paper-1.21.11-100.jar\",\"checksums\":{\"sha256\":\"" + "a".repeat(64) + "\"},\"size\":5000}}},"
                + "{\"id\":99,\"channel\":\"STABLE\",\"downloads\":{\"server:default\":{\"name\":\"paper-1.21.11-99.jar\","
                + "\"url\":\"https://fill-data.papermc.io/v1/objects/bb/paper-1.21.11-99.jar\",\"checksums\":{\"sha256\":\"" + "b".repeat(64) + "\"},\"size\":4000}}},"
                + "{\"id\":98,\"channel\":\"STABLE\",\"downloads\":{}}]";
        List<PaperDownloads.Build> b = PaperDownloads.parseBuilds(JsonParser.parseString(json));
        assertEquals(3, b.size(), "Builds ohne server:default-Download werden ignoriert");
        assertEquals(264, b.get(0).id());
        assertEquals("STABLE", b.get(0).channel());
        assertEquals("c".repeat(64), b.get(0).sha256());
        assertEquals(100, b.get(1).id());
        assertEquals("ALPHA", b.get(1).channel());
        assertEquals("b".repeat(64), b.get(2).sha256());
        assertEquals(4000, b.get(2).size());
        assertEquals(264, b.stream().filter(x -> x.channel().equalsIgnoreCase("STABLE")).findFirst().orElseThrow().id(),
                "nur STABLE-Builds duerfen angeboten werden");
    }

    @Test
    void unexpectedResponsesAreReportedNotSwallowed() {
        assertThrows(IOException.class, () -> PaperDownloads.parseVersions(JsonParser.parseString("[]")));
        assertThrows(IOException.class, () -> PaperDownloads.parseVersions(JsonParser.parseString("{\"x\":1}")));
        assertThrows(IOException.class, () -> PaperDownloads.parseBuilds(JsonParser.parseString("{}")));
    }
}
