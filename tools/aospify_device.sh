#!/bin/bash

# Script to swap core networking modules in a GMS userdebug device to AOSP modules, by remounting
# the system partition and replacing module prebuilts. This is only to be used for local testing,
# and should only be used on userdebug devices that support "adb root" and remounting the system
# partition using overlayfs. The setup wizard should be cleared before running the script.
#
# Usage: aospify_device.sh [device_serial]
#
# Reset with "adb enable-verity", then wiping data (from Settings, or:
# "adb reboot bootloader && fastboot erase userdata && fastboot reboot").
# Some devices output errors like "Overlayfs teardown failed" on "enable-verity" but it still works
# (/mnt/scratch should be deleted).
#
# This applies to NetworkStack, CaptivePortalLogin, dnsresolver, tethering, cellbroadcast modules,
# which generally need to be preloaded together (core networking modules + cellbroadcast which
# shares its certificates with NetworkStack and CaptivePortalLogin)
#
# This allows device manufacturers to test their changes in AOSP modules, running them on their
# own device builds, before contributing contributing the patches to AOSP. After running this script
# once AOSP modules can be quickly built and updated on the prepared device with:
#   m NetworkStack
#   adb install --staged $ANDROID_PRODUCT_OUT/system/priv-app/NetworkStack/NetworkStack.apk \
#   adb reboot
# or for APEX modules:
#   m com.android.tethering deapexer
#   $ANDROID_HOST_OUT/bin/deapexer decompress --input $ANDROID_PRODUCT_OUT/system/apex/com.android.tethering.capex --output /tmp/decompressed.apex
#   adb install /tmp/decompressed.apex && adb reboot
#
# This has been tested on Android T and Android U Pixel devices. On recent (U+) devices, it requires
# setting a released target SDK (for example target_sdk_version: "34") in
# packages/modules/Connectivity/service/ServiceConnectivityResources/Android.bp before building.
set -e

function push_apex {
    local original_apex_name=$1
    local aosp_apex_name=$2
    if $ADB_CMD shell ls /system/apex/$original_apex_name.capex 1>/dev/null 2>/dev/null; then
        $ADB_CMD shell rm /system/apex/$original_apex_name.capex
        $ADB_CMD push $ANDROID_PRODUCT_OUT/system/apex/$aosp_apex_name.capex /system/apex/
    else
        rm -f /tmp/decompressed_$aosp_apex_name.apex
        $ANDROID_HOST_OUT/bin/deapexer decompress --input $ANDROID_PRODUCT_OUT/system/apex/$aosp_apex_name.capex --output /tmp/decompressed_$aosp_apex_name.apex
        if ! $ADB_CMD shell ls /system/apex/$original_apex_name.apex 1>/dev/null 2>/dev/null; then
            # Filename observed on some phones, even though it is not actually compressed
            original_apex_name=${original_apex_name}_compressed
        fi
        $ADB_CMD shell rm /system/apex/$original_apex_name.apex
        $ADB_CMD push /tmp/decompressed_$aosp_apex_name.apex /system/apex/$aosp_apex_name.apex
        rm /tmp/decompressed_$aosp_apex_name.apex
    fi
}

function push_apk {
    local app_type=$1
    local original_apk_name=$2
    local aosp_apk_name=$3
    $ADB_CMD shell rm /system/$app_type/$original_apk_name/$original_apk_name*.apk
    $ADB_CMD push $ANDROID_PRODUCT_OUT/system/$app_type/$aosp_apk_name/$aosp_apk_name.apk /system/$app_type/$original_apk_name/
}

