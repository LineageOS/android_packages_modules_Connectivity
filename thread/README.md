# Thread

Bring the [Thread](https://www.threadgroup.org/) networking protocol to Android.

## Try Thread with Cuttlefish

```
# Get the code and go to the Android source code root directory

source build/envsetup.sh
lunch aosp_cf_x86_64_phone-trunk_staging-userdebug
m

launch_cvd
```

Open `https://localhost:8443/` in your web browser, you can find the Thread
demoapp (with the Thread logo) in the cuttlefish instance. Open it and have fun with Thread!
