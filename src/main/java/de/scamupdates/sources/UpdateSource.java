package de.scamupdates.sources;

import de.scamupdates.model.PluginInfo;
import de.scamupdates.model.ProjectRef;
import de.scamupdates.model.RemoteRelease;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Ein Adapter pro Plattform. Alle Methoden blockieren und duerfen nie auf dem Hauptthread laufen. */
public interface UpdateSource {
    String id();

    String displayName();

    /** Ob automatische Suche nach Projekten unterstuetzt wird. */
    boolean supportsSearch();

    List<ProjectRef> search(PluginInfo plugin) throws IOException, InterruptedException;

    /** Neueste zum Kanal/Minecraft passende Version oder leer, wenn keine existiert. */
    Optional<RemoteRelease> latest(ProjectRef project, ReleaseQuery query) throws IOException, InterruptedException;
}
