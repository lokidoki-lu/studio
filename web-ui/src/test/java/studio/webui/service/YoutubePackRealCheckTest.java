/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import studio.core.v1.model.StageNode;
import studio.core.v1.model.StoryPack;
import studio.core.v1.reader.archive.ArchiveStoryPackReader;
import studio.metadata.DatabaseMetadataService;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real thing: yt-dlp, ffmpeg, SponsorBlock and the voices, into a temporary library. Needs the
 * network, hence off by default:
 * {@code mvn test -Dtest=YoutubePackRealCheckTest -Dstudio.youtube.realCheck=true
 * -Dstudio.youtube.realUrl=https://www.youtube.com/watch?v=…}
 */
@EnabledIfSystemProperty(named = "studio.youtube.realCheck", matches = "true")
class YoutubePackRealCheckTest {

    @TempDir
    Path tmp;

    @Test
    void makesAPackFromARealLink() throws Exception {
        String url = System.getProperty("studio.youtube.realUrl", "https://www.youtube.com/watch?v=YE7VzlLtp-4");
        Path library = Files.createDirectories(tmp.resolve("library"));
        String previousLibrary = System.getProperty(LibraryService.LOCAL_LIBRARY_PROP);
        String previousTmp = System.getProperty(LibraryService.TMP_DIR_PROP);
        System.setProperty(LibraryService.LOCAL_LIBRARY_PROP, library + File.separator);
        System.setProperty(LibraryService.TMP_DIR_PROP, tmp.resolve("tmp") + File.separator);
        try {
            LibraryService libraryService = new LibraryService(new DatabaseMetadataService(true));
            YoutubePackService service = new YoutubePackService(new TitleVoiceService(), libraryService);
            System.out.println("tools: " + service.tools(true).toJson().encode());
            long start = System.currentTimeMillis();
            YoutubePackJob job = service.start(YoutubePackRequest.fromJson(new JsonObject()
                    .put("urls", url).put("pauses", false).put("bitrate", 64)));
            String lastStep = "";
            while (job.isRunning() && System.currentTimeMillis() - start < 15 * 60_000) {
                Thread.sleep(500);
                JsonObject s = job.toJson();
                String step = s.getString("step") + " " + Math.round(s.getDouble("progress") * 100) + "%";
                if (!step.equals(lastStep)) {
                    System.out.println(step);
                    lastStep = step;
                }
            }
            JsonObject state = job.toJson();
            state.getJsonArray("log").forEach(l -> System.out.println("  | " + l));
            System.out.println("took " + (System.currentTimeMillis() - start) / 1000 + " s");
            assertEquals("DONE", state.getString("state"), state.encodePrettily());

            Path pack = library.resolve(state.getJsonObject("result").getString("path"));
            StoryPack read;
            try (InputStream in = Files.newInputStream(pack)) {
                read = new ArchiveStoryPackReader().read(in);
            }
            for (StageNode n : read.getStageNodes()) {
                System.out.println("node " + n.getEnriched().getName() + " image=" + (n.getImage() != null)
                        + " audio=" + (n.getAudio() == null ? null : n.getAudio().getName() + " " + n.getAudio().getRawData().length + " B"));
            }
            assertTrue(read.getStageNodes().size() >= 3);
            // The library sees it
            assertEquals(1, libraryService.packs().size());
            Path keep = Paths.get("target", "youtube-check", pack.getFileName().toString());
            Files.createDirectories(keep.getParent());
            Files.copy(pack, keep, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("pack: " + keep.toAbsolutePath() + " (" + Files.size(keep) + " B)");
        } finally {
            restore(LibraryService.LOCAL_LIBRARY_PROP, previousLibrary);
            restore(LibraryService.TMP_DIR_PROP, previousTmp);
        }
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
