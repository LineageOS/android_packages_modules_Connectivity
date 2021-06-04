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
 - Unit tests for Nearby module (both Java and native code).

## IDE setup

```sh
$ source build/envsetup.sh && lunch <TARGE>
$ cd packages/modules/Nearby
$ aidegen
# This will launch Intellij project for Nearby module.
```

## Build and Install

```sh
$ source build/envsetup.sh && lunch <TARGE>
$mmm -j packages/modules/Nearby/
$adb install -r {ANDROID_PRODUCT_OUT}/system/apex/com.android.nearby.apex
```