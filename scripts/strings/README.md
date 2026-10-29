Source of truth for the Arabic/English UI strings.

    grep -rhoE "R\.string\.[a-z0-9_]+" app/src/main/java | sort -u | sed 's/R.string.//' \
      | python3 scripts/strings/gen.py app/src/main/res

Regenerates `values/strings.xml` and `values-ar/strings.xml`, checks that both languages have the
same placeholders, and reports keys that are missing or unused.
