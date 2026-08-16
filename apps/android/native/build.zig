// Adapted from chuchu (MIT) — zig-src/build.zig
//
// Modified: everything that is not the terminal core is gone. chuchu is an SSH
// client and its build linked libssh2, OpenSSL and mosh; our daemon already
// owns the PTY, so the dependencies left are ghostty's headless `ghostty-vt`
// module and zigimg, which decodes kitty-graphics PNG payloads (MIT, no
// transitive deps). Also dropped: the armeabi-v7a / x86 targets (our minSdk
// is 31 and we ship 64-bit), the jniLibs copy step (Gradle owns that), the
// OpenSSL test step, and the fmt step.
//
// LICENSING INVARIANT — do not "upgrade" this to ghostty's `ghostty` module.
// `ghostty-vt` resolves to src/lib_vt.zig + unicode tables + uucode (all MIT),
// with oniguruma explicitly disabled and SIMD off. Ghostty's LGPL-carrying
// dependencies (libintl, gtk4-layer-shell, glib/gobject, plasma-wayland-
// protocols) are all declared `.lazy = true` in its build.zig.zon and are
// never fetched on this path. Depending on the full `ghostty` module would
// pull them in and break the project's no-copyleft rule. See CREDITS.md.

const std = @import("std");
const builtin = @import("builtin");
const ndk = @import("src/ndk.zig");

/// 64-bit only: arm64-v8a is every real device, x86_64 is the emulator.
const build_targets: []const std.Target.Query = &.{
    .{ .cpu_arch = .aarch64, .os_tag = .linux, .abi = .android, .android_api_level = 31 },
    .{ .cpu_arch = .x86_64, .os_tag = .linux, .abi = .android, .android_api_level = 31 },
};

fn ndkPrebuiltTag() []const u8 {
    const os_part = switch (builtin.os.tag) {
        .macos => "darwin",
        .linux => "linux",
        .windows => "windows",
        else => @panic("Unsupported host OS for Android NDK prebuilt toolchain"),
    };

    // The NDK only ships x86_64 prebuilt tools for macOS (Apple Silicon runs
    // them under Rosetta 2).
    const arch_part = switch (builtin.cpu.arch) {
        .x86_64 => "x86_64",
        .aarch64 => if (builtin.os.tag == .macos) "x86_64" else "aarch64",
        else => @panic("Unsupported host architecture for Android NDK prebuilt toolchain"),
    };

    return std.fmt.comptimePrint("{s}-{s}", .{ os_part, arch_part });
}

/// ANDROID_NDK_HOME may point either at an NDK or at the SDK's `ndk/` parent
/// holding one version directory. Accept both.
fn resolveNdkHome(b: *std.Build, ndk_root: []const u8) []const u8 {
    if (ndk_root.len == 0) return ndk_root;

    const toolchains_path = b.pathJoin(&.{ ndk_root, "toolchains", "llvm" });
    std.fs.cwd().access(toolchains_path, .{}) catch {
        var dir = std.fs.cwd().openDir(ndk_root, .{ .iterate = true }) catch return ndk_root;
        defer dir.close();

        var iter = dir.iterate();
        while (iter.next() catch null) |entry| {
            if (entry.kind != .directory) continue;
            return b.pathJoin(&.{ ndk_root, entry.name });
        }
        return ndk_root;
    };

    return ndk_root;
}

fn buildNativeLibrary(
    b: *std.Build,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
) *std.Build.Step.Compile {
    // simd=false keeps highway/simdutf out of the graph — smaller .so, and one
    // less pair of licences to carry for a gain we cannot measure on a phone.
    const ghostty_dep = b.dependency("ghostty", .{
        .target = target,
        .optimize = optimize,
        .simd = false,
    });
    // PNG decode for kitty graphics. MIT, no transitive dependencies.
    const zigimg_dep = b.dependency("zigimg", .{
        .target = target,
        .optimize = optimize,
    });

    const ndk_root = b.graph.env_map.get("ANDROID_NDK_HOME") orelse
        b.graph.env_map.get("ANDROID_NDK_ROOT") orelse
        @panic("set ANDROID_NDK_HOME (or ANDROID_NDK_ROOT) to the Android NDK");
    const ndk_home = resolveNdkHome(b, ndk_root);

    const android_target = ndk.getAndroidTriple(target.result) catch {
        std.debug.panic("target must be Android", .{});
    };
    std.debug.assert(target.result.os.tag == .linux);
    const android_api_version: u32 = target.result.os.version_range.linux.android;

    const ndk_sysroot = b.pathJoin(&.{
        ndk_home,
        "toolchains",
        "llvm",
        "prebuilt",
        ndkPrebuiltTag(),
        "sysroot",
    });

    // Zig has no native bionic support (ziglang/zig#23906, open, milestone
    // post-1.0), so Android builds ride an explicit NDK sysroot handed over as
    // a libc config file. This is the workaround, not an optimisation.
    const libc_config = ndk.createLibC(
        b,
        android_target,
        android_api_version,
        ndk_sysroot,
    );

    const include_dir = b.pathJoin(&.{ ndk_sysroot, "usr", "include" });
    const target_include_dir = b.pathJoin(&.{ include_dir, android_target });

    const root_module = b.createModule(.{
        .root_source_file = b.path("src/bridge/root.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .strip = true,
        .unwind_tables = .none,
        .omit_frame_pointer = true,
    });
    root_module.addIncludePath(b.path("src/bridge"));
    root_module.addImport("ghostty-vt", ghostty_dep.module("ghostty-vt"));
    root_module.addImport("zigimg", zigimg_dep.module("zigimg"));

    const lib = b.addLibrary(.{
        .linkage = .dynamic,
        .name = "marmalade_term",
        .root_module = root_module,
    });
    lib.link_function_sections = true;
    lib.link_data_sections = true;
    lib.link_gc_sections = true;
    lib.link_eh_frame_hdr = false;
    lib.lto = .thin;

    lib.addIncludePath(.{ .cwd_relative = include_dir });
    lib.addIncludePath(.{ .cwd_relative = target_include_dir });

    const api_dir = b.fmt("{d}", .{android_api_version});
    const lib_dir = b.pathJoin(&.{ ndk_sysroot, "usr", "lib", android_target, api_dir });
    lib.addLibraryPath(.{ .cwd_relative = lib_dir });

    lib.setLibCFile(libc_config);
    lib.linkSystemLibrary("log");
    lib.linkLibC();
    // Hide everything but the JNI entry points and the bionic shm shims.
    lib.version_script = b.path("src/bridge/version-script.map");

    return lib;
}

pub fn build(b: *std.Build) void {
    const optimize = b.standardOptimizeOption(.{});

    const native_step = b.step("native", "Build libmarmalade_term.so for every shipped ABI");
    b.default_step = native_step;

    for (build_targets) |target_query| {
        const resolved_target = b.resolveTargetQuery(target_query);
        const lib = buildNativeLibrary(b, resolved_target, optimize);

        // Install straight into the jniLibs layout Gradle already packages.
        const abi_name = ndk.getOutputDir(resolved_target.result) catch unreachable;
        const install = b.addInstallFileWithDir(
            lib.getEmittedBin(),
            .{ .custom = b.fmt("jniLibs/{s}", .{abi_name}) },
            "libmarmalade_term.so",
        );
        native_step.dependOn(&install.step);
    }
}
