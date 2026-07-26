import re

with open('app/src/main/java/my/hinoki/booxreader/ui/reader/ReaderActivity.kt', 'r') as f:
    content = f.read()

# Replace SharedPreferences with android.content.SharedPreferences
content = content.replace(" SharedPreferences", " android.content.SharedPreferences")

with open('app/src/main/java/my/hinoki/booxreader/ui/reader/ReaderActivity.kt', 'w') as f:
    f.write(content)
