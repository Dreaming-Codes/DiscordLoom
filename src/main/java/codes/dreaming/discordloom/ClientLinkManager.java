package codes.dreaming.discordloom;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.ServerAddress;
import org.jetbrains.annotations.NotNull;

@Environment(EnvType.CLIENT)
public class ClientLinkManager {
    private static ServerAddress lastServerAddress = null;

    private static String oauthToken = null;

    public static String getOAuthToken() {
        return oauthToken;
    }

    public static void setOauthToken( String oauthToken ) {
        ClientLinkManager.oauthToken = oauthToken;
    }

    /**
     * Extracts the port number from the OAuth URL's redirect_uri parameter.
     *
     * @return The port number extracted from the redirect_uri parameter, or null if the URL is null or does not contain a redirect_uri parameter.
     */
    public static Integer getPortFromOauthURL( @NotNull String url ) {
        var urlSplit = url.split("&");
        for ( var section : urlSplit ) {
            if ( section.contains( "redirect_uri" ) ) {
                var redirectSplit = section.split("=");
                return Integer.parseInt(redirectSplit[redirectSplit.length - 1].split(":")[2].split("/")[0]);
            }
        }
        return null;
    }

    public static ServerAddress getLastServerAddress() {
        return lastServerAddress;
    }

    /**
     * Sets the server address used for communication.
     * <p>
     * If the provided serverAddress parameter is different from the current server address, the code is reset to null.
     *
     * @param serverAddress The server address to be set.
     */
    public static void setLastServerAddress( ServerAddress serverAddress ) {
        if ( lastServerAddress != null && !lastServerAddress.equals(serverAddress) )
            setOauthToken(null);
        lastServerAddress = serverAddress;
    }
}
