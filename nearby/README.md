# Nearby Mainline Module
This directory contains code for the AOSP Nearby mainline module.

##Directory Structure

`apex`
 - Files associated with the Nearby mainline module APEX.

`framework`
 - Contains client side APIs and AIDL files.

`jni`
 - JNI wrapper for invoking Android APIs from native code.

`native`
 - Native code implementation for nearby module services.

`service`
 - Server side implementation for nearby module services.

`tests`
 - Unit/Multi devices tests for Nearby module (both Java and native code).

## IDE setup

```sh
$ source build/envsetup.sh && lunch <TARGET>
$ cd packages/modules/Nearby
$ aidegen .
# This will launch Intellij project for Nearby module.
```
Note, the setup above may fail to index classes defined in proto, such
that all classes defined in proto shows red in IDE and cannot be auto-completed.
To fix, you can mannually add jar files generated from proto to the class path
as below.  First, find the jar file of presence proto with
```sh
ls $ANDROID_BUILD_TOP/out/soong/.intermediates/packages/modules/Connectivity/nearby/service/proto/presence-lite-protos/android_common/combined/presence-lite-protos.jar
```
Then, add the jar in IDE as below.
1. Menu: File > Project Structure
2. Select Modules at the left panel and select the Dependencies tab.
3. Select the + icon and select 1 JARs or Directories option.
4. Select the JAR file found above.
5. Click the OK button.
6. Restart the IDE to index.

## Build and Install

```sh
$ source build/envsetup.sh && lunch <TARGET>
$ m com.google.android.tethering.next deapexer
$ $ANDROID_BUILD_TOP/out/host/linux-x86/bin/deapexer decompress --input \
    ${ANDROID_PRODUCT_OUT}/system/apex/com.google.android.tethering.next.capex \
    --output /tmp/tethering.apex
$ adb install -r /tmp/tethering.apex
```
