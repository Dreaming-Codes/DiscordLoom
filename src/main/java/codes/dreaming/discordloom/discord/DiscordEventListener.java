package codes.dreaming.discordloom.discord;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.luckperms.api.LuckPermsProvider;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import static codes.dreaming.discordloom.DiscordLoom.LOGGER;
import static codes.dreaming.discordloom.DiscordLoom.MOD_ID;
import static codes.dreaming.discordloom.DiscordLoomServer.PLAYER_MANAGER;
import static codes.dreaming.discordloom.DiscordLoomServer.SERVER_CONFIG;

public class DiscordEventListener extends ListenerAdapter {
    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
        if (SERVER_CONFIG.mandatoryVCChannels().isEmpty()) {
            return;
        }

        if (event.getChannelJoined() != null && SERVER_CONFIG.mandatoryVCChannels().contains(event.getChannelJoined().getId())) {
            return;
        }

        if (event.getChannelLeft() != null && SERVER_CONFIG.mandatoryVCChannels().contains(event.getChannelLeft().getId())) {
            ServerDiscordManager.getPlayersFromDiscordId(event.getEntity().getId()).forEach(uuid -> {
                ServerPlayerEntity player = PLAYER_MANAGER.getPlayer(uuid);
                if (player == null)
					return;

                if (Permissions.check(player, MOD_ID + ".bypass_vc"))
					return;

                LOGGER.info("Kicking player {} from server since he left a mandatory VC channel", uuid);
                player.networkHandler.disconnect(Text.of("You left a mandatory VC channel"));
            });
        }
    }

    @Override
    public void onUserContextInteraction(UserContextInteractionEvent event) {
        if (event.getName().equals("Get user minecraft info")) {
            Set<UUID> uuids = ServerDiscordManager.getPlayersFromDiscordId(event.getTarget().getId());

            if (uuids.isEmpty()) {
                event.reply("This user is not linked to any minecraft account").queue();
                return;
            }

            ArrayList<String> names = new ArrayList<>();

            for (UUID uuid : uuids) {
                net.luckperms.api.model.user.User luckUser;

                try {
                    luckUser = LuckPermsProvider.get().getUserManager().loadUser(uuid).join();
                } catch (Exception e) {
                    luckUser = null;
                }

                if (luckUser == null) {
                    names.add("§cUnknown user (§4" + uuid + "§c)");
                } else {
                    names.add(luckUser.getFriendlyName());
                }
            }

            event.reply("Found matches " + String.join(", ", names)).setEphemeral(true).queue();
        }
    }
}
