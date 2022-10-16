# 1. Should rlocation always return an absolute path?
# 2  Should rlocation always return an OS-specific path or is a mix of / and \
#    fine?
# 3. Should the RUNFILES_DIR set in env_vars be absolute? Subprocesses may be
#    executed with different working directories.
# 4. Should implementations use the manifest whenever it is found or rather
#    whenever RUNFILES_MANIFEST_ONLY is set to 1? Accidentally using the dir can
#    lead to lookup failures, but otherwise using it should be more efficient.
# 5. Is all the logic that finds one of dir/manifest relative to the other
#    needed? Is there any situation in which a runfiles directory can't be used,
#    but a manifest can?
# 6. How should empty files in manifests be handled?
# 7. Should absolute paths be detected uniformly or dependent on the host
#    platform?
def create(
        argv0,
        env,
        is_file,
        is_dir,
        read_file):
    """
    Args:

    Returns:
      an opaque struct
    """
    manifest = env.get("RUNFILES_MANIFEST_FILE")
    directory = env.get("RUNFILES_DIR")
    manifest_valid = manifest and is_file(manifest)
    directory_valid = directory and is_dir(directory)

    if argv0 and not manifest_valid and not directory_valid:
        manifest = argv0 + ".runfiles/MANIFEST"
        directory = argv0 + ".runfiles"
        manifest_valid = is_file(manifest)
        directory_valid = is_dir(directory)
        if not manifest_valid:
            manifest = argv0 + ".runfiles_manifest"
            manifest_valid = is_file(manifest)

    if not manifest_valid and not directory_valid:
        return None

    # At this point, either a valid runfiles directory or a valid runfiles
    # manifest has been found. Try to find the other one in case a subprocess
    # only works with one particular method of runfiles discovery.

    if not manifest_valid:
        manifest = directory + "/MANIFEST"
        manifest_valid = is_file(manifest)
        if not manifest_valid:
            manifest = directory + "_manifest"
            manifest_valid = is_file(manifest)

    if not directory_valid:
        if manifest.endswith("/MANIFEST") or manifest.endswith("\\MANIFEST"):
            directory = manifest[:-len("/MANIFEST")]
            directory_valid = is_dir(directory)
        elif manifest.endswith(".runfiles_manifest"):
            directory = manifest[:-len("_manifest")]
            directory_valid = is_dir(directory)

    if manifest_valid:
        manifest_entries = _parse_runfiles_manifest(read_file(manifest))
    else:
        manifest_entries = None

    return struct(
        manifest_entries = manifest_entries,
        directory = directory if directory_valid else None,
        repo_mapping = _parse_repo_mapping(read_file("_repo_mapping")),
    )

def rlocation(runfiles, path, *, current_canonical = "", fail = fail):
    """
    Args:
      runfiles (struct): The runfiles struct returned by create_runfiles.
      path (string):
    """
    if not path:
        fail("path is empty")

    fail_if_not_normalized(path, fail = fail)

    if is_absolute(path):
        return path

    target_repo, _, remainder = path.partition("/")
    if (current_canonical, target_repo) not in runfiles.repo_mapping:
        # target_repo is already a canonical repository name and does not have
        # to be mapped.
        # Note: The particular form of canonical repository names is an
        # implementation detail and subject to change. However, it is guaranteed
        # that canonical repository names are never valid local repository
        # names.
        return _rlocation_unchecked(runfiles, path, fail = fail)

    # target_repo is a local repository name. Look up the corresponding
    # canonical repository name with respect to the current repository,
    # identified by its canonical name.
    target_canonical = runfiles.repo_mapping[(current_canonical, target_repo)]
    return _rlocation_unchecked(runfiles, target_canonical + "/" + remainder, fail = fail)

def env_vars(runfiles):
    env = {}

    if runfiles.directory:
        env["RUNFILES_DIR"] = runfiles.directory
        env["JAVA_RUNFILES"] = runfiles.directory

    if runfiles.manifest:
        env["RUNFILES_MANIFEST_FILE"] = runfiles.manifest

    return env

def _rlocation_unchecked(runfiles, path, *, fail = fail):
    if runfiles.directory:
        return runfiles.directory + "/" + path

    # If path references a runfile that lies under a directory that itself is a
    # runfile, then only the directory is listed in the manifest. Look up all
    # prefixes of path in the manifest and append the relative path from the
    # prefix to the looked up path.
    prefix = path

    # For the path "repo/pkg/dir/file", look up the following four paths in the
    # manifest:
    # * repo/some/dir/file
    # * repo/some/dir
    # * repo/some
    # * repo
    # The prefix consisting of a single segment (repository name only) does not
    # have to be looked up as manifests never contain such paths.
    for _ in range(path.count("/") + 1):
        real_path = runfiles.manifest_entries.get(prefix)

        # This is a very special case that only arises for paths to empty
        # __init__.py files generated implicitly by Bazel's Python rules as long
        # as --incompatible_default_to_explicit_init_py hasn't been flipped to
        # true.
        if real_path == "":
            if prefix == path:
                return ""
            fail("Tried to look up {}, but {} is an empty file".format(
                path,
                prefix,
            ))

        if real_path != None:
            return real_path + path.removeprefix(prefix)
        prefix, _ = prefix.rsplit("/", 1)

    return None

def _parse_runfiles_manifest(content):
    map = {}
    for line in content.split("\n"):
        if not line:
            # Empty line following the last line break
            break
        rlocation_path, real_path = line.split(" ", 1)
        map[rlocation_path] = real_path

    return map

def _parse_repo_mapping(content):
    repo_mapping = {}
    for line in content.split("\n"):
        if not line:
            # Empty line following the last line break
            break
        current_canonical, target_local, target_canonical = line.split(",")
        repo_mapping[(current_canonical, target_local)] = target_canonical

    return repo_mapping

# Visible only for testing. Real runfiles library implementations should not
# export these functions.

def fail_if_not_normalized(path, fail = fail):
    if path.startswith("../") or "/.." in path:
        fail("path must not contain '..' segments: " + path)
    if path.startswith("./") or "/./" in path or path.endswith("/."):
        fail("path must not contain '.' segments: " + path)
    if "//" in path:
        fail("path must not contain consecutive forward slashes: " + path)

def is_absolute(path):
    if (len(path) >= 3 and path[0].isalpha() and path[1] == ":" and
        (path[2] == "/" or path[2] == "\\")):
        # Windows absolute path with drive letter, e.g. C:\foo or d:/bar.
        return True
    return path.startswith("/")

# The following functions aren't implemented in Starlark, but are assumed to be
# supplied by the runfiles library or the standard library of the language the
# runfiles library is written in.

def _is_dir(path):
    """
    Args:
      name: The name of an environment variable.
    Returns:
      The value of the environment variable or None if it isn't set.
    """
    "to be implemented"

def _is_file(path):
    """
    Args:
      name: The name of an environment variable.
    Returns:
      The value of the environment variable or None if it isn't set.
    """
    "to be implemented"

def _read_file(path):
    "to be implemented"
