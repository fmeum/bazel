"""A prototype of C++20 modules support built on ctx.actions.map_directory().

Each target scans its sources with clang-scan-deps in a single action, which
writes one P1689 file per source and a manifest that lists all of them. A
map_directory() implementation then reads the P1689 files of the target and the
manifests of its transitive dependencies with template_ctx.read(), and creates
one action per BMI and one per object file. Every action gets the BMIs of all
modules it imports transitively as inputs.

BMIs of dependencies are looked up with template_ctx.find_file() in the BMI
directories of the dependencies, which are passed as referenced_directories:
the implementation function doesn't wait for them to be built, and an action
consuming a BMI only waits for that BMI. The manifests of dependencies are
regular files produced by their scan actions, so reading them doesn't wait for
any compilation.

With --define=modules_barrier=1, the BMI directories of dependencies are instead
passed as input directories, which is all that template_ctx.read() allows on its
own: the implementation function only runs after all BMIs of all dependencies
have been built. The actions get the same inputs in both modes.
"""

CcModulesInfo = provider(
    doc = "The C++20 module outputs of a target and its transitive dependencies.",
    fields = {
        "libraries": "A depset of structs with the scan manifest and BMI directory of a target.",
        "object_dirs": "A depset of directories containing object files.",
    },
)

def _bmi_basename(module):
    return module.replace(":", "-") + ".pcm"

def _module_file_flag(bmi):
    module = bmi.basename.removesuffix(".pcm").replace("-", ":")
    return "-fmodule-file=%s=%s" % (module, bmi.path)

def _parse_p1689(p1689):
    """Returns the module provided by a decoded P1689 file (or None) and the modules it imports."""
    rule = p1689["rules"][0]
    provides = rule.get("provides", [])
    return (
        provides[0]["logical-name"] if provides else None,
        [r["logical-name"] for r in rule.get("requires", [])],
    )

def _transitive_imports(roots, imports):
    """Returns the modules imported by roots, directly or transitively, in a stable order."""
    seen = {}
    stack = list(roots)

    # Starlark has neither recursion nor while loops: every iteration pops one entry, and every
    # entry was pushed by a root or an import edge.
    max_steps = len(roots) + 1
    for module_imports in imports.values():
        max_steps += len(module_imports)
    for _ in range(max_steps):
        if not stack:
            break
        module = stack.pop()
        if module in seen:
            continue
        if module not in imports:
            fail("unknown module " + module)
        seen[module] = True
        stack.extend(imports[module])
    return sorted(seen.keys())

def _run_compiler(template_ctx, tools, *, progress_message, inputs, output, arguments, bmis):
    args = template_ctx.args()
    args.add("compile")
    args.add("-std=c++20")
    args.add_all(arguments)
    args.add_all(bmis, map_each = _module_file_flag)
    args.add("-o", output)
    template_ctx.run(
        inputs = inputs + bmis,
        outputs = [output],
        executable = tools["cc"],
        arguments = [args],
        progress_message = progress_message,
    )

def _compile_modules(
        template_ctx,
        input_directories,
        output_directories,
        additional_inputs,
        tools,
        additional_params,
        referenced_directories = {}):
    barrier = additional_params["barrier"]

    # The sources of this target, keyed by their key in additional_inputs.
    units = {}

    # The imports and BMIs of all modules of this target and its transitive dependencies.
    imports = {}
    bmis = {}

    for ddi in input_directories["ddi"].children:
        key = ddi.basename.removesuffix(".ddi")
        module, requires = _parse_p1689(json.decode(template_ctx.read(ddi)))
        units[key] = struct(src = additional_inputs[key], module = module, requires = requires)
        if module:
            imports[module] = requires
            bmis[module] = template_ctx.declare_file(
                _bmi_basename(module),
                directory = output_directories["bmis"],
            )

    dep_keys = [key for key in additional_inputs if key.startswith("lib_")]
    for key in dep_keys:
        if barrier:
            # All BMIs of the dependency have already been built.
            children = {f.basename: f for f in input_directories[key].children}
        for p1689 in json.decode(template_ctx.read(additional_inputs[key])):
            module, requires = _parse_p1689(p1689)
            if not module:
                continue
            if module in imports:
                fail("module %s is provided by more than one target" % module)
            imports[module] = requires
            if barrier:
                bmis[module] = children.get(_bmi_basename(module))
            else:
                bmis[module] = template_ctx.find_file(
                    directory = referenced_directories[key],
                    path = _bmi_basename(module),
                )
            if not bmis[module]:
                fail("missing BMI for module " + module)

    for key, unit in units.items():
        needed_bmis = [bmis[m] for m in _transitive_imports(unit.requires, imports)]
        obj = template_ctx.declare_file(key + ".o", directory = output_directories["objs"])
        if unit.module:
            bmi = bmis[unit.module]
            _run_compiler(
                template_ctx,
                tools,
                progress_message = "Precompiling " + unit.src.short_path,
                inputs = [unit.src],
                output = bmi,
                arguments = ["--precompile", "-x", "c++-module", unit.src],
                bmis = needed_bmis,
            )
            _run_compiler(
                template_ctx,
                tools,
                progress_message = "Compiling " + bmi.basename,
                inputs = [bmi],
                output = obj,
                arguments = ["-c", bmi],
                bmis = needed_bmis,
            )
        else:
            _run_compiler(
                template_ctx,
                tools,
                progress_message = "Compiling " + unit.src.short_path,
                inputs = [unit.src],
                output = obj,
                arguments = ["-c", unit.src],
                bmis = needed_bmis,
            )

def _compile(ctx):
    srcs = ctx.files.srcs
    ddi_dir = ctx.actions.declare_directory(ctx.label.name + "_ddi")
    manifest = ctx.actions.declare_file(ctx.label.name + ".modules.json")
    args = ctx.actions.args()
    args.add("scan")
    args.add(ddi_dir.path)
    args.add(manifest)
    args.add_all(srcs)
    ctx.actions.run(
        executable = ctx.executable._cc,
        arguments = [args],
        inputs = srcs,
        outputs = [ddi_dir, manifest],
        mnemonic = "CppModuleScan",
        progress_message = "Scanning %{label}",
    )

    dep_libraries = depset(transitive = [dep[CcModulesInfo].libraries for dep in ctx.attr.deps])
    dep_object_dirs = depset(transitive = [dep[CcModulesInfo].object_dirs for dep in ctx.attr.deps])
    barrier = ctx.var.get("modules_barrier") == "1"

    input_directories = {"ddi": ddi_dir}
    referenced_directories = {}
    additional_inputs = {"src_%d" % i: src for i, src in enumerate(srcs)}
    for i, library in enumerate(dep_libraries.to_list()):
        key = "lib_%d" % i
        additional_inputs[key] = library.manifest
        if barrier:
            input_directories[key] = library.bmis
        else:
            referenced_directories[key] = library.bmis

    bmi_dir = ctx.actions.declare_directory(ctx.label.name + "_bmis")
    object_dir = ctx.actions.declare_directory(ctx.label.name + "_objs")
    ctx.actions.map_directory(
        implementation = _compile_modules,
        input_directories = input_directories,
        additional_inputs = additional_inputs,
        output_directories = {"bmis": bmi_dir, "objs": object_dir},
        referenced_directories = referenced_directories,
        tools = {"cc": ctx.attr._cc.files_to_run},
        additional_params = {"barrier": barrier},
        mnemonic = "CppModuleCompile",
    )
    return CcModulesInfo(
        libraries = depset(
            [struct(manifest = manifest, bmis = bmi_dir)],
            transitive = [dep_libraries],
        ),
        object_dirs = depset([object_dir], transitive = [dep_object_dirs]),
    )

_ATTRS = {
    "srcs": attr.label_list(allow_files = [".cppm", ".cpp"]),
    "deps": attr.label_list(providers = [CcModulesInfo]),
    "_cc": attr.label(
        default = "//rules:cc.sh",
        allow_single_file = True,
        executable = True,
        cfg = "exec",
    ),
}

def _cc_module_library_impl(ctx):
    info = _compile(ctx)
    return [DefaultInfo(files = info.object_dirs), info]

cc_module_library = rule(
    implementation = _cc_module_library_impl,
    attrs = _ATTRS,
)

def _cc_module_binary_impl(ctx):
    info = _compile(ctx)
    executable = ctx.actions.declare_file(ctx.label.name)
    args = ctx.actions.args()
    args.add("compile")
    args.add_all(info.object_dirs)
    args.add("-o", executable)
    ctx.actions.run(
        executable = ctx.executable._cc,
        arguments = [args],
        inputs = info.object_dirs,
        outputs = [executable],
        mnemonic = "CppModuleLink",
        progress_message = "Linking %{output}",
    )
    return [DefaultInfo(executable = executable)]

cc_module_binary = rule(
    implementation = _cc_module_binary_impl,
    attrs = _ATTRS,
    executable = True,
)
