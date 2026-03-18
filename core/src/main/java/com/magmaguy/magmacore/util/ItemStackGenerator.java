package com.magmaguy.magmacore.util;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

public class ItemStackGenerator {

    private static final HashMap<String, PlayerProfile> cachedPlayerProfiles = new HashMap<>();

    private ItemStackGenerator() {
    }

    public static ItemStack generateSkullItemStack(String username, String name, List<String> lore) {
        ItemStack itemStack = new ItemStack(Material.PLAYER_HEAD);
        UUID playerUUID = getUUIDFromUsername(username);
        SkullMeta skullMeta = (SkullMeta) itemStack.getItemMeta();

        if (cachedPlayerProfiles.containsKey(username)) {
            if (cachedPlayerProfiles.get(username).getName() != null)
                skullMeta.setOwnerProfile(cachedPlayerProfiles.get(username));
        } else if (playerUUID != null) {
            String sessionServerURL = "https://sessionserver.mojang.com/session/minecraft/profile/" + playerUUID;
            try {
                // Step 1: Make an HTTP request to the session server URL
                URL url = new URL(sessionServerURL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/json");
                connection.connect();

                // Step 2: Read the JSON response
                InputStreamReader reader = new InputStreamReader(connection.getInputStream());
                Scanner scanner = new Scanner(reader);
                StringBuilder jsonResponse = new StringBuilder();
                while (scanner.hasNextLine()) {
                    jsonResponse.append(scanner.nextLine());
                }

                // Step 3: Parse the JSON response
                JsonObject jsonObject = JsonParser.parseString(jsonResponse.toString()).getAsJsonObject();
                JsonArray properties = jsonObject.getAsJsonArray("properties");

                // Step 4: Extract the Base64 encoded value
                for (JsonElement property : properties) {
                    JsonObject propertyObject = property.getAsJsonObject();
                    if (propertyObject.get("name").getAsString().equals("textures")) {
                        String encodedValue = propertyObject.get("value").getAsString();

                        // Step 5: Get the URL from the Base64 decoded string
                        URL skinUrl = getUrlFromBase64(encodedValue);

                        // Set the profile with the decoded textures URL
                        PlayerProfile playerProfile = Bukkit.createPlayerProfile(playerUUID);
                        PlayerTextures textures = playerProfile.getTextures();
                        textures.setSkin(skinUrl);
                        if (playerProfile.getName() != null)
                            skullMeta.setOwnerProfile(playerProfile);
                        cachedPlayerProfiles.put(username, playerProfile);
                        break;
                    }
                }
                scanner.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        skullMeta.setDisplayName(ChatColorConverter.convert(name));
        skullMeta.setLore(ChatColorConverter.convert(lore));
        itemStack.setItemMeta(skullMeta);
        return itemStack;
    }

    // Method to extract the URL from the Base64-encoded string
    public static URL getUrlFromBase64(String base64) throws MalformedURLException {
        String decoded = new String(Base64.getDecoder().decode(base64));

        // Parse the decoded string as JSON to extract the URL
        JsonObject jsonObject = JsonParser.parseString(decoded).getAsJsonObject();
        JsonObject textures = jsonObject.getAsJsonObject("textures");
        JsonObject skin = textures.getAsJsonObject("SKIN");
        String urlString = skin.get("url").getAsString();

        return new URL(urlString);
    }

    private static UUID getUUIDFromUsername(String username) throws IllegalStateException {

        try {
            URL url = new URL("https://api.mojang.com/users/profiles/minecraft/" + username);

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            conn.addRequestProperty("User-Agent", "Mozilla/4.0");
            conn.setDoOutput(false);

            try (JsonReader reader = new JsonReader(new InputStreamReader(conn.getInputStream()))) {

                reader.setLenient(true);

                // read JSON data
                JsonObject json = new Gson().fromJson(reader, JsonObject.class);

                // close reader
                reader.close();

                return UUID.fromString(json.get("id").getAsString().replaceFirst(
                        "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5"
                ));

            } catch (IOException e) {
                throw new IllegalStateException("ERROR_CHECKING");
            }

        } catch (Exception e) {
            /*
             * Fail quietly.
             * No need to spam a stack trace.
             */
            return null;
        }
    }

    public static ItemStack generateItemStack(ItemStack itemStack, String name, List<String> lore) {
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.setDisplayName(ChatColorConverter.convert(name));
        itemMeta.setLore(ChatColorConverter.convert(lore));
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    public static ItemStack generateItemStack(Material material, String name, List<String> lore, int customModelID) {
        ItemStack itemStack = generateItemStack(material, ChatColorConverter.convert(name));
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.setLore(ChatColorConverter.convert(lore));
        if (customModelID > 0)
            itemMeta.setCustomModelData(customModelID);
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    public static ItemStack generateItemStack(Material material, String name, List<String> lore, String namespacedKey) {
        ItemStack itemStack = generateItemStack(material, ChatColorConverter.convert(name));
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.setLore(ChatColorConverter.convert(lore));
        if (!VersionChecker.serverVersionOlderThan(21, 4) && namespacedKey != null)
            try {
                itemMeta.setItemModel(NamespacedKey.fromString(namespacedKey));
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("Failed to set item model for " + namespacedKey);
            }
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    public static ItemStack generateItemStack(Material material, String name, List<String> lore) {
        ItemStack itemStack = generateItemStack(material, ChatColorConverter.convert(name));
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.setLore(ChatColorConverter.convert(lore));
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    public static ItemStack generateItemStack(Material material, String name) {
        ItemStack itemStack = generateItemStack(material);
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.setDisplayName(ChatColorConverter.convert(name));
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    public static ItemStack generateItemStack(Material material) {
        if (material == null) material = Material.AIR;
        ItemStack itemStack = new ItemStack(material);
        if (material.equals(Material.AIR)) return itemStack;
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.setDisplayName("");
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    public static ItemStack generateFlaglessItemStack(Material material, String name, List<String> loreList) {
        ItemStack itemStack = generateItemStack(material, name, loreList);
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        itemMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        itemMeta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

}
