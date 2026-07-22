version = "1.2.0"
description = "Locally hide selected users: messages, mentions/replies, typing, notifications, incoming DM rings, call participants (grid auto-repositions) + auto local mute. Open a user's profile sheet for the Ghost button."

aliucord {
    changelog.set(
        """
        # 1.2.0
        * Sakriveni se ne prikazuju ni na ringing/preview ekranu poziva (pre ulaska) — raspored se sam centrira
        # 1.1.0
        * Ispravljen auto-mute (toggleUserMuted iz Discord 126021)
        * Sakriveni se izbacuju iz poziva — grid se sam prerasporedi
        # 1.0.0
        * Initial release — port of the BetterDiscord GhostUsers plugin
        """.trimIndent(),
    )

    // Objavljuje se → Aliucord ume sam da se ažurira sa builds grane repo-a
    deploy.set(true)
}
