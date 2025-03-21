package io.th0rgal.oraxen.utils;

import io.th0rgal.oraxen.utils.logs.Logs;
import org.apache.commons.lang3.Validate;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

public enum VersionUtil implements Comparable<VersionUtil> {
    v1_20_R2(6),
    v1_20_R1(5),
    v1_19_R3(4),
    v1_19_R2(3),
    v1_19_R1(2),
    v1_18_R2(1),
    v1_18_R1(0),
    UNKNOWN(-1);

    public static VersionUtil fromString(String version) {
        try {
            String versionNumber = (version.charAt(0) == 'v' ? "" : "v") + version.replace(".", "_").trim();
            if (!version.contains("_R")) {
                char lastChar = versionNumber.charAt(versionNumber.length() - 1);
                versionNumber = versionNumber.substring(0, versionNumber.length() - 1) + "R" + lastChar;
            }
            return valueOf(versionNumber);
        } catch (final IllegalArgumentException e) {
            return VersionUtil.UNKNOWN;
        }
    }

    private final int value;
    private static final boolean leaked = JarReader.checkIsLeaked();

    VersionUtil(int value) {
        this.value = value;
    }

    /**
     * @return the version of the server
     * @throws IllegalArgumentException if server is null
     */
    @NotNull
    public static VersionUtil getServerVersion() {
        return fromString(Bukkit.getBukkitVersion().split("-")[0]);
    }

    /**
     * @return true if the server is Paper or false of not
     * @throws IllegalArgumentException if server is null
     */
    public static boolean isPaperServer() {
        Server server = Bukkit.getServer();
        Validate.notNull(server, "Server cannot be null");
        if (server.getName().equalsIgnoreCase("Paper")) return true;

        try {
            Class.forName("com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static boolean isFoliaServer() {
        Server server = Bukkit.getServer();
        Validate.notNull(server, "Server cannot be null");

        return server.getName().equalsIgnoreCase("Folia");
    }

    public static boolean isSupportedVersionOrNewer(VersionUtil serverVersion) {
        return VersionUtil.getServerVersion().value >= serverVersion.value;
    }

    public static boolean isSupportedVersionOrNewer(String serverVersion) {
        return VersionUtil.getServerVersion().value >= VersionUtil.fromString(serverVersion).value;
    }

    public static boolean isSupportedVersion(@NotNull VersionUtil serverVersion, @NotNull VersionUtil... supportedVersions) {
        for (VersionUtil version : supportedVersions) {
            if (version == serverVersion) {
                return true;
            }
        }

        Logs.logWarning(String.format("The Server version which you are running is unsupported, you are running version '%s'.", serverVersion));
        Logs.logWarning(String.format("The plugin supports following versions %s.", combineVersions(supportedVersions)));

        if (serverVersion == VersionUtil.UNKNOWN) {
            Logs.logWarning(String.format("The Version '%s' can indicate, that you are using a newer Minecraft version than currently supported.", serverVersion));
            Logs.logWarning("In this case please update to the newest version of this plugin. If this is the newest Version, than please be patient. It can take a few weeks until the plugin is updated.");
        }

        Logs.logWarning("No compatible Server version found!");

        return false;
    }

    @NotNull
    private static String combineVersions(@NotNull VersionUtil... versions) {
        StringBuilder stringBuilder = new StringBuilder();

        boolean first = true;

        for (VersionUtil version : versions) {
            if (first) {
                first = false;
            } else {
                stringBuilder.append(" ");
            }

            stringBuilder.append("'");
            stringBuilder.append(version);
            stringBuilder.append("'");
        }

        return stringBuilder.toString();
    }

    /**
     * Checks if the version is newer than the given version
     * <p>
     * If both versions are the same, the method will return false
     *
     * @param version to check against
     * @return true if the version is newer than the given one, otherwise false
     * @throws IllegalArgumentException if version is null
     * @throws IllegalArgumentException if this version or the given version, is the version UNKNOWN
     */
    public boolean isNewerThan(@NotNull VersionUtil version) {
        Validate.notNull(version, "Version cannot be null");
        Validate.isTrue(this != UNKNOWN, "Cannot check, if version UNKNOWN is newer");
        Validate.isTrue(version != UNKNOWN, "Cannot check, if version UNKNOWN is newer");

        return value > version.value;
    }

    /**
     * Checks if the version is newer or the same than the given version
     *
     * @param version to check against
     * @return true if the version is newer or the same than the given one, otherwise false
     * @throws IllegalArgumentException if version is null
     * @throws IllegalArgumentException if this version or the given version, is the version UNKNOWN
     */
    public boolean isNewerOrSameThan(@NotNull VersionUtil version) {
        Validate.notNull(version, "Version cannot be null");
        Validate.isTrue(this != UNKNOWN, "Cannot check, if version UNKNOWN is newer or same");
        Validate.isTrue(version != UNKNOWN, "Cannot check, if version UNKNOWN is newer or same");

        return value >= version.value;
    }

    /**
     * Checks if the version is older than the given version
     *
     * @param version to check against
     * @return true if the version is older than the given one, otherwise false
     * @throws IllegalArgumentException if version is null
     * @throws IllegalArgumentException if this version or the given version, is the version UNKNOWN
     */
    public boolean isOlderThan(@NotNull VersionUtil version) {
        Validate.notNull(version, "Version cannot be null");
        Validate.isTrue(this != UNKNOWN, "Cannot check, if version UNKNOWN is older");
        Validate.isTrue(version != UNKNOWN, "Cannot check, if version UNKNOWN is older");

        return value < version.value;
    }

    /**
     * Checks if the version is older or the same than the given version
     *
     * @param version to check against
     * @return true if the version is older or the same than the given one, otherwise false
     * @throws IllegalArgumentException if version is null
     * @throws IllegalArgumentException if this version or the given version, is the version UNKNOWN
     */
    public boolean isOlderOrSameThan(@NotNull VersionUtil version) {
        Validate.notNull(version, "Version cannot be null");
        Validate.isTrue(this != UNKNOWN, "Cannot check, if version UNKNOWN is older or same");
        Validate.isTrue(version != UNKNOWN, "Cannot check, if version UNKNOWN is older or same");

        return value <= version.value;
    }

    private final static String manifest = JarReader.getManifestContent();

    public static boolean isCompiled() {
        List<String> split = Arrays.stream(manifest.split(":|\n")).map(String::trim).toList();
        return Boolean.parseBoolean(split.get(split.indexOf("Compiled") + 1)) && !isValidCompiler();
    }

    public static boolean isLeaked() {
        return leaked;
    }

    public static boolean isValidCompiler() {
        List<String> split = Arrays.stream(manifest.split(":|\n")).map(String::trim).toList();
        return Set.of("sivert", "thomas").contains(split.get(split.indexOf("Built-By") + 1).toLowerCase());
    }
}
