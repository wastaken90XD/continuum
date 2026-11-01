# Continuum, standalone: minifyEnabled is false for the release build type, so R8 doesn't shrink
# or obfuscate at all right now and this file isn't actually exercised. Left in place (referenced
# from app/build.gradle's release buildType) only so turning minification on later has somewhere to
# add rules, rather than because anything here is currently needed.
