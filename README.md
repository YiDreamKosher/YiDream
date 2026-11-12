# YiDream

Plateforme de gestion d'appareils Android filtrés. Ce dépôt contient l'app Android et le site **YiDream Suite** (configurateur Web ADB).

## Application Android

YiDream ouvre les onglets Accueil, Boutique, Sites et Réglages pour gérer les applications et les demandes de sites. Les informations de version et d’utilisation se trouvent dans Réglages. Le launcher musical est un projet distinct : [YZ MDM](https://github.com/YiDreamKosher/YZ-MDM). YiDream ne remplace pas l’écran d’accueil Android.

## Offline access and authorized updates (v6.2.0, Android version code 21)

Previously approved phones can open the workspace offline for up to seven days, within their approved session's expiry. Requests to open websites and installed applications are queued on the phone and submitted when connectivity returns. An administrator reviews them in Suite. A confirmed denial is never accepted as an offline session.

Under Appearance and navigation, the optional Quick Settings mode hides the launcher icon after the YiDream tile is added. Removing the tile restores the icon.

On an approved phone with a valid recovery key, activation disables ADB over USB and Wi-Fi. YiDream enforces Android's unknown-source restrictions and hides detected app stores. It clears its former blanket install restriction once these safeguards are active so it can apply verified updates to itself and to already installed approved catalog apps. The filter offers offline administrator removal using the existing recovery code, with limited attempts.

If a previous installation refuses the initial upgrade because of its blanket install restriction, an administrator can remove the filter with the existing recovery code and prepare the phone again in WebADB. Complete USB activation before checking the final ADB block on the phone. OEM-specific privileged installers should be checked on each supported device model.

## Device approval and remote rules (v6.1.0, Android version code 20)

Prepare the phone in [YiDream Suite](https://yidreamkosher.github.io/YiDream-Suite/admin/). WebADB saves a stable device ID after applying the USB configuration. The owner can approve the prepared device. Its client can be assigned later. An approved administrator enters the account contact details and issues a single-use activation code, which can be entered on the phone or sent through the connected USB session. The app opens after the server accepts the code. New users do not need an email sign-in code. Previously approved device sessions can continue.

The configured Android Device Owner hides or unhides managed packages and applies the existing device restrictions. Administrators can set individual package and domain rules from the device record. A device acknowledges the policy revision after processing it. YiDream checks for changes while open and schedules a background check when online. Android can delay background jobs. The last installed package restrictions persist offline.

The Sites screen sends requests to allow or block a domain and shows the administrator's decision. The YiDream reader opens approved HTTPS text and HTML pages. It disables scripts, forms and subresource loading, and checks every redirect. Its rules apply to pages opened in YiDream.

The account screen retains WhatsApp assistance. Settings includes the device ID and the app update screen. Device and session credentials are stored with Android Keystore. Install the matching Suite migration before publishing this APK, then update the Suite web console.

## Réglages et mises à jour Android (v5.10.0)

Les réglages sont organisés en cinq sections : **Apparence et navigation**, **Langues**, **Informations et conditions**, **Filtrage** et **Contacter l’administrateur**. La navigation peut conserver la barre d’onglets ou utiliser les raccourcis de l’accueil ; ce choix reste enregistré. Le bouton « Retour à l’accueil » et le retour Android permettent de revenir à l’accueil dans le second mode. Le logo est réservé à l’accueil. Le retrait du filtre conserve son code administrateur.

Le contact WhatsApp ouvre une conversation vers **+33 7 66 79 03 52** ; aucun message n’est envoyé automatiquement. Le contact par e-mail reste disponible. Les informations d’utilisation intégrées décrivent les fonctions de l’application ; elles ne remplacent pas les conditions contractuelles et la politique de confidentialité du service, à fournir par son responsable.

La boutique compare les versions installées aux versions **publiées dans le catalogue autorisé**, pour toutes ses applications et celles ajoutées ensuite. « Tout mettre à jour » installe ces APK après vérification de leur empreinte, du paquet, de la version et de la signature Android. L’état est rechargé après le traitement.

Pour les entrées dont la source est une fiche Google Play correspondant exactement au paquet, « Vérifier sur Google Play » ouvre uniquement l’application Google Play officielle. Elle décide de la dernière version compatible et de son installation. Ce bouton reste désactivé lorsque Play est absent, désactivé ou interdit par les restrictions de l’appareil ; YiDream ne désactive aucune protection et n’ouvre pas un navigateur en remplacement.

**Une fiche Google Play ne fournit pas à YiDream la dernière APK.** Sur un téléphone filtré sans accès Play, l’administrateur doit préparer puis publier chaque nouvelle APK autorisée depuis la console YiDream Suite. Le service d’import Play local existant nécessite son fournisseur configuré et démarré sur son ordinateur ; ce changement n’installe pas ce fournisseur et ne crée pas une synchronisation automatique en arrière-plan. Les cinq applications de base restent non installables tant que leurs fichiers ne sont pas publiés.

## Contenu

| Dossier | Rôle |
|---|---|
| `app/` | App Android (Device Owner, package `com.yidream.mdm`) : 3 actions pour l'utilisateur (mises à jour, apps autorisées, supprimer le filtre) |
| `webadb/` | Site YiDream Suite (nouveau design v3.6) + configurateur Web ADB réel, publié sur GitHub Pages |
| `.github/workflows/` | `build-apk.yml` compile l'APK ; `deploy-webadb.yml` compile l'APK, l'embarque dans le site et publie |
| `version.json`, `VERSIONING.md`, `HOW_TO_GET_APK.md` | Système de mise à jour de l'app (inchangé) |

## Ce que fait la v1.2

Fusion du design **Suite** (Admin, Android, iOS, Info) avec la logique du configurateur v1 :

- **YiDream Android** : connexion USB (WebUSB), installation/mise à jour de l'APK, activation Device Owner, blocage par catégories + apps supplémentaires, envoi de la configuration au téléphone.
- **Unlock & Apply 🔒** et **Advanced 🔒** (protégés par mot de passe admin) : code du jour, application de la config, retrait des restrictions, désinstallation.
- **Conditions d'utilisation** à accepter au premier chargement (recopie d'une phrase), consultables dans Info → Legal.
- **Verrou d'origine** : le site refuse de fonctionner hors des adresses listées dans `webadb/src/config.js`.
- Langues EN / FR / HE / YI, thème clair/sombre.
- **Design fusionné** : structure Suite (barre du haut, hero, cartes, fenêtres d'apps) + identité de l'ancien site (fond dégradé violet, accent violet, dock de badges colorés façon macOS, thème sombre violet).

## Nouveautés v1.3

- **Comptes revendeurs pour YiDream Admin** : à l'ouverture, un revendeur crée un compte (boutique, e-mail, mot de passe) ou se connecte. **C'est un compte local à ce navigateur (localStorage), pas un vrai compte cloud partagé** — poser cette base permet de brancher une vraie API plus tard sans revoir l'interface. Déconnexion et suppression du compte local dans Admin → Settings.
- **Plus aucun faux chiffre dans Admin** : le dashboard, les appareils, les clients, les groupes et le journal d'activité partent tous de zéro et reflètent uniquement ce que le revendeur ajoute lui-même (formulaires « + Nouveau client » / « + Ajouter un appareil »). Un appareil ajouté ici est une fiche locale, pas une vraie connexion MDM — c'est dit explicitement dans l'interface pour ne pas donner une fausse impression de suivi en direct.
- **Badges « OPEN APP » retirés** des 4 cartes de l'accueil.
- **Installation de l'APK simplifiée** : le site détecte et propose automatiquement l'APK qu'il héberge — plus besoin d'aller chercher un fichier sur l'ordinateur. Un lien discret « Utiliser un fichier local (avancé) » reste disponible si besoin (APK bêta personnalisé, ou site sans APK publié).
- **Logo agrandi** partout (barre du haut, fenêtres d'apps, panneau Admin, écran iOS) avec le nouveau fichier fourni, et utilisé comme favicon du site.

**YiDream Admin est donc un vrai mini-CRM local fonctionnel (comptes, clients, appareils, notifications), mais toujours sans backend ni synchronisation entre appareils/navigateurs — voir « À faire avant de vendre » ci-dessous. iOS reste une maquette.**

## Mise en ligne

1. Pousse ce dossier sur la branche `main` (dépôt `YiDreamKosher/YiDream` pour garder l'adresse actuelle).
2. `Settings → Pages → Source : GitHub Actions`.
3. Le workflow du site publie `https://yidreamkosher.github.io/YiDream/` en conservant l’APK de production déjà publié. Une modification Android déclenche son workflow de compilation séparé.

Si tu publies sous un autre nom de dépôt, ajoute son chemin dans `allowedSites` (`webadb/src/config.js`), sinon l'écran « Site non autorisé » s'affiche.

## ⚠️ À faire avant de vendre / distribuer

- **Les comptes YiDream Admin sont locaux à chaque navigateur** : un revendeur qui change d'ordinateur ne retrouve pas ses clients/appareils, et le mot de passe (simple SHA-256, sans sel) n'est pas une vraie authentification. Pour plusieurs employés ou plusieurs postes, il faudra un vrai backend (comptes, base de données, synchronisation) — c'est justement ce que cette interface est prête à recevoir.
- **Accès administrateur** : les informations d’accès sont confidentielles et doivent être transmises séparément aux seules personnes autorisées. Remplacez tout identifiant ou secret précédemment publié avant la mise en service.
- **Ce verrou n'est pas une vraie sécurité** : il tourne dans le navigateur et le hash est lisible dans le code du site. Il empêche un usage accidentel, pas un attaquant. Pour une offre professionnelle, il faudra une authentification côté serveur (comptes, sessions, isolation par revendeur).
- Le blocage par catégories est appliqué par l'app Android (Device Owner), pas par le site : le site ne fait qu'envoyer `yidream_config.json`.
- Compatibilité non garantie sur tous les téléphones (Device Owner, WebUSB, constructeur). À documenter au fur et à mesure des tests.
- Licence : aucune licence open source n'est incluse. Vérifie l'historique public du dépôt `YiDreamKosher/YiDream` avant une diffusion commerciale.

## Développement local

```bash
cd webadb
npm install
npm run dev     # http://localhost:5173 (le verrou d'origine est levé sur localhost)
```

Windows : WebUSB exige de remplacer le pilote USB du téléphone par WinUSB avec Zadig (détails affichés dans l'onglet Connect).

## Boutique et demandes de sites (v5.7)

L’application utilise des écrans Android natifs avec la palette YiDream d’origine. Dans YiDream Suite, « Boutique et demandes » permet de publier les APK approuvées, de générer un code d’association et de répondre aux demandes. L’utilisateur saisit ce code dans les réglages de l’application.

Avant mise en service, appliquer `20261029000000_mdm_catalog_and_site_requests.sql` du dépôt YiDream-Suite dans Supabase. Aucun rôle administrateur ni clé serveur ne doit être placé dans l’APK. Sans migration, l’interface indique que le service doit être activé ; elle ne simule aucun envoi réussi.

Une décision d’acceptation est transmise à l’utilisateur ; elle ne configure pas à elle seule un filtrage réseau par domaine. Ce filtrage doit être assuré par la solution réseau retenue.
