version = "1.6.1"
description = "Locally hide selected users: messages, mentions/replies, typing, notifications, incoming DM rings, call participants (grid auto-repositions) + auto local mute. Open a user's profile sheet for the Ghost button."

aliucord {
    changelog.set(
        """
        # 1.6.1
        * DM lista "N Members": popravljeno brojanje sakrivenih (čita recipients objekte, ne id-eve)
        # 1.6.0
        * Notifikacije (traka + in-app) od sakrivenih se ne prikazuju dok je app živ — važi za DM, grupe i server kanale (po scope-u)
        * "N Members" u DM listi umanjen za broj sakrivenih
        # 1.5.0
        * Nema više treptaja pri otvaranju — poruke sakrivenih se sakriju pri iscrtavanju umesto brisanja posle
        # 1.4.0
        * Serveri: sakriveni izbačeni iz member liste (+ ONLINE/OFFLINE brojevi umanjeni) i ispod voice kanala. Uključi "Serveri" scope u podešavanjima.
        # 1.3.0
        * Sakriveni izbačeni i iz liste članova grupe; "MEMBERS — N" pokazuje broj bez njih
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
