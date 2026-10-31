#!/usr/bin/env python3
"""Build a reproducible ARMv7 TV APK using Rust, Android SDK/NDK and JDK (no Gradle)."""
import argparse
import os
import platform
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent
TV = ROOT / 'android-tv'
OUT = TV / 'build'

def run(*command, env=None):
    command = [str(x) for x in command]
    if os.name == 'nt':
        resolved = shutil.which(command[0], path=(env or os.environ).get('PATH'))
        if resolved:
            command[0] = resolved
    subprocess.run(command, cwd=ROOT, env=env, check=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--skip-rust', action='store_true')
    args = parser.parse_args()
    sdk = Path(os.environ['ANDROID_HOME'])
    ndk = Path(os.environ['ANDROID_NDK_ROOT'])
    java = Path(os.environ['JAVA_HOME']) / 'bin'
    build_tools = sdk / 'build-tools' / os.environ.get('ANDROID_BUILD_TOOLS', '36.0.0')
    android = sdk / 'platforms' / 'android-35' / 'android.jar'
    host = {'Windows': 'windows-x86_64', 'Darwin': 'darwin-x86_64'}.get(platform.system(), 'linux-x86_64')
    llvm = ndk / 'toolchains' / 'llvm' / 'prebuilt' / host / 'bin'
    env = os.environ.copy()
    env['CARGO_PROFILE_RELEASE_BUILD_OVERRIDE_STRIP'] = 'none'
    # Keep build-machine paths out of Rust panic strings and release binaries.
    remap = f'--remap-path-prefix={Path.home()}=/build --remap-path-prefix={ROOT}=/build/netodia'
    env['RUSTFLAGS'] = (env.get('RUSTFLAGS', '') + ' ' + remap).strip()
    env['PATH'] = str(java) + os.pathsep + str(Path.home()/'.cargo/bin') + os.pathsep + env['PATH']
    compiler = 'armv7a-linux-androideabi26-clang' + ('.cmd' if os.name == 'nt' else '')
    env['CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER'] = str(llvm/compiler)
    env['CC_armv7_linux_androideabi'] = env['CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER']
    env['AR_armv7_linux_androideabi'] = str(llvm/'llvm-ar')
    if not args.skip_rust:
        run('cargo', 'build', '-p', 'netodia-android', '--target', 'armv7-linux-androideabi', '--release', env=env)
    OUT.mkdir(exist_ok=True)
    classes = OUT / 'classes'
    if classes.exists(): shutil.rmtree(classes)
    classes.mkdir()
    run(build_tools/'aapt2','compile','--dir',TV/'res','-o',OUT/'resources.zip')
    run(build_tools/'aapt2','link','-o',OUT/'unsigned.apk','--manifest',TV/'AndroidManifest.xml','-I',android,OUT/'resources.zip')
    run(java/'javac','-encoding','UTF-8','-source','8','-target','8','-classpath',android,'-d',classes,*sorted((TV/'src').rglob('*.java')))
    run(build_tools/'d8','--min-api','26','--lib',android,'--output',OUT,*sorted(classes.rglob('*.class')),env=env)
    with zipfile.ZipFile(OUT/'unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as apk:
        apk.write(OUT/'classes.dex','classes.dex')
        apk.write(ROOT/'target/armv7-linux-androideabi/release/libnetodia_android.so','lib/armeabi-v7a/libnetodia_android.so')
        apk.write(ROOT/'LICENSE','assets/LICENSE')
        font_root = ROOT/'crates/netodia-ui/ui/assets/fonts'
        for name in ['MaterialSymbolsRounded.ttf', 'MaterialSymbolsRoundedFilled.ttf', 'NotoSansSC-VF.ttf']:
            apk.write(font_root/name, 'assets/fonts/' + name)
        for license in sorted((ROOT/'licenses').glob('*')):
            if license.is_file(): apk.write(license, 'assets/licenses/' + license.name)
    run(build_tools/'zipalign','-f','4',OUT/'unsigned.apk',OUT/'aligned.apk')
    key = TV / '.debug.keystore'
    if not key.exists():
        run(java/'keytool','-genkeypair','-keystore',key,'-storepass','android','-keypass','android','-alias','androiddebugkey','-dname','CN=Netodia TV Debug','-keyalg','RSA','-validity','10000')
    run(build_tools/'apksigner','sign','--v1-signing-enabled','true','--ks',key,'--ks-pass','pass:android','--out',OUT/'Netodia-TV-armv7-debug.apk',OUT/'aligned.apk',env=env)
    run(build_tools/'apksigner','verify','--verbose',OUT/'Netodia-TV-armv7-debug.apk',env=env)
    print(OUT/'Netodia-TV-armv7-debug.apk')

if __name__ == '__main__': main()
