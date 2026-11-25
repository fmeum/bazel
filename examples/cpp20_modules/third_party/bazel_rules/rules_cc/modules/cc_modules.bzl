"""A prototype of C++20 modules support built on ctx.actions.map_directory().

Each target scans its sources with clang-scan-deps in a single action. A
map_directory() implementation then reads the P1689 output with
template_ctx.read(), sorts the target's modules topologically and creates one
action per BMI and one per object file.

Modules imported from dependencies are looked up with
cc_internal.look_up_module_interface(), which returns the individual BMI and the
BMIs it was compiled against. An action consuming them only waits for these BMIs
to be built, not for the entire BMI directory of the dependency. The rules live
under third_party/bazel_rules/rules_cc since that function is a private rules_cc
API.

With --define=modules_barrier=1, the BMI directories of dependencies are instead
passed as input directories, which is all that template_ctx.read() allows on its
own: the implementation function only runs after all BMIs of all dependencies
have been built, and since it can't tell which of them a module was compiled
against, every compile that imports a module from a dependency gets all of them.
"""

CcModulesInfo = provider(
    doc = "The C++20 module outputs of a target and its transitive dependencies.",
    fields = {
        "bmi_dirs": "A depset of directories containing BMIs named after their modules.",
        "object_dirs": "A depset of directories containing object files.",
    },
)

def _bmi_basename(module):
    return module.replace(":", "-") + ".pcm"

def _module_file_flag(bmi):
    module = bmi.basename.removesuffix(".pcm").replace("-", ":")
    return "-fmodule-file=%s=%s" % (module, bmi.path)

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
        additional_params):
    barrier = additional_params["barrier"]
    if barrier:
        all_dep_bmis = []
        for name, directory in input_directories.items():
            if name.startswith("dep_bmis_"):
                all_dep_bmis.extend(directory.children)
    else:
        cc_internal = cc_common.internal_DO_NOT_USE()

    # One P1689 file per source, named after the key of the source in additional_inputs.
    units = {}
    for ddi in input_directories["ddi"].children:
        key = ddi.basename.removesuffix(".ddi")
        rule = json.decode(template_ctx.read(ddi))["rules"][0]
        provides = rule.get("provides", [])
        units[key] = struct(
            src = additional_inputs[key],
            module = provides[0]["logical-name"] if provides else None,
            requires = [r["logical-name"] for r in rule.get("requires", [])],
        )

    local_bmis = {
        unit.module: template_ctx.declare_file(
            _bmi_basename(unit.module),
            directory = output_directories["bmis"],
        )
        for unit in units.values()
        if unit.module
    }

    # The BMIs needed to import each local module, including its own.
    closures = {}
    done = {}

    # Starlark has neither recursion nor while loops: each pass handles at least one unit.
    for _ in range(len(units)):
        for key, unit in sorted(units.items()):
            if key in done or [r for r in unit.requires if r in local_bmis and r not in closures]:
                continue
            bmis = []
            for required in unit.requires:
                if required in local_bmis:
                    bmis.extend(closures[required])
                elif barrier:
                    bmis.extend(all_dep_bmis)
                else:
                    found = cc_internal.look_up_module_interface(
                        template_ctx = template_ctx,
                        path = _bmi_basename(required),
                    )
                    if not found:
                        fail("%s imports unknown module %s" % (unit.src.short_path, required))
                    bmi, transitive_bmis = found
                    bmis.append(bmi)
                    bmis.extend(transitive_bmis)
            bmis = depset(bmis).to_list()

            obj = template_ctx.declare_file(key + ".o", directory = output_directories["objs"])
            if unit.module:
                bmi = local_bmis[unit.module]
                _run_compiler(
                    template_ctx,
                    tools,
                    progress_message = "Precompiling " + unit.src.short_path,
                    inputs = [unit.src],
                    output = bmi,
                    arguments = ["--precompile", "-x", "c++-module", unit.src],
                    bmis = bmis,
                )
                _run_compiler(
                    template_ctx,
                    tools,
                    progress_message = "Compiling " + bmi.basename,
                    inputs = [bmi],
                    output = obj,
                    arguments = ["-c", bmi],
                    bmis = bmis,
                )
                closures[unit.module] = [bmi] + bmis
            else:
                _run_compiler(
                    template_ctx,
                    tools,
                    progress_message = "Compiling " + unit.src.short_path,
                    inputs = [unit.src],
                    output = obj,
                    arguments = ["-c", unit.src],
                    bmis = bmis,
                )
            done[key] = True

    if len(done) != len(units):
        fail("cyclic module imports among " + ", ".join([u.src.short_path for u in units.values()]))

def _compile(ctx):
    srcs = ctx.files.srcs
    ddi_dir = ctx.actions.declare_directory(ctx.label.name + "_ddi")
    args = ctx.actions.args()
    args.add("scan")
    args.add(ddi_dir.path)
    args.add_all(srcs)
    ctx.actions.run(
        executable = ctx.executable._cc,
        arguments = [args],
        inputs = srcs,
        outputs = [ddi_dir],
        mnemonic = "CppModuleScan",
        progress_message = "Scanning %{label}",
    )

    dep_bmi_dirs = depset(transitive = [dep[CcModulesInfo].bmi_dirs for dep in ctx.attr.deps])
    dep_object_dirs = depset(transitive = [dep[CcModulesInfo].object_dirs for dep in ctx.attr.deps])
    barrier = ctx.var.get("modules_barrier") == "1"
    input_directories = {"ddi": ddi_dir}
    if barrier:
        for i, directory in enumerate(dep_bmi_dirs.to_list()):
            input_directories["dep_bmis_%d" % i] = directory

    bmi_dir = ctx.actions.declare_directory(ctx.label.name + "_bmis")
    object_dir = ctx.actions.declare_directory(ctx.label.name + "_objs")
    ctx.actions.map_directory(
        implementation = _compile_modules,
        input_directories = input_directories,
        additional_inputs = {"src_%d" % i: src for i, src in enumerate(srcs)},
        output_directories = {"bmis": bmi_dir, "objs": object_dir},
        referenced_directories = None if barrier else dep_bmi_dirs,
        tools = {"cc": ctx.attr._cc.files_to_run},
        additional_params = {"barrier": barrier},
        mnemonic = "CppModuleCompile",
    )
    return CcModulesInfo(
        bmi_dirs = depset([bmi_dir], transitive = [dep_bmi_dirs]),
        object_dirs = depset([object_dir], transitive = [dep_object_dirs]),
    )

_ATTRS = {
    "srcs": attr.label_list(allow_files = [".cppm", ".cpp"]),
    "deps": attr.label_list(providers = [CcModulesInfo]),
    "_cc": attr.label(
        default = "//third_party/bazel_rules/rules_cc/modules:cc.sh",
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
