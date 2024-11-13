#!/bin/bash

echo "=============================="
echo "      Clean the project       "
echo "=============================="
pushd ../../server
./gradlew clean
popd

# Uncomment this section if image is not available in the docker registry
#echo "===================================="
#echo "      Build Hadoop 3.3.6 image      "
#echo "===================================="
#pushd hadoop
#docker build -f Dockerfile -t cloud-hub.adsw.io/library/pxf-hadoop:3.3.6 .
#popd

#echo "===================================="
#echo "      Build Vault image      "
#echo "===================================="
docker build -f ./vault/Dockerfile -t hub.adsw.io/pxf/pxf-vault-test:it .

echo "=============================="
echo "Build PXF image for automation"
echo "=============================="
# Supported base images:
# centos: cloud-hub.adsw.io/library/gpdb6_regress:adb-6.x-dev
# ubuntu: cloud-hub.adsw.io/library/gpdb6_u22:adb-6.x-dev
# default image is ubuntu
# To build centos image - run the command: GPDB_IMAGE=hub.adsw.io/library/gpdb6_regress:adb-6.x-dev bash build-images.sh
pushd ../..
docker build -t gpdb6_pxf_automation:it --build-arg "GPDB_IMAGE=${GPDB_IMAGE:-cloud-hub.adsw.io/library/gpdb6_u22:adb-6.x-dev}" -f automation/arenadata/Dockerfile .
popd
