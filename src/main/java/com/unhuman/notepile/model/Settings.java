package com.unhuman.notepile.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Application settings stored in the hidden per-storage file `.NotePile.cfg` as JSON inside the chosen storage directory.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Settings {
    // Settings filename stored inside the user-chosen storage directory (hidden file)
    public static final String CONFIG_FILE_NAME = ".NotePile.cfg";
    // Pointer file in the user's home that stores the chosen storage directory (JSON)
    private static final String STORAGE_POINTER_FILENAME = ".NotePileStorage.cfg";

    // Do NOT serialize storageLocation into the per-storage config file; it's stored in the pointer file.
    @JsonIgnore
    private String storageLocation;
    private String dateFormat;
    // Content date sort order setting for listing notes/chapters
    public enum SortOrder { Ascending, Descending }
    private SortOrder contentDateSortOrder;

    public Settings() {
        // Default values
        this.dateFormat = "yyyy-MM-dd";
        this.contentDateSortOrder = SortOrder.Descending;
    }

    public String getStorageLocation() {
        return storageLocation;
    }

    public void setStorageLocation(String storageLocation) {
        this.storageLocation = storageLocation;
    }

    public String getDateFormat() {
        return dateFormat;
    }

    public void setDateFormat(String dateFormat) {
        this.dateFormat = dateFormat;
    }

    public SortOrder getContentDateSortOrder() {
        return contentDateSortOrder == null ? SortOrder.Descending : contentDateSortOrder;
    }

    public void setContentDateSortOrder(SortOrder contentDateSortOrder) {
        this.contentDateSortOrder = contentDateSortOrder;
    }

    @JsonIgnore
    public boolean isValid() {
        return storageLocation != null && !storageLocation.trim().isEmpty();
    }

    @JsonIgnore
    public File getConfigFile() {
        if (storageLocation == null) {
            return null;
        }
        return Paths.get(storageLocation, CONFIG_FILE_NAME).toFile();
    }

    /**
     * Save settings to the per-storage file `.NotePile.cfg` in the storage location
     */
    public void save() throws IOException {
        if (storageLocation == null || storageLocation.trim().isEmpty()) {
            throw new IllegalStateException("Storage location must be set before saving");
        }

        File storageDir = new File(storageLocation);
        if (!storageDir.exists()) {
            boolean created = storageDir.mkdirs();
            if (!created && !storageDir.exists()) {
                throw new IOException("Failed to create storage directory: " + storageLocation);
            }
        }

        File configFile = getConfigFile();
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(configFile, this);
    }

    /**
     * Load settings from the per-storage file `.NotePile.cfg` in the specified directory
     */
    public static Settings load(String storageLocation) throws IOException {
        if (storageLocation == null || storageLocation.trim().isEmpty()) {
            return null;
        }

        File configFile = Paths.get(storageLocation, CONFIG_FILE_NAME).toFile();
        if (!configFile.exists()) {
            return null;
        }

        ObjectMapper mapper = new ObjectMapper();
        Settings s = mapper.readValue(configFile, Settings.class);
        // Ensure storageLocation is tracked in the object (not persisted in per-storage config)
        s.setStorageLocation(storageLocation);
        return s;
    }

    /**
     * Return the pointer file in the user's home directory that stores the chosen storage location.
     * Assumption: "root directory" means the user's home directory (System.getProperty("user.home")).
     */
    public static File getStoragePointerFile() {
        Path home = Paths.get(System.getProperty("user.home"));
        return home.resolve(STORAGE_POINTER_FILENAME).toFile();
    }

    // Small POJO used for the pointer file (allows future extension)
    public static class StoragePointer {
        private String storageLocation;

        public StoragePointer() {}
        public StoragePointer(String storageLocation) { this.storageLocation = storageLocation; }
        public String getStorageLocation() { return storageLocation; }
        public void setStorageLocation(String storageLocation) { this.storageLocation = storageLocation; }
    }

    /**
     * Read storage location from the pointer file in the user's home. Returns null if not set.
     * The pointer file is JSON: { "storageLocation": "C:/path/to/dir" }
     */
    public static String readStorageLocationPointer() throws IOException {
        File pointer = getStoragePointerFile();
        if (!pointer.exists()) {
            return null;
        }
        ObjectMapper mapper = new ObjectMapper();
        StoragePointer sp = mapper.readValue(pointer, StoragePointer.class);
        if (sp == null || sp.getStorageLocation() == null) return null;
        String content = sp.getStorageLocation().trim();
        return content.isEmpty() ? null : content;
    }

    /**
     * Write storage location to the pointer file in the user's home as JSON.
     */
    public static void writeStorageLocationPointer(String storageLocation) throws IOException {
        Path pointer = getStoragePointerFile().toPath();
        Path parent = pointer.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        StoragePointer sp = new StoragePointer(storageLocation == null ? "" : storageLocation);
        mapper.writeValue(pointer.toFile(), sp);
    }

    /**
     * Load settings from the storage pointer (user home). Returns null if no pointer or no settings found.
     */
    public static Settings loadFromStoragePointer() throws IOException {
        String storageLocation = readStorageLocationPointer();
        if (storageLocation == null) {
            return null;
        }
        return load(storageLocation);
    }

    /**
     * Set the storage location and persist the pointer in the user's home. If an existing settings file is present
     * in the storage location, load and return it (so the app can use existing configuration instead of prompting).
     * Otherwise return a new Settings instance with the storageLocation set.
     */
    public static Settings setStorageLocationAndLoadIfExists(String storageLocation) throws IOException {
        if (storageLocation == null || storageLocation.trim().isEmpty()) {
            throw new IllegalArgumentException("storageLocation must not be null or empty");
        }

        // Persist the pointer first so subsequent runs know about this location
        writeStorageLocationPointer(storageLocation);

        // If there's an existing config file at that location, load it
        File configFile = Paths.get(storageLocation, CONFIG_FILE_NAME).toFile();
        if (configFile.exists()) {
            Settings s = load(storageLocation);
            if (s != null) {
                return s;
            }
        }

        // No existing config - return defaults with storageLocation set
        Settings s = new Settings();
        s.setStorageLocation(storageLocation);
        return s;
    }

    /**
     * Try to find existing settings by searching common locations
     */
    public static Settings findExisting() {
        // Could search user home, documents, etc.
        // For now, attempt to read pointer and load; otherwise return null
        try {
            Settings s = loadFromStoragePointer();
            if (s != null) {
                return s;
            }
        } catch (IOException e) {
            // ignore and fall through
        }
        return null;
    }
}
