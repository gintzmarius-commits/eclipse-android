# Éclat — lecteur et gestionnaire de fichiers

Le compagnon d'Eclipse : **photos, vidéos, musique et fichiers**, avec la même
interface **Liquid Glass**. Les vidéos et la musique sont lues par le **moteur
de VLC** (libVLC, open source) : presque tous les formats, sous-titres, pistes
audio.

## Envoyer un fichier par NFC (comme AirDrop)

1. Ouvre une photo et **fais-la glisser vers le haut**. Pour un autre fichier :
   **appui long → « Partager via NFC »** (ou le bouton NFC dans le lecteur, ou
   « Partager » depuis n'importe quelle appli → Éclat).
2. Mets les deux téléphones **dos contre dos**.
3. Sur l'autre téléphone, une **bulle façon Dynamic Island** descend du haut de
   l'écran : « Galaxy de Léa veut t'envoyer une photo » → **Accepter**.

L'autre téléphone n'a **pas besoin d'avoir Éclat ouvert** : Android lit le
« tag » NFC et ouvre la bulle tout seul (Éclat doit seulement être installé).

### Et vers un iPhone ?

Oui, dans un sens : **Android → iPhone**. Un iPhone XS ou plus récent lit le
tag NFC du téléphone Android (écran allumé, sans appli) et affiche une
notification ; elle ouvre une page de téléchargement dans Safari, servie par
le téléphone qui envoie. Il faut que les deux soient **sur le même Wi-Fi**.

Ce qui n'est **pas possible** (limites d'Apple, pas d'Éclat) :
- **AirDrop** : le protocole est réservé aux appareils Apple ; une appli
  Android ne peut ni envoyer ni recevoir en AirDrop. (Seuls quelques Pixel
  récents le font, grâce à Google, dans Quick Share.)
- **iPhone → Android par NFC** : un iPhone ne peut pas se faire passer pour un
  tag NFC (Apple ne l'ouvre qu'aux applis de paiement).

### Comment ça marche

Le NFC ne sert qu'à **se trouver** (comme Android Beam autrefois) ; le fichier
passe par le **Wi-Fi**, beaucoup plus rapide.

- Le téléphone qui envoie devient un **tag NFC** (Host Card Emulation, tag NFC
  Forum Type 4) : `share/NfcTagService.java`, `share/Type4Tag.java`.
- Le tag contient un lien `http://<adresse>:<port>/eclat/<jeton secret>?n=…`
  (lu par Android **et** par l'iPhone), un enregistrement qui fait ouvrir
  Éclat sur Android, et — s'il n'y a pas de Wi-Fi — le nom et le mot de passe
  d'un **petit réseau Wi-Fi créé par le téléphone qui envoie**
  (`share/Ndef.java`).
- Ce téléphone sert le fichier, et seulement à qui connaît le jeton
  (`share/ShareServer.java`). Il sait quand l'autre demande, refuse, reçoit.
- Le téléphone qui reçoit affiche la bulle (`share/ReceiveActivity.java`,
  `assets/ui/island.html`) et enregistre le fichier dans
  `Pictures/Éclat`, `Movies/Éclat`, `Music/Éclat` ou `Download/Éclat`
  (`share/ReceiveService.java`).

## Le lecteur (moteur VLC)

Gestes comme dans VLC : glisser à gauche = luminosité, à droite = volume, de
côté = avancer/reculer, double tap sur un côté = ±10 s. Pistes audio et
sous-titres, vitesse, format d'image, rotation, verrouillage, image dans
l'image, reprise là où tu t'étais arrêté, musique en arrière-plan.

VLC est open source, mais l'appli VLC pour Android est très grosse (et en
Kotlin) : Éclat reprend **son moteur** (libVLC, la bibliothèque officielle
publiée par VideoLAN) et met dessus sa propre interface Liquid Glass.

## Fabriquer et tester

| | |
| --- | --- |
| `eclat/` | Projet Android (Gradle) |
| `eclat/VERSION` | Version d'Éclat |
| `eclat/tests/ShareTest.java` | Tests sur PC : lien, message NDEF, tag NFC lu comme un vrai téléphone, vrai transfert réseau |
| `scripts/eclat_smoke.sh` | Test sur émulateurs Android 8 à 16 : écrans, **réception complète d'un fichier « par NFC »** (le PC joue le téléphone qui envoie), lecteur VLC |
| `.github/workflows/eclat.yml` | Compile, teste, publie `Eclat.apk` |

Chaque modification d'`eclat/` sur `main` publie **Eclat.apk** dans les
Releases (version `eclat-v…`), si elle passe sur tous les émulateurs.
Signé avec la même clé publique qu'Eclipse (`signing/`).

Ce que l'émulateur ne peut pas tester : le NFC lui-même (il n'en a pas). Le
tag NFC est testé sur PC en le lisant exactement comme Android et iOS le
lisent ; un essai entre deux vrais téléphones reste à faire.

Licence : GPL v3. libVLC : LGPL 2.1+, © VideoLAN.
