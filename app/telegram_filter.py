"""Which bot messages are forwarded, and which images are suppressed.

Pure functions only, so the policy is unit tested without booting the application
(see ``tests/test_telegram_filter.py``); the rules are documented in
``.clinerules/telegram.md``.
"""

#: Event detail the processor sets on a message whose payload *is* a picture (a
#: camera snapshot wired to an output). It also labels the chat message, e.g.
#: "Front Gate snapshot (14:02)".
SNAPSHOT_SOURCE = "snapshot"

#: Annotations the mDash snapshot processor appends when its model sees someone.
PERSON_ANNOTATIONS = ("person", "face")


def is_explicit_snapshot(source: str | None) -> bool:
    """True when an input device sent the image itself, rather than a camera frame."""
    return isinstance(source, str) and source.strip().lower() == SNAPSHOT_SOURCE


def include_image(source: str | None, text: str, only_with_people: bool) -> bool:
    """Decide whether an image-bearing message reaches the chat.

    ``only_with_people`` is ``[telegram] image_send_only_with_people``, which gates
    *unattended* camera frames: they are frequent and only interesting when someone
    is in them. An image an input explicitly sent — a Ring snapshot wired to this
    output — is a deliberate notification and always passes.

    A suppressed message is dropped whole: no photo and no text.
    """
    if is_explicit_snapshot(source):
        return True
    if not only_with_people:
        return True
    return any(annotation in text for annotation in PERSON_ANNOTATIONS)
