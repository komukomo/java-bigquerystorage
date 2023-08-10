#!/bin/bash

set -eu
CLASS_TO_EXEUTE=${CLASS_TO_EXECUTE}
GCP_PROJECT=${GCP_PROJECT}

echo GCP Project: ${GCP_PROJECT}
echo mainClass: com.example.bigquerystorage.${CLASS_TO_EXECUTE}

set -eux
mvn exec:java -Dexec.mainClass="com.example.bigquerystorage.${CLASS_TO_EXECUTE}"  -Dexec.args="${GCP_PROJECT}"
echo "Done"