package codes.dreaming.discordloom.velocity;

import codes.dreaming.discordloom.BuildConstants;
import codes.dreaming.discordloom.discord.ServerDiscordManager;
import com.google.inject.Inject;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import discord4j.rest.RestClient;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.kyori.adventure.text.Component;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.NodeEqualityPredicate;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.matcher.NodeMatcher;
import net.luckperms.api.node.types.MetaNode;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.spongepowered.configurate.serialize.SerializationException;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static codes.dreaming.discordloom.DiscordLoom.MOD_ID;
import static codes.dreaming.discordloom.DiscordLoomServer.LuckPermsMetadataKey;

@Plugin(id = MOD_ID,
        name = "DiscordLoomVelocity",
        version = BuildConstants.VERSION,
        url = "https://codes.dreaming/",
        authors = {"DreamingCodes"})
public class DiscordLoomVelocity {
    public static final ChannelIdentifier QUERY_PACKET_ID = MinecraftChannelIdentifier.create(MOD_ID, "query");
    public static final ChannelIdentifier RELAY_PACKET_ID = MinecraftChannelIdentifier.create(MOD_ID, "relay");
    private final ConfigHelper configHelper;
    private static ProxyServer server;
    private final Logger logger;
    private static JDA jdaApi;

    @Inject
    public DiscordLoomVelocity(ProxyServer server, Logger logger) {
        DiscordLoomVelocity.server = server;
        this.logger = logger;
        this.configHelper = new ConfigHelper(logger);
    }

    public int setRole(CommandContext<CommandSource> ctx, boolean add) {
        String player = ctx.getArgument("player", String.class);
        Optional<Player> p = server.getPlayer(player);
        if(p.isPresent()) {
            long guildId = LongArgumentType.getLong(ctx, "guildId");
            long roleId = LongArgumentType.getLong(ctx, "role");
            String discordId = LuckPermsProvider.get()
                    .getUserManager()
                    .getUser(p.get().getUniqueId())
                    .getNodes(NodeType.META)
                    .stream()
                    .filter(node -> node.getMetaKey().equals(LuckPermsMetadataKey))
                    .findAny()
                    .orElseThrow()
                    .getMetaValue();

            Guild guild = jdaApi.getGuildById(guildId);

            if (guild == null) {
                ctx.getSource().sendMessage(Component.text("§cGuild not found!"));
                return 0;
            }

            Role role = guild.getRoleById(roleId);

            if (role == null) {
                ctx.getSource().sendMessage(Component.text("§cRole not found!"));
                return 0;
            }

            UserSnowflake userSnowflake = UserSnowflake.fromId(discordId);

            if (add) {
                guild.addRoleToMember(userSnowflake, role).queue();

            } else {
                guild.removeRoleFromMember(userSnowflake, role).queue();
            }

            return 1;
        }else{
            ctx.getSource().sendMessage(Component.text("§cPlayer not found!"));
            return 0;
        }
    }

    public static int whoisPlayer(CommandContext<CommandSource> ctx) {
        String player = ctx.getArgument("player", String.class);
        Optional<Player> p = server.getPlayer(player);
        if(p.isPresent()) {
            String discordId = LuckPermsProvider.get()
                    .getUserManager()
                    .getUser(p.get().getUniqueId())
                    .getNodes(NodeType.META)
                    .stream()
                    .filter(node -> node.getMetaKey().equals(LuckPermsMetadataKey))
                    .findAny()
                    .orElseThrow()
                    .getMetaValue();

            User user = jdaApi.retrieveUserById(discordId).complete();

            if (user == null) {
                ctx.getSource().sendMessage(Component.text("§cNo matches found!"));
                return 0;
            }

            String username = user.getName();
            ctx.getSource().sendMessage(Component.text("§a" + p.get().getGameProfile().getName() + " is " + username + " on Discord!"));

            return 1;
        }else{
            ctx.getSource().sendMessage(Component.text("§cPlayer not found!"));
            return 0;
        }
    }

    public static int whoisDiscord(CommandContext<CommandSource> ctx) {
        long id = LongArgumentType.getLong(ctx, "id");

        Set<UUID> matches = ServerDiscordManager.getPlayersFromDiscordId(Long.toString(id));

        if (matches.isEmpty()) {
            ctx.getSource().sendMessage(Component.text("§cNo matches found!"));
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
                if(server.getPlayer(luckUser.getUniqueId()).isPresent()){
                    names.add(server.getPlayer(luckUser.getUniqueId()).get().getGameProfile().getName());
                }else {
                    names.add(luckUser.getFriendlyName());
                }
            }
        }
        ctx.getSource().sendMessage(Component.text("§aFound " + names.size() + " matches: " + String.join(", ", names)));

