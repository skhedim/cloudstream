# Publication de CloudStream Skhedim

Le fork public est `skhedim/cloudstream`, branche principale `main`. `upstream` conserve le dépôt officiel. Les bibliothèques et namespaces restent ceux de CloudStream pour préserver l'API des extensions.

## Installation et mises à jour

Installer l'APK depuis [la dernière release](https://github.com/skhedim/cloudstream/releases/latest). Son nom est la version, par exemple `1.0.1.apk`. Le paquet de distribution est `com.lagradost.cloudstream3.skhedim`, nommé CloudStream Skhedim. Il cohabite avec l'application officielle et le paquet de développement `com.lagradost.cloudstream3.debug`.

La première installation est distincte : exporter une sauvegarde depuis l'ancienne application puis la restaurer dans celle-ci pour transférer l'historique et les réglages. Les prochaines mises à jour conservent les données de ce paquet grâce à sa signature permanente. L'autorisation Android d'installer depuis cette application peut être nécessaire.

Les deux mécanismes de mise à jour de CloudStream consultent exclusivement ce fork. Le mécanisme courant sélectionne l'APK dont le nom correspond au tag, vérifie le digest GitHub lorsqu'il est fourni, et compare les versions numériquement pour éviter les réinstallations et retours en arrière. Android vérifie aussi la signature de l'APK. Il n'y a qu'un canal de distribution, stable.

## CI

`.github/workflows/fork-release.yml` se déclenche à chaque push sur `main`, ou manuellement depuis Actions sur cette branche. Il vérifie l'ABI des extensions, exécute les tests JVM partagés et Android locaux, construit l'APK signé, le vérifie avec `apksigner`, puis publie une release avec `SHA256SUMS`. Les releases sont publiques, sans connexion nécessaire au téléchargement. Les anciens workflows de publication officiels sont réservés au dépôt officiel.

La version est `1.0.<github.run_number>`, le code Android `1000000 + github.run_number`, et le tag `v1.0.<github.run_number>`. Ne pas renommer ni remplacer ce workflow sans maintenir la progression du compteur. Une nouvelle exécution manuelle crée une nouvelle version ; relancer un job existant ne remplace pas une release publiée. Les publications sont sérialisées pour ne pas faire reculer la dernière release.

Les pull requests exécutent les contrôles et construisent un APK de développement, sans accès à la signature de distribution. Les tests sur appareil restent disponibles avec les scripts `tools/*fixture.sh` ; les tests CI de publication ne nécessitent pas d'émulateur.

## Signature

La clé privée n'est jamais dans Git. Les secrets Actions sont `FORK_KEYSTORE_BASE64`, `FORK_KEY_ALIAS`, `FORK_KEY_PASSWORD`, `FORK_STORE_PASSWORD`. Conserver une sauvegarde privée de la clé et de ses mots de passe : une autre clé ne peut pas mettre à jour une installation existante. Les identifiants optionnels des services tiers utilisent les secrets déjà reconnus par CloudStream (`SIMKL_CLIENT_ID`, `SIMKL_CLIENT_SECRET`, `TRAKT_CLIENT_ID`, `MDL_API_KEY`, `MAL_KEY`, `ANILIST_KEY`).

## Build local

JDK 17, Android SDK et plateforme indiquée dans `gradle/libs.versions.toml` sont nécessaires. Les builds de développement conservent le paquet et les scripts existants :

```sh
./gradlew :shared:jvmTest :app:testStableDebugUnitTest :app:assembleStableDebug
```

Pour construire une distribution signée, définir `SIGNING_STORE_FILE`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`, `SIGNING_STORE_PASSWORD` dans l'environnement privé, puis exécuter :

```sh
./gradlew :app:assembleStableRelease -PforkVersionName=1.0.0 -PforkVersionCode=1000000
```

Les versions locales par défaut (`1.0.0`) précèdent toutes les versions CI. Ne pas installer une version locale avec un code supérieur aux publications futures.
