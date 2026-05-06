# Consumer ProGuard rules contributed by passes-pdf.
# Empty for now: the renderer service is referenced by manifest entries (which AGP keeps
# automatically) and the binder proxy is hand-rolled rather than AIDL-generated, so R8
# has no special-case stripping risk to head off here.
