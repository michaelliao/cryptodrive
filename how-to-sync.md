Here is how sync from local vault directory to cloud works:

When user updates or deletes a file in mounted drive, the change is recorded in a queue.

A background thread will process the queue and sync the changes to cloud storage.

The queue is stored in a JSON file as "sync.json", which looks like this:

```
{
    "queue": [
        {
            "action": "updated",
            "path": "00/0f/a5.c9e",
            "timestamp": 123455000
        },
        {
            "action": "deleted",
            "path": "00/9c/4f.c9e",
            "timestamp": 123456000
        },
        {
            "action": "updated",
            "path": "01/65/b4.c9e",
            "timestamp": 123457000
        }
    ]
}
```

The app loads the queue from "sync.json" and deserializes to VaultQueue object which keeps in memory during vault's mounted lifetime.

The background thread will process the queue one by one, and sync the changes to cloud storage.

The "sync.json" file will be updated after each change is processed.

Only supports one-way sync from local to cloud. Changes in cloud will not be synced back to local.

Please update the context in CLAUDE.md for sync mechanism.
