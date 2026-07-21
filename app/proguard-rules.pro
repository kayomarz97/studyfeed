# StudyFeed — R8 rules for release builds.
#
# Compose, AndroidX and java-fsrs all ship their own consumer rules, so this file stays near-empty
# on purpose. Add a rule only when a real release build proves it necessary — speculative -keep
# rules silently defeat the shrinking that §11c asks for.

# java-fsrs serializes Card/ReviewLog via Jackson. Jackson is reflective, so R8 must not rename
# the fields it maps. Only enable if a release build actually breaks on it — noted here so the
# next person knows where to look rather than guessing:
# -keepclassmembers class io.github.openspacedrepetition.** { *; }

# java-fsrs (io.github.openspacedrepetition:fsrs) was compiled with Lombok, so its Card/ReviewLog
# classes carry references to lombok.Generated / lombok.NonNull. Those annotations are compile-time
# only (never on the runtime classpath), so R8 reports them as "missing classes" and fails the
# release build. Suppressing the warning is correct and safe: -dontwarn does NOT keep any code or
# weaken shrinking (it only silences the missing-reference check), and Lombok is not — and must not
# be — a runtime dependency, so the offline guard is unaffected. Proven necessary by the first real
# assembleRelease (2026-07-21); R8's own missing_rules.txt generated exactly these lines.
-dontwarn lombok.**
