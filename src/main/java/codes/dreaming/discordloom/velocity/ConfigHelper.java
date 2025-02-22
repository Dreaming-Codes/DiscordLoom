package codes.dreaming.discordloom.velocity;

import codes.dreaming.discordloom.DiscordLoom;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ConfigHelper {
    private final Logger logger;
    private final Path dataFolder;
    private ConfigurationNode configData;

    public ConfigHelper(Logger logger) {
        this.logger = logger;
        this.dataFolder = Path.of("plugins/"+ DiscordLoom.MOD_ID);
    }

    public void loadConfiguration() {
        try {
            if (!Files.exists(dataFolder)) {
                Files.createDirectories(dataFolder);
            }

            Path configFile = dataFolder.resolve("config.yml");
            YamlConfigurationLoader loader =
                    YamlConfigurationLoader.builder()
                            .path(configFile)
                            .nodeStyle(NodeStyle.BLOCK)
                            .build();

            if (!Files.exists(configFile)) {
                try (InputStream defaultConfigStream = this.getClass().getResourceAsStream("/config.yml")) {
                    if (defaultConfigStream != null) {
                        Files.copy(defaultConfigStream, configFile);
                    } else {
                        throw new IOException("Default config not found in resources!");
                    }
                }
            }
            configData = loader.load();
        } catch (IOException e) {
            logger.warn("Failed to load config.yml: " + e.getMessage());
        }
    }

    public long getDiscordClientId() {
        return configData.node("discordClientId").getLong(1111111111111111111L);
    }
    public String getDiscordClientSecret() {
        return configData.node("discordClientSecret").getString("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
    }
    public String getDiscordBotToken() {
        return configData.node("discordBotToken").getString("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
    }
    public int getDiscordRedirectUriPort() {
        return configData.node("discordRedirectUriPort").getInt(8080);
    }
    public List<String> getCheckForGuildsOnJoin() throws SerializationException {
        return configData.node("checkForGuildsOnJoin").getList(String.class,List.of());
    }
    public List<String> getSyncDiscordRolesOnJoin() throws SerializationException {
        return configData.node("syncDiscordRolesOnJoin").getList(String.class,List.of());
    }
    public boolean getAllowMultipleMinecraftAccountsPerDiscordAccount() {
        return configData.node("allowMultipleMinecraftAccountsPerDiscordAccount").getBoolean(false);
    }
    public List<String> getMandatoryVCChannels() throws SerializationException {
        return configData.node("mandatoryVCChannels").getList(String.class,List.of());
    }
    public boolean getBanDiscordAccount() throws SerializationException {
        return configData.node("banDiscordAccount").getBoolean(true);
    }
}