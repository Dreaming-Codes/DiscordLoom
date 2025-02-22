package codes.dreaming.discordloom.velocity;

import codes.dreaming.discordloom.PermissionHelper;
import codes.dreaming.discordloom.discord.ServerDiscordManager;
import codes.dreaming.discordloom.velocity.utils.FriendlyByteBuf;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.proxy.LoginPhaseConnection;
import com.velocitypowered.api.proxy.Player;
import discord4j.discordjson.json.AuthorizationCodeGrantRequest;
import discord4j.oauth2.DiscordOAuth2Client;
import discord4j.rest.RestClient;
import io.netty.buffer.Unpooled;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.kyori.adventure.text.Component;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.MetaNode;
import org.slf4j.Logger;
import org.spongepowered.configurate.serialize.SerializationException;

import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static codes.dreaming.discordloom.DiscordLoom.MOD_ID;
import static codes.dreaming.discordloom.DiscordLoomServer.*;

public class DiscordLoomVelocityEventHandler {
    private static Logger logger;
    private static ConfigHelper config;
    private static JDA jdaApi;
    private static RestClient restClient;
    private final HashMap<UUID, CompletableFuture<Void>> playerFutures = new HashMap<>();

    public DiscordLoomVelocityEventHandler(Logger logger, ConfigHelper configHelper, JDA jdaApi, RestClient restClient) {
        DiscordLoomVelocityEventHandler.logger = logger;
        DiscordLoomVelocityEventHandler.config = configHelper;
        DiscordLoomVelocityEventHandler.jdaApi = jdaApi;
        DiscordLoomVelocityEventHandler.restClient = restClient;
    }

    @Subscribe()
    public void onPermissionSetup(PermissionsSetupEvent event, Continuation continuation) {
        if (!(event.getSubject() instanceof Player player)) {
            continuation.resume();
            return;
        }

        var playerFuture = playerFutures.remove(player.getUniqueId());

        if (playerFuture == null) {
            player.disconnect(Component.text("To join this server you need to install DiscordLoom mod"));
            continuation.resume();
            return;
        }

        playerFuture.whenComplete((result, ex) -> {
            if (ex != null) {
                player.disconnect(Component.text(ex.getMessage()));
                continuation.resume();
                return;
            }
            continuation.resume();
        });
    }


    @Subscribe(priority = -1)
    public void preLoginEvent(PreLoginEvent event) {
        // get user's profile
        LoginPhaseConnection connection = (LoginPhaseConnection) event.getConnection();
        var uuid = event.getUniqueId();
        if (uuid == null) {
            logger.error("UUID is null!");
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(Component.translatable("text.discordloom.disconnect.profile")));
            return;
        }

        CompletableFuture<Void> playerFuture = new CompletableFuture<>();
        playerFutures.put(uuid, playerFuture);

        // here, luck-perms might not have yet loaded the user, so we do it instead
        var manager = LuckPermsProvider.get().getUserManager();
        var user = manager.isLoaded(uuid) ? manager.getUser(uuid) : manager.loadUser(uuid).join();

