# Configuration du catalogue

Le wizard configure les langues et les catégories vidéo avant de télécharger les extensions. Le même menu est accessible depuis **Découvrir → Configurer le catalogue** et **Réglages → Général → Configurer le catalogue**.

La sélection recommandée privilégie les paquets et origines répertoriés dans `CatalogCuration`, choisis d'après la mesure des extensions (`ProviderSurveyInstrumentedTest`) : Movix, Frembed (yorik100 et le dépôt français) et Xalaflix. Seuls les catalogues français sont recommandés pour l'instant : les extensions VO et anime ne sont pas encore évaluées. AfterDark ne fournit aucun lien jouable et son site affiche une vérification pendant la lecture ; FsMirrorLol n'apportait aucun titre absent des autres et ses pages ne sont plus exploitables ; Coflix, Flemmix et FrenchAnime ne trouvaient plus aucun titre lors de la mesure du 26/10/2026. Ils ne sont plus recommandés. Les filtres de langues, catégories, statut et compatibilité restent appliqués. Décocher cette sélection donne accès aux autres extensions vidéo du catalogue. Les paquets intrusifs AllForU/All4U et les dépôts de jeux identifiés sont exclus ; les utilitaires ne sont pas installés automatiquement.

Le français et l’anglais sont proposés en tête, les autres langues proviennent des métadonnées des extensions. La langue d’une extension ne garantit pas celle des pistes audio : VF/VOST se choisit dans le lecteur.

**Mettre à jour (N)** installe ou actualise les paquets correspondant à la sélection, en conservant ceux déjà à jour. Les échecs sont isolés et signalés. Relancer l’action permet de réessayer. Le wizard ne supprime pas les fichiers existants. Les catégories qu’il ne gère pas et les autres préférences natives sont préservées.

## Architecture

- `CatalogIndex` utilise l’index public de MegaRepo et les dépôts déjà configurés, sans exécuter MegaProvider.
- `CatalogInstaller` borne les lectures simultanées et les délais. Les téléchargements utilisent `PluginManager`, les empreintes publiées et le remplacement atomique des fichiers.
- `CatalogSelection` sélectionne les paquets selon langue, type vidéo, statut et version d’API. Un même paquet miroir n’est installé qu’une fois, en privilégiant son origine déjà installée.
- `CatalogPreferences` ne sauvegarde que les choix de langues, de catégories du catalogue et de sélection recommandée.
- Le chargeur conserve les protections contre les paquets intrusifs connus. Les paquets provisionnés par le wizard restent soumis à sa sélection recommandée. Les installations manuelles suivent le chargement natif ; une installation explicite depuis le menu des extensions retire le paquet de la gestion automatique du wizard.
- Aucun changement de l’API des extensions, de leurs interfaces d’épisodes ou de `loadLinks()`.

## Découvrir et Voir plus

Découvrir agrège les films, séries et catégories vidéo disponibles. Les œuvres compatibles sont fusionnées avec leurs bindings d’origine. Les recommandations films/séries reposent sur l’historique et les éléments déjà vus sont écartés.

Chaque rubrique affiche 24 cartes dans l’accueil. **Voir plus** ouvre une grille sur plusieurs colonnes. **Charger davantage** demande les pages suivantes des sections ayant contribué à la rubrique, en conservant la déduplication et les bindings canoniques.

La navigation verticale de cette grille utilise le gestionnaire Android standard pour faire défiler les cartes hors écran à la télécommande. Le compteur indique le nombre de titres et un chargement réussi place le focus sur les nouvelles cartes. Les erreurs proposent une reprise ; une page répétée arrête sa section. Une action peut explorer jusqu’à trois lots de pages lorsqu’ils ne donnent que des variantes d’œuvres déjà affichées.

Les requêtes sont bornées à quatre fournisseurs simultanés, huit secondes par tentative, vingt pages par section et 500 cartes par rubrique. Fermer la grille annule ses requêtes. Un changement de profil ou de catalogue empêche les anciennes réponses de modifier la nouvelle page.

## Vérification

```bash
source ~/.config/cloudstream/env.sh
./gradlew :shared:jvmTest :app:testStableDebugUnitTest \
  :app:assembleStableDebug :app:assembleStableDebugAndroidTest \
  --max-workers=4 --console=plain
ANDROID_SERIAL=192.0.2.10:5555 tools/catalog-fixture.sh --skip-build
ANDROID_SERIAL=192.0.2.10:5555 tools/home-shelves-fixture.sh --skip-build
```

Les tests utilisent des films et séries fictifs et des profils temporaires. Le scénario catalogue vérifie aussi les métadonnées de dépôts, un téléchargement local à mauvaise empreinte, la conservation d’une archive valide, l’enregistrement d’une archive déjà téléchargée et le parcours initial langue → catalogue → disposition. Le scénario de grille vérifie le déplacement réel du focus au-delà des douze premières cartes, le défilement après chargement, le compteur, la reprise et la conservation des résultats à la réouverture.
