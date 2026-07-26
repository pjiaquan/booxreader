import os
import glob

# The memory said:
# The project uses GitHub Actions with `google-github-actions/run-gemini-cli` for PR reviews and triage. Gemini workflow steps must conditionally check for available credentials (e.g., `if: ${{ secrets.GEMINI_API_KEY != '' || secrets.GOOGLE_API_KEY != '' || vars.GCP_WIF_PROVIDER != '' }}`) to prevent CI failures in environments missing these secrets.

workflows_dir = ".github/workflows"
for filename in os.listdir(workflows_dir):
    if filename.endswith(".yml"):
        filepath = os.path.join(workflows_dir, filename)
        with open(filepath, 'r') as f:
            content = f.read()

        # Check if the file contains the action usage
        if "uses: 'google-github-actions/run-gemini-cli@v0'" in content:
            # Add the if condition before the uses statement if it's not already there
            if "if: ${{ secrets.GEMINI_API_KEY != '' || secrets.GOOGLE_API_KEY != '' || vars.GCP_WIF_PROVIDER != '' }}" not in content:
                new_content = []
                lines = content.split('\n')
                for i, line in enumerate(lines):
                    if "uses: 'google-github-actions/run-gemini-cli@v0'" in line:
                        indent = len(line) - len(line.lstrip())
                        new_content.append(" " * indent + "if: ${{ secrets.GEMINI_API_KEY != '' || secrets.GOOGLE_API_KEY != '' || vars.GCP_WIF_PROVIDER != '' }}")
                    new_content.append(line)

                with open(filepath, 'w') as f:
                    f.write('\n'.join(new_content))
                print(f"Fixed {filename}")
