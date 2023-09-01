package codes.dreaming.discordloom.command;

import codes.dreaming.discordloom.discord.ServerDiscordManager;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.NodeType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import static codes.dreaming.discordloom.DiscordLoomServer.DISCORD_MANAGER;
import static codes.dreaming.discordloom.DiscordLoomServer.LuckPermsMetadataKey;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class DiscordLoomCommand {
    public static LiteralArgumentBuilder<ServerCommandSource> command() {
        return literal("discordloom")
                .then(literal("whois")
                        .requires(src -> Permissions.check(src, "discordloom.whois", 2))
                        .then(literal("player")
                                .then(argument("player", EntityArgumentType.player())
                                        .executes(DiscordLoomCommand::whoisPlayer))
                        )
                        .then(literal("discord")
                                .then(argument("id", LongArgumentType.longArg())
                                        .executes(DiscordLoomCommand::whoisDiscord)))
                )
                .then(literal("role")
                        .requires(src -> Permissions.check(src, "discordloom.role", 4))
                        .then(literal("add")
                                .then(argument("player", EntityArgumentType.player())
                                        .then(argument("guildId", LongArgumentType.longArg())
                                                .then(argument("role", LongArgumentType.longArg())
                                                        .executes(ctx -> setRole(ctx, true)))))
                        )
                        .then(literal("remove")
                                .then(argument("player", EntityArgumentType.player())
                                        .then(argument("guildId", LongArgumentType.longArg())
                                                .then(argument("role", LongArgumentType.longArg())
                                                        .executes(ctx -> setRole(ctx, false)))))
                        )
                );
    }

    public static int setRole(CommandContext<ServerCommandSource> ctx, boolean add) throws CommandSyntaxException {
        PlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");
        Long guildId = LongArgumentType.getLong(ctx, "guildId");
        long roleId = LongArgumentType.getLong(ctx, "role");

        String discordId = LuckPermsProvider.get()
			.getUserManager()
			.getUser(player.getUuid())
			.getNodes(NodeType.META)
			.stream()
			.filter(node -> node.getMetaKey().equals(LuckPermsMetadataKey))
			.findAny()
			.orElseThrow()
			.getMetaValue();

        Guild guild = DISCORD_MANAGER.getDiscordGuildFromId(guildId);

        if(guild == null) {
            ctx.getSource().sendFeedback(Text.of("§cGuild not found!"), false);
            return 0;
        }

        Role role = guild.getRoleById(roleId);

        if(role == null) {
            ctx.getSource().sendFeedback(Text.of("§cRole not found!"), false);
            return 0;
        }

        UserSnowflake userSnowflake = UserSnowflake.fromId(discordId);

        if(add){
            guild.addRoleToMember(userSnowflake, role).queue();

        }else{
            guild.removeRoleFromMember(userSnowflake, role).queue();
        }

        return 1;
    }

    public static int whoisPlayer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        PlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");

        String discordId = LuckPermsProvider.get()
			.getUserManager()
			.getUser(player.getUuid())
			.getNodes(NodeType.META)
			.stream()
			.filter(node -> node.getMetaKey().equals(LuckPermsMetadataKey))
			.findAny()
			.orElseThrow()
			.getMetaValue();

        User user = DISCORD_MANAGER.getDiscordUserFromId(discordId);

        if (user == null) {
            ctx.getSource().sendFeedback(Text.of("§cNo matches found!"), false);
            return 0;
        }

        String username = user.getName();

        ctx.getSource().sendFeedback(
			Text.of("§a" + player.getDisplayName().getString() + " is " + username + " on Discord!"),
			false
		);

        return 1;
    }

    public static int whoisDiscord(CommandContext<ServerCommandSource> ctx) {
        long id = LongArgumentType.getLong(ctx, "id");

        Set<UUID> matches = ServerDiscordManager.getPlayersFromDiscordId(Long.toString(id));

        if (matches.isEmpty()) {
            ctx.getSource().sendFeedback(Text.of("§cNo matches found!"), false);
            return 0;
        }

        ArrayList<String> names = new ArrayList<>();

        for (UUID uuid : matches) {
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

        ctx.getSource().sendFeedback(
			Text.of("§aFound " + names.size() + " matches: " + String.join(", ", names)),
			false
		);

        return 1;
    }
}
