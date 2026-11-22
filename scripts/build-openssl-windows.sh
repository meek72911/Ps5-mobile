#!/usr/bin/env bash
# Builds OpenSSL with the Windows Android NDK from Git Bash.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
ndk=$(cygpath -u "$1")
make=$(cygpath -au "$2")
toolchain="$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin"
version=3.5.9
build="$root/third_party/build/openssl-$version"
install="$root/third_party/android-deps/arm64-v8a/openssl-$version"

mkdir -p "$build/bin" "$install"
cat > "$build/bin/aarch64-linux-android24-clang" <<'WRAPPER'
#!/usr/bin/env bash
exec clang --target=aarch64-linux-android24 "$@"
WRAPPER
chmod +x "$build/bin/aarch64-linux-android24-clang"
export PATH="$build/bin:$toolchain:$PATH"
export ANDROID_NDK_ROOT="$ndk"
perl_source="$root/third_party/src/perl"
if [[ -d "$perl_source/cpan" ]]; then
    for module in "$perl_source"/{cpan,dist}/*/lib; do
        [[ -d "$module" ]] || continue
        export PERL5LIB="$module${PERL5LIB:+:$PERL5LIB}"
    done
fi
unset CFLAGS CPPFLAGS CXXFLAGS LDFLAGS

cd "$build"
perl "$root/third_party/src/openssl-$version/Configure" android-arm64 \
    -D__ANDROID_API__=24 -fPIC no-shared no-tests no-ui-console \
    --prefix="$install" --libdir=lib
"$make" -j8 build_libs
"$make" install_dev
