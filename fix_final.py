import os

workflows_dir = ".github/workflows"
for filename in os.listdir(workflows_dir):
    if filename.endswith(".yml"):
        filepath = os.path.join(workflows_dir, filename)
        with open(filepath, 'r') as f:
            content = f.read()

        new_content = []
        lines = content.split('\n')
        i = 0
        while i < len(lines):
            line = lines[i]
            if "if: ${{ secrets.GEMINI_API_KEY != '' || secrets.GOOGLE_API_KEY != '' || vars.GCP_WIF_PROVIDER != '' }}" in line and "${{ steps." not in line:
                # skip this duplicate single-line if statement
                i += 1
                continue
            new_content.append(line)
            i += 1

        with open(filepath, 'w') as f:
            f.write('\n'.join(new_content))
