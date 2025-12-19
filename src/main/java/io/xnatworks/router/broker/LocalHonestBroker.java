/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.broker;

import io.xnatworks.router.config.AppConfig.HonestBrokerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Local Honest Broker implementation with configurable naming schemes.
 *
 * <p>Provides de-identification of patient identifiers using various naming
 * schemes. All mappings are stored persistently in the CrosswalkStore for
 * audit and consistency.</p>
 *
 * <p>Supported naming schemes:
 * <ul>
 *   <li>adjective_animal - Docker-style names like "happy-panda"</li>
 *   <li>color_animal - Names like "blue-falcon"</li>
 *   <li>nato_phonetic - NATO alphabet like "alpha-7"</li>
 *   <li>sequential - Simple sequential numbers like "SUBJ-00001"</li>
 *   <li>hash - Short hash-based IDs like "HB-A7F3"</li>
 *   <li>script - Custom JavaScript function for full control over ID generation</li>
 * </ul>
 * </p>
 */
public class LocalHonestBroker {
    private static final Logger log = LoggerFactory.getLogger(LocalHonestBroker.class);

    private final CrosswalkStore crosswalkStore;
    private final String brokerName;
    private final HonestBrokerConfig config;

    // Word lists for naming schemes
    private static final List<String> ADJECTIVES = Arrays.asList(
            "admiring", "adoring", "agitated", "amazing", "angry", "awesome",
            "bold", "boring", "brave", "busy", "calm", "charming", "clever",
            "cool", "cranky", "crazy", "dazzling", "determined", "distracted",
            "dreamy", "eager", "ecstatic", "elastic", "elated", "elegant",
            "eloquent", "epic", "exciting", "fervent", "festive", "flamboyant",
            "focused", "friendly", "frosty", "funny", "gallant", "gifted",
            "goofy", "gracious", "great", "happy", "hardcore", "heuristic",
            "hopeful", "hungry", "infallible", "inspiring", "intelligent",
            "interesting", "jolly", "jovial", "keen", "kind", "laughing",
            "loving", "lucid", "magical", "modest", "musing", "mystifying",
            "naughty", "nervous", "nice", "nifty", "nostalgic", "objective",
            "optimistic", "peaceful", "pedantic", "pensive", "practical",
            "priceless", "quirky", "quizzical", "recursing", "relaxed",
            "reverent", "romantic", "sad", "serene", "sharp", "silly",
            "sleepy", "stoic", "strange", "stupefied", "suspicious", "sweet",
            "tender", "thirsty", "trusting", "unruffled", "upbeat", "vibrant",
            "vigilant", "vigorous", "wizardly", "wonderful", "xenodochial",
            "youthful", "zealous", "zen"
    );

    private static final List<String> ANIMALS = Arrays.asList(
            "albatross", "alligator", "alpaca", "ant", "anteater", "antelope",
            "ape", "armadillo", "baboon", "badger", "barracuda", "bat", "bear",
            "beaver", "bee", "bison", "boar", "buffalo", "butterfly", "camel",
            "capybara", "caribou", "cat", "caterpillar", "cattle", "chameleon",
            "cheetah", "chicken", "chimpanzee", "chinchilla", "clam", "cobra",
            "condor", "cormorant", "coyote", "crab", "crane", "crocodile",
            "crow", "deer", "dingo", "dog", "dolphin", "donkey", "dove",
            "dragonfly", "duck", "eagle", "echidna", "eel", "elephant", "elk",
            "emu", "falcon", "ferret", "finch", "fish", "flamingo", "fox",
            "frog", "gazelle", "gerbil", "giraffe", "gnu", "goat", "goose",
            "gopher", "gorilla", "grasshopper", "grouse", "gull", "hamster",
            "hare", "hawk", "hedgehog", "heron", "hippo", "hornet", "horse",
            "hound", "hummingbird", "hyena", "ibex", "iguana", "impala",
            "jackal", "jaguar", "jay", "jellyfish", "kangaroo", "koala",
            "lark", "lemur", "leopard", "lion", "llama", "lobster", "locust",
            "loon", "lynx", "magpie", "mallard", "manatee", "mink", "mole",
            "mongoose", "monkey", "moose", "moth", "mouse", "mule", "narwhal",
            "newt", "nightingale", "octopus", "opossum", "oryx", "osprey",
            "ostrich", "otter", "owl", "ox", "oyster", "panda", "panther",
            "parrot", "partridge", "peacock", "pelican", "penguin", "pheasant",
            "pig", "pigeon", "platypus", "pony", "porcupine", "porpoise",
            "quail", "rabbit", "raccoon", "ram", "rat", "raven", "reindeer",
            "rhino", "salamander", "salmon", "sandpiper", "sardine", "scorpion",
            "seal", "shark", "sheep", "shrew", "skunk", "sloth", "snail",
            "snake", "sparrow", "spider", "squid", "squirrel", "starling",
            "stingray", "stork", "swallow", "swan", "tapir", "termite",
            "tiger", "toad", "trout", "turkey", "turtle", "viper", "vulture",
            "wallaby", "walrus", "wasp", "weasel", "whale", "wolf", "wombat",
            "woodpecker", "wren", "yak", "zebra"
    );