        return 1;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        this.configHelper.loadConfiguration();
        ExecutorService discordExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() - 1);
        ScheduledExecutorService discordScheduledExecutor = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() - 1);

        jdaApi = JDABuilder
                .createDefault(configHelper.getDiscordBotToken(), GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.GUILD_MEMBERS)
                .setCallbackPool(discordExecutor)
                .setGatewayPool(discordScheduledExecutor)
                .setRateLimitPool(discordScheduledExecutor)
                .addEventListeners(new ListenerAdapter() {
                    @Override
                    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
                        List<String> mandatoryVCChannels;
                        try {
                            mandatoryVCChannels = configHelper.getMandatoryVCChannels();
                        } catch (SerializationException e) {
                            return;
                        }
                        if (mandatoryVCChannels.isEmpty()) {
                            return;
                        }

                        if (event.getChannelJoined() != null && mandatoryVCChannels.contains(event.getChannelJoined().getId())) {
                            return;
                        }
                        if (event.getChannelLeft() != null && mandatoryVCChannels.contains(event.getChannelLeft().getId())) {
                            MetaNode discordIdNode = MetaNode.builder()
                                    .key(LuckPermsMetadataKey)
                                    .value(event.getEntity().getId())
                                    .build();

                            Set<UUID> matches;
                            try {
                                matches = LuckPermsProvider.get()
                                        .getUserManager()
                                        .searchAll(NodeMatcher.equals(discordIdNode, NodeEqualityPredicate.EXACT))
                                        .get()
                                        .keySet();
                            } catch (Exception e) {
                                matches = Collections.emptySet();
                            }
                            matches.forEach(uuid -> {
                                if (server.getPlayer(uuid).isPresent()) {
                                    if (server.getPlayer(uuid).get().hasPermission(MOD_ID + ".bypass_vc")) {
                                        return;
                                    }
                                    logger.info("Kicking player {} from server since he left a mandatory VC channel", uuid);
                                    server.getPlayer(uuid).get().disconnect(Component.text("You left a mandatory VC channel"));
                                }
                            });
                        }
                    }

                    @Override
                    public void onUserContextInteraction(@NotNull UserContextInteractionEvent event) {
                        if (event.getName().equals("Get user minecraft info")) {
                            MetaNode discordIdNode = MetaNode.builder()
                                    .key(LuckPermsMetadataKey)
                                    .value(event.getTarget().getId())
                                    .build();

                            Set<UUID> uuids;
                            try {
                                uuids = LuckPermsProvider.get()
                                        .getUserManager()
                                        .searchAll(NodeMatcher.equals(discordIdNode, NodeEqualityPredicate.EXACT))
                                        .get()
                                        .keySet();
                            } catch (Exception e) {
                                uuids = Collections.emptySet();
                            }

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
                })
                .build();

        jdaApi.updateCommands()
                .addCommands(Commands.context(Command.Type.USER, "Get user minecraft info"))
                .queue();
        RestClient restClient = RestClient.create(configHelper.getDiscordBotToken());
        server.getChannelRegistrar().register(QUERY_PACKET_ID);
        server.getChannelRegistrar().register(RELAY_PACKET_ID);
        server.getEventManager().register(this, new DiscordLoomVelocityEventHandler(logger, configHelper, jdaApi, restClient));
        CommandManager commandManager = server.getCommandManager();
        CommandMeta commandMeta = commandManager.metaBuilder("discordloom").plugin(this).build();
        commandManager.register(commandMeta, new BrigadierCommand(discordLoomCommmand()));
    }

    private @NotNull LiteralArgumentBuilder<CommandSource> discordLoomCommmand() {
        return BrigadierCommand.literalArgumentBuilder("discordloom")
                .then(BrigadierCommand.literalArgumentBuilder("whois")
                        .requires(src -> src.hasPermission("discordloom.whois"))
                        .then(BrigadierCommand.literalArgumentBuilder("player")
                                .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.string())
                                        .executes(DiscordLoomVelocity::whoisPlayer))
                        )
                        .then(BrigadierCommand.literalArgumentBuilder("discord")
                                .then(BrigadierCommand.requiredArgumentBuilder("id", LongArgumentType.longArg())
                                        .executes(DiscordLoomVelocity::whoisDiscord)))
                )
                .then(BrigadierCommand.literalArgumentBuilder("role")
                        .requires(src -> src.hasPermission("discordloom.role"))
                        .then(BrigadierCommand.literalArgumentBuilder("add")
                                .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.string())
                                        .then(BrigadierCommand.requiredArgumentBuilder("guildId", LongArgumentType.longArg())
                                                .then(BrigadierCommand.requiredArgumentBuilder("role", LongArgumentType.longArg())
                                                        .executes(ctx -> setRole(ctx, true)))))
                        )
                        .then(BrigadierCommand.literalArgumentBuilder("remove")
                                .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.string())
                                        .then(BrigadierCommand.requiredArgumentBuilder("guildId", LongArgumentType.longArg())
                                                .then(BrigadierCommand.requiredArgumentBuilder("role", LongArgumentType.longArg())
                                                        .executes(ctx -> setRole(ctx, false)))))
                        )
                );
    }
}
