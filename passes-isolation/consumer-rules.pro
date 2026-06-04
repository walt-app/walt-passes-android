# Consumer ProGuard rules contributed by passes-isolation.
# Empty: the shared isolated-worker plumbing is plain Kotlin (a PFD factory and a
# bind-session facade). The isolated services that consume it are declared in the
# consumer modules' manifests, which AGP keeps automatically, so R8 has no special-case
# stripping risk to head off here.
