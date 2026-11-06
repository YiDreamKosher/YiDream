# Publication de YiDream

## Numéro de version

Pour chaque version, augmente `versionCode` et change `versionName` dans
`app/build.gradle` et `version.json`. La publication du site calcule le
SHA-256 de l'APK et le place dans le manifeste publié.

## Clé de signature Android

Une APK destinée à des mises à jour durables doit être signée avec la même clé
privée à chaque publication. Ne place jamais le fichier de clé dans le dépôt.

Dans les paramètres GitHub du dépôt, ajoute ces quatre Actions secrets :

- `YIDREAM_KEYSTORE_BASE64` : fichier keystore encodé en Base64
- `YIDREAM_STORE_PASSWORD`
- `YIDREAM_KEY_ALIAS`
- `YIDREAM_KEY_PASSWORD`

Garde une copie de sauvegarde du keystore et de ses mots de passe. Sans ces
secrets, le workflow compile et teste un APK de prévisualisation disponible
uniquement dans les artefacts Actions. Il ne déploie pas le site, ne remplace
pas l'APK ni le manifeste public et ne crée pas de version GitHub Release.
Une clé debug créée sur un nouveau runner peut différer de celle du précédent :
ces APK ne doivent pas être proposées comme mises à jour sur les téléphones.

Le certificat public de production est épinglé dans `signing-certificate.sha256`.
La publication vérifie la signature réelle de l'APK avec l'outil Android avant
de le diffuser ; modifier les secrets avec une autre clé ne suffit pas à
remplacer silencieusement cette identité.

La clé d'origine est nécessaire pour remplacer une APK déjà installée. Le
certificat public présent dans un ancien APK ne permet pas de reconstruire sa
clé privée. Conserve la configuration MDM et l'application existante pendant
le diagnostic ; ne désinstalle pas l'application pour contourner ce refus.

## Publication

Le workflow `Deploy YiDream client site and Android console` construit l'APK,
l'ajoute au site Web ADB et publie le site sur
[YiDream Pages](https://yidreamkosher.github.io/YiDream/). Quand les secrets de
signature sont présents, il crée aussi la GitHub Release correspondant à la version de l'APK.

Pour les changements du site ou de la console Web, `Deploy YiDream website with existing APK` réutilise le fichier de production, après vérification de son empreinte et de son certificat. Il conserve son numéro de version et ses octets. Les changements du code Android, de `version.json` ou du workflow Android lancent la compilation signée de l’application. Ces fichiers ne déclenchent pas la publication indépendante du site.

Le programme de mise à jour n'accepte que l'URL YiDream publiée et compare le
SHA-256 du fichier, le paquet, la version annoncée et la compatibilité de
signature avant l'installation. Il attend le résultat Android et vérifie la
version effectivement installée avant d'annoncer un succès. Android conserve
la décision finale, notamment lors d'une rotation de clé avec preuve valide.

