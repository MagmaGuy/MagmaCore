package com.magmaguy.magmacore.command;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;

public class LogifyCommand extends AdvancedCommand {
    private final JavaPlugin plugin;

    public LogifyCommand(JavaPlugin plugin) {
        super(new ArrayList<>());
        setUsage("/logify");
        setSenderType(SenderType.ANY);
        setPermission("logify.*");
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandData commandData) {
        if (!commandData.getCommandSender().hasPermission("logify.*")) return;
        CommandSender sender = commandData.getCommandSender();

        // Locate the logs folder relative to the plugin's data folder.
        File logsFolder = new File(plugin.getDataFolder().getParentFile().getParentFile(), "Logs");
        if (!logsFolder.exists())
            logsFolder = new File(plugin.getDataFolder().getParentFile().getParentFile(), "logs");
        if (!logsFolder.exists() || !logsFolder.isDirectory()) {
            sender.sendMessage("§cCould not find the logs folder on your server!");
            return;
        }

        // Try to use the latest.log file first.
        File logFile = new File(logsFolder, "latest.log");
        if (!logFile.exists()) {
            // If latest.log doesn't exist, search for any .log file and choose the most recently modified one.
            File[] logFiles = logsFolder.listFiles(file -> file.isFile() && file.getName().endsWith(".log"));
            if (logFiles != null && logFiles.length > 0) {
                File mostRecentLog = null;
                long mostRecentTime = 0;
                for (File file : logFiles) {
                    if (file.lastModified() > mostRecentTime) {
                        mostRecentTime = file.lastModified();
                        mostRecentLog = file;
                    }
                }
                logFile = mostRecentLog;
            }
        }

        if (logFile == null || !logFile.exists()) {
            sender.sendMessage("§cNo log file found!");
            return;
        }

        try {
            byte[] fileBytes = Files.readAllBytes(logFile.toPath());
            String content = new String(fileBytes, StandardCharsets.UTF_8);

            // Anonymize IP addresses (e.g., 127.125.201.60:58555 -> ***.***.***.**:*****)
            content = anonymizeIPs(content);

            // URL-encode the content
            String encodedContent = URLEncoder.encode(content, StandardCharsets.UTF_8);
            String response = uploadLog(encodedContent);

            if (response != null && response.contains("\"success\":true")) {
                String logUrl = extractLogUrl(response);
                commandData.getCommandSender().spigot().sendMessage(
                        Logger.simpleMessage("&aLog uploaded successfully! View it here: "),
                        Logger.hoverLinkMessage("&9" + logUrl, "Click to go to link!", logUrl),
                        Logger.simpleMessage(" &a. "),
                        Logger.hoverCopyMessage("&6Click here to copy it!", "Click to copy link to clipboard!", logUrl)
                );
            } else {
                Logger.sendMessage(commandData.getCommandSender(), "&cFailed to upload log!");
            }

        } catch (IOException e) {
            sender.sendMessage("§cAn error occurred while processing the log file.");
            Logger.warn("Error reading log file: " + e.getMessage());
        }
    }

    /**
     * Replace digits in IPv4 addresses (and optional port) with asterisks.
     */
    private String anonymizeIPs(String content) {
        Pattern ipPattern = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?\\b");
        Matcher matcher = ipPattern.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String ipPart = matcher.group();
            String anonymized = ipPart.replaceAll("\\d", "*");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(anonymized));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String uploadLog(String encodedContent) {
        try {
            URL url = new URL("https://api.mclo.gs/1/log");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setDoOutput(true);

            String postData = "content=" + encodedContent;

            try (OutputStream os = connection.getOutputStream()) {
                os.write(postData.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            Scanner scanner = new Scanner(connection.getInputStream(), StandardCharsets.UTF_8);
            StringBuilder response = new StringBuilder();
            while (scanner.hasNext()) {
                response.append(scanner.nextLine());
            }
            scanner.close();

            return response.toString();

        } catch (IOException e) {
            Logger.warn("Error uploading log: " + e.getMessage());
            return null;
        }
    }

    private String extractLogUrl(String jsonResponse) {
        int urlIndex = jsonResponse.indexOf("\"url\":\"") + 7;
        int endIndex = jsonResponse.indexOf("\"", urlIndex);
        return jsonResponse.substring(urlIndex, endIndex).replace("\\/", "/");
    }
}
