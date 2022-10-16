load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load(":runfiles.bzl", "create", "fail_if_not_normalized", "is_absolute", "rlocation")

MOCK_MAPPING = """,my_module,_main
,my_protobuf,protobuf~3.19.2
,my_workspace,_main
protobuf~3.19.2,protobuf,protobuf~3.19.2
"""

PARSED_MOCK_MAPPING = {
    ("", "my_module"): "_main",
    ("", "my_protobuf"): "protobuf~3.19.2",
    ("", "my_workspace"): "_main",
    ("protobuf~3.19.2", "protobuf"): "protobuf~3.19.2",
}

# The trailing space at the end of the first line is intentional: Such a
# manifest entry indicates that this runfile is an implicitly empty file.
MOCK_MANIFEST = "_main/foo/__init__.py \n" + """protobuf~3.19.2/foo/runfile C:/Actual Path\\protobuf\runfile
_main/bar/runfile /the/path/./to/other//other runfile.txt
protobuf~3.19.2/bar/dir E:\\Actual Path\\Directory
"""

def _create_runfiles_test_impl(ctx):
    env = unittest.begin(ctx)

    runfiles = create(
        argv0 = "argv0",
        env = {},
        is_file = lambda path: False,
        is_dir = lambda path: path == "argv0.runfiles",
        read_file = lambda path: MOCK_MAPPING if path == "_repo_mapping" else fail(path),
    )
    asserts.equals(env, "argv0.runfiles", runfiles.directory)
    asserts.equals(env, None, runfiles.manifest_entries)
    asserts.equals(env, PARSED_MOCK_MAPPING, runfiles.repo_mapping)

    return unittest.end(env)

