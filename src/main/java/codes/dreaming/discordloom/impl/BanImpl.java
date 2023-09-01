package codes.dreaming.discordloom.impl;

import com.mojang.authlib.GameProfile;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.MetaNode;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static codes.dreaming.discordloom.DiscordLoomServer.*;

public class BanImpl {
    public static void ban(Collection<GameProfile> targets, @Nullable String reason, @Nullable String source) {
        if (!SERVER_CONFIG.banDiscordAccount()) {
            return;
        }
        applyToDiscordIds(
			targets,
			discordId -> SERVER_CONFIG.checkForGuildsOnJoin()
				.forEach(guildId -> {
					Guild guild = DISCORD_MANAGER.getDiscordGuildFromId(Long.valueOf(guildId));
					if (guild == null) {
						return;
					}
					String finalReason = "Banned by DiscordLoom: " + reason;
					if (source != null) {
						finalReason += " (" + source + ")";
					}
					guild.ban(UserSnowflake.fromId(discordId), 0, TimeUnit.SECONDS)
						.reason(finalReason)
						.queue();
				})
        );
    }

    public static void pardon(Collection<GameProfile> targets) {
        if (!SERVER_CONFIG.banDiscordAccount()) {
            return;
        }
        applyToDiscordIds(
			targets,
			discordId -> SERVER_CONFIG.checkForGuildsOnJoin()
				.forEach(guildId -> {
					Guild guild = DISCORD_MANAGER.getDiscordGuildFromId(Long.valueOf(guildId));
					if (guild == null) {
						return;
					}
					guild.unban(UserSnowflake.fromId(discordId))
						.queue();
				})
        );
    }

    private static void applyToDiscordIds(Collection<GameProfile> targets, Consumer<String> discordIdAction) {
        targets.stream()
			.map(GameProfile::getId)
			.filter(Objects::nonNull)
			.map(LuckPermsProvider.get().getUserManager()::getUser)
			.filter(Objects::nonNull)
			.map(user -> user
				.getNodes(NodeType.META)
				.stream()
				.filter(node -> node.getMetaKey().equals(LuckPermsMetadataKey))
				.findAny()
				.map(MetaNode::getMetaValue)
			)
			.filter(Optional::isPresent)
			.map(Optional::get)
			.forEach(discordIdAction);
    }
}