def _java_runfiles_library_impl(ctx):
    return [
        ctx.attr._runfiles_library[DefaultInfo],
        ctx.attr._runfiles_library[JavaInfo],
        IsRunfilesLibraryInfo(),
    ]

java_runfiles_library = rule(
    implementation = _java_runfiles_library_impl,
    attrs = {
        "_runfiles_library": attr.label(default = ":runfiles_impl"),
    },
)
