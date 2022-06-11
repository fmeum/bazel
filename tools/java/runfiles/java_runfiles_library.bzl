def _java_runfiles_library_impl(ctx):
    return [
        ctx.attr.lib[DefaultInfo],
        ctx.attr.lib[JavaInfo],
        RunfilesLibraryInfo(),
    ]

java_runfiles_library = rule(
    implementation = _java_runfiles_library_impl,
    attrs = {
        "lib": attr.label(),
    },
)
