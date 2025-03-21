package io.th0rgal.oraxen.pack.generation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.api.OraxenItems;
import io.th0rgal.oraxen.api.events.OraxenPackGenerateEvent;
import io.th0rgal.oraxen.config.Message;
import io.th0rgal.oraxen.config.ResourcesManager;
import io.th0rgal.oraxen.config.Settings;
import io.th0rgal.oraxen.font.Font;
import io.th0rgal.oraxen.font.FontManager;
import io.th0rgal.oraxen.font.Glyph;
import io.th0rgal.oraxen.gestures.GestureManager;
import io.th0rgal.oraxen.items.ItemBuilder;
import io.th0rgal.oraxen.items.OraxenMeta;
import io.th0rgal.oraxen.sound.CustomSound;
import io.th0rgal.oraxen.sound.SoundManager;
import io.th0rgal.oraxen.utils.AdventureUtils;
import io.th0rgal.oraxen.utils.Utils;
import io.th0rgal.oraxen.utils.VirtualFile;
import io.th0rgal.oraxen.utils.ZipUtils;
import io.th0rgal.oraxen.utils.customarmor.CustomArmorsTextures;
import io.th0rgal.oraxen.utils.logs.Logs;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ResourcePack {

    private Map<String, Collection<Consumer<File>>> packModifiers;
    private static Map<String, VirtualFile> outputFiles;
    private CustomArmorsTextures customArmorsTextures;
    private File packFolder;
    private File pack;
    JavaPlugin plugin;

    public ResourcePack(final JavaPlugin plugin) {
        this.plugin = plugin;
        clear();
    }

    public void clear() {
        // we use maps to avoid duplicate
        packModifiers = new HashMap<>();
        outputFiles = new HashMap<>();
    }

    public void generate() {
        outputFiles.clear();

        customArmorsTextures = new CustomArmorsTextures((int) Settings.ARMOR_RESOLUTION.getValue());
        packFolder = new File(plugin.getDataFolder(), "pack");
        makeDirsIfNotExists(packFolder);
        makeDirsIfNotExists(new File(packFolder, "assets"));
        pack = new File(packFolder, packFolder.getName() + ".zip");
        File assetsFolder = new File(packFolder, "assets");
        File modelsFolder = new File(packFolder, "models");
        File fontFolder = new File(packFolder, "font");
        File optifineFolder = new File(packFolder, "optifine");
        File langFolder = new File(packFolder, "lang");
        File textureFolder = new File(packFolder, "textures");
        File soundFolder = new File(packFolder, "sounds");

        if (Settings.GENERATE_DEFAULT_ASSETS.toBool())
            extractFolders(!modelsFolder.exists(), !textureFolder.exists(), !langFolder.exists(), !fontFolder.exists(),
                    !soundFolder.exists(), !assetsFolder.exists(), !optifineFolder.exists());
        extractRequired();

        if (!Settings.GENERATE.toBool()) return;

        if (Settings.HIDE_SCOREBOARD_NUMBERS.toBool() && Bukkit.getPluginManager().isPluginEnabled("HappyHUD")) {
            Logs.logError("HappyHUD detected with hide_scoreboard_numbers enabled!");
            Logs.logWarning("Recommend following this guide for compatibility: https://docs.oraxen.com/compatibility/happyhud");
        }

        try {
            Files.deleteIfExists(pack.toPath());
        } catch (IOException e) {
            e.printStackTrace();
        }

        extractInPackIfNotExists(plugin, new File(packFolder, "pack.mcmeta"));
        extractInPackIfNotExists(plugin, new File(packFolder, "pack.png"));

        // Sorting items to keep only one with models (and generate it if needed)
        generatePredicates(extractTexturedItems());
        generateFont();
        if (Settings.GESTURES_ENABLED.toBool()) generateGestureFiles();
        if (Settings.HIDE_SCOREBOARD_NUMBERS.toBool()) generateScoreboardFiles();
        if (Settings.GENERATE_ARMOR_SHADER_FILES.toBool()) CustomArmorsTextures.generateArmorShaderFiles();

        OraxenPlugin.get().getServer().getPluginManager().callEvent(new OraxenPackGenerateEvent());

        for (final Collection<Consumer<File>> packModifiers : packModifiers.values())
            for (Consumer<File> packModifier : packModifiers)
                packModifier.accept(packFolder);
        List<VirtualFile> output = new ArrayList<>(outputFiles.values());

        // zipping resourcepack
        try {
            // Adds all non-directory root files
            getFilesInFolder(packFolder, output, packFolder.getCanonicalPath(), packFolder.getName() + ".zip");

            // needs to be ordered, forEach cannot be used
            File[] files = packFolder.listFiles();
            if (files != null) for (final File folder : files) {
                if (folder.isDirectory() && folder.getName().equalsIgnoreCase("assets"))
                    getAllFiles(folder, output, "");
                else if (folder.isDirectory())
                    getAllFiles(folder, output, "assets/minecraft");
            }

            // Convert the global.json within the lang-folder to all languages
            convertGlobalLang(output);

            if (Settings.GENERATE_CUSTOM_ARMOR_TEXTURES.toBool() && customArmorsTextures.hasCustomArmors()) {
                String armorPath = "assets/minecraft/textures/models/armor";
                output.add(new VirtualFile(armorPath, "leather_layer_1.png", customArmorsTextures.getLayerOne()));
                output.add(new VirtualFile(armorPath, "leather_layer_2.png", customArmorsTextures.getLayerTwo()));
                if (Settings.AUTOMATICALLY_GENERATE_SHADER_COMPATIBLE_ARMOR.toBool())
                    output.addAll(customArmorsTextures.getOptifineFiles());
            }

            Collections.sort(output);
        } catch (IOException e) {
            e.printStackTrace();
        }

        Set<String> malformedTextures = new HashSet<>();
        if (Settings.VERIFY_PACK_FILES.toBool())
            malformedTextures = verifyPackFormatting(output);

        if (Settings.GENERATE_ATLAS_FILE.toBool())
            AtlasGenerator.generateAtlasFile(output, malformedTextures);

        if (Settings.MERGE_DUPLICATE_FONTS.toBool())
            DuplicationHandler.mergeFontFiles(output);
        if (Settings.MERGE_ITEM_MODELS.toBool())
            DuplicationHandler.mergeBaseItemFiles(output);

        List<String> excludedExtensions = Settings.EXCLUDED_FILE_EXTENSIONS.toStringList();
        excludedExtensions.removeIf(f -> f.equals("png") || f.equals("json"));
        if (!excludedExtensions.isEmpty() && !output.isEmpty()) {
            List<VirtualFile> newOutput = new ArrayList<>();
            for (VirtualFile virtual : output)
                for (String extension : excludedExtensions)
                    if (virtual.getPath().endsWith(extension)) newOutput.add(virtual);
            output.removeAll(newOutput);
        }

        generateSound(output);

        ZipUtils.writeZipFile(pack, output);
    }

    private static Set<String> verifyPackFormatting(List<VirtualFile> output) {
        Logs.logInfo("Verifying formatting for textures and models...");
        Set<VirtualFile> textures = new HashSet<>();
        Set<String> texturePaths = new HashSet<>();
        Set<String> mcmeta = new HashSet<>();
        Set<VirtualFile> models = new HashSet<>();
        Set<VirtualFile> malformedTextures = new HashSet<>();
        Set<VirtualFile> malformedModels = new HashSet<>();
        for (VirtualFile virtualFile : output) {
            String path = virtualFile.getPath();
            if (path.matches("assets/.*/models/.*.json")) models.add(virtualFile);
            else if (path.matches("assets/.*/textures/.*.png.mcmeta")) mcmeta.add(path);
            else if (path.matches("assets/.*/textures/.*.png")) {
                textures.add(virtualFile);
                texturePaths.add(path);
            }
        }

        if (models.isEmpty() && !textures.isEmpty()) return Collections.emptySet();

        for (VirtualFile model : models) {
            if (model.getPath().contains(" ") || !model.getPath().toLowerCase().equals(model.getPath())) {
                Logs.logWarning("Found invalid model at <blue>" + model.getPath());
                Logs.logError("Models cannot contain spaces or Capital Letters in the filepath or filename");
                malformedModels.add(model);
            }

            String content;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            InputStream inputStream = model.getInputStream();
            try {
                inputStream.transferTo(baos);
                content = baos.toString(StandardCharsets.UTF_8);
                baos.close();
                inputStream.reset();
                inputStream.close();
            } catch (IOException e) {
                content = "";
            }

            if (!content.isEmpty()) {
                JsonObject jsonModel;
                try {
                    jsonModel = JsonParser.parseString(content).getAsJsonObject();
                } catch (JsonSyntaxException e) {
                    Logs.logError("Found malformed json at <red>" + model.getPath() + "</red>");
                    e.printStackTrace();
                    continue;
                }
                if (jsonModel.has("textures")) {
                    for (JsonElement element : jsonModel.getAsJsonObject("textures").entrySet().stream().map(Map.Entry::getValue).toList()) {
                        String jsonTexture = element.getAsString();
                        if (!texturePaths.contains(modelPathToPackPath(jsonTexture))) {
                            if (!jsonTexture.startsWith("#") && !jsonTexture.startsWith("item/") && !jsonTexture.startsWith("block/") && !jsonTexture.startsWith("entity/")) {
                                if (Material.matchMaterial(Utils.getFileNameOnly(jsonTexture).toUpperCase()) == null) {
                                    Logs.logWarning("Found invalid texture-path inside model-file <blue>" + model.getPath() + "</blue>: " + jsonTexture);
                                    Logs.logWarning("Verify that you have a texture in said path.");
                                    Logs.newline();
                                    malformedModels.add(model);
                                }
                            }
                        }
                    }
                }
            }
        }

        for (VirtualFile texture : textures) {
            if (texture.getPath().contains(" ") || !texture.getPath().toLowerCase().equals(texture.getPath())) {

                Logs.logWarning("Found invalid texture at <blue>" + texture.getPath());
                Logs.logError("Textures cannot contain spaces or Capital Letters in the filepath or filename");
                malformedTextures.add(texture);
            }
            if (!texture.getPath().matches(".*_layer_.*.png")) {
                if (mcmeta.contains(texture.getPath() + ".mcmeta")) continue;
                BufferedImage image;
                InputStream inputStream = texture.getInputStream();
                try {
                    image = ImageIO.read(new File("fake_file.png"));
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    inputStream.transferTo(baos);
                    ImageIO.write(image, "png", baos);
                    baos.close();
                    inputStream.reset();
                    inputStream.close();
                } catch (IOException e) {
                    continue;
                }

                if (image.getHeight() > 256 || image.getWidth() > 256) {
                    Logs.logWarning("Found invalid texture at <blue>" + texture.getPath());
                    Logs.logError("Resolution of textures cannot exceed 256x256");
                    malformedTextures.add(texture);
                }
            }
        }

        Logs.newline();
        if (!malformedTextures.isEmpty() || !malformedModels.isEmpty()) {
            Logs.logError("Pack contains malformed texture(s) and/or model(s)");
            Logs.logError("These need to be fixed, otherwise the resourcepack will be broken");
        } else Logs.logSuccess("No broken models or textures were found in the resourcepack");
        Logs.newline();

        Set<String> malformedFiles = malformedTextures.stream().map(VirtualFile::getPath).collect(Collectors.toSet());
        malformedFiles.addAll(malformedModels.stream().map(VirtualFile::getPath).collect(Collectors.toSet()));
        return malformedFiles;
    }

    private static String modelPathToPackPath(String modelPath) {
        String namespace = modelPath.split(":").length == 1 ? "minecraft" : modelPath.split(":")[0];
        String texturePath = modelPath.split(":").length == 1 ? modelPath : modelPath.split(":")[1];
        texturePath = texturePath.endsWith(".png") ? texturePath : texturePath + ".png";
        return "assets/" + namespace + "/textures/" + texturePath;
    }

    private void extractFolders(boolean extractModels, boolean extractTextures,
                                boolean extractLang, boolean extractFonts, boolean extractSounds, boolean extractAssets, boolean extractOptifine) {
        if (!extractModels && !extractTextures && !extractLang && !extractAssets && !extractOptifine && !extractFonts && !extractSounds)
            return;

        final ZipInputStream zip = ResourcesManager.browse();
        try {
            ZipEntry entry = zip.getNextEntry();
            final ResourcesManager resourcesManager = new ResourcesManager(OraxenPlugin.get());
            while (entry != null) {
                extract(entry, extractModels, extractTextures,
                        extractLang, extractFonts, extractSounds, extractAssets, extractOptifine, resourcesManager);
                entry = zip.getNextEntry();
            }
            zip.closeEntry();
            zip.close();
        } catch (final IOException ex) {
            ex.printStackTrace();
        }
    }

    private void extractRequired() {
        final ZipInputStream zip = ResourcesManager.browse();
        try {
            ZipEntry entry = zip.getNextEntry();
            final ResourcesManager resourcesManager = new ResourcesManager(OraxenPlugin.get());
            while (entry != null) {
                if (entry.getName().startsWith("pack/textures/models/armor/leather_layer_") || entry.getName().startsWith("pack/textures/required") || entry.getName().startsWith("pack/models/required")) {
                    resourcesManager.extractFileIfTrue(entry, !OraxenPlugin.get().getDataFolder().toPath().resolve(entry.getName()).toFile().exists());
                }
                entry = zip.getNextEntry();
            }
            zip.closeEntry();
            zip.close();
        } catch (final IOException ex) {
            ex.printStackTrace();
        }
    }

    private void extract(ZipEntry entry, boolean extractModels, boolean extractTextures,
                         boolean extractLang, boolean extractFonts,
                         boolean extractSounds, boolean extractAssets,
                         boolean extractOptifine, ResourcesManager resourcesManager) {
        final String name = entry.getName();
        final boolean isSuitable = (extractModels && name.startsWith("pack/models"))
                || (extractTextures && name.startsWith("pack/textures"))
                || (extractLang && name.startsWith("pack/lang"))
                || (extractFonts && name.startsWith("pack/font"))
                || (extractSounds && name.startsWith("pack/sounds"))
                || (extractAssets && name.startsWith("/pack/assets"))
                || (extractOptifine && name.startsWith("pack/optifine"));
        resourcesManager.extractFileIfTrue(entry, isSuitable);
    }

    private Map<Material, List<ItemBuilder>> extractTexturedItems() {
        final Map<Material, List<ItemBuilder>> texturedItems = new HashMap<>();
        for (final Map.Entry<String, ItemBuilder> entry : OraxenItems.getEntries()) {
            final ItemBuilder item = entry.getValue();
            if (item.getOraxenMeta().hasPackInfos()) {
                OraxenMeta oraxenMeta = item.getOraxenMeta();
                if (oraxenMeta.shouldGenerateModel()) {
                    writeStringToVirtual(oraxenMeta.getModelPath(),
                            item.getOraxenMeta().getModelName() + ".json",
                            new ModelGenerator(oraxenMeta).getJson().toString());
                }
                final List<ItemBuilder> items = texturedItems.getOrDefault(item.build().getType(), new ArrayList<>());
                // todo: could be improved by using
                // items.get(i).getOraxenMeta().getCustomModelData() when
                // items.add(customModelData, item) with catch when not possible
                if (items.isEmpty())
                    items.add(item);
                else
                    // for some reason those breaks are needed to avoid some nasty "memory leak"
                    for (int i = 0; i < items.size(); i++) {
                        if (items.get(i).getOraxenMeta().getCustomModelData() > item.getOraxenMeta().getCustomModelData()) {
                            items.add(i, item);
                            break;
                        } else if (i == items.size() - 1) {
                            items.add(item);
                            break;
                        }
                    }
                texturedItems.put(item.build().getType(), items);
            }
        }
        return texturedItems;
    }

    @SafeVarargs
    public final void addModifiers(String groupName, final Consumer<File>... modifiers) {
        packModifiers.put(groupName, Arrays.asList(modifiers));
    }

    public static void addOutputFiles(final VirtualFile... files) {
        for (VirtualFile file : files)
            outputFiles.put(file.getPath(), file);
    }

    public File getFile() {
        return pack;
    }

    private void extractInPackIfNotExists(final JavaPlugin plugin, final File file) {
        if (!file.exists()) plugin.saveResource("pack/" + file.getName(), true);
    }

    private void makeDirsIfNotExists(final File folder) {
        if (!folder.exists()) folder.mkdirs();
    }

    private void generatePredicates(final Map<Material, List<ItemBuilder>> texturedItems) {
        for (final Map.Entry<Material, List<ItemBuilder>> texturedItemsEntry : texturedItems.entrySet()) {
            final Material entryMaterial = texturedItemsEntry.getKey();
            final PredicatesGenerator predicatesGenerator = new PredicatesGenerator(entryMaterial,
                    texturedItemsEntry.getValue());
            final String[] vanillaModelPath =
                    (predicatesGenerator.getVanillaModelName(entryMaterial) + ".json").split("/");
            writeStringToVirtual("assets/minecraft/models/" + vanillaModelPath[0], vanillaModelPath[1],
                    predicatesGenerator.toJSON().toString());
        }
    }

    private void generateFont() {
        FontManager fontManager = OraxenPlugin.get().getFontManager();
        if (!fontManager.autoGenerate) return;
        final JsonObject output = new JsonObject();
        final JsonArray providers = new JsonArray();
        for (final Glyph glyph : fontManager.getGlyphs()) {
            if (!glyph.hasBitmap()) providers.add(glyph.toJson());
        }
        for (FontManager.GlyphBitMap glyphBitMap : FontManager.glyphBitMaps.values()) {
            providers.add(glyphBitMap.toJson(fontManager));
        }
        for (final Font font : fontManager.getFonts()) {
            providers.add(font.toJson());
        }
        output.add("providers", providers);
        writeStringToVirtual("assets/minecraft/font", "default.json", output.toString());
    }

    private void generateSound(List<VirtualFile> output) {
        SoundManager soundManager = OraxenPlugin.get().getSoundManager();
        if (!soundManager.isAutoGenerate()) return;

        List<VirtualFile> soundFiles = output.stream().filter(file -> file.getPath().equals("assets/minecraft/sounds.json")).toList();
        JsonObject outputJson = new JsonObject();

        // If file was imported by other means, we attempt to merge in sound.yml entries
        for (VirtualFile soundFile : soundFiles) {
            if (soundFile != null) {
                try {
                    JsonElement soundElement = JsonParser.parseString(IOUtils.toString(soundFile.getInputStream(), StandardCharsets.UTF_8));
                    if (soundElement != null && soundElement.isJsonObject()) {
                        for (Map.Entry<String, JsonElement> entry : soundElement.getAsJsonObject().entrySet())
                            outputJson.add(entry.getKey(), entry.getValue());
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    continue;
                }
            }
            output.remove(soundFile);
        }

        for (CustomSound sound : handleCustomSoundEntries(soundManager.getCustomSounds())) {
            outputJson.add(sound.getName(), sound.toJson());
        }

        InputStream soundInput = new ByteArrayInputStream(outputJson.toString().getBytes(StandardCharsets.UTF_8));
        output.add(new VirtualFile("assets/minecraft", "sounds.json", soundInput));
        try {
            soundInput.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void generateGestureFiles() {
        GestureManager gestureManager = OraxenPlugin.get().getGesturesManager();
        for (Map.Entry<String, String> entry : gestureManager.getPlayerHeadJsons().entrySet())
            writeStringToVirtual(StringUtils.removeEnd(Utils.getParentDirs(entry.getKey()), "/"), Utils.removeParentDirs(entry.getKey()), entry.getValue());
        writeStringToVirtual("assets/minecraft/models/item", "player_head.json", gestureManager.getSkullJson());
        writeStringToVirtual("assets/minecraft/shaders/core", "rendertype_entity_translucent.vsh", gestureManager.getShaderVsh());
        writeStringToVirtual("assets/minecraft/shaders/core", "rendertype_entity_translucent.fsh", gestureManager.getShaderFsh());
        writeStringToVirtual("assets/minecraft/shaders/core", "rendertype_entity_translucent.json", gestureManager.getShaderJson());
    }

    private Collection<CustomSound> handleCustomSoundEntries(Collection<CustomSound> sounds) {
        ConfigurationSection mechanic = OraxenPlugin.get().getConfigsManager().getMechanics();
        ConfigurationSection customSounds = mechanic.getConfigurationSection("custom_block_sounds");
        ConfigurationSection noteblock = mechanic.getConfigurationSection("noteblock");
        ConfigurationSection stringblock = mechanic.getConfigurationSection("stringblock");
        ConfigurationSection furniture = mechanic.getConfigurationSection("furniture");
        ConfigurationSection block = mechanic.getConfigurationSection("block");

        if (customSounds == null) {
            sounds.removeIf(s -> s.getName().startsWith("required.wood") || s.getName().startsWith("block.wood"));
            sounds.removeIf(s -> s.getName().startsWith("required.stone") || s.getName().startsWith("block.stone"));
        } else {
            if (!customSounds.getBoolean("noteblock_and_block", true)) {
                sounds.removeIf(s -> s.getName().startsWith("required.wood") || s.getName().startsWith("block.wood"));
            }
            if (!customSounds.getBoolean("stringblock_and_furniture", true)) {
                sounds.removeIf(s -> s.getName().startsWith("required.stone") || s.getName().startsWith("block.stone"));
            }
            if ((noteblock != null && !noteblock.getBoolean("enabled", true) && block != null && !block.getBoolean("enabled", false))) {
                sounds.removeIf(s -> s.getName().startsWith("required.wood") || s.getName().startsWith("block.wood"));
            }
            if (stringblock != null && !stringblock.getBoolean("enabled", true) && furniture != null && !furniture.getBoolean("enabled", true)) {
                sounds.removeIf(s -> s.getName().startsWith("required.stone") || s.getName().startsWith("block.stone"));
            }
        }

        // Clear the sounds.json file of yaml configuration entries that should not be there
        sounds.removeIf(s ->
                s.getName().equals("required") ||
                        s.getName().equals("block") ||
                        s.getName().equals("block.wood") ||
                        s.getName().equals("block.stone") ||
                        s.getName().equals("required.wood") ||
                        s.getName().equals("required.stone")
        );

        return sounds;
    }

    public static void writeStringToVirtual(String folder, String name, String content) {
        folder = !folder.endsWith("/") ? folder : folder.substring(0, folder.length() - 1);
        addOutputFiles(new VirtualFile(folder, name, new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))));
    }

    private void getAllFiles(File dir, Collection<VirtualFile> fileList, String newFolder, String... excluded) {
        final File[] files = dir.listFiles();
        final List<String> blacklist = Arrays.asList(excluded);
        if (files != null) for (final File file : files) {
            if (file.isDirectory()) getAllFiles(file, fileList, newFolder, excluded);
            else if (!file.isDirectory() && !blacklist.contains(file.getName()))
                readFileToVirtuals(fileList, file, newFolder);
        }
    }

    private void getFilesInFolder(File dir, Collection<VirtualFile> fileList, String newFolder, String... excluded) {
        final File[] files = dir.listFiles();
        if (files != null) for (final File file : files)
            if (!file.isDirectory() && !Arrays.asList(excluded).contains(file.getName()))
                readFileToVirtuals(fileList, file, newFolder);
    }

    private void readFileToVirtuals(final Collection<VirtualFile> output, File file, String newFolder) {
        try {
            final InputStream fis;
            if (file.getName().endsWith(".json")) fis = processJsonFile(file);
            else if (customArmorsTextures.registerImage(file)) return;
            else fis = new FileInputStream(file);

            output.add(new VirtualFile(getZipFilePath(file.getParentFile().getCanonicalPath(), newFolder), file.getName(), fis));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private InputStream processJsonFile(File file) throws IOException {
        InputStream newStream;
        String content;
        if (!file.exists())
            return new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8));
        try {
            content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException | NullPointerException e) {
            Logs.logError("Error while reading file " + file.getPath());
            Logs.logError("It seems to be malformed!");
            newStream = new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8));
            newStream.close();
            return newStream;
        }

        // If the json file is a font file, do not format it through MiniMessage
        if (file.getPath().replace("\\", "/").split("assets/.*/font/").length > 1) {
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        return processJson(content);
    }

    private InputStream processJson(String content) {
        InputStream newStream;
        // Deserialize said component to a string to handle other tags like glyphs
        String parsedContent = AdventureUtils.parseMiniMessage(AdventureUtils.parseLegacy(content), AdventureUtils.tagResolver("prefix", Message.PREFIX.toString()));
        // Deserialize adventure component to legacy format due to resourcepacks not supporting adventure components
        parsedContent = AdventureUtils.parseLegacyThroughMiniMessage(content);
        newStream = new ByteArrayInputStream(parsedContent.getBytes(StandardCharsets.UTF_8));
        try {
            newStream.close();
        } catch (IOException e) {
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }
        return newStream;
    }

    private String getZipFilePath(String path, String newFolder) throws IOException {
        // we want the zipEntry's path to be a relative path that is relative
        // to the directory being zipped, so chop off the rest of the path
        if (newFolder.equals(packFolder.getCanonicalPath())) return "";
        String prefix = newFolder.isEmpty() ? newFolder : newFolder + "/";
        return prefix + path.substring(packFolder.getCanonicalPath().length() + 1);
    }

    private void convertGlobalLang(List<VirtualFile> output) {
        Logs.logWarning("Converting global lang file to individual language files...");
        Set<VirtualFile> virtualLangFiles = new HashSet<>();
        File globalLangFile = new File(packFolder, "lang/global.json");
        JsonObject globalLang = new JsonObject();
        String content = "";
        if (!globalLangFile.exists()) plugin.saveResource("pack/lang/global.json", false);

        try {
            content = Files.readString(globalLangFile.toPath(), StandardCharsets.UTF_8);
            globalLang = JsonParser.parseString(content).getAsJsonObject();
        } catch (IOException | IllegalStateException | IllegalArgumentException ignored) {
        }

        if (content.isEmpty() || globalLang.isJsonNull()) return;

        for (String lang : availableLanguageCodes) {
            File langFile = new File(packFolder, "lang/" + lang + ".json");
            JsonObject langJson = new JsonObject();

            // If the file is in the pack, we want to keep the existing entries over global ones
            if (langFile.exists()) {
                try {
                    langJson = JsonParser.parseString(Files.readString(langFile.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
                } catch (IOException | IllegalStateException ignored) {
                }
            }

            for (Map.Entry<String, JsonElement> entry : globalLang.entrySet()) {
                if (entry.getKey().equals("DO_NOT_ALTER_THIS_LINE")) continue;
                // If the entry already exists in the lang file, we don't want to overwrite it
                if (langJson.has(entry.getKey())) continue;
                langJson.add(entry.getKey(), entry.getValue());
            }

            InputStream langStream = processJson(langJson.toString());
            virtualLangFiles.add(new VirtualFile("assets/minecraft/lang", lang + ".json", langStream));
        }
        // Remove previous langfiles as these have been migrated in above
        output.removeIf(virtualFile -> virtualLangFiles.stream().anyMatch(v -> v.getPath().equals(virtualFile.getPath())));
        output.addAll(virtualLangFiles);
    }

    private static final Set<String> availableLanguageCodes = new HashSet<>(Arrays.asList(
            "af_za", "ar_sa", "ast_es", "az_az", "ba_ru",
            "bar", "be_by", "bg_bg", "br_fr", "brb", "bs_ba", "ca_es", "cs_cz",
            "cy_gb", "da_dk", "de_at", "de_ch", "de_de", "el_gr", "en_au", "en_ca",
            "en_gb", "en_nz", "en_pt", "en_ud", "en_us", "enp", "enws", "eo_uy",
            "es_ar", "es_cl", "es_ec", "es_es", "es_mx", "es_uy", "es_ve", "esan",
            "et_ee", "eu_es", "fa_ir", "fi_fi", "fil_ph", "fo_fo", "fr_ca", "fr_fr",
            "fra_de", "fur_it", "fy_nl", "ga_ie", "gd_gb", "gl_es", "haw_us", "he_il",
            "hi_in", "hr_hr", "hu_hu", "hy_am", "id_id", "ig_ng", "io_en", "is_is",
            "isv", "it_it", "ja_jp", "jbo_en", "ka_ge", "kk_kz", "kn_in", "ko_kr",
            "ksh", "kw_gb", "la_la", "lb_lu", "li_li", "lmo", "lol_us", "lt_lt",
            "lv_lv", "lzh", "mk_mk", "mn_mn", "ms_my", "mt_mt", "nah", "nds_de",
            "nl_be", "nl_nl", "nn_no", "no_no", "oc_fr", "ovd", "pl_pl", "pt_br",
            "pt_pt", "qya_aa", "ro_ro", "rpr", "ru_ru", "ry_ua", "se_no", "sk_sk",
            "sl_si", "so_so", "sq_al", "sr_sp", "sv_se", "sxu", "szl", "ta_in",
            "th_th", "tl_ph", "tlh_aa", "tok", "tr_tr", "tt_ru", "uk_ua", "val_es",
            "vec_it", "vi_vn", "yi_de", "yo_ng", "zh_cn", "zh_hk", "zh_tw", "zlm_arab"));

    private void generateScoreboardFiles() {
        Map<String, String> scoreboardShaderFiles = Map.of("assets/minecraft/shaders/core/rendertype_text.json", getScoreboardJson(), "assets/minecraft/shaders/core/rendertype_text.vsh", getScoreboardVsh());
        for (Map.Entry<String, String> entry : scoreboardShaderFiles.entrySet())
            writeStringToVirtual(StringUtils.removeEnd(Utils.getParentDirs(entry.getKey()), "/"), Utils.removeParentDirs(entry.getKey()), entry.getValue());
    }

    private String getScoreboardVsh() {
        return """
                #version 150
                 
                 #moj_import <fog.glsl>
                 
                 in vec3 Position;
                 in vec4 Color;
                 in vec2 UV0;
                 in ivec2 UV2;
                 
                 uniform sampler2D Sampler2;
                 
                 uniform mat4 ModelViewMat;
                 uniform mat4 ProjMat;
                 uniform mat3 IViewRotMat;
                 uniform int FogShape;
                 
                 uniform vec2 ScreenSize;
                 
                 out float vertexDistance;
                 out vec4 vertexColor;
                 out vec2 texCoord0;
                 
                 void main() {
                     gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
                 
                     vertexDistance = fog_distance(ModelViewMat, IViewRotMat * Position, FogShape);
                     vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
                     texCoord0 = UV0;
                    
                 	// delete sidebar numbers
                 	if(	Position.z == 0.0 && // check if the depth is correct (0 for gui texts)
                 			gl_Position.x >= 0.94 && gl_Position.y >= -0.35 && // check if the position matches the sidebar
                 			vertexColor.g == 84.0/255.0 && vertexColor.g == 84.0/255.0 && vertexColor.r == 252.0/255.0 && // check if the color is the sidebar red color
                 			gl_VertexID <= 7 // check if it's the first character of a string !! if you want two characters removed replace '3' with '7'
                 		) gl_Position = ProjMat * ModelViewMat * vec4(ScreenSize + 100.0, 0.0, 0.0); // move the vertices offscreen, idk if this is a good solution for that but vec4(0.0) doesnt do the trick for everyone
                 }
                """;
    }

    private String getScoreboardJson() {
        return """
                {
                     "blend": {
                         "func": "add",
                         "srcrgb": "srcalpha",
                         "dstrgb": "1-srcalpha"
                     },
                     "vertex": "rendertype_text",
                     "fragment": "rendertype_text",
                     "attributes": [
                         "Position",
                         "Color",
                         "UV0",
                         "UV2"
                     ],
                     "samplers": [
                         { "name": "Sampler0" },
                         { "name": "Sampler2" }
                     ],
                     "uniforms": [
                         { "name": "ModelViewMat", "type": "matrix4x4", "count": 16, "values": [ 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0 ] },
                         { "name": "ProjMat", "type": "matrix4x4", "count": 16, "values": [ 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0 ] },
                         { "name": "ColorModulator", "type": "float", "count": 4, "values": [ 1.0, 1.0, 1.0, 1.0 ] },
                         { "name": "FogStart", "type": "float", "count": 1, "values": [ 0.0 ] },
                         { "name": "FogEnd", "type": "float", "count": 1, "values": [ 1.0 ] },
                         { "name": "FogColor", "type": "float", "count": 4, "values": [ 0.0, 0.0, 0.0, 0.0 ] },
                        { "name": "ScreenSize", "type": "float", "count": 2,  "values": [ 1.0, 1.0 ] }
                     ]
                 }
                """;
    }
}
