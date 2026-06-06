تعليمات الرفع على GitHub
========================

بعد فك الضغط، ارفع محتويات هذا المجلد إلى GitHub كما هي.
لا ترفع ملفات MainActivity أو AndroidManifest في الجذر مباشرة.

الشكل الصحيح داخل GitHub:
app/
build.gradle
settings.gradle
codemagic.yaml
.github/
README_AR.txt

بعد الرفع يمكنك البناء عبر Codemagic أو GitHub Actions.
