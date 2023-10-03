    List<String> newContent;
    try {
      // By using applyFuzzy, the patch also applies when there is an offset.
      newContent = patch.applyFuzzy(oldContent, 0);
    } catch (PatchFailedException e) {
      throw new PatchFailedException(
          String.format("in patch applied to %s: %s", oldFile, e.getMessage()));
    }