# YiDream

Plateforme de gestion d'appareils Android filtrés. Ce dépôt contient l'app Android et le site **YiDream Suite** (configurateur Web ADB).

## Application Android

YiDream ouvre directement les onglets Accueil, Boutique, Réglages et À propos pour gérer le filtre. Le launcher musical est un projet distinct : [YZ MDM](https://github.com/YiDreamKosher/YZ-MDM). YiDream ne remplace pas l’écran d’accueil Android.

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
