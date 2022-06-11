def _cc_runfiles_library_impl(ctx):
    return [
        ctx.attr.lib[DefaultInfo],
        ctx.attr.lib[CcInfo],
        RunfilesLibraryInfo(),
    ]

cc_runfiles_library = rule(
    implementation = _cc_runfiles_library_impl,
    attrs = {
        "lib": attr.label(),
    },
)
