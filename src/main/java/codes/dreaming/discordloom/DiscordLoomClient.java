package codes.dreaming.discordloom;

import codes.dreaming.discordloom.screen.DiscordLoginScreen;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static codes.dreaming.discordloom.DiscordLoom.*;

public class DiscordLoomClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
		ClientLoginNetworking.registerGlobalReceiver(QUERY_PACKET_ID, DiscordLoomClient::onQueryRequest);
		ClientLoginNetworking.registerGlobalReceiver(RELAY_PACKET_ID, DiscordLoomClient::onRelayRequest);
    }

	private static CompletableFuture<@Nullable PacketByteBuf> onQueryRequest(MinecraftClient client, ClientLoginNetworkHandler handler, PacketByteBuf buf, Consumer<GenericFutureListener<? extends Future<? super Void>>> listenerAdder) {
		var oauthUrl = buf.readString();

		var flag = new Object();
		client.executeSync( () -> client.setScreen( new DiscordLoginScreen( client.currentScreen, oauthUrl, flag ) ) );

		return CompletableFuture.supplyAsync( () -> {
			var send = PacketByteBufs.create();

			try {
				flag.wait();
			} catch ( InterruptedException e ) {
				throw new RuntimeException( e );
			}
			var code = ClientLinkManager.getCode();
			send.writeOptional(Optional.ofNullable(code), PacketByteBuf::writeString);

			LOGGER.info("Sent code: {}", code);
			ClientLinkManager.setCode(null);

			return send;
		});
	}

	private static CompletableFuture<@Nullable PacketByteBuf> onRelayRequest(MinecraftClient client, ClientLoginNetworkHandler handler, PacketByteBuf buf, Consumer<GenericFutureListener<? extends Future<? super Void>>> listenerAdder) {
		return CompletableFuture.completedFuture( PacketByteBufs.empty() );
	}
}
