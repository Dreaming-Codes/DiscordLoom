package codes.dreaming.discordloom.mixin.server;

import codes.dreaming.discordloom.PermissionHelper;
import codes.dreaming.discordloom.ServerDiscordManager;
import codes.dreaming.discordloom.config.server.Config;
import codes.dreaming.discordloom.mixinInterfaces.LoginHelloC2SPacketAccessor;
import com.mojang.authlib.GameProfile;
import dev.architectury.networking.NetworkManager;
import discord4j.common.util.Snowflake;
import io.netty.buffer.Unpooled;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.MetaNode;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;
import net.minecraft.network.packet.s2c.play.DisconnectS2CPacket;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.*;

import static codes.dreaming.discordloom.DiscordLoom.*;

@Mixin(ServerLoginNetworkHandler.class)
public abstract class ServerLoginNetworkHandlerMixin {

    @Shadow public abstract void disconnect(Text reason);

    @Shadow @Nullable GameProfile profile;

    @Shadow @Final public ClientConnection connection;


    @Shadow @Final private static Logger LOGGER;

    @Inject(method = "onHello", at = @At("TAIL"))
    private void onHelloMixin(LoginHelloC2SPacket packet, CallbackInfo ci) {
        LoginHelloC2SPacketAccessor mixin = (LoginHelloC2SPacketAccessor) (Object) packet;

        //noinspection ConstantValue
        assert mixin != null;

        String code = mixin.discordloom$getCode();

        if(code == null) return;
        LOGGER.trace("Received code: " + code);
        String userId = DISCORD_MANAGER.doDicordLink(code);

        if (!Config.CONFIG.allowMultipleMinecraftAccountsPerDiscordAccount.get()) {
            Set<UUID> uuids = ServerDiscordManager.getPlayersFromDiscordId(userId);
            if(!uuids.isEmpty()){
                UUID uuid = uuids.stream().findFirst().get();
                User user = LuckPermsProvider.get().getUserManager().getUser(uuids.stream().findFirst().get());

                String username;

                if(user != null){
                    username = user.getUsername();
                } else {
                    username = uuid.toString() + " (unknown)";
                }

                Text text = Text.of("This Discord account is already linked to " + username + " Minecraft account!");

                this.disconnect(text);
                return;
            }
        }

        ServerDiscordManager.link(userId, packet.profileId().get());
    }

    @Inject(method = "acceptPlayer", at = @At(value = "RETURN", target = "Lnet/minecraft/network/ClientConnection;send(Lnet/minecraft/network/Packet;Lnet/minecraft/network/PacketCallbacks;)V", ordinal = 1), cancellable = true)
    private void checkCanJoin(CallbackInfo ci) {
        if(this.profile == null) {
            LOGGER.error("Profile is null!");
            this.disconnect(Text.of("There was an error while trying to fetch your profile, please try again later."));
            ci.cancel();
            return;
        }

        User luckUser = LuckPermsProvider.get().getUserManager().getUser(profile.getId());

        if(luckUser == null) {
            LOGGER.error("User not found in LuckPerms!");
            this.disconnect(Text.of("There was an error while trying to fetch your LuckPerms user, please try again later."));
            ci.cancel();
            return;
        }

        Optional<MetaNode> idNode = luckUser.getNodes(NodeType.META).stream().filter(node -> node.getMetaKey().equals(LuckPermsMetadataKey)).findAny();

        if(idNode.isEmpty()) {
            LOGGER.trace("A user without a discordloom.id node tried to join!");
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            buf.writeString(DISCORD_MANAGER.generateDiscordOauthUri());
            NetworkManager.collectPackets(packet -> this.connection.send(packet), NetworkManager.serverToClient(), LINK_PACKET, buf);
            Text text = Text.of("If you're seeing this, it means that you haven't installed the DiscordLoom mod. Please install it and try again.");
            this.connection.send(new DisconnectS2CPacket(text));
            this.disconnect(text);
            ci.cancel();
            return;
        }

        ServerDiscordManager.DiscordMemberCache memberCache = new ServerDiscordManager.DiscordMemberCache();

        for (String guild : Config.CONFIG.checkForGuildsOnJoin.get()) {
            if (memberCache.getMember(idNode.get().getMetaValue(), guild).block() == null) {
                LOGGER.info("A user not in the required discord channel tried to join!");
                Text text = Text.of("You are not in the required discord channel to join this server.");
                this.connection.send(new DisconnectS2CPacket(text));
                this.disconnect(text);
                ci.cancel();
                return;
            }
        }


        boolean hasMandatoryVCChannel = Config.CONFIG.mandatoryVCChannels.get().isEmpty() || PermissionHelper.hasPermission(luckUser, MOD_ID + ".bypass_vc") || Config.CONFIG.mandatoryVCChannels.get()
                .stream()
                .anyMatch(mandatoryVCChannel -> DISCORD_MANAGER.isUserInVoiceChannel(Snowflake.of(idNode.get().getMetaValue()), mandatoryVCChannel));

        LOGGER.info("User " + hasMandatoryVCChannel + " in mandatory voice channel!");

        if (!hasMandatoryVCChannel) {
            LOGGER.info(String.format("User %s (%s) joined without being in a mandatory voice channel!", this.profile.getName(), this.profile.getId()));
            Text text = Text.of("You are not in a mandatory voice channel to join this server.");
            this.connection.send(new DisconnectS2CPacket(text));
            this.disconnect(text);
            ci.cancel();
            return;
        }

        luckUser.getNodes().stream().filter(node -> node.getKey().startsWith("group." + MOD_ID + ":")).forEach(node -> luckUser.data().remove(node));

        for (String guildRole : Config.CONFIG.syncDiscordRolesOnJoin.get()) {
            String[] guildRoleSplit = guildRole.split(":");
            if (DISCORD_MANAGER.userHasRoles(memberCache.getMember(idNode.get().getMetaValue(), guildRoleSplit[0]).block(), guildRoleSplit[1])) {
                LOGGER.info("User " + this.profile.getName() + " (" + this.profile.getId() + ") joined with " + guildRoleSplit[1] + " role!");
                luckUser.data().add(Node.builder("group." + MOD_ID + ":" + guildRoleSplit[1]).build());
            }
        }

        LuckPermsProvider.get().getUserManager().saveUser(luckUser);

        LOGGER.info("User " + this.profile.getName() + " (" + this.profile.getId() + ") joined with a discordloom.id node! (" + idNode.get().getMetaValue() + ")");
    }
}