NETWORKSTACK_AOSP_SEPOLICY_KEY="<signer signature=\"308205dc308203c4a003020102020900fc6cb0d8a6fdd16\
8300d06092a864886f70d01010b0500308181310b30090603550406130255533113301106035504080c0a43616c69666f72\
6e69613116301406035504070c0d4d6f756e7461696e20566965773110300e060355040a0c07416e64726f69643110300e0\
60355040b0c07416e64726f69643121301f06035504030c18636f6d2e616e64726f69642e6e6574776f726b737461636b30\
20170d3139303231323031343632305a180f34373537303130383031343632305a308181310b30090603550406130255533\
113301106035504080c0a43616c69666f726e69613116301406035504070c0d4d6f756e7461696e20566965773110300e06\
0355040a0c07416e64726f69643110300e060355040b0c07416e64726f69643121301f06035504030c18636f6d2e616e647\
26f69642e6e6574776f726b737461636b30820222300d06092a864886f70d01010105000382020f003082020a0282020100\
bb71f5137ff0b2d757acc2ca3d378e0f8de11090d5caf3d49e314d35c283b778b02d792d8eba440364ca970985441660f0b\
c00afbc63dd611b1bf51ad28a1edd21e0048f548b80f8bd113e25682822f57dab8273afaf12c64d19a0c6be238f3e66ddc7\
9b10fd926931e3ee60a7bf618644da3c2c4fc428139d45d27beda7fe45e30075b493ead6ec01cdd55d931c0a657e2e59742\
ca632b6dc3842a2deb7d22443c809291d7a549203ae6ae356582a4ca23f30f0549c4ec8408a75278e95c69e8390ad5280bc\
efaef6f1309a41bd9f3bfb5d12dca7e79ec6fd6848193fa9ab728224887b4f93e985ec7cbf6401b0e863a4b91c05d046f04\
0fe954004b1645954fcb4114cee1e8b64b47d719a19ef4c001cb183f7f3e166e43f56d68047c3440da34fdf529d44274b8b\
2f6afb345091ad8ad4b93bd5c55d52286a5d3c157465db8ddf62e7cdb6b10fb18888046afdd263ae6f2125d9065759c7e42\
f8610a6746edbdc547d4301612eeec3c3cbd124dececc8d38b20e73b13f24ee7ca13a98c5f61f0c81b07d2b519749bc2bcb\
9e0949aef6c118a3e8125e6ab57fce46bb091a66740e10b31c740b891900c0ecda9cc69ecb4f3369998b175106dd0a4ffd7\
024eb7e75fedd1a5b131d0bb2b40c63491e3cf86b8957b21521b3a96ed1376a51a6ac697866b0256dee1bcd9ab9a188bf4c\
ed80b59a5f24c2da9a55eb7b0e502116e30203010001a3533051301d0603551d0e041604149383c92cfbf099d5c47b0c365\
7d8622a084b72e1301f0603551d230418301680149383c92cfbf099d5c47b0c3657d8622a084b72e1300f0603551d130101\
ff040530030101ff300d06092a864886f70d01010b050003820201006a0501382fde2a6b8f70c60cd1b8ee4f788718c288b\
170258ef3a96230b65005650d6a4c42a59a97b2ddec502413e7b438fbd060363d74b74a232382a7f77fd3da34e38f79fad0\
35a8b472c5cff365818a0118d87fa1e31cc7ed4befd27628760c290980c3cc3b7ff0cfd01b75ff1fcc83e981b5b25a54d85\
b68a80424ac26015fb3a4c754969a71174c0bc283f6c88191dced609e245f5938ffd0ad799198e2d0bf6342221c1b0a5d33\
2ed2fffc668982cabbcb7d3b630ff8476e5c84ac0ad37adf9224035200039f95ec1fa95bf83796c0e8986135cee2dcaef19\
0b249855a7e7397d4a0bf17ea63d978589c6b48118a381fffbd790c44d80233e2e35292a3b5533ca3f2cc173f85cf904adf\
e2e4e2183dc1eba0ebae07b839a81ff1bc92e292550957c8599af21e9c0497b9234ce345f3f508b1cc872aa55ddb5e773c5\
c7dd6577b9a8b6daed20ae1ff4b8206fd9f5c8f5a22ba1980bef01ae6fcb2659b97ad5b985fa81c019ffe008ddd9c8130c0\
6fc6032b2149c2209fc438a7e8c3b20ce03650ad31c4ee48f169777a0ae182b72ca31b81540f61f167d8d7adf4f6bb2330f\
f5c24037245000d8172c12ab5d5aa5890b8b12db0f0e7296264eb66e7f9714c31004649fb4b864005f9c43c80db3f6de52f\
d44d6e2036bfe7f5807156ed5ab591d06fd6bb93ba4334ea2739af8b41ed2686454e60b666d10738bb7ba88001\">\
<seinfo value=\"network_stack\"\/><\/signer>"