    private static final List<String> COLORS = Arrays.asList(
            "red", "orange", "yellow", "green", "blue", "indigo", "violet",
            "purple", "pink", "brown", "black", "white", "gray", "silver",
            "gold", "bronze", "copper", "coral", "crimson", "cyan", "emerald",
            "fuchsia", "jade", "lavender", "lime", "magenta", "maroon", "navy",
            "olive", "peach", "plum", "rose", "ruby", "salmon", "sapphire",
            "scarlet", "tan", "teal", "turquoise", "amber", "azure", "beige",
            "charcoal", "cream", "ebony", "ivory", "khaki", "lilac", "mint"
    );

    private static final List<String> NATO_ALPHABET = Arrays.asList(
            "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf",
            "hotel", "india", "juliet", "kilo", "lima", "mike", "november",
            "oscar", "papa", "quebec", "romeo", "sierra", "tango", "uniform",
            "victor", "whiskey", "xray", "yankee", "zulu"
    );

    public LocalHonestBroker(CrosswalkStore crosswalkStore, String brokerName, HonestBrokerConfig config) {
        this.crosswalkStore = crosswalkStore;
        this.brokerName = brokerName;
        this.config = config;
        log.info("LocalHonestBroker initialized: {} with scheme: {}", brokerName, config.getNamingScheme());
    }

    /**
     * Look up or create a de-identified ID for the given original ID.
     *
     * @param idIn Original ID (e.g., PatientID)
     * @param idType Type of ID (patient_id, patient_name, accession)
     * @return The de-identified ID
     */
    public String lookup(String idIn, String idType) {
        // Check if we already have a mapping
        String existing = crosswalkStore.lookup(brokerName, idIn, idType);
        if (existing != null) {
            log.debug("Found existing mapping for {}: {} -> {}", idType, idIn, existing);
            crosswalkStore.logOperation(brokerName, "lookup", idIn, existing, idType, null, null, null, "cache_hit");
            return existing;
        }

        // Generate a new de-identified ID
        String idOut = generateNewId(idIn, idType);

        // Store the mapping
        crosswalkStore.store(brokerName, idIn, idOut, idType);
        crosswalkStore.logOperation(brokerName, "create", idIn, idOut, idType, null, null, null, "new_mapping");

        log.info("Created new mapping for {}: {} -> {}", idType, idIn, idOut);
        return idOut;
    }

    /**
     * Reverse lookup - get original ID from de-identified ID.
     */
    public String reverseLookup(String idOut, String idType) {
        String idIn = crosswalkStore.reverseLookup(brokerName, idOut, idType);
        if (idIn != null) {
            crosswalkStore.logOperation(brokerName, "reverse_lookup", idIn, idOut, idType, null, null, null, null);
        }
        return idIn;
    }

