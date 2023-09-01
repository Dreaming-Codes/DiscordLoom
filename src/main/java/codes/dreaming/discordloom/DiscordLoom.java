package codes.dreaming.discordloom;

import net.fabricmc.api.*;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class DiscordLoom implements ModInitializer {
    public static final String MOD_ID = "discordloom";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

   /**
	 * server -> client = oauth url
	 * server <- client = oauth token
	 */
    public static final Identifier QUERY_PACKET_ID = new Identifier(MOD_ID, "query");
    public static final Identifier RELAY_PACKET_ID = new Identifier(MOD_ID, "relay");

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing DiscordLoom");
    }
}