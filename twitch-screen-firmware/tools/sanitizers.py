"""PlatformIO separates compiler and linker options; both need sanitizer flags."""
Import("env")
env.Append(LINKFLAGS=["-fsanitize=address,undefined", "-no-pie"])