    /**
     * Generate a new de-identified ID based on the configured naming scheme.
     */
    private String generateNewId(String idIn, String idType) {
        String scheme = config.getNamingScheme();
        if (scheme == null) {
            scheme = "adjective_animal";
        }

        String prefix = getPrefix(idType);
        int hash = deterministicHash(idIn);

        switch (scheme.toLowerCase()) {
            case "adjective_animal":
                return generateAdjectiveAnimal(hash, prefix);

            case "color_animal":
                return generateColorAnimal(hash, prefix);

            case "nato_phonetic":
                return generateNatoPhonetic(hash, prefix);

            case "sequential":
                return generateSequential(idType, prefix);

            case "hash":
                return generateHash(idIn, prefix);

            case "script":
                return generateFromScript(idIn, idType, prefix);

            default:
                log.warn("Unknown naming scheme: {}, using adjective_animal", scheme);
                return generateAdjectiveAnimal(hash, prefix);
        }
    }

    private String getPrefix(String idType) {
        String prefix = config.getPatientIdPrefix();
        if (prefix == null || prefix.isEmpty()) {
            switch (idType) {
                case "patient_id": return "SUBJ";
                case "patient_name": return "NAME";
                case "accession": return "ACC";
                default: return "ID";
            }
        }
        return prefix;
    }

    /**
     * Generate adjective-animal name (Docker-style).
     * Example: "happy-panda", "brave-falcon"
     */
    private String generateAdjectiveAnimal(int hash, String prefix) {
        int adjIndex = Math.abs(hash) % ADJECTIVES.size();
        int animalIndex = Math.abs(hash / ADJECTIVES.size()) % ANIMALS.size();

        String adjective = ADJECTIVES.get(adjIndex);
        String animal = ANIMALS.get(animalIndex);

        // Add a numeric suffix to ensure uniqueness
        int suffix = getNextSuffix(prefix + "-" + adjective + "-" + animal);

        if (suffix == 0) {
            return String.format("%s-%s-%s", prefix, adjective, animal).toUpperCase();
        } else {
            return String.format("%s-%s-%s-%d", prefix, adjective, animal, suffix).toUpperCase();
        }
    }

    /**
     * Generate color-animal name.
     * Example: "blue-falcon", "red-hawk"
     */
    private String generateColorAnimal(int hash, String prefix) {
        int colorIndex = Math.abs(hash) % COLORS.size();
        int animalIndex = Math.abs(hash / COLORS.size()) % ANIMALS.size();

        String color = COLORS.get(colorIndex);
        String animal = ANIMALS.get(animalIndex);

        int suffix = getNextSuffix(prefix + "-" + color + "-" + animal);

        if (suffix == 0) {
            return String.format("%s-%s-%s", prefix, color, animal).toUpperCase();
        } else {
            return String.format("%s-%s-%s-%d", prefix, color, animal, suffix).toUpperCase();
        }
    }

    /**
     * Generate NATO phonetic name.
     * Example: "ALPHA-7", "BRAVO-42"
     */
    private String generateNatoPhonetic(int hash, String prefix) {
        int natoIndex = Math.abs(hash) % NATO_ALPHABET.size();
        String nato = NATO_ALPHABET.get(natoIndex);

        int number = getNextSuffix(prefix + "-" + nato);

        return String.format("%s-%s-%d", prefix, nato, number).toUpperCase();
    }

    /**
     * Generate sequential ID.
     * Example: "SUBJ-00001", "SUBJ-00002"
     */
    private String generateSequential(String idType, String prefix) {
        int count = crosswalkStore.getMappingCount(brokerName) + 1;
        return String.format("%s-%05d", prefix, count);
    }

    /**
     * Generate short hash-based ID.
     * Example: "HB-A7F3B2"
     */
    private String generateHash(String idIn, String prefix) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(idIn.getBytes(StandardCharsets.UTF_8));

