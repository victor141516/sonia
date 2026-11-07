"""Builds "Upload to Google Photos" for the Sony a6000 (Android 2.3.7, API 10) without Gradle (Windows).

ndk-build (mbedTLS + JNI, NDK r14b) -> javac (API 10 boot classpath) -> R8 (min-api 10) -> aapt2
-> zipalign -> apksigner (v1, required by API 10). Output: build/uploader.apk
"""
import os
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SDK = Path(os.environ.get("ANDROID_HOME") or Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk")
BUILD_TOOLS = SDK / "build-tools" / "37.0.0"
ANDROID_JAR = SDK / "platforms" / "android-10" / "android.jar"
# NDK r14b: the last NDK that still targets android-9 (r15+ forces API 14, which the camera's 2.3 lacks).
NDK = Path(os.environ.get("NDK_R14B") or ROOT.parent / "tools" / "android-ndk-r14b")
OUT = ROOT / "build"
KEYSTORE = ROOT / "debug.keystore"


def run(*cmd):
    print("$", " ".join(str(c) for c in cmd), flush=True)
    subprocess.run([str(c) for c in cmd], check=True)


def apk():
    run(NDK / "ndk-build.cmd", "-C", ROOT, "-j16")
    for name in ("classes", "dex"):
        shutil.rmtree(OUT / name, ignore_errors=True)
        (OUT / name).mkdir(parents=True)

    # Compile against the API 10 boot classpath so any newer Android/Java API fails here, not on the camera.
    run("javac", "-Xlint:-options", "-source", "8", "-target", "8", "-bootclasspath", ANDROID_JAR,
        "-d", OUT / "classes", *sorted((ROOT / "src" / "main" / "java").rglob("*.java")))
    app_jar = OUT / "app-classes.jar"
    with zipfile.ZipFile(app_jar, "w") as z:
        for f in (OUT / "classes").rglob("*.class"):
            z.write(f, f.relative_to(OUT / "classes").as_posix())

    run("java", "-cp", BUILD_TOOLS / "lib" / "d8.jar", "com.android.tools.r8.R8", "--release", "--min-api", "10",
        "--lib", ANDROID_JAR, "--pg-conf", ROOT / "proguard.pro", "--output", OUT / "dex", app_jar)

    base = OUT / "base.apk"
    resources = OUT / "res.zip"
    run(BUILD_TOOLS / "aapt2.exe", "compile", "--dir", ROOT / "res", "-o", resources)
    run(BUILD_TOOLS / "aapt2.exe", "link", "-o", base, "-I", ANDROID_JAR, "--manifest", ROOT / "AndroidManifest.xml",
        "-A", ROOT / "assets", resources)
    unaligned = OUT / "unaligned.apk"
    shutil.copy(base, unaligned)
    with zipfile.ZipFile(unaligned, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(OUT / "dex" / "classes.dex", "classes.dex")
        # The camera reports CPU_ABI armeabi-v7a / CPU_ABI2 armeabi; ship the ARMv7 build under both.
        arm = ROOT / "libs" / "armeabi-v7a" / "libsoniatls.so"
        z.write(arm, "lib/armeabi-v7a/libsoniatls.so")
        z.write(arm, "lib/armeabi/libsoniatls.so")
        # x86: only for the API 10 emulator used in tests.
        z.write(ROOT / "libs" / "x86" / "libsoniatls.so", "lib/x86/libsoniatls.so")
    aligned = OUT / "aligned.apk"
    run(BUILD_TOOLS / "zipalign.exe", "-f", "4", unaligned, aligned)

    if not KEYSTORE.exists():
        run("keytool", "-genkeypair", "-keystore", KEYSTORE, "-storepass", "android", "-keypass", "android",
            "-alias", "uploader", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
            "-dname", "CN=sonia uploader")
    final = OUT / "uploader.apk"
    run(BUILD_TOOLS / "apksigner.bat", "sign", "--ks", KEYSTORE, "--ks-pass", "pass:android",
        "--min-sdk-version", "10", "--out", final, aligned)
    run(BUILD_TOOLS / "apksigner.bat", "verify", "--min-sdk-version", "10", final)
    print(f"OK {final} ({final.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    os.chdir(ROOT)
    apk()
