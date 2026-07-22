version = "1.0.0"
description = "Locally hide selected users in group DMs: their messages, messages that mention/reply to them, typing, notifications, incoming DM rings + auto local mute. Open a user's profile sheet for the Ghost button."

aliucord {
    changelog.set(
        """
        # 1.0.0
        * Initial release — port of the BetterDiscord GhostUsers plugin
        """.trimIndent(),
    )

    // Lični plugin — ne objavljuje se u javne Aliucord repoe
    deploy.set(false)
}
