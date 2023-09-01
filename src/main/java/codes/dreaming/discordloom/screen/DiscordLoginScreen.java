package codes.dreaming.discordloom.screen;

import codes.dreaming.discordloom.ClientLinkManager;
import codes.dreaming.discordloom.mixin.client.ConnectScreenAccessor;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.font.MultilineText;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.net.InetSocketAddress;

import static codes.dreaming.discordloom.DiscordLoom.LOGGER;

public class DiscordLoginScreen extends Screen {
	private final Screen parent;
	private final String oauthUrl;
	private int reasonHeight;
	private HttpServer server;
	private ButtonWidget linkButton;
	private MultilineText reasonFormatted;


	public DiscordLoginScreen( Screen parent, String oauthUrl ) {
        super( Text.translatable( "screen.discordloom.login.title" ) );
		this.parent = parent;
		this.oauthUrl = oauthUrl;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	/**
     * Initializes the functionality for linking the account to the server.
     * This sets up the necessary UI components and handles the link account process.
     */
	@Override
    public void init() {
        assert this.client != null;

		this.client.keyboard.setClipboard( this.oauthUrl );

		this.reasonFormatted = MultilineText.create(
			this.textRenderer,
			Text.translatable( "text.discordloom.connect.message" ),
			this.width - 50
		);
		this.reasonHeight = reasonFormatted.count() * this.textRenderer.fontHeight;

		this.linkButton = new ButtonWidget(
			this.width / 2 - 100,
			Math.min(this.height / 2 + reasonHeight / 2 + this.textRenderer.fontHeight, this.height - 30),
			200, 20,
			Text.translatable( "text.discordloom.connect.link_button" ),
			button -> this.startLinkingProcess()
		);
		this.addDrawableChild(this.linkButton);
		var cancelButton = new ButtonWidget(
			this.width / 2 - 100,
			Math.min( this.height / 2 + reasonHeight / 2 + this.textRenderer.fontHeight + 30, this.height - 30 ),
			200, 20,
			Text.of( "Cancel" ),
			this::onCancel
		);
		this.addDrawableChild( cancelButton );
    }

	public void render( MatrixStack matrices, int mouseX, int mouseY, float delta ) {
		this.renderBackground(matrices);
		int yPosition = this.height / 2 - this.reasonHeight / 2;

		drawCenteredText(
			matrices,
			this.textRenderer,
			this.title,
			this.width / 2,
			yPosition - 9 * 2,
			0xAAAAAA
		);
		this.reasonFormatted.drawCenterWithShadow(
			matrices,
			this.width / 2,
			yPosition
		);
		super.render(matrices, mouseX, mouseY, delta);
	}

    private void startLinkingProcess() {
        assert this.client != null;

        this.linkButton.active = false;
        this.linkButton.setMessage(Text.of("Linking..."));

        try {
			var port = ClientLinkManager.getPortFromOauthURL( this.oauthUrl );
			if ( port == null ) {
				this.linkButton.setMessage(Text.translatable("text.discordloom.connect.failed"));
				LOGGER.error("Error creating server for linking process: Failed to retrieve port from oauth url!");
				return;
			}

            this.server = HttpServer.create(new InetSocketAddress(port), 0);
            this.server.createContext("/callback", exchange -> {
                var response = "You can now close this tab and go back to the game";
                exchange.sendResponseHeaders(200, response.length());
                exchange.getResponseBody().write(response.getBytes());
                exchange.close();
                this.server.stop(0);
                ClientLinkManager.setOauthToken(exchange.getRequestURI().getQuery().split("=")[1]);

				// done with everything, reconnect to the server
				this.client.submit( () ->
					ConnectScreen.connect(
						( (ConnectScreenAccessor) this.parent ).getParent(),
						this.client,
						ClientLinkManager.getLastServerAddress(),
						this.client.getCurrentServerEntry()
					)
				);
            });
            this.server.setExecutor(null);
            this.server.start();
            Util.getOperatingSystem().open( this.oauthUrl );
            this.client.keyboard.setClipboard( this.oauthUrl );

            this.linkButton.setMessage(Text.translatable( "text.discordloom.connect.browser_clipboard" ));
        } catch (Exception e) {
            this.linkButton.setMessage(Text.of("text.discordloom.connect.failed" ));
            LOGGER.error("Error creating server for linking process: {}", e.getMessage());
        }

    }

	private void onCancel( ButtonWidget btn ) {
		assert this.client != null;
		if ( this.server != null )
			this.server.stop( 0 );

		// first set back to connection screen
		this.client.setScreen( this.parent );

		// then close the connection
		( (ConnectScreenAccessor) this.parent ).cancel( null );
	}
}