        if (user == null) {
            logger.error("User not found in LuckPerms!");
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(Component.translatable("text.discordloom.disconnect.luckperms")));
            playerFuture.completeExceptionally(new RuntimeException("There was an error while trying to fetch your LuckPerms user, please try again later."));
            return;
        }


        // load the 'meta' node ( key-value load )
        var idNode = user.getNodes(NodeType.META)
                .stream()
                .filter(node -> node.getMetaKey().equals(LuckPermsMetadataKey))
                .findAny();

        // if we have a match...
        if (idNode.isPresent()) {
            // ask to just relay a packet back
            connection.sendLoginPluginMessage(DiscordLoomVelocity.RELAY_PACKET_ID, new byte[0], responseBody -> {
                try {
                    event.setResult(validateDiscord(event, idNode.get(), user));
                    if(!event.getResult().isAllowed()){
                        playerFuture.completeExceptionally(new RuntimeException("To play you must be connected to the Discord voice chat ‘Elysium: in game’."));
                        return;
                    }
                } catch (SerializationException e) {
                    logger.warn("Error in validateDiscord", e);
                    playerFuture.completeExceptionally(new RuntimeException("Error in validation Discord, try again later."));
                    return;
                }
                playerFuture.complete(null);
            });

        } else {
            // ask for the oauth, token as we don't have it
            logger.info("A user without a discordloom.id node tried to join!");

            var buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeUtf("https://discord.com/api/oauth2/authorize?client_id=" + config.getDiscordClientId() + "&redirect_uri=" + "http://localhost:" + config.getDiscordRedirectUriPort() + "/callback" + "&response_type=code&scope=identify");

            connection.sendLoginPluginMessage(DiscordLoomVelocity.QUERY_PACKET_ID, buf.array(), responseBody -> {
                FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(responseBody));
                String token = null;
                if(friendlyByteBuf.readBoolean()){
                    // read the oauth token from the packet
                    token = friendlyByteBuf.readUtf();
                }
                // not actually possible...
                if (token == null || token.isEmpty()) {
                    event.setResult(PreLoginEvent.PreLoginComponentResult.denied(Component.text("If you're seeing this, it means that you haven't installed the DiscordLoom mod. Please install it and try again.")));
                    playerFuture.completeExceptionally(new RuntimeException("If you're seeing this, it means that you haven't installed the DiscordLoom mod. Please install it and try again."));
                    return;
                }

                logger.info("Received code: {}", token);
                DiscordOAuth2Client oAuth2Client = DiscordOAuth2Client.createFromCode(
                        restClient,
                        AuthorizationCodeGrantRequest.builder()
                                .code(token)
                                .clientId(config.getDiscordClientId())
                                .clientSecret(config.getDiscordClientSecret())
                                .redirectUri("http://localhost:" + config.getDiscordRedirectUriPort() + "/callback")
                                .build()
                );
                var userId = Objects.requireNonNull(oAuth2Client.getCurrentUser().block()).id().toString();

                if (!config.getAllowMultipleMinecraftAccountsPerDiscordAccount()) {
                    var uuids = ServerDiscordManager.getPlayersFromDiscordId(userId);
                    if (!uuids.isEmpty()) {
                        var uuid_discord = uuids.stream().findFirst().get();
                        var user_discord = LuckPermsProvider.get()
                                .getUserManager()
                                .getUser(uuids.stream().findFirst().orElseThrow());

                        String username;

                        if (user_discord != null) {
                            username = user_discord.getUsername();
                        } else {
                            username = uuid_discord + " (unknown)";
                        }
                        event.setResult(PreLoginEvent.PreLoginComponentResult.denied(Component.translatable("text.discordloom.disconnect.relink", Component.text(username))));
                        playerFuture.completeExceptionally(new RuntimeException("This Discord account is already linked to "+username+" Minecraft account!"));
                        return;
                    }
                }

                var newNode = MetaNode.builder()
                        .key(LuckPermsMetadataKey)
                        .value(userId)
                        .build();;
                user.data().add(newNode);

                try {
                    event.setResult(validateDiscord(event, newNode, user));
                    if(!event.getResult().isAllowed()){
                        playerFuture.completeExceptionally(new RuntimeException("Not in the mandatory channel."));
                        return;
                    }
                } catch (SerializationException e) {
                    logger.warn("Error in validateDiscord", e);
                    playerFuture.completeExceptionally(new RuntimeException("Error in validation Discord, try again later."));
                }
                playerFuture.complete(null);
            });
        }
    }

    private PreLoginEvent.PreLoginComponentResult validateDiscord(PreLoginEvent event, MetaNode idNode, User user) throws SerializationException {
        var discordUser = jdaApi.retrieveUserById(idNode.getMetaValue()).complete();
        if (discordUser == null) {
            logger.error("Discord user not found!");
             return PreLoginEvent.PreLoginComponentResult.denied(Component.translatable("text.discordloom.disconnect.discord"));
        }
        Optional<String> nonMatchGuildOptional = config.getCheckForGuildsOnJoin()
                .stream()
                .filter(guildId -> {
                    var guild = jdaApi.getGuildById(guildId);
                    if (guild == null)
                        return false;

                    return guild
                            .retrieveMemberById(discordUser.getId())
                            .onErrorMap(throwable -> null)
                            .complete() == null;
                })
                .findAny();
        if (nonMatchGuildOptional.isPresent()) {
            logger.info("A user not in the required discord channel tried to join!");
           return PreLoginEvent.PreLoginComponentResult.denied(Component.translatable("text.discordloom.disconnect.channel.text"));
        }
        var hasMandatoryVCChannel = config.getMandatoryVCChannels().isEmpty() || (user != null && PermissionHelper.hasPermission(user, MOD_ID + ".bypass_vc"));
        if (!hasMandatoryVCChannel) {
            hasMandatoryVCChannel = config.
                    getMandatoryVCChannels()
                    .stream()
                    .anyMatch(mandatoryVCChannel -> {
                        var voiceChannel = jdaApi.getVoiceChannelById(mandatoryVCChannel);
                        if (voiceChannel == null)
                            return false;

                        return voiceChannel.getMembers()
                                .stream()
                                .anyMatch(member -> member.getId().equals(discordUser.getId()));
                    });

            if (!hasMandatoryVCChannel) {
                logger.info("User {} ({}) joined without being in a mandatory voice channel!", event.getUsername(), event.getUniqueId());
                return PreLoginEvent.PreLoginComponentResult.denied(Component.translatable("text.discordloom.disconnect.channel.voice"));
            }
        }
        // save stuff
        if (user != null) {
            user.getNodes()
                    .stream()
                    .filter(node -> node.getKey().startsWith("group." + MOD_ID + ":"))
                    .forEach(node -> user.data().remove(node));

            var mutualGuildsMap = discordUser.getMutualGuilds()
                    .stream()
                    .collect(Collectors.toMap(Guild::getId, Function.identity()));

            config.getSyncDiscordRolesOnJoin()
                    .stream()
                    .map(guildRole -> guildRole.split(":"))
                    .filter(guildRoleSplit -> mutualGuildsMap.containsKey(guildRoleSplit[0]))
                    .forEach(guildRoleSplit -> {
                        var member = mutualGuildsMap
                                .get(guildRoleSplit[0])
                                .getMemberById(discordUser.getId());

                        if (member != null) {
                            member.getRoles()
                                    .stream()
                                    .filter(role -> role.getId().equals(guildRoleSplit[1]))
                                    .findAny()
                                    .ifPresent(role -> {
                                        logger.info("User {} ({}) joined with {} role!", event.getUsername(), event.getUniqueId(), guildRoleSplit[1]);
                                        user.data().add(Node.builder("group." + MOD_ID + ":" + guildRoleSplit[1]).build());
                                    });
                        }
                    });

            LuckPermsProvider.get()
                    .getUserManager()
                    .saveUser(user);
        }

        logger.info("User {} ({}) joined with a discordloom.id node! ({})", event.getUsername(), event.getUniqueId(), idNode.getMetaValue());
        return PreLoginEvent.PreLoginComponentResult.allowed();
    }

}