def _rlocation_manifest_test_impl(ctx):
    env = unittest.begin(ctx)

    runfiles = create(
        argv0 = "argv0",
        env = {"RUNFILES_MANIFEST_FILE": "my_manifest"},
        is_file = lambda path: path == "my_manifest",
        is_dir = lambda path: False,
        read_file = lambda path: MOCK_MAPPING if path == "_repo_mapping" else MOCK_MANIFEST if path == "my_manifest" else fail(path),
    )

    # absolute path
    asserts.equals(env, "/the/path/to/other/other runfile.txt", rlocation(runfiles, "/the/path/to/other/other runfile.txt"))
    asserts.equals(env, "C:/Actual Path\\protobuf\runfile", rlocation(runfiles, "C:/Actual Path\\protobuf\runfile"))
    asserts.equals(env, "d:\\absol  ut e/pa~th", rlocation(runfiles, "d:\\absol  ut e/pa~th"))

    # relative path, local repository name relative to main repository
    asserts.equals(env, "/the/path/./to/other//other runfile.txt", rlocation(runfiles, "my_module/bar/runfile"))
    asserts.equals(env, "/the/path/./to/other//other runfile.txt", rlocation(runfiles, "my_workspace/bar/runfile"))
    asserts.equals(env, "C:/Actual Path\\protobuf\runfile", rlocation(runfiles, "my_protobuf/foo/runfile"))
    asserts.equals(env, "", rlocation(runfiles, "my_module/foo/__init__.py"))
    asserts.equals(env, "", rlocation(runfiles, "my_workspace/foo/__init__.py"))
    asserts.equals(env, "E:\\Actual Path\\Directory", rlocation(runfiles, "my_protobuf/bar/dir"))
    asserts.equals(env, "E:\\Actual Path\\Directory/file", rlocation(runfiles, "my_protobuf/bar/dir/file"))
    asserts.equals(env, "E:\\Actual Path\\Directory/de eply/nes  ted/fi~le", rlocation(
        runfiles,
        "my_protobuf/bar/dir/de eply/nes  ted/fi~le",
    ))

    asserts.equals(env, None, rlocation(runfiles, "protobuf/foo/runfile"))
    asserts.equals(env, None, rlocation(runfiles, "protobuf/bar/dir"))
    asserts.equals(env, None, rlocation(runfiles, "protobuf/bar/dir/file"))
    asserts.equals(env, None, rlocation(runfiles, "protobuf/bar/dir/dir/de eply/nes  ted/fi~le"))

    assert_fails(
        env,
        lambda **kw: rlocation(runfiles, "my_module/foo/__init__.py/file", **kw),
        "Tried to look up _main/foo/__init__.py/file, but _main/foo/__init__.py is an empty file",
    )

    # relative path, local repository name relative to protobuf~3.19.2 repository
    asserts.equals(env, "C:/Actual Path\\protobuf\runfile", rlocation(
        runfiles,
        "protobuf/foo/runfile",
        current_canonical = "protobuf~3.19.2",
    ))
    asserts.equals(env, "E:\\Actual Path\\Directory", rlocation(
        runfiles,
        "protobuf/bar/dir",
        current_canonical = "protobuf~3.19.2",
    ))
    asserts.equals(env, "E:\\Actual Path\\Directory/file", rlocation(
        runfiles,
        "protobuf/bar/dir/file",
        current_canonical = "protobuf~3.19.2",
    ))
    asserts.equals(env, "E:\\Actual Path\\Directory/dir/de eply/nes  ted/fi~le", rlocation(
        runfiles,
        "protobuf/bar/dir/dir/de eply/nes  ted/fi~le",
        current_canonical = "protobuf~3.19.2",
    ))

    asserts.equals(env, None, rlocation(
        runfiles,
        "my_module/bar/runfile",
        current_canonical = "protobuf~3.19.2",
    ))
    asserts.equals(env, None, rlocation(
        runfiles,
        "my_protobuf/foo/runfile",
        current_canonical = "protobuf~3.19.2",
    ))
    asserts.equals(env, None, rlocation(
        runfiles,
        "my_workspace/foo/__init__.py",
        current_canonical = "protobuf~3.19.2",
    ))
    asserts.equals(env, None, rlocation(
        runfiles,
        "my_protobuf/bar/dir",
        current_canonical = "protobuf~3.19.2",
    ))
    asserts.equals(env, None, rlocation(
        runfiles,
        "my_protobuf/bar/dir/file",
        current_canonical = "protobuf~3.19.2",
    ))
    asserts.equals(env, None, rlocation(
        runfiles,
        "my_protobuf/bar/dir/de eply/nes  ted/fi~le",
        current_canonical = "protobuf~3.19.2",
    ))

    # relative path, canonical repository name
    asserts.equals(env, "/the/path/./to/other//other runfile.txt", rlocation(runfiles, "_main/bar/runfile"))
    asserts.equals(env, "C:/Actual Path\\protobuf\runfile", rlocation(runfiles, "protobuf~3.19.2/foo/runfile"))
    asserts.equals(env, "", rlocation(runfiles, "_main/foo/__init__.py"))
    asserts.equals(env, "E:\\Actual Path\\Directory", rlocation(runfiles, "protobuf~3.19.2/bar/dir"))
    asserts.equals(env, "E:\\Actual Path\\Directory/file", rlocation(runfiles, "protobuf~3.19.2/bar/dir/file"))
    asserts.equals(env, "E:\\Actual Path\\Directory/de eply/nes  ted/fi~le", rlocation(
        runfiles,
        "protobuf~3.19.2/bar/dir/de eply/nes  ted/fi~le",
    ))

    asserts.equals(env, "/the/path/./to/other//other runfile.txt", rlocation(
        runfiles,
        "_main/bar/runfile",
        current_canonical = "protobuf~3.19.2",
    ))
    asserts.equals(env, "C:/Actual Path\\protobuf\runfile", rlocation(
        runfiles,
        "protobuf~3.19.2/foo/runfile",
        current_canonical = "protobuf~3.19.2",
    ))
    asserts.equals(env, "", rlocation(
        runfiles,
        "_main/foo/__init__.py",
        current_canonical = "protobuf~3.19.2",
    ))
    asserts.equals(env, "E:\\Actual Path\\Directory", rlocation(
        runfiles,
        "protobuf~3.19.2/bar/dir",
        current_canonical = "protobuf~3.19.2",
    ))
    asserts.equals(env, "E:\\Actual Path\\Directory/file", rlocation(
        runfiles,
        "protobuf~3.19.2/bar/dir/file",
        current_canonical = "protobuf~3.19.2",
    ))
    asserts.equals(env, "E:\\Actual Path\\Directory/de eply/nes  ted/fi~le", rlocation(
        runfiles,
        "protobuf~3.19.2/bar/dir/de eply/nes  ted/fi~le",
    ))

    return unittest.end(env)

