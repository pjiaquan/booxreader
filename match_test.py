import re

with open('app/src/main/java/my/hinoki/booxreader/ui/reader/ReaderActivity.kt', 'r') as f:
    content = f.read()

pattern = r"    private fun showSettingsDialog\(\) \{.*?(?=    private fun applySettingsDialogTheme)"
match = re.search(pattern, content, re.DOTALL)
if match:
    print(f"Matched {len(match.group(0))} chars")
else:
    print("No match")
