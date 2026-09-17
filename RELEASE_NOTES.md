## New Features

### Attachments in Notes

- Added support for attaching files to a day's note
   - By default, all file types are supported, but this can be configured using `NOTE_ATTACHMENT_EXTENSIONS`
- Attachments are embedded in the note box, and can be renamed
- Attachments will have a preview window for image/audio formats
- Attachments can be searched through by name on the **Notes** page
- Exports now have the option to include attachments
   - `MAX_ATTACHMENT_SIZE` can be used to define the largest file size
   - `MAX_ARCHIVE_SIZE` and `MAX_UPLOAD_SIZE` are used for the export archive
   - If you're trying to support large files, you should also increase:
       - `UPLOAD_READ_TIMEOUT`/`MAX_MEMORY_SIZE` and `deploy.resources.limits.memory`/`memswap_limit` in your compose file

### CTRL+S to Save Note

- When the note box is selected, entering **CTRL+S** on the keyboard will now save the note content

### Import/Export

- The export archive now also includes a user's settings, in addition to actions/notes/attachments
- There is a progress bar when uploading an archive file (helpful if there are attachments in the archive that might take longer to load)
- Adding application version to export filename

### Statistics Graphing

- Added the option to graph stats over all time, grouping stats by year
- Hiding the `Compare to...` option when there are no more stats available to compare with
