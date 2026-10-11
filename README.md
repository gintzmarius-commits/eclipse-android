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

## Comment c'est fabriqué

Eclipse est basé sur [Moonlight Android](https://github.com/moonlight-stream/moonlight-android).
Ce dépôt ne contient que ce qu'Eclipse change :

| Fichier | Rôle |
| --- | --- |
| `upstream.txt` | Version de Moonlight utilisée (commit) |
| `VERSION` | Version d'Eclipse |
| `scripts/apply_eclipse.py` | Applique les changements d'Eclipse (nom, identifiant, couleurs, textes) |
| `eclipse/res/` | Fichiers qui remplacent ceux de Moonlight (icônes…) |
| `.github/workflows/build.yml` | Fabrique l'APK sur GitHub à chaque modification |

À chaque modification envoyée sur `main`, GitHub télécharge Moonlight,
applique les changements, compile l'APK et le publie dans **Releases**.

### Clé de signature

`signing/eclipse.keystore` signe chaque APK avec la même clé, pour que les
mises à jour s'installent par-dessus. Elle est publique, comme le reste du
dépôt : n'installe un APK Eclipse que depuis cette page.

## Licence

GPL v3, comme Moonlight (voir `LICENSE`). Merci à l'équipe Moonlight.
