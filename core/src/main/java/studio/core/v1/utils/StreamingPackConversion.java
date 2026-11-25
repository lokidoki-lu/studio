/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1.utils;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import studio.core.v1.model.Asset;
import studio.core.v1.model.AudioAsset;
import studio.core.v1.model.ImageAsset;
import studio.core.v1.model.StoryPack;
import studio.core.v1.reader.archive.ArchiveStoryPackReader;
import studio.core.v1.reader.fs.FsStoryPackReader;
import studio.core.v1.writer.archive.ArchiveStoryPackWriter;
import studio.core.v1.writer.fs.FsStoryPackWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Pack conversions that handle the assets one at a time instead of loading the whole pack.
 *
 * <p>The conversions of the library used to read every asset of the source pack into memory,
 * transform them all, then write them all: a pack of 500 MB took more than a gigabyte of heap. Here
 * only the structure of the pack (nodes, transitions, asset names) is held; each asset is read,
 * transformed and written before the next one is read. The peak memory is that of the largest asset
 * (and its transformed copy), whatever the size of the pack.
 *
 * <p>The output is the same, byte for byte, as the in-memory conversions it replaces: the same
 * readers parse and validate the structure, the same transforms prepare each asset
 * ({@link PackAssetsCompression#prepareImageFirmware2dot4}, {@link PackAssetsCompression#prepareAudioFirmware2dot4}),
 * and the same writers number, share and check the assets ({@link FsStoryPackWriter},
 * {@link ArchiveStoryPackWriter}), only fed one asset at a time.
 */
public final class StreamingPackConversion {

    private StreamingPackConversion() {
    }

    /** What a conversion produced: the uuid of the pack and the file or folder written. */
    public static final class Result {
        private final String uuid;
        private final Path produced;

        Result(String uuid, Path produced) {
            this.uuid = uuid;
            this.produced = produced;
        }

        public String getUuid() {
            return uuid;
        }

        public Path getProduced() {
            return produced;
        }
    }

    /**
     * Converts the archive pack at {@code archive} to an FS pack (firmware 2.4 assets: 4-bits RLE
     * BMP images, mono 44100 Hz MP3 without ID3 tags) in a folder created under {@code outputFolder}.
     *
     * <p>Same result as {@code ArchiveStoryPackReader.read}, then
     * {@code PackAssetsCompression.withPreparedAssetsFirmware2dot4}, then {@code FsStoryPackWriter.write}.
     */
    public static Result archiveToFs(Path archive, Path outputFolder) throws Exception {
        try (ArchiveStoryPackReader.OpenedArchive opened = new ArchiveStoryPackReader().open(archive)) {
            StoryPack pack = opened.getPack();
            Path folder = new FsStoryPackWriter().write(pack, outputFolder, new Firmware2dot4Source(opened::readAsset));
            return new Result(pack.getUuid(), folder);
        }
    }

    /**
     * Converts the FS pack in {@code fsFolder} to an archive written to {@code out} (not closed by
     * this method, but finished: the zip is complete when it returns). The assets are copied as they
     * are, deciphered when the pack is stored ciphered.
     *
     * <p>Same entries as {@code FsStoryPackReader.read} then {@code ArchiveStoryPackWriter.write}.
     *
     * @return the uuid of the pack
     */
    public static String fsToArchive(Path fsFolder, OutputStream out) throws IOException {
        FsStoryPackReader.OpenedFsPack opened = new FsStoryPackReader().open(fsFolder);
        new ArchiveStoryPackWriter().write(opened.getPack(), out, new FsAssetSource(opened));
        return opened.getPack().getUuid();
    }

    /** Reads the bytes of an asset by its name in the source pack. */
    @FunctionalInterface
    public interface AssetLoader {
        byte[] load(String name) throws IOException;
    }

    /**
     * Prepares each asset for firmware 2.4 when the FS writer asks for it, reading it from the
     * source on demand.
     *
     * <p>An asset is read and prepared once: the digest of the prepared bytes is remembered by asset
     * name (several nodes using the same file) and by digest of the original bytes (several files
     * with the same content, which the in-memory transform also prepared once). The writer only
     * needs the bytes the first time it meets a digest, so a known one is returned without them.
     */
    public static final class Firmware2dot4Source implements FsStoryPackWriter.AssetSource {
        private final AssetLoader loader;
        private final Map<String, String> imageByName = new HashMap<>();
        private final Map<String, String> imageByOriginal = new HashMap<>();
        private final Map<String, String> audioByName = new HashMap<>();
        private final Map<String, String> audioByOriginal = new HashMap<>();

        public Firmware2dot4Source(AssetLoader loader) {
            this.loader = loader;
        }

        @Override
        public FsStoryPackWriter.StoredAsset image(ImageAsset image) throws Exception {
            return prepared(image, "image/bmp", imageByName, imageByOriginal, true);
        }

        @Override
        public FsStoryPackWriter.StoredAsset audio(AudioAsset audio) throws Exception {
            return prepared(audio, "audio/mpeg", audioByName, audioByOriginal, false);
        }

        private FsStoryPackWriter.StoredAsset prepared(Asset asset, String preparedMimeType,
                                                       Map<String, String> byName, Map<String, String> byOriginal,
                                                       boolean image) throws Exception {
            String known = byName.get(asset.getName());
            if (known != null) {
                return new FsStoryPackWriter.StoredAsset(preparedMimeType, known, null);
            }
            byte[] original = asset.getRawData() != null ? asset.getRawData() : loader.load(asset.getName());
            String originalHash = DigestUtils.sha1Hex(original);
            known = byOriginal.get(originalHash);
            if (known != null) {
                byName.put(asset.getName(), known);
                return new FsStoryPackWriter.StoredAsset(preparedMimeType, known, null);
            }
            byte[] prepared = image
                    ? PackAssetsCompression.prepareImageFirmware2dot4(original, asset.getMimeType(), asset.getName())
                    : PackAssetsCompression.prepareAudioFirmware2dot4(original, asset.getMimeType(), asset.getName());
            String preparedHash = DigestUtils.sha1Hex(prepared);
            byName.put(asset.getName(), preparedHash);
            byOriginal.put(originalHash, preparedHash);
            return new FsStoryPackWriter.StoredAsset(preparedMimeType, preparedHash, prepared);
        }
    }

    /**
     * The assets of an FS pack, streamed from their files: the digest is computed by reading the
     * file once (remembered by file, several nodes sharing one), the bytes are copied into the
     * archive by reading it again. Nothing larger than a read buffer is held.
     *
     * <p>The bytes copied are digested as they go, and must give the digest the entry is named
     * after: a file changed between the two reads would otherwise be stored under a name that is
     * not its content's. The conversion then fails (and its output is thrown away).
     */
    static final class FsAssetSource implements ArchiveStoryPackWriter.AssetSource {
        private final FsStoryPackReader.OpenedFsPack pack;
        private final Map<String, String> imageDigests = new HashMap<>();
        private final Map<String, String> audioDigests = new HashMap<>();

        FsAssetSource(FsStoryPackReader.OpenedFsPack pack) {
            this.pack = pack;
        }

        private boolean isImage(Asset asset) {
            return asset instanceof ImageAsset;
        }

        private InputStream open(Asset asset) throws IOException {
            return isImage(asset) ? pack.openImage(asset) : pack.openAudio(asset);
        }

        @Override
        public String sha1(Asset asset) throws IOException {
            Map<String, String> digests = isImage(asset) ? imageDigests : audioDigests;
            String known = digests.get(asset.getName());
            if (known == null) {
                try (InputStream in = open(asset)) {
                    known = DigestUtils.sha1Hex(in);
                }
                digests.put(asset.getName(), known);
            }
            return known;
        }

        @Override
        public void copy(Asset asset, OutputStream out) throws IOException {
            MessageDigest digest = DigestUtils.getSha1Digest();
            try (InputStream in = new DigestInputStream(open(asset), digest)) {
                in.transferTo(out);
            }
            String named = (isImage(asset) ? imageDigests : audioDigests).get(asset.getName());
            String copied = Hex.encodeHexString(digest.digest());
            if (named != null && !named.equals(copied)) {
                throw new IOException("Asset " + asset.getName() + " changed while the pack was being converted"
                        + " (SHA-1 " + named + " when named, " + copied + " when copied)");
            }
        }
    }
}
