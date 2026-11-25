/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.core.v1.model.AudioAsset;
import studio.core.v1.model.ControlSettings;
import studio.core.v1.model.ImageAsset;
import studio.core.v1.model.StageNode;
import studio.core.v1.model.StoryPack;
import studio.core.v1.writer.archive.ArchiveStoryPackWriter;
import studio.core.v1.writer.fs.FsStoryPackWriter;
import studio.driver.StoryTellerException;
import studio.metadata.DatabaseMetadataService;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Official packs are never transferred, between a Lunii and the library, in either direction, and
 * the guard does not depend on the official database alone: a pack goes only if it is known to be
 * one of the user's own (an archive of it made with STUdio is in the library), and the official
 * database is there.
 */
@DisplayName("The official pack guard")
class OfficialPackGuardTest {

    private static final String HOMEMADE = "11111111-2222-3333-4444-555555555555";
    private static final String UNKNOWN = "99999999-8888-7777-6666-555555555555";
    private static final String OFFICIAL = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String RENEWED = "12121212-3434-5656-7878-909090909090";

    @TempDir
    Path root;

    private Path library;
    private LibraryService service;
    /** What the official database lists; empty is a database never downloaded (offline). */
    private final Set<String> officialDatabase = new HashSet<>();
    private final Map<String, String> previousProperties = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        library = Files.createDirectories(root.resolve("library"));
        Files.createDirectories(root.resolve("tmp"));
        Files.createDirectories(root.resolve("db"));
        setProperty(LibraryService.LOCAL_LIBRARY_PROP, library + File.separator);
        setProperty(LibraryService.TMP_DIR_PROP, root.resolve("tmp") + File.separator);
        setProperty(ConversionProvenanceStore.CONVERSIONS_DB_PROP, root.resolve("db").resolve("conversions.json").toString());
        setProperty(DeviceSourcedPackStore.DEVICE_SOURCED_DB_PROP, root.resolve("db").resolve("device-sourced.json").toString());
        setProperty(DatabaseMetadataService.UNOFFICIAL_DB_PROP, root.resolve("db").resolve("unofficial.json").toString());
        // The official database is downloaded, unless a test says otherwise
        officialDatabase.add(OFFICIAL);
        service = new CountingLibraryService(metadata());
    }

    private DatabaseMetadataService metadata() {
        return new DatabaseMetadataService(true) {
            @Override
            public boolean isOfficialPack(String uuid) {
                return uuid != null && officialDatabase.contains(uuid.toLowerCase(Locale.ROOT));
            }

            @Override
            public boolean isOfficialDatabaseAvailable() {
                return !officialDatabase.isEmpty();
            }
        };
    }

    /** Counts the artefacts hashed for a provenance, and the archives digested on entry. */
    static final class CountingLibraryService extends LibraryService {
        final AtomicInteger hashed = new AtomicInteger();
        final AtomicInteger archivesDigested = new AtomicInteger();

        CountingLibraryService(DatabaseMetadataService metadata) {
            super(metadata);
        }

        @Override
        Optional<String> identityOf(Path artifact) {
            hashed.incrementAndGet();
            return super.identityOf(artifact);
        }

        @Override
        Optional<String> sourceIdentity(String packPath) {
            hashed.incrementAndGet();
            return super.sourceIdentity(packPath);
        }

        @Override
        Set<String> archiveAssetDigests(Path archive) throws IOException {
            archivesDigested.incrementAndGet();
            return super.archiveAssetDigests(archive);
        }
    }

    private CountingLibraryService counting() {
        return (CountingLibraryService) service;
    }

    @AfterEach
    void tearDown() {
        previousProperties.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    private void setProperty(String key, String value) {
        previousProperties.putIfAbsent(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    // ------------------------------------------------- fixtures

    private static StoryPack pack(String uuid) {
        StageNode node = new StageNode(uuid, null, null, null, null,
                new ControlSettings(false, false, false, false, false), null);
        return new StoryPack(uuid, false, (short) 1, Collections.singletonList(node), null, false);
    }

    /** Bytes standing for an asset: distinct by seed, {@code length} long. */
    private static byte[] asset(int seed, int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (seed * 31 + i % 251);
        }
        return data;
    }

    /** A pack with one node holding an image and a sound. */
    private static StoryPack packWithAssets(String uuid, byte[] image, byte[] audio) {
        StageNode node = new StageNode(uuid,
                new ImageAsset("image/png", image, "cover.png"),
                new AudioAsset("audio/mpeg", audio, "story.mp3"),
                null, null, new ControlSettings(false, false, false, false, false), null);
        return new StoryPack(uuid, false, (short) 1, Collections.singletonList(node), null, false);
    }

    private static void writeArchive(StoryPack pack, Path file) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            new ArchiveStoryPackWriter().write(pack, out);
        }
    }

    /** An archive made with STUdio (the editor's format). */
    private Path archive(String uuid, String name) throws IOException {
        Path file = library.resolve(name);
        writeArchive(pack(uuid), file);
        return file;
    }

    /** An FS pack folder, as extracted from a Lunii, or renamed by hand: named after its UUID. */
    private Path fsFolder(String uuid, String name) throws Exception {
        Path parent = Files.createDirectories(root.resolve("fixture-" + name));
        Path written = new FsStoryPackWriter().write(pack(uuid), parent);
        return Files.move(written, library.resolve(name));
    }

    /** An archive saved by the editor, entering the library through the upload route. */
    private void upload(StoryPack pack, String name) throws IOException {
        Path file = root.resolve("upload-" + System.nanoTime() + ".zip");
        writeArchive(pack, file);
        assertTrue(service.addPackFile(name, file.toString()));
    }

    private Optional<StoryTellerException> send(String uuid, String name) {
        return LibrarySendGuard.refusal(service, uuid, name, library.resolve(name));
    }

    private Optional<StoryTellerException> extract(String uuid) {
        return LibrarySendGuard.extractionRefusal(service, uuid);
    }

    private String convertToFs(String archiveName) {
        return service.addConvertedFsPackFile(archiveName, false).orElseThrow().toString();
    }

    // ------------------------------------------------- the official database

    @Test
    @DisplayName("with an empty official database, nothing is sent nor extracted, not even a pack of the user's")
    void emptyDatabaseIsFailClosed() throws Exception {
        archive(HOMEMADE, "my pack.zip");
        fsFolder(UNKNOWN, UNKNOWN);
        officialDatabase.clear();

        for (Optional<StoryTellerException> refused : List.of(send(UNKNOWN, UNKNOWN), send(HOMEMADE, "my pack.zip"), extract(HOMEMADE))) {
            assertTrue(refused.isPresent());
            assertTrue(refused.get().getMessage().contains("base des packs officiels"), refused.get().getMessage());
        }
    }

    @Test
    @DisplayName("a pack absent from a loaded official database is still refused without proof of being the user's")
    void absentFromTheDatabaseIsNotEnough() throws Exception {
        fsFolder(UNKNOWN, UNKNOWN);

        assertTrue(send(UNKNOWN, UNKNOWN).isPresent());
        assertTrue(extract(UNKNOWN).isPresent());
    }

    @Test
    @DisplayName("a pack of the user's own (an archive made with STUdio) goes, as the archive or as its conversion")
    void homemadePackGoes() throws Exception {
        archive(HOMEMADE, "my pack.zip");
        String converted = convertToFs("my pack.zip");

        assertFalse(send(HOMEMADE, "my pack.zip").isPresent(), "the archive itself");
        assertFalse(send(HOMEMADE, converted).isPresent(), "its FS conversion");
        assertFalse(send(HOMEMADE.toUpperCase(Locale.ROOT), converted).isPresent(), "whatever the case of the UUID");
        assertFalse(extract(HOMEMADE).isPresent(), "copied back from the Lunii");
    }

    @Test
    @DisplayName("an official pack is refused, even when the library holds an archive of it")
    void officialPackIsRefused() throws Exception {
        archive(OFFICIAL, "official.zip");
        fsFolder(OFFICIAL, OFFICIAL);

        assertTrue(send(OFFICIAL, "official.zip").isPresent());
        assertTrue(send(OFFICIAL, OFFICIAL).isPresent());
        assertTrue(send(OFFICIAL.toUpperCase(Locale.ROOT), OFFICIAL).isPresent(), "whatever the case of the UUID");
        assertTrue(extract(OFFICIAL).isPresent());
    }

    @Test
    @DisplayName("a library entry that does not exist is left to the transfer, unless the database says official or is empty")
    void missingEntry() {
        assertFalse(send(UNKNOWN, "missing.zip").isPresent(), "the transfer refuses it with LIBRARY_PACK_NOT_FOUND");
        assertTrue(send(OFFICIAL, "missing.zip").isPresent());
        officialDatabase.clear();
        assertTrue(send(UNKNOWN, "missing.zip").isPresent());
    }

    // ------------------------------------------------- what is no proof

    @Test
    @DisplayName("an archive converted by STUdio from an FS pack (an extracted one, maybe) is no proof")
    void archiveConvertedFromFsIsNoProof() throws Exception {
        fsFolder(UNKNOWN, UNKNOWN + ".folder");
        String archiveName = service.addConvertedArchivePackFile(UNKNOWN + ".folder").orElseThrow().getFileName().toString();
        assertTrue(archiveName.endsWith(".zip"), archiveName);

        assertTrue(send(UNKNOWN, archiveName).isPresent(), "the converted archive is not sent");
        assertTrue(extract(UNKNOWN).isPresent());
    }

    @Test
    @DisplayName("an archive named as a conversion output is no proof, even without any record of the conversion")
    void conversionOutputNameIsNoProof() throws Exception {
        archive(UNKNOWN, UNKNOWN + ".converted_1600000000000.zip");

        assertTrue(send(UNKNOWN, UNKNOWN + ".converted_1600000000000.zip").isPresent());
        assertTrue(extract(UNKNOWN).isPresent());
    }

    @Test
    @DisplayName("while the provenance database cannot be read, no archive is a proof, and the message names it")
    void unreadableProvenanceDatabaseMeansNoProof() throws Exception {
        archive(HOMEMADE, "my pack.zip");
        assertFalse(send(HOMEMADE, "my pack.zip").isPresent(), "fixture: a proof while the ledger is readable");

        Files.write(root.resolve("db").resolve("conversions.json"), "{not json".getBytes(StandardCharsets.UTF_8));
        Optional<StoryTellerException> refused = send(HOMEMADE, "my pack.zip");
        assertTrue(refused.isPresent());
        assertTrue(refused.get().getMessage().contains("conversions.json"), refused.get().getMessage());
    }

    @Test
    @DisplayName("an unreadable list of marks proves nothing, says so, and is not overwritten")
    void unreadableMarksMeanNoProof() throws Exception {
        archive(HOMEMADE, "my pack.zip");
        Path marks = root.resolve("db").resolve("device-sourced.json");
        Files.write(marks, "garbage".getBytes(StandardCharsets.UTF_8));

        Optional<StoryTellerException> refused = send(HOMEMADE, "my pack.zip");
        assertTrue(refused.isPresent());
        assertTrue(refused.get().getMessage().contains("device-sourced.json"), refused.get().getMessage());
        assertThrows(IOException.class, () -> service.recordExtractedFromDevice(UNKNOWN));
        assertEquals("garbage", new String(Files.readAllBytes(marks), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a converted archive renamed (downloaded and uploaded again, saved as) is still no proof: the UUID is marked")
    void renamedConversionIsNoProof() throws Exception {
        fsFolder(UNKNOWN, UNKNOWN + ".folder");
        Path converted = library.resolve(service.addConvertedArchivePackFile(UNKNOWN + ".folder").get());
        Files.copy(converted, library.resolve("my own pack.zip"));
        Files.delete(converted);
        // Even with the ledger gone, the mark of the UUID stays
        Files.deleteIfExists(root.resolve("db").resolve("conversions.json"));

        assertTrue(send(UNKNOWN, "my own pack.zip").isPresent());
        assertTrue(extract(UNKNOWN).isPresent());
    }

    @Test
    @DisplayName("a folder renamed by hand after one of the user's packs is not sent; a conversion STUdio made of it is")
    void renamedFolderIsNotASendSource() throws Exception {
        archive(HOMEMADE, "my pack.zip");
        fsFolder(HOMEMADE, HOMEMADE);
        fsFolder(HOMEMADE, HOMEMADE + ".converted_1600000000000");

        assertTrue(send(HOMEMADE, HOMEMADE).isPresent(), "named by hand");
        assertTrue(send(HOMEMADE, HOMEMADE + ".converted_1600000000000").isPresent(), "named as a conversion, with no record");
        assertFalse(send(HOMEMADE, convertToFs("my pack.zip")).isPresent(), "converted by STUdio from the archive");
    }

    // ------------------------------------------------- marks

    @Test
    @DisplayName("the user's own pack, extracted or converted while its archive is in the library, is not marked")
    void homemadePackIsNotMarked() throws Exception {
        archive(HOMEMADE, "my pack.zip");
        fsFolder(HOMEMADE, HOMEMADE + ".folder");

        service.recordExtractedFromDevice(HOMEMADE);
        assertTrue(service.addConvertedArchivePackFile(HOMEMADE + ".folder").isPresent());

        assertFalse(send(HOMEMADE, "my pack.zip").isPresent());
        assertFalse(extract(HOMEMADE).isPresent());
        // The archive kept, under another name: still the user's
        Files.move(library.resolve("my pack.zip"), library.resolve("renamed.zip"));
        assertFalse(send(HOMEMADE, "renamed.zip").isPresent());
    }

    @Test
    @DisplayName("a pack extracted without being proven the user's is marked for good")
    void unprovenExtractionIsMarked() throws Exception {
        service.recordExtractedFromDevice(UNKNOWN);
        archive(UNKNOWN, "looks homemade.zip");

        assertTrue(send(UNKNOWN, "looks homemade.zip").isPresent());
        assertTrue(extract(UNKNOWN).isPresent());
    }

    // ------------------------------------------------- content saved again under a new UUID

    /**
     * A pack that may come from a Lunii, marked with its content by the library check: an FS pack
     * of the library (as extracted) and an unrecorded conversion of it to an archive.
     */
    private void markedPackWithContent(byte[] image, byte[] audio) throws Exception {
        fsFolder(UNKNOWN, UNKNOWN);
        writeArchive(packWithAssets(UNKNOWN, image, audio), library.resolve(UNKNOWN + ".converted_1600000000000.zip"));
        service.checkLibrary();
    }

    @Test
    @DisplayName("official content saved again by the editor under a new UUID is refused")
    void contentUnderANewUuidIsRefused() throws Exception {
        byte[] audio = asset(2, 9000);
        markedPackWithContent(asset(1, 5000), audio);

        // Opened in the editor, the cover recreated (new UUID), saved
        upload(packWithAssets(RENEWED, asset(3, 4000), audio), "Mon pack.zip");

        assertTrue(send(RENEWED, "Mon pack.zip").isPresent());
        assertTrue(extract(RENEWED).isPresent());
        assertFalse(service.mayOpenInEditor("Mon pack.zip"));
    }

    @Test
    @DisplayName("a pack of the user's edited and saved again (new UUID, same assets) is still the user's")
    void homemadeContentSavedAgainGoes() throws Exception {
        byte[] image = asset(4, 5000);
        byte[] audio = asset(5, 9000);
        writeArchive(packWithAssets(HOMEMADE, image, audio), library.resolve("my pack.zip"));
        // A marked pack elsewhere, sharing only a trivial asset (under 2 KB) with the user's
        byte[] tiny = asset(6, 100);
        markedPackWithContent(tiny, asset(7, 9000));

        upload(packWithAssets(RENEWED, image, audio), "my pack v2.zip");
        upload(packWithAssets(HOMEMADE, tiny, audio), "my pack.zip");

        assertFalse(send(RENEWED, "my pack v2.zip").isPresent());
        assertFalse(send(HOMEMADE, "my pack.zip").isPresent());
        assertTrue(service.mayOpenInEditor("my pack v2.zip"));
    }

    @Test
    @DisplayName("the editor does not open an official pack, a marked one, or the conversion of an unproven one")
    void editorRefusals() throws Exception {
        archive(OFFICIAL, "official.zip");
        archive(UNKNOWN, UNKNOWN + ".converted_1600000000000.zip");
        archive(HOMEMADE, "my pack.zip");
        archive(HOMEMADE, HOMEMADE + ".converted_1600000000001.zip");

        assertFalse(service.mayOpenInEditor("official.zip"));
        assertFalse(service.mayOpenInEditor(UNKNOWN + ".converted_1600000000000.zip"));
        assertTrue(service.mayOpenInEditor("my pack.zip"));
        assertTrue(service.mayOpenInEditor(HOMEMADE + ".converted_1600000000001.zip"), "conversion of a proven pack");

        service.recordExtractedFromDevice(UNKNOWN);
        archive(UNKNOWN, "renamed.zip");
        assertFalse(service.mayOpenInEditor("renamed.zip"), "marked");
    }

    // ------------------------------------------------- library check at startup

    @Test
    @DisplayName("library check: four community archives and the FS conversion of one are left alone")
    void libraryCheckKeepsTheUsersLibrary() throws Exception {
        String daltons = "0a0a0a0a-0000-4000-8000-000000000001";
        String[] others = {"0b0b0b0b-0000-4000-8000-000000000002", "0c0c0c0c-0000-4000-8000-000000000003",
                "0d0d0d0d-0000-4000-8000-000000000004"};
        archive(daltons, "Les daltons.zip");
        for (int i = 0; i < others.length; i++) {
            writeArchive(packWithAssets(others[i], asset(10 + i, 5000), asset(20 + i, 9000)), library.resolve("Pack " + i + ".zip"));
        }
        String converted = convertToFs("Les daltons.zip");

        service.checkLibrary();
        service.checkLibrary();   // idempotent

        assertFalse(send(daltons, "Les daltons.zip").isPresent());
        assertFalse(send(daltons, converted).isPresent());
        for (int i = 0; i < others.length; i++) {
            assertFalse(send(others[i], "Pack " + i + ".zip").isPresent(), others[i]);
        }
        Path marks = root.resolve("db").resolve("device-sourced.json");
        assertTrue(!Files.exists(marks) || !new String(Files.readAllBytes(marks), StandardCharsets.UTF_8).contains("-4000-8000-"),
                "nothing of the user's is marked");

        // The same, with the ledger of the conversion lost
        Files.deleteIfExists(root.resolve("db").resolve("conversions.json"));
        service.checkLibrary();
        assertFalse(send(daltons, "Les daltons.zip").isPresent());
        assertTrue(!Files.exists(marks) || !new String(Files.readAllBytes(marks), StandardCharsets.UTF_8).contains("-4000-8000-"));
    }

    @Test
    @DisplayName("library check: raw / FS packs, and conversions of them, are marked")
    void libraryCheckMarksDeviceSourcedPacks() throws Exception {
        String extracted = "e0e0e0e0-0000-4000-8000-000000000001";
        String convertedFromFs = "e1e1e1e1-0000-4000-8000-000000000002";
        String unrecorded = "e2e2e2e2-0000-4000-8000-000000000003";
        fsFolder(extracted, extracted);
        archive(extracted, "renamed long ago.zip");
        fsFolder(convertedFromFs, convertedFromFs + ".folder");
        String archiveOfFs = service.addConvertedArchivePackFile(convertedFromFs + ".folder").orElseThrow().toString();
        fsFolder(unrecorded, unrecorded);
        archive(unrecorded, unrecorded + ".converted_1600000000000.zip");

        service.checkLibrary();

        assertFalse(send(extracted, "renamed long ago.zip").isPresent(),
                "proven by its archive: not marked, the user's own pack extracted once is not blocked for good");
        assertTrue(extract(convertedFromFs).isPresent());
        assertTrue(send(convertedFromFs, archiveOfFs).isPresent());
        assertTrue(extract(unrecorded).isPresent());
    }

    @Test
    @DisplayName("library check: the user's own pack, extracted once beside its archive, is not marked")
    void libraryCheckSparesProvenPacks() throws Exception {
        archive(HOMEMADE, "my pack.zip");
        fsFolder(HOMEMADE, HOMEMADE);
        fsFolder(UNKNOWN, UNKNOWN);

        service.checkLibrary();

        assertFalse(send(HOMEMADE, "my pack.zip").isPresent());
        assertFalse(extract(HOMEMADE).isPresent());
        assertTrue(extract(UNKNOWN).isPresent(), "no archive of it: marked");
    }

    @Test
    @DisplayName("a failed library check names the unreadable file, and is started again once it is repaired")
    void failedLibraryCheckIsRetried() throws Exception {
        archive(HOMEMADE, "my pack.zip");
        fsFolder(UNKNOWN, UNKNOWN);
        Path marks = root.resolve("db").resolve("device-sourced.json");
        Files.write(marks, "garbage".getBytes(StandardCharsets.UTF_8));

        service.startLibraryCheck();
        Optional<String> problem = awaitLibraryCheck();
        assertTrue(problem.isPresent(), "the mark of the FS pack cannot be stored");
        assertTrue(problem.get().contains("device-sourced.json"), problem.get());

        Files.delete(marks);
        // Asking again starts it again
        service.libraryCheckProblem();
        assertFalse(awaitLibraryCheck().isPresent());
        assertFalse(send(HOMEMADE, "my pack.zip").isPresent());
        assertTrue(extract(UNKNOWN).isPresent());
    }

    /** Waits for the library check to end; the problem it leaves, if any (without asking again). */
    private Optional<String> awaitLibraryCheck() throws Exception {
        Field pending = LibraryService.class.getDeclaredField("libraryCheckPending");
        Field failed = LibraryService.class.getDeclaredField("libraryCheckFailed");
        pending.setAccessible(true);
        failed.setAccessible(true);
        long deadline = System.currentTimeMillis() + 10_000;
        while ((boolean) pending.get(service) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertFalse((boolean) pending.get(service), "the library check ended");
        return (boolean) failed.get(service) ? service.proofDatabaseProblem().or(() -> Optional.of("failed")) : Optional.empty();
    }

    // ------------------------------------------------- costs and refusals of the regular user

    @Test
    @DisplayName("a conversion just made, then sent, is not hashed again; a change is")
    void verifiedConversionIsNotHashedAgain() throws Exception {
        archive(HOMEMADE, "my pack.zip");
        String converted = convertToFs("my pack.zip");
        counting().hashed.set(0);

        assertFalse(send(HOMEMADE, converted).isPresent(), "at the request");
        assertFalse(send(HOMEMADE, converted).isPresent(), "again when the transfer starts");
        assertEquals(0, counting().hashed.get(), "nothing hashed again");
        // /verify-conversion, which decides a silent transfer, always hashes
        assertEquals(ProvenanceVerdict.MATCH, service.verifyConversion("my pack.zip", converted));
        assertTrue(counting().hashed.get() > 0);
        counting().hashed.set(0);

        // The artefact changed (another size): hashed again, and no longer a match
        Files.write(library.resolve(converted).resolve("ni"), new byte[]{1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);
        assertTrue(send(HOMEMADE, converted).isPresent());
        assertTrue(counting().hashed.get() > 0);
    }

    @Test
    @DisplayName("a conversion of an archive that cannot be recorded is cancelled, with a clear message")
    void unrecordedConversionOfAnArchiveIsCancelled() throws Exception {
        archive(HOMEMADE, "my pack.zip");
        Files.write(root.resolve("db").resolve("conversions.json"), "{not json".getBytes(StandardCharsets.UTF_8));

        StoryTellerException e = assertThrows(StoryTellerException.class, () -> convertToFs("my pack.zip"));
        assertTrue(e.getMessage().contains("conversions.json"), e.getMessage());
        try (java.util.stream.Stream<Path> entries = Files.list(library)) {
            assertEquals(List.of("my pack.zip"), entries.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toList()),
                    "no copy left behind");
        }
    }

    @Test
    @DisplayName("an archive entering the library is not read while no content is marked")
    void uploadIsNotDigestedWithoutMarkedContent() throws Exception {
        upload(packWithAssets(HOMEMADE, asset(1, 5000), asset(2, 9000)), "mine.zip");
        assertEquals(0, counting().archivesDigested.get());

        markedPackWithContent(asset(3, 5000), asset(4, 9000));
        upload(packWithAssets(RENEWED, asset(5, 5000), asset(6, 9000)), "other.zip");
        assertEquals(1, counting().archivesDigested.get());
        assertFalse(send(RENEWED, "other.zip").isPresent());
    }

    @Test
    @DisplayName("an archive holding marked content whose UUID cannot be read is refused cleanly")
    void unreadableArchiveWithMarkedContentIsRefused() throws Exception {
        byte[] audio = asset(8, 9000);
        markedPackWithContent(asset(9, 5000), audio);
        Path file = root.resolve("broken.zip");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("story.json"));
            zip.write("{ broken".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("assets/a.mp3"));
            zip.write(audio);
            zip.closeEntry();
        }

        StoryTellerException e = assertThrows(StoryTellerException.class, () -> service.addPackFile("broken.zip", file.toString()));
        assertEquals(StoryTellerException.Reason.INVALID_REQUEST, e.getReason());
        assertFalse(Files.exists(library.resolve("broken.zip")));
    }

    @Test
    @DisplayName("the editor opens nothing while the marks cannot be read, and says which file")
    void editorIsFailClosed() throws Exception {
        archive(HOMEMADE, "my pack.zip");
        Files.write(root.resolve("db").resolve("device-sourced.json"), "garbage".getBytes(StandardCharsets.UTF_8));

        Optional<String> refused = service.editorRefusal("my pack.zip");
        assertTrue(refused.isPresent());
        assertTrue(refused.get().contains("device-sourced.json"), refused.get());
    }

    /** Whether the marks name {@code uuid}. */
    private boolean marked(String uuid) throws IOException {
        Path marks = root.resolve("db").resolve("device-sourced.json");
        return Files.exists(marks) && new String(Files.readAllBytes(marks), StandardCharsets.UTF_8).contains(uuid);
    }

    @Test
    @DisplayName("an unreadable ledger marks nothing: the library check fails, conversions from raw / FS and marks are refused")
    void unreadableLedgerMarksNothing() throws Exception {
        archive(HOMEMADE, "my pack.zip");
        fsFolder(HOMEMADE, HOMEMADE + ".folder");
        Path ledger = root.resolve("db").resolve("conversions.json");
        Files.write(ledger, "{not json".getBytes(StandardCharsets.UTF_8));

        StoryTellerException check = assertThrows(StoryTellerException.class, () -> service.checkLibrary());
        assertTrue(check.getMessage().contains("conversions.json"), check.getMessage());
        StoryTellerException conversion = assertThrows(StoryTellerException.class,
                () -> service.addConvertedArchivePackFile(HOMEMADE + ".folder"));
        assertTrue(conversion.getMessage().contains("conversions.json"), conversion.getMessage());
        assertThrows(StoryTellerException.class, () -> service.recordExtractedFromDevice(HOMEMADE));
        assertFalse(marked(HOMEMADE), "no mark set while the ledger cannot be read");

        // The ledger repaired: the user's own pack is still the user's
        Files.delete(ledger);
        service.checkLibrary();
        assertFalse(marked(HOMEMADE));
        assertFalse(send(HOMEMADE, "my pack.zip").isPresent());
    }

    @Test
    @DisplayName("transfers wait for the library check, which runs in the background")
    void transfersWaitForTheLibraryCheck() throws Exception {
        archive(HOMEMADE, "my pack.zip");
        Field pending = LibraryService.class.getDeclaredField("libraryCheckPending");
        pending.setAccessible(true);
        pending.set(service, true);
        Optional<StoryTellerException> refused = send(HOMEMADE, "my pack.zip");
        assertTrue(refused.isPresent());
        assertTrue(refused.get().getMessage().contains("Vérification de la bibliothèque en cours"), refused.get().getMessage());
        assertTrue(extract(HOMEMADE).isPresent());
        pending.set(service, false);

        service.startLibraryCheck();
        long deadline = System.currentTimeMillis() + 10_000;
        while (service.libraryCheckProblem().isPresent() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(service.libraryCheckProblem().isPresent());
        assertFalse(send(HOMEMADE, "my pack.zip").isPresent());
    }
}
