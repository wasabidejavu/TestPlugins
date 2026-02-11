# Senpai Stream Cloudstream Extension

Extension pour Cloudstream permettant de visionner le contenu de [Senpai Stream](https://senpai-stream.hair/).

## Installation

### Via GitHub (Recommandé)

1.  Créez un nouveau **repository public** sur votre compte GitHub (ex: `SenpaiStream-Extension`).
2.  Poussez le contenu de ce dossier sur ce repository :
    ```bash
    git remote add origin https://github.com/VOTRE_NOM/SenpaiStream-Extension.git
    git branch -M main
    git push -u origin main
    ```
3.  Attendez que l'action **Build** se termine dans l'onglet "Actions" de votre repository GitHub.
4.  Une fois terminé, une branche `builds` sera créée (ou mise à jour) avec le fichier `.cs3`.
5.  Dans l'application **Cloudstream** :
    *   Allez dans **Paramètres** > **Extensions**.
    *   Cliquez sur **Ajouter un dépôt**.
    *   Entrez l'URL courte de votre repository (ex: `https://github.com/VOTRE_NOM/SenpaiStream-Extension`).
    *   L'extension "Senpai Stream" devrait apparaître. Installez-la.

### Installation Manuelle (Développeur)

Si vous avez un environnement Android/Java configuré :
1.  Ouvrez un terminal dans ce dossier.
2.  Exécutez : `.\gradlew.bat make`
3.  Le fichier `.cs3` sera généré dans le dossier `SenpaiStreamProvider/build/`.
4.  Transférez ce fichier sur votre téléphone et ouvrez-le avec Cloudstream.

## Fonctionnalités

*   Navigation (Films, Séries, Animés, Tendances)
*   Recherche
*   Lecture de vidéos (Contournement basique de la protection Cloudflare R2 via signature Livewire)

## Disclaimer

Cette extension est fournie à titre expérimental. Le site Senpai Stream utilise des protections (Cloudflare, Publicités) qui peuvent évoluer et bloquer l'accès à tout moment.
