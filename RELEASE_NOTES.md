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
