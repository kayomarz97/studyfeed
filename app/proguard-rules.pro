# StudyFeed — R8 rules for release builds.
#
# Compose, AndroidX and java-fsrs all ship their own consumer rules, so this file stays near-empty
# on purpose. Add a rule only when a real release build proves it necessary — speculative -keep
# rules silently defeat the shrinking that §11c asks for.

# java-fsrs serializes Card/ReviewLog via Jackson. Jackson is reflective, so R8 must not rename
# the fields it maps. Only enable if a release build actually breaks on it — noted here so the
# next person knows where to look rather than guessing:
# -keepclassmembers class io.github.openspacedrepetition.** { *; }
