package de.scamupdates.model;

import java.util.List;
import java.util.Map;

/**
 * @param downloadUrl null, wenn die Quelle keinen direkten Download anbietet
 * @param hashes      Algorithmus (SHA-1/SHA-256/SHA-512) -> Hex-Hash
 * @param hashDecides true, wenn nicht die Versionsnummer, sondern der SHA-256 ueber ein Update entscheidet
 */
public record RemoteRelease(String version, Channel channel, String downloadUrl, String fileName,
                            Map<String, String> hashes, long size, List<String> gameVersions,
                            boolean hashDecides) {
}
