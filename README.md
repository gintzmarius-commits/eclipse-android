# Eclipse pour Android

Joue sur ton téléphone aux jeux de ton PC. Eclipse pour Android va avec
**Eclipse pour PC**, qui installe et règle Sunshine tout seul.

## Installer

1. Sur ton téléphone, ouvre la page **Releases** de ce dépôt
   (ou directement `https://github.com/gintzmarius-commits/eclipse-android/releases/latest/download/Eclipse.apk`).
2. Télécharge **Eclipse.apk** et ouvre-le.
3. Si Android le demande, autorise l'installation depuis ton navigateur
   (« Sources inconnues »).

Eclipse s'installe **à côté** de Moonlight : les deux peuvent cohabiter.
Chaque nouvelle version s'installe par-dessus la précédente.

## Connecter un PC (version 2.0)

1. Sur le PC, ouvre Eclipse et clique **Connecter un appareil** : un QR code s'affiche.
2. Dans Eclipse sur le téléphone, touche **Scanner le QR code** et vise l'écran.

C'est tout : le PC accepte le téléphone tout seul. Sans QR code (Eclipse 1.x
sur le PC, ou Sunshine seul), touche **Connecter** sur la carte du PC et tape
sur le PC le code à 4 chiffres affiché par le téléphone.

## Éclat : lecteur et fichiers (avec envoi par NFC)

Ce dépôt contient aussi **Éclat**, un lecteur de photos, vidéos et musique et
un gestionnaire de fichiers, avec la même interface Liquid Glass et le moteur
de VLC. Il envoie un fichier à un autre téléphone **par NFC, comme AirDrop**.
Voir [`eclat/README.md`](eclat/README.md). Son APK est publié dans les
Releases sous le nom **Eclat.apk** (versions `eclat-v…`).

## Comment c'est fabriqué

Eclipse est basé sur [Moonlight Android](https://github.com/moonlight-stream/moonlight-android).
Ce dépôt ne contient que ce qu'Eclipse change :

| Fichier | Rôle |
| --- | --- |
| `upstream.txt` | Version de Moonlight utilisée (commit) |
| `VERSION` | Version d'Eclipse |
| `scripts/apply_eclipse.py` | Applique les changements d'Eclipse (nom, identifiant, couleurs, textes) |
| `eclipse/res/` | Fichiers qui remplacent ceux de Moonlight (icônes…) |
| `eclipse/java/`, `eclipse/assets/` | L'écran Eclipse (Liquid Glass) et la connexion par QR code |
| `tests/` | Tests lancés avant chaque compilation |
| `.github/workflows/build.yml` | Fabrique l'APK sur GitHub à chaque modification |

À chaque modification envoyée sur `main`, GitHub teste, télécharge Moonlight,
applique les changements, compile l'APK et le publie dans **Releases**.
Les branches `dev/…` font une compilation d'essai, en brouillon privé.

### Clé de signature

`signing/eclipse.keystore` signe chaque APK avec la même clé, pour que les
mises à jour s'installent par-dessus. Elle est publique, comme le reste du
dépôt : n'installe un APK Eclipse que depuis cette page.

## Licence

GPL v3, comme Moonlight (voir `LICENSE`). Merci à l'équipe Moonlight.