def _rlocation_directory_test_impl(ctx):
    env = unittest.begin(ctx)

    runfiles = create(
        argv0 = "argv0",
        env = {"RUNFILES_DIR": "my_directory"},
        is_file = lambda path: False,
        is_dir = lambda path: path == "my_directory",
        read_file = lambda path: MOCK_MAPPING if path == "_repo_mapping" else fail(path),
    )

    asserts.equals(
        env,
        rlocation(runfiles, "my_module/bar/runfile"),
        "my_directory/_main/bar/runfile",
    )
    asserts.equals(
        env,
        rlocation(runfiles, "my_workspace/bar/runfile"),
        "my_directory/_main/bar/runfile",
    )
    asserts.equals(
        env,
        rlocation(runfiles, "my_protobuf/foo/runfile"),
        "my_directory/protobuf~3.19.2/foo/runfile",
    )
    asserts.equals(
        env,
        rlocation(runfiles, "my_protobuf/bar/dir"),
        "my_directory/protobuf~3.19.2/bar/dir",
    )
    asserts.equals(
        env,
        rlocation(runfiles, "my_protobuf/bar/dir/file"),
        "my_directory/protobuf~3.19.2/bar/dir/file",
    )
    asserts.equals(
        env,
        rlocation(runfiles, "my_protobuf/bar/dir/de eply/nes ted/fi~le"),
        "my_directory/protobuf~3.19.2/bar/dir/de eply/nes ted/fi~le",
    )

    asserts.equals(
        env,
        rlocation(runfiles, "protobuf/foo/runfile"),
        "my_directory/protobuf/foo/runfile",
    )
    asserts.equals(
        env,
        rlocation(runfiles, "protobuf/bar/dir/dir/de eply/nes ted/fi~le"),
        "my_directory/protobuf/bar/dir/dir/de eply/nes ted/fi~le",
    )

    asserts.equals(
        env,
        rlocation(runfiles, "_main/bar/runfile"),
        "my_directory/_main/bar/runfile",
    )
    asserts.equals(
        env,
        rlocation(runfiles, "protobuf~3.19.2/foo/runfile"),
        "my_directory/protobuf~3.19.2/foo/runfile",
    )
    asserts.equals(
        env,
        rlocation(runfiles, "protobuf~3.19.2/bar/dir"),
        "my_directory/protobuf~3.19.2/bar/dir",
    )
    asserts.equals(
        env,
        rlocation(runfiles, "protobuf~3.19.2/bar/dir/file"),
        "my_directory/protobuf~3.19.2/bar/dir/file",
    )
    asserts.equals(
        env,
        rlocation(runfiles, "protobuf~3.19.2/bar/dir/de eply/nes  ted/fi~le"),
        "my_directory/protobuf~3.19.2/bar/dir/de eply/nes  ted/fi~le",
    )

    asserts.equals(env, rlocation(runfiles, "config.json"), "my_directory/config.json")

    return unittest.end(env)

def _is_absolute_test_impl(ctx):
    env = unittest.begin(ctx)

    asserts.true(env, is_absolute("C:\\"))
    asserts.true(env, is_absolute("c:/"))
    asserts.true(env, is_absolute("/"))
    asserts.true(env, is_absolute("//"))

    asserts.false(env, is_absolute("\\"))
    asserts.false(env, is_absolute("c:f"))

    return unittest.end(env)

_IS_ABSOLUTE_FAILING_TEST_CASES = {
    "../repo/foo": "path must not contain '..' segments",
    "repo/../foo": "path must not contain '..' segments",
    "repo/foo/..": "path must not contain '..' segments",
    "./repo/foo": "path must not contain '.' segments",
    "repo/./foo": "path must not contain '.' segments",
    "repo/foo/.": "path must not contain '.' segments",
    "//repo/foo": "path must not contain consecutive forward slashes",
    "repo///foo": "path must not contain consecutive forward slashes",
    "repo/foo//": "path must not contain consecutive forward slashes",
}

def _fail_if_not_normalized_test_impl(ctx):
    env = unittest.begin(ctx)

    for path, error in _IS_ABSOLUTE_FAILING_TEST_CASES.items():
        assert_fails(env, lambda **kw: fail_if_not_normalized(path, **kw), error + ": " + path)

    return unittest.end(env)

def assert_fails(env, func, expected_msg = None):
    actual_msg = []

    def record_failure(msg):
        actual_msg.append(msg)

    func(fail = record_failure)
    asserts.true(env, actual_msg, "call to " + repr(func) + " didn't fail")
    if expected_msg and actual_msg:
        asserts.equals(env, expected_msg, actual_msg[0], "call failed with unexpected message")

_create_runfiles_test = unittest.make(_create_runfiles_test_impl)
_rlocation_manifest_test = unittest.make(_rlocation_manifest_test_impl)
_rlocation_directory_test = unittest.make(_rlocation_directory_test_impl)
_fail_if_not_normalized_test = unittest.make(_fail_if_not_normalized_test_impl)
_is_absolute_test = unittest.make(_is_absolute_test_impl)

def runfiles_tests():
    unittest.suite(
        "runfiles_tests",
        _create_runfiles_test,
        _rlocation_manifest_test,
        _rlocation_directory_test,
        _fail_if_not_normalized_test,
        _is_absolute_test,
    )