            // Take first 3 bytes and convert to hex
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                hex.append(String.format("%02X", hashBytes[i]));
            }

            String baseId = prefix + "-" + hex.toString();
            int suffix = getNextSuffix(baseId);

            if (suffix == 0) {
                return baseId;
            } else {
                return baseId + "-" + suffix;
            }
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 not available, falling back to sequential", e);
            return generateSequential("patient_id", prefix);
        }
    }

    /**
     * Generate ID using custom JavaScript.
     *
     * <p>The JavaScript must define a function called 'lookup' that takes these parameters:
     * <ul>
     *   <li>idIn - The original patient ID</li>
     *   <li>idType - Type of ID: "patient_id", "patient_name", or "accession"</li>
     *   <li>prefix - The configured prefix (e.g., "SUBJ")</li>
     *   <li>context - A Map containing additional context (brokerName, mappingCount)</li>
     * </ul>
     * </p>
     *
     * <p>Example script:
     * <pre>
     * function lookup(idIn, idType, prefix, context) {
     *   // Simple example: use first 4 chars of ID with prefix
     *   return prefix + "-" + idIn.substring(0, Math.min(4, idIn.length)).toUpperCase();
     * }
     * </pre>
     * </p>
     *
     * @param idIn Original ID
     * @param idType Type of ID (patient_id, patient_name, accession)
     * @param prefix Configured prefix
     * @return Generated de-identified ID
     */
    private String generateFromScript(String idIn, String idType, String prefix) {
        String script = config.getLookupScript();
        if (script == null || script.trim().isEmpty()) {
            log.warn("No lookup script defined for 'script' naming scheme, falling back to adjective_animal");
            return generateAdjectiveAnimal(deterministicHash(idIn), prefix);
        }

        try {
            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByName("nashorn");

            if (engine == null) {
                // Try JavaScript as fallback (for GraalJS or other engines)
                engine = manager.getEngineByName("JavaScript");
            }

            if (engine == null) {
                log.error("No JavaScript engine available (tried nashorn and JavaScript). " +
                          "Ensure you're running Java 8-14 with Nashorn or have GraalJS installed.");
                return generateAdjectiveAnimal(deterministicHash(idIn), prefix);
            }

            // Create context map with additional info the script might need
            Map<String, Object> context = new HashMap<String, Object>();
            context.put("brokerName", brokerName);
            context.put("mappingCount", crosswalkStore.getMappingCount(brokerName));

            // Execute the script to define the lookup function
            engine.eval(script);

            // Check if it's invocable (has defined functions)
            if (!(engine instanceof Invocable)) {
                log.error("Script engine does not support function invocation");
                return generateAdjectiveAnimal(deterministicHash(idIn), prefix);
            }

            Invocable inv = (Invocable) engine;

            // Call the lookup function
            Object result = inv.invokeFunction("lookup", idIn, idType, prefix, context);

            if (result == null) {
                log.warn("Script lookup function returned null for idIn={}, using fallback", idIn);
                return generateAdjectiveAnimal(deterministicHash(idIn), prefix);
            }

            String generatedId = result.toString();
            log.debug("Script generated ID: {} -> {}", idIn, generatedId);

            return generatedId;

        } catch (ScriptException e) {
            log.error("Error executing lookup script: {}", e.getMessage(), e);
            return generateAdjectiveAnimal(deterministicHash(idIn), prefix);
        } catch (NoSuchMethodException e) {
            log.error("Script must define a 'lookup' function: {}", e.getMessage());
            return generateAdjectiveAnimal(deterministicHash(idIn), prefix);
        } catch (Exception e) {
            log.error("Unexpected error in script execution: {}", e.getMessage(), e);
            return generateAdjectiveAnimal(deterministicHash(idIn), prefix);
        }
    }

    /**
     * Get the next available suffix for a base ID to ensure uniqueness.
     */
    private int getNextSuffix(String baseId) {
        // Check if baseId alone is available
        List<CrosswalkStore.CrosswalkEntry> mappings = crosswalkStore.getMappings(brokerName);

        int maxSuffix = -1;
        for (CrosswalkStore.CrosswalkEntry entry : mappings) {
            String existingOut = entry.getIdOut();
            if (existingOut.equalsIgnoreCase(baseId)) {
                maxSuffix = Math.max(maxSuffix, 0);
            } else if (existingOut.toUpperCase().startsWith(baseId.toUpperCase() + "-")) {
                String suffixStr = existingOut.substring(baseId.length() + 1);
                try {
                    int suffix = Integer.parseInt(suffixStr);
                    maxSuffix = Math.max(maxSuffix, suffix);
                } catch (NumberFormatException ignored) {
                    // Not a numeric suffix, ignore
                }
            }
        }

        return maxSuffix + 1;
    }

    /**
     * Compute a deterministic hash from a string.
     * This ensures the same input always produces the same base name.
     */
    private int deterministicHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));

            // Combine first 4 bytes into an int
            return ((hashBytes[0] & 0xFF) << 24) |
                   ((hashBytes[1] & 0xFF) << 16) |
                   ((hashBytes[2] & 0xFF) << 8) |
                   (hashBytes[3] & 0xFF);
        } catch (NoSuchAlgorithmException e) {
            // Fallback to String.hashCode()
            return input.hashCode();
        }
    }

    /**
     * Test the connection (always succeeds for local broker).
     */
    public boolean testConnection() {
        return true;
    }

    /**
     * Get the date shift offset for a patient.
     * If date shifting is enabled and no offset exists, generates a random one.
     *
     * @param patientId Original PatientID
     * @return Date shift offset in days, or 0 if date shifting is disabled
     */
    public int getDateShiftForPatient(String patientId) {
        if (!config.isDateShiftEnabled()) {
            return 0;
        }

        int minDays = config.getDateShiftMinDays();
        int maxDays = config.getDateShiftMaxDays();

        int offset = crosswalkStore.getOrCreateDateShift(brokerName, patientId, minDays, maxDays);
        log.debug("Date shift for patient {}: {} days", patientId, offset);
        return offset;
    }

    /**
     * Get existing date shift for a patient without creating a new one.
     *
     * @param patientId Original PatientID
     * @return Date shift offset in days, or null if not found
     */
    public Integer getExistingDateShift(String patientId) {
        if (!config.isDateShiftEnabled()) {
            return null;
        }
        return crosswalkStore.getDateShift(brokerName, patientId);
    }

    /**
     * Store a UID mapping if UID hashing is enabled.
     *
     * @param originalUid Original UID
     * @param hashedUid Hashed/anonymized UID
     * @param uidType Type of UID (study_uid, series_uid, sop_uid)
     * @return true if stored or if hashing is disabled, false on error
     */
    public boolean storeUidMapping(String originalUid, String hashedUid, String uidType) {
        if (!config.isHashUidsEnabled()) {
            // UID hashing is disabled, nothing to store
            return true;
        }

        boolean success = crosswalkStore.storeUidMapping(brokerName, originalUid, hashedUid, uidType);
        if (success) {
            log.debug("Stored UID mapping: {} {} -> {}", uidType, originalUid, hashedUid);
        }
        return success;
    }

    /**
     * Lookup a hashed UID from the crosswalk.
     *
     * @param originalUid Original UID
     * @param uidType Type of UID (study_uid, series_uid, sop_uid)
     * @return Hashed UID or null if not found
     */
    public String lookupHashedUid(String originalUid, String uidType) {
        if (!config.isHashUidsEnabled()) {
            return null;
        }
        return crosswalkStore.lookupHashedUid(brokerName, originalUid, uidType);
    }

    /**
     * Check if date shifting is enabled for this broker.
     */
    public boolean isDateShiftEnabled() {
        return config.isDateShiftEnabled();
    }

    /**
     * Check if UID hashing crosswalk storage is enabled for this broker.
     */
    public boolean isHashUidsEnabled() {
        return config.isHashUidsEnabled();
    }

    /**
     * Get statistics about this broker.
     */
    public BrokerStats getStats() {
        BrokerStats stats = new BrokerStats();
        stats.setBrokerName(brokerName);
        stats.setNamingScheme(config.getNamingScheme());
        stats.setTotalMappings(crosswalkStore.getMappingCount(brokerName));
        return stats;
    }

    public static class BrokerStats {
        private String brokerName;
        private String namingScheme;
        private int totalMappings;

        public String getBrokerName() { return brokerName; }
        public void setBrokerName(String brokerName) { this.brokerName = brokerName; }

        public String getNamingScheme() { return namingScheme; }
        public void setNamingScheme(String namingScheme) { this.namingScheme = namingScheme; }

        public int getTotalMappings() { return totalMappings; }
        public void setTotalMappings(int totalMappings) { this.totalMappings = totalMappings; }
    }
}
