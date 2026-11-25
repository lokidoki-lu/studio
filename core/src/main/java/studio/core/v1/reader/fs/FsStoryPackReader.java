/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1.reader.fs;

import studio.core.v1.Constants;
import studio.core.v1.model.*;
import studio.core.v1.model.metadata.StoryPackMetadata;
import studio.core.v1.utils.BytesUtils;
import studio.core.v1.utils.XXTEACipher;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class FsStoryPackReader {

    private static final Logger LOGGER = Logger.getLogger(FsStoryPackReader.class.getName());

    public static final String NODE_INDEX_FILENAME = "ni";
    public static final String LIST_INDEX_FILENAME = "li";
    public static final String IMAGE_INDEX_FILENAME = "ri";
    public static final String IMAGE_FOLDER = "rf" + File.separator;
    public static final String SOUND_INDEX_FILENAME = "si";
    public static final String SOUND_FOLDER = "sf" + File.separator;
    public static final String NIGHT_MODE_FILENAME = "nm";
    public static final String CLEARTEXT_FILENAME = ".cleartext";
    public static final byte[] CLEARTEXT_RI_BEGINNING = "000\\".getBytes(StandardCharsets.UTF_8);

    /** Size of one entry of the ri / si index files: a relative path such as {@code 000\00000001}. */
    private static final int ASSET_INDEX_ENTRY_SIZE = 12;
    /** An ri / si entry: two plain path segments separated by a single (back)slash, nothing else. */
    private static final Pattern ASSET_PATH_PATTERN = Pattern.compile("[A-Za-z0-9_-]+[\\\\/][A-Za-z0-9_-]+");

    public StoryPackMetadata readMetadata(Path inputFolder) throws IOException {
        // Pack metadata model
        StoryPackMetadata metadata = new StoryPackMetadata(Constants.PACK_FORMAT_FS);

        // Open 'ni' file
        File packFolder = inputFolder.toFile();
        try (FileInputStream niFis = new FileInputStream(new File(packFolder, NODE_INDEX_FILENAME));
             DataInputStream niDis = new DataInputStream(niFis)) {
            ByteBuffer bb = ByteBuffer.wrap(niDis.readNBytes(512)).order(ByteOrder.LITTLE_ENDIAN);
            metadata.setVersion(bb.getShort(2));
        }

        // Folder name is the uuid (minus the eventual timestamp, so we just trim everything starting at the dot)
        String uuid = inputFolder.getFileName().toString().split("\\.", 2)[0];
        metadata.setUuid(uuid);

        // Night mode is available if file 'nm' exists
        metadata.setNightModeAvailable(new File(packFolder, NIGHT_MODE_FILENAME).exists());

        return metadata;
    }

    public StoryPack read(Path inputFolder) throws IOException {
        return read(inputFolder, true);
    }

    /**
     * A pack folder opened for a conversion that streams its assets one at a time.
     *
     * <p>{@link #getPack()} is what {@link #read(Path)} returns, except that the assets carry no
     * bytes: each is named by its ri / si path (e.g. {@code 000\00000003}), and its cleartext bytes
     * are read from its file with {@link #openImage} / {@link #openAudio}, the first block being
     * deciphered on the fly for a pack stored ciphered.
     */
    public static final class OpenedFsPack {
        private final StoryPack pack;
        private final File packFolder;
        private final boolean cleartext;

        private OpenedFsPack(StoryPack pack, File packFolder, boolean cleartext) {
            this.pack = pack;
            this.packFolder = packFolder;
            this.cleartext = cleartext;
        }

        public StoryPack getPack() {
            return pack;
        }

        /** The cleartext bytes of an image of {@link #getPack()}. The caller closes the stream. */
        public InputStream openImage(Asset image) throws IOException {
            return openCleartext(assetFile(packFolder, IMAGE_FOLDER, image.getName()), cleartext);
        }

        /** The cleartext bytes of a sound of {@link #getPack()}. The caller closes the stream. */
        public InputStream openAudio(Asset audio) throws IOException {
            return openCleartext(assetFile(packFolder, SOUND_FOLDER, audio.getName()), cleartext);
        }
    }

    /** Opens the pack folder for a streaming conversion. See {@link OpenedFsPack}. */
    public OpenedFsPack open(Path inputFolder) throws IOException {
        StoryPack pack = read(inputFolder, false);
        boolean isCleartext = isCleartext(inputFolder, false) || looksCleartext(inputFolder.toFile());
        return new OpenedFsPack(pack, inputFolder.toFile(), isCleartext);
    }

    private StoryPack read(Path inputFolder, boolean withAssetData) throws IOException {
        TreeMap<Integer, StageNode> stageNodes = new TreeMap<>();                   // Keep stage nodes
        TreeMap<Integer, Integer> actionNodesOptionsCount = new TreeMap<>();        // Keep action nodes' options count
        TreeMap<Integer, List<Transition>> transitionsWithAction = new TreeMap<>(); // Transitions must be updated with the actual ActionNode

        // Folder name is the uuid (minus the eventual timestamp, so we just trim everything starting at the dot)
        String uuid = inputFolder.getFileName().toString().split("\\.", 2)[0];

        File packFolder = inputFolder.toFile();

        // Night mode is available if file 'nm' exists
        boolean nightModeAvailable = new File(packFolder, NIGHT_MODE_FILENAME).exists();

        // Pack files should be kept as cleartext in the library, with cipher operations happening during transfer to/from device
        // We keep a backward-compatibility with packs in library ciphered for v2

        // Assets are cleartext if file '.cleartext' exists, or if the ri file is obviously cleartext.
        // Reading never writes into the pack folder: the repair of a missing '.cleartext' file is an
        // explicit operation, see isCleartext(Path, boolean).
        boolean isCleartext = isCleartext(inputFolder, false) || looksCleartext(packFolder);

        // Load ri, si and li files
        byte[] riContent = readCipheredFile(new File(packFolder, IMAGE_INDEX_FILENAME).toPath(), isCleartext);
        byte[] siContent = readCipheredFile(new File(packFolder, SOUND_INDEX_FILENAME).toPath(), isCleartext);
        byte[] liContent = readCipheredFile(new File(packFolder, LIST_INDEX_FILENAME).toPath(), isCleartext);

        // Open 'ni' file
        short version;
        boolean factoryDisabled;
        try (FileInputStream niFis = new FileInputStream(new File(packFolder, NODE_INDEX_FILENAME));
             DataInputStream niDis = new DataInputStream(niFis)) {
            byte[] header = niDis.readNBytes(512);
            if (header.length < 25) {
                throw new IOException("Invalid FS story pack: ni file is too short");
            }
            ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            // Nodes index file format version (1)
            bb.getShort();
            // Story pack version
            version = bb.getShort();
            // Start of actual nodes list in this file (0x200 / 512)
            int nodesList = bb.getInt();
            // Size of a stage node in this file (0x2C / 44)
            int nodeSize = bb.getInt();
            // Number of stage nodes in this file
            int stageNodesCount = bb.getInt();
            // Number of images (in RI file and rf/ folder)
            int imageAssetsCount = bb.getInt();
            // Number of sounds (in SI file and sf/ folder)
            int soundAssetsCount = bb.getInt();
            // Is factory pack (boolean) set to true to avoid pack inspection by official Luniistore application
            factoryDisabled = bb.get() != 0x00;
            if (stageNodesCount <= 0 || nodeSize < 42) {
                throw new IOException("Invalid FS story pack: " + stageNodesCount + " stage nodes of " + nodeSize + " bytes");
            }

            // Read stage nodes
            for (int i=0; i<stageNodesCount; i++) {
                byte[] nodeBytes = niDis.readNBytes(nodeSize);
                if (nodeBytes.length < nodeSize) {
                    throw new IOException("Invalid FS story pack: ni file ends before stage node " + i);
                }
                bb = ByteBuffer.wrap(nodeBytes).order(ByteOrder.LITTLE_ENDIAN);
                int imageAssetIndexInRI = bb.getInt();
                int soundAssetIndexInSI = bb.getInt();
                int okTransitionActionNodeIndexInLI = bb.getInt();
                int okTransitionNumberOfOptions = bb.getInt();
                int okTransitionSelectedOptionIndex = bb.getInt();
                int homeTransitionActionNodeIndexInLI = bb.getInt();
                int homeTransitionNumberOfOptions = bb.getInt();
                int homeTransitionSelectedOptionIndex = bb.getInt();
                boolean wheel = bb.getShort() != 0;
                boolean ok = bb.getShort() != 0;
                boolean home = bb.getShort() != 0;
                boolean pause = bb.getShort() != 0;
                boolean autoplay = bb.getShort() != 0;

                // Transition will be updated later with the actual action nodes
                // A selected option index of -1 is a random option (as in the archive format): the
                // transition exists as soon as it points to an action node, see FsStoryPackWriter.
                Transition okTransition = null;
                if (okTransitionActionNodeIndexInLI != -1 && okTransitionNumberOfOptions != -1) {
                    if (!actionNodesOptionsCount.containsKey(okTransitionActionNodeIndexInLI)) {
                        actionNodesOptionsCount.put(okTransitionActionNodeIndexInLI, okTransitionNumberOfOptions);
                    }
                    okTransition = new Transition(null, (short) okTransitionSelectedOptionIndex);
                    List<Transition> twa = transitionsWithAction.getOrDefault(okTransitionActionNodeIndexInLI, new ArrayList<>());
                    twa.add(okTransition);
                    transitionsWithAction.put(okTransitionActionNodeIndexInLI, twa);
                }
                Transition homeTransition = null;
                if (homeTransitionActionNodeIndexInLI != -1 && homeTransitionNumberOfOptions != -1) {
                    if (!actionNodesOptionsCount.containsKey(homeTransitionActionNodeIndexInLI)) {
                        actionNodesOptionsCount.put(homeTransitionActionNodeIndexInLI, homeTransitionNumberOfOptions);
                    }
                    homeTransition = new Transition(null, (short) homeTransitionSelectedOptionIndex);
                    List<Transition> twa = transitionsWithAction.getOrDefault(homeTransitionActionNodeIndexInLI, new ArrayList<>());
                    twa.add(homeTransition);
                    transitionsWithAction.put(homeTransitionActionNodeIndexInLI, twa);
                }

                // Read Image and audio assets
                ImageAsset image = null;
                if (imageAssetIndexInRI != -1) {
                    // Read image path
                    String path = assetPath(riContent, imageAssetIndexInRI, IMAGE_INDEX_FILENAME, i);
                    // Read image file
                    byte[] rfContent = assetContent(assetFile(packFolder, IMAGE_FOLDER, path), isCleartext, withAssetData);
                    image = new ImageAsset("image/bmp", rfContent, path);
                }
                AudioAsset audio = null;
                if (soundAssetIndexInSI != -1) {
                    // Read audio path
                    String path = assetPath(siContent, soundAssetIndexInSI, SOUND_INDEX_FILENAME, i);
                    // Read audio file
                    byte[] sfContent = assetContent(assetFile(packFolder, SOUND_FOLDER, path), isCleartext, withAssetData);
                    audio = new AudioAsset("audio/mpeg", sfContent, path);
                }

                StageNode stageNode = new StageNode(
                        i == 0 ? uuid : UUID.randomUUID().toString(), // First node should have the same UUID as the story pack FIXME node uuids from metadata file
                        image,
                        audio,
                        okTransition,
                        homeTransition,
                        new ControlSettings(
                                wheel,
                                ok,
                                home,
                                pause,
                                autoplay
                        ),
                        null
                );
                stageNodes.put(i, stageNode);
            }

        }

        // Read action nodes from 'li' file
        ByteBuffer liBb = ByteBuffer.wrap(liContent).order(ByteOrder.LITTLE_ENDIAN);
        for (Map.Entry<Integer, Integer> actionCount: actionNodesOptionsCount.entrySet()) {
            Integer offset = actionCount.getKey();
            Integer count = actionCount.getValue();
            if (offset < 0 || count < 0 || ((long) offset + count) * 4 > liContent.length) {
                throw new IOException("Invalid FS story pack: action node at index " + offset + " with " + count
                        + " option(s) does not fit in the li file (" + liContent.length + " bytes)");
            }
            List<StageNode> options = new ArrayList<>(count);
            liBb.position(offset*4);    // Each entry takes 4 bytes
            for (int i=0; i<count; i++) {
                int stageNodeIndex = liBb.getInt();
                StageNode option = stageNodes.get(stageNodeIndex);
                if (option == null) {
                    throw new IOException("Invalid FS story pack: action node at index " + offset
                            + " references unknown stage node " + stageNodeIndex);
                }
                options.add(option);
            }
            // Update action on transitions referencing this sector
            ActionNode actionNode = new ActionNode(options, null);
            for (Transition transition : transitionsWithAction.get(offset)) {
                // -1 stands for a random option, like in ArchiveStoryPackReader
                if (transition.getOptionIndex() < -1 || transition.getOptionIndex() >= count) {
                    throw new IOException("Invalid FS story pack: a transition selects option " + transition.getOptionIndex()
                            + " of the action node at index " + offset + ", which has " + count + " option(s)");
                }
                transition.setActionNode(actionNode);
            }
        }

        return new StoryPack(uuid, factoryDisabled, version, List.copyOf(stageNodes.values()), null, nightModeAvailable);
    }
    
    public boolean isCleartext(Path inputFolder, boolean fixBrokenCleartext) throws IOException {
        File packFolder = inputFolder.toFile();

        // Assets are cleartext if file '.cleartext' exists
        boolean isCleartext = new File(packFolder, CLEARTEXT_FILENAME).exists();

        if (fixBrokenCleartext) {
            // Fix broken story packs with missing .cleartext file. Same rule as the read path
            // (looksCleartext): an ri shorter than the marker — empty for a pack without images —
            // does not look cleartext, rather than reading past its end.
            if (!isCleartext && looksCleartext(packFolder)) {
                LOGGER.warning("Story pack contains cleartext data but is missing .cleartext file: fixing...");

                // Indicate that files are cleartext
                new File(packFolder, CLEARTEXT_FILENAME).createNewFile();

                isCleartext = true;
            }
        }
        
        return isCleartext;
    }

    /** Whether the ri file starts like a cleartext one. Reads only, never writes. */
    private static boolean looksCleartext(File packFolder) throws IOException {
        File ri = new File(packFolder, IMAGE_INDEX_FILENAME);
        if (!ri.isFile()) {
            return false;
        }
        byte[] riRawContent = Files.readAllBytes(ri.toPath());
        return riRawContent.length >= CLEARTEXT_RI_BEGINNING.length
                && Arrays.equals(riRawContent, 0, CLEARTEXT_RI_BEGINNING.length, CLEARTEXT_RI_BEGINNING, 0, CLEARTEXT_RI_BEGINNING.length);
    }

    /** The relative path stored at {@code index} in an ri / si index file, validated. */
    private static String assetPath(byte[] indexContent, int index, String indexName, int stageNode) throws IOException {
        long start = (long) index * ASSET_INDEX_ENTRY_SIZE;
        if (index < 0 || start + ASSET_INDEX_ENTRY_SIZE > indexContent.length) {
            throw new IOException("Invalid FS story pack: stage node " + stageNode + " references entry " + index
                    + " of the " + indexName + " file, which has " + (indexContent.length / ASSET_INDEX_ENTRY_SIZE) + " entries");
        }
        String path = new String(indexContent, (int) start, ASSET_INDEX_ENTRY_SIZE, StandardCharsets.UTF_8);
        if (!ASSET_PATH_PATTERN.matcher(path).matches()) {
            throw new IOException("Invalid FS story pack: unexpected asset path in the " + indexName + " file: " + path);
        }
        return path;
    }

    /** The asset file a validated ri / si path designates, which must stay inside its folder. */
    private static Path assetFile(File packFolder, String folder, String path) throws IOException {
        Path assetsRoot = new File(packFolder, folder).toPath().toAbsolutePath().normalize();
        Path file = assetsRoot.resolve(path.replace('\\', '/')).normalize();
        if (!file.startsWith(assetsRoot)) {
            throw new IOException("Invalid FS story pack: asset path escapes its folder: " + path);
        }
        return file;
    }

    /**
     * The bytes of an asset file, or {@code null} without reading it when the pack is opened for
     * streaming: the file must exist all the same, as reading it would have required.
     */
    private byte[] assetContent(Path path, boolean isCleartext, boolean withAssetData) throws IOException {
        if (withAssetData) {
            return readCipheredFile(path, isCleartext);
        }
        if (!Files.isRegularFile(path)) {
            throw new NoSuchFileException(path.toString());
        }
        return null;
    }

    /**
     * The cleartext content of a file of the pack, as a stream: the first block (at most 512 bytes)
     * is deciphered when the pack is ciphered, everything after it is read straight from the file.
     * Same bytes as {@link #decipherFirstBlockCommonKey} on the whole file, which only ever changes
     * that first block.
     */
    static InputStream openCleartext(Path path, boolean isCleartext) throws IOException {
        InputStream in = Files.newInputStream(path);
        if (isCleartext) {
            return in;
        }
        try {
            byte[] head = in.readNBytes(512);
            return new SequenceInputStream(new ByteArrayInputStream(decipherFirstBlockCommonKey(head)), in);
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    private byte[] readCipheredFile(Path path, boolean isCleartext) throws IOException {
        byte[] content = Files.readAllBytes(path);
        return isCleartext ? content : decipherFirstBlockCommonKey(content);
    }

    /**
     * Deciphers the whole 32-bit words of the first block (at most 512 bytes); every other byte is
     * copied as is and the length is preserved. The 1 to 3 trailing bytes of a block shorter than
     * 512 bytes that do not fill a word are not ciphered (the driver ciphers {@code len / 4} words
     * too): they used to come out as zeros and are now kept.
     */
    static byte[] decipherFirstBlockCommonKey(byte[] data) {
        byte[] block = Arrays.copyOfRange(data, 0, Math.min(512, data.length));
        int[] dataInt = BytesUtils.toIntArray(block, ByteOrder.LITTLE_ENDIAN);
        int[] decryptedInt = XXTEACipher.btea(dataInt, -(Math.min(128, data.length/4)), BytesUtils.toIntArray(XXTEACipher.COMMON_KEY, ByteOrder.BIG_ENDIAN));
        byte[] decryptedBlock = BytesUtils.toByteArray(decryptedInt, ByteOrder.LITTLE_ENDIAN);
        byte[] result = Arrays.copyOf(data, data.length);
        System.arraycopy(decryptedBlock, 0, result, 0, decryptedBlock.length);
        return result;
    }

}
