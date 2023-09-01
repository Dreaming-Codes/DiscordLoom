package codes.dreaming.discordloom;

import net.luckperms.api.model.user.User;

public class PermissionHelper {
    public static boolean hasPermission(User user, String permission) {
        return user.getCachedData()
			.getPermissionData()
			.checkPermission(permission)
			.asBoolean();
    }
}
