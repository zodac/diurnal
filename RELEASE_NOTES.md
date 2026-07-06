## Patch Updates

Focussing on some performance updates

### Extracting \<scripts\>

Extracting `<script>` blocks to avoid redownloads, and only loading on necessary pages. ~10% reduction in transferred content.

### UPSERT for Action Logging

Previous we had performed a read then write for actions, resulting in 2 DB calls and a potential race condition. Changed this to an 'UPSERT' call instead.
