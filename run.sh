#!/bin/bash
export JAVA_HOME=/Users/james/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home
cd /Users/james/projects/xnat_dicom_router/java-app
$JAVA_HOME/bin/java -jar build/libs/dicom-router-2.0.0.jar --config ../config.yaml start --admin-port 9090
