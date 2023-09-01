package codes.dreaming.discordloom;

import codes.dreaming.discordloom.command.DiscordLoomCommand;
import codes.dreaming.discordloom.config.server.ServerConfig;
import codes.dreaming.discordloom.discord.ServerDiscordManager;
import codes.dreaming.discordloom.impl.BanImpl;
import codes.dreaming.discordloom.mixin.ServerLoginNetworkHandlerAccessor;
import com.mojang.authlib.GameProfile;
import eu.pb4.banhammer.api.PunishmentType;
import eu.pb4.banhammer.impl.BanHammerImpl;
import net.dv8tion.jda.api.entities.Guild;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.MetaNode;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;
import net.minecraft.util.crash.CrashException;
import net.minecraft.util.crash.CrashReport;
import org.apache.commons.lang3.NotImplementedException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static codes.dreaming.discordloom.DiscordLoom.*;
import static codes.dreaming.discordloom.discord.ServerDiscordManager.buildNodeMatcherWithDiscordId;

public class DiscordLoomServer implements DedicatedServerModInitializer {
	private static final Text NO_MOD_TEXT = Text.of( "If you're seeing this, it means that you haven't installed the DiscordLoom mod. Please install it and try again." );
	public static final ServerConfig SERVER_CONFIG = ServerConfig.createAndLoad();
	public static final String LuckPermsMetadataKey = MOD_ID + ":discordid";

	public static ServerDiscordManager DISCORD_MANAGER;

	public static PlayerManager PLAYER_MANAGER;

	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTED.register( DiscordLoomServer::serverStarted );

		ServerLoginConnectionEvents.QUERY_START.register( DiscordLoomServer::onLoginStart );
		ServerLoginNetworking.registerGlobalReceiver( QUERY_PACKET_ID, DiscordLoomServer::onQueryResponse );
		ServerLoginNetworking.registerGlobalReceiver( RELAY_PACKET_ID, DiscordLoomServer::onRelayResponse );

		CommandRegistrationCallback.EVENT.register( ( ( dispatcher, registry, selection ) -> dispatcher.register( DiscordLoomCommand.command() ) ) );
	}

	public static void serverStarted( MinecraftServer server ) {
		if ( server.isDedicated() ) {
			PLAYER_MANAGER = server.getPlayerManager();
			DISCORD_MANAGER = new ServerDiscordManager();
			List< Guild > missingGuilds = DISCORD_MANAGER.getMissingGuilds();
			if ( !missingGuilds.isEmpty() ) {
				missingGuilds.forEach( guild -> LOGGER.error( "Bot is not in required guild: {}", guild.getName() ) );
				throw new CrashException( new CrashReport( "Bot is not in all required guilds", new Exception() ) );
			}

			if ( SERVER_CONFIG.banDiscordAccount() && FabricLoader.getInstance().isModLoaded( "banhammer" ) ) {
				LOGGER.info( "BanHammer detected" );
				BanHammerImpl.PUNISHMENT_EVENT.register( ( ( punishmentData, b, b1 ) -> {
					if ( punishmentData.type != PunishmentType.BAN && punishmentData.type != PunishmentType.IP_BAN ) {
						return;
					}
					BanImpl.ban( List.of( new GameProfile( punishmentData.playerUUID, punishmentData.playerName ) ), punishmentData.reason, punishmentData.adminDisplayName.getString() );
				} ) );
			}
		}
	}

	private static void onLoginStart( ServerLoginNetworkHandler handler, MinecraftServer server, PacketSender sender, ServerLoginNetworking.LoginSynchronizer synchronizer ) {
		// get user's profile
		var profile = ( (ServerLoginNetworkHandlerAccessor) handler ).getProfile();

		if ( profile == null ) {
			LOGGER.error( "Profile is null!" );
			handler.disconnect( Text.translatable( "text.discordloom.disconnect.profile" ) );
			return;
		}


		// load luck-perms user data
		final Optional<User> luckUser;
		var uuid = profile.getId();
		if ( profile.getId() != null ) {
			// here, luck-perms might not have yet loaded the user, so we do it instead
			var manager = LuckPermsProvider.get().getUserManager();
			var user = manager.isLoaded( uuid ) ? manager.getUser( uuid ) : manager.loadUser( uuid ).join();

			if ( user == null ) {
				LOGGER.error( "User not found in LuckPerms!" );
				handler.disconnect( Text.translatable( "text.discordloom.disconnect.luckperms" ) );
				return;
			}

			luckUser = Optional.of( user );
		} else if ( server.isOnlineMode() ) {
			throw new NotImplementedException( "Access for offline mode players is not yet implemented" );
		} else {
			luckUser = Optional.empty();
		}

		// load the 'meta' node ( key-value load )
		var idNode = luckUser
			.flatMap( it ->
				it.getNodes( NodeType.META )
					.stream()
					.filter( node -> node.getMetaKey().equals( LuckPermsMetadataKey ) )
					.findAny()
			);

		// if we have a match...
		if ( idNode.isPresent() ) {
			// ask to just relay a packet back
			sender.sendPacket( RELAY_PACKET_ID, PacketByteBufs.empty() );
		} else {
			// ask for the oauth, token as we don't have it
			LOGGER.trace( "A user without a discordloom.id node tried to join!" );

			var buf = PacketByteBufs.create();
			buf.writeString( DISCORD_MANAGER.generateDiscordOauthUri() );

			sender.sendPacket( QUERY_PACKET_ID, buf );
		}
	}

	/**
	 * Called when the user is logging in for the first time, with the oauth token.<br/>
	 * In this we can't assume that the GameProfile has an uuid, luck-perms user or that they joined again.
	 */
	private static void onQueryResponse( MinecraftServer server, ServerLoginNetworkHandler handler, boolean understood, PacketByteBuf buf, ServerLoginNetworking.LoginSynchronizer synchronizer, PacketSender responseSender ) {
		if ( !understood )
			return;

		// get user's profile
		var profile = ( (ServerLoginNetworkHandlerAccessor) handler ).getProfile();

		if ( profile == null ) {
			LOGGER.error( "Profile is null!" );
			handler.disconnect( Text.translatable( "text.discordloom.disconnect.profile" ) );
			return;
		}

		// load luck-perms user data
		final Optional<User> luckUser;
		if ( profile.getId() != null ) {
			var user = LuckPermsProvider.get().getUserManager().getUser( profile.getId() );

			if ( user == null ) {
				LOGGER.error( "User not found in LuckPerms!" );
				handler.disconnect( Text.translatable( "text.discordloom.disconnect.luckperms" ) );
				return;
			}

			luckUser = Optional.of( user );
		} else if ( server.isOnlineMode() ) {
			LOGGER.error( "An offline-mode user tried to join while in online mode!" );
			handler.disconnect( Text.translatable( "text.discordloom.disconnect.profile.offline" ) );
			return;
		} else {
			luckUser = Optional.empty();
		}

		// read the oauth token from the packet
		var token = buf.readOptional( PacketByteBuf::readString ).orElse( null );

		// not actually possible...
		if ( token == null ) {
			handler.disconnect( NO_MOD_TEXT );
			return;
		}

		LOGGER.trace( "Received code: {}", token );
		var userId = DISCORD_MANAGER.doDiscordLink( token );

		if ( !SERVER_CONFIG.allowMultipleMinecraftAccountsPerDiscordAccount() ) {
			var uuids = ServerDiscordManager.getPlayersFromDiscordId( userId );
			if ( !uuids.isEmpty() ) {
				var uuid = uuids.stream().findFirst().get();
				var user = LuckPermsProvider.get()
					.getUserManager()
					.getUser( uuids.stream().findFirst().orElseThrow() );

				String username;

				if ( user != null ) {
					username = user.getUsername();
				} else {
					username = uuid + " (unknown)";
				}

				handler.disconnect( Text.translatable( "text.discordloom.disconnect.relink", username ) );
				return;
			}
		}

		var idNode = buildNodeMatcherWithDiscordId( userId );
		luckUser.ifPresent( user -> user.data().add( idNode ) );

		validateDiscord( handler, luckUser.orElse(null), idNode, profile );
	}

	/**
	 * Called when the user had already logged in before.<br/>
	 * In this we can assume three things:
	 * <ul>
	 *     <li>The player already joined once</li>
	 *     <li>LuckPerms has a LuckUser instance for them</li>
	 *     <li>It has already a validated discord userId</li>
	 * </ul>
 	 */
	private static void onRelayResponse( MinecraftServer server, ServerLoginNetworkHandler handler, boolean understood, PacketByteBuf buf, ServerLoginNetworking.LoginSynchronizer synchronizer, PacketSender responseSender ) {
		if ( !understood )
			return;

		var profile = ( (ServerLoginNetworkHandlerAccessor) handler ).getProfile();

		if ( profile == null ) {
			LOGGER.error( "Profile is null!" );
			handler.disconnect( Text.translatable( "text.discordloom.disconnect.profile" ) );
			return;
		}

		// load luck-perms user data
		final var luckUser = LuckPermsProvider.get().getUserManager().getUser( profile.getId() );

		if ( luckUser == null ) {
			LOGGER.error( "User not found in LuckPerms!" );
			handler.disconnect( Text.translatable( "text.discordloom.disconnect.luckperms" ) );
			return;
		}

		var idNode = luckUser
			.getNodes(NodeType.META)
			.stream()
			.filter(node -> node.getMetaKey().equals(LuckPermsMetadataKey))
			.findAny()
			.orElseThrow(); // must have it, as not first join

		validateDiscord( handler, luckUser, idNode, profile );
	}

	private static void validateDiscord( @NotNull ServerLoginNetworkHandler handler, @Nullable User luckUser, @NotNull MetaNode idNode, @NotNull GameProfile profile ) {
		var discordUser = DISCORD_MANAGER.getDiscordUserFromId( idNode.getMetaValue() );

		if ( discordUser == null ) {
			LOGGER.error( "Discord user not found!" );
			handler.disconnect( Text.translatable( "text.discordloom.disconnect.discord" ) );
			return;
		}

		var nonMatchGuildOptional = SERVER_CONFIG.checkForGuildsOnJoin()
			.stream()
			.filter( guildId -> {
				var guild = DISCORD_MANAGER.getJdaApi().getGuildById( guildId );
				if ( guild == null )
					return false;

				return guild
				   .retrieveMemberById( discordUser.getId() )
				   .onErrorMap( throwable -> null )
				   .complete() == null;
			})
			.findAny();

		if ( nonMatchGuildOptional.isPresent() ) {
			LOGGER.info( "A user not in the required discord channel tried to join!" );
			handler.disconnect( Text.translatable( "text.discordloom.disconnect.channel.text" ) );
			return;
		}

		var hasMandatoryVCChannel = SERVER_CONFIG.mandatoryVCChannels().isEmpty() || ( luckUser != null && PermissionHelper.hasPermission( luckUser, MOD_ID + ".bypass_vc" ) );

		if ( !hasMandatoryVCChannel ) {
			hasMandatoryVCChannel = SERVER_CONFIG
				.mandatoryVCChannels()
				.stream()
				.anyMatch( mandatoryVCChannel -> {
					var voiceChannel = DISCORD_MANAGER.getJdaApi().getVoiceChannelById( mandatoryVCChannel );
					if ( voiceChannel == null )
						return false;

					return voiceChannel.getMembers()
						.stream()
						.anyMatch( member -> member.getId().equals( discordUser.getId() ) );
				} );

			if ( !hasMandatoryVCChannel ) {
				LOGGER.info( "User {} ({}) joined without being in a mandatory voice channel!", profile.getName(), profile.getId() );
				handler.disconnect( Text.translatable( "text.discordloom.disconnect.channel.voice" ) );
				return;
			}
		}

		// save stuff
		if ( luckUser != null ) {
			luckUser
				.getNodes()
				.stream()
				.filter( node -> node.getKey().startsWith( "group." + MOD_ID + ":" ) )
				.forEach( node -> luckUser.data().remove( node ) );

			var mutualGuildsMap = discordUser.getMutualGuilds()
				.stream()
				.collect( Collectors.toMap( Guild::getId, Function.identity() ) );

			SERVER_CONFIG.syncDiscordRolesOnJoin()
				.stream()
				.map( guildRole -> guildRole.split( ":" ) )
				.filter( guildRoleSplit -> mutualGuildsMap.containsKey( guildRoleSplit[ 0 ] ) )
				.forEach( guildRoleSplit -> {
					var member = mutualGuildsMap
						.get( guildRoleSplit[ 0 ] )
						.getMemberById( discordUser.getId() );

					if ( member != null ) {
						member.getRoles()
							.stream()
							.filter( role -> role.getId().equals( guildRoleSplit[ 1 ] ) )
							.findAny()
							.ifPresent( role -> {
								LOGGER.info( "User {} ({}) joined with {} role!", profile.getName(), profile.getId(), guildRoleSplit[ 1 ] );
								luckUser.data().add( Node.builder( "group." + MOD_ID + ":" + guildRoleSplit[ 1 ] ).build() );
							} );
					}
				} );

			LuckPermsProvider.get()
				.getUserManager()
				.saveUser( luckUser );
		}

		LOGGER.info( "User {} ({}) joined with a discordloom.id node! ({})", profile.getName(), profile.getId(), idNode.getMetaValue() );
	}
}