DEVICE=$1
ADB_CMD="adb -s $DEVICE"

if [ -z "$DEVICE" ]; then
    echo "Usage: aospify_device.sh [device_serial]"
    exit 1
fi

if [ -z "$ANDROID_BUILD_TOP" ]; then
    echo "Run build/envsetup.sh first to set ANDROID_BUILD_TOP"
    exit 1
fi

if ! $ADB_CMD wait-for-device shell pm path com.google.android.networkstack 1>/dev/null 2>/dev/null; then
    echo "This device is already not using GMS modules"
    exit 1
fi

read -p "This script is only for test purposes and highly likely to make your device unusable. \
Continue ? <y/N>" prompt
if [[ $prompt != "y" ]]
then
    exit 0
fi

cd $ANDROID_BUILD_TOP
source build/envsetup.sh
lunch aosp_arm64-trunk_staging-userdebug
m NetworkStack CaptivePortalLogin com.android.tethering com.android.cellbroadcast \
    com.android.resolv deapexer \
    out/target/product/generic_arm64/system/etc/selinux/plat_mac_permissions.xml \
    out/target/product/generic_arm64/system/etc/permissions/com.android.networkstack.xml

$ADB_CMD root
$ADB_CMD remount
$ADB_CMD reboot

echo "Waiting for boot..."
until [[ $($ADB_CMD wait-for-device shell getprop sys.boot_completed) == 1 ]]; do
    sleep 1;
done

$ADB_CMD root
$ADB_CMD remount

push_apk priv-app NetworkStackGoogle NetworkStack
push_apk app CaptivePortalLoginGoogle CaptivePortalLogin
push_apex com.google.android.tethering com.android.tethering
push_apex com.google.android.cellbroadcast com.android.cellbroadcast
push_apex com.google.android.resolv com.android.resolv

# Replace the network_stack key used to set its sepolicy context
rm -f /tmp/pulled_plat_mac_permissions.xml
$ADB_CMD pull /system/etc/selinux/plat_mac_permissions.xml /tmp/pulled_plat_mac_permissions.xml
sed_replace='s/<signer signature="[0-9a-fA-F]+"><seinfo value="network_stack"\/><\/signer>/'$NETWORKSTACK_AOSP_SEPOLICY_KEY'/'
sed -E "$sed_replace" /tmp/pulled_plat_mac_permissions.xml |
    $ADB_CMD shell 'cat > /system/etc/selinux/plat_mac_permissions.xml'
rm /tmp/pulled_plat_mac_permissions.xml

# Update the networkstack privapp-permissions allowlist
rm -f /tmp/pulled_privapp-permissions.xml
networkstack_permissions=/system/etc/permissions/GoogleNetworkStack_permissions.xml
if ! $ADB_CMD shell ls $networkstack_permissions 1>/dev/null 2>/dev/null; then
    networkstack_permissions=/system/etc/permissions/privapp-permissions-google.xml
fi

$ADB_CMD pull $networkstack_permissions /tmp/pulled_privapp-permissions.xml

# Remove last </permission> line, and the permissions for com.google.android.networkstack
sed -nE '1,/<\/permissions>/p' /tmp/pulled_privapp-permissions.xml \
    | sed -E '/com.google.android.networkstack/,/privapp-permissions/d' > /tmp/modified_privapp-permissions.xml
# Add the AOSP permissions and re-add the </permissions> line
sed -nE '/com.android.networkstack/,/privapp-permissions/p' $ANDROID_PRODUCT_OUT/system/etc/permissions/com.android.networkstack.xml \
    >> /tmp/modified_privapp-permissions.xml
echo '</permissions>' >> /tmp/modified_privapp-permissions.xml

$ADB_CMD push /tmp/modified_privapp-permissions.xml $networkstack_permissions

rm /tmp/pulled_privapp-permissions.xml /tmp/modified_privapp-permissions.xml

echo "Done modifying, rebooting"
$ADB_CMD reboot