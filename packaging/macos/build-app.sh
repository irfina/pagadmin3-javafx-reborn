#!/bin/sh
# Build PgAdmin3-JavaFx-Reborn.app with the pgAdmin III icon. Needs JDK 24+ (jpackage).
#
# This is a local convenience for a real double-clickable Finder citizen, not a
# release artifact: the resulting .app is unsigned and unnotarized, so first
# launch needs right-click -> Open.
set -e
cd "$(dirname "$0")/../.."
mvn -q package -DskipTests
rm -rf target/app-image target/app-staging
mkdir -p target/app-staging
cp target/pgadmin3-javafx-reborn-1.0.0.jar target/app-staging/
jpackage --type app-image \
  --name "PgAdmin3-JavaFx-Reborn" \
  --app-version 1.0.0 \
  --input target/app-staging \
  --main-jar pgadmin3-javafx-reborn-1.0.0.jar \
  --main-class com.fxpgadmin.Launcher \
  --icon packaging/macos/pgAdmin3.icns \
  --dest target/app-image
echo "Built: target/app-image/PgAdmin3-JavaFx-Reborn.app"
