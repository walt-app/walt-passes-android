# Consumer ProGuard rules contributed by passes-image-decode.
# Empty: this module is a single plain-Kotlin decode function over android.graphics
# (no reflection, no entry points R8 can't see). Each consumer keeps its own decode
# policy and reaches decodeBounded by a normal call, so there is nothing to keep here.
