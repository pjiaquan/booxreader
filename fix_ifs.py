import os

workflows_dir = ".github/workflows"

def fix_file(filename):
    filepath = os.path.join(workflows_dir, filename)
    with open(filepath, 'r') as f:
        content = f.read()

    if filename == "gemini-scheduled-triage.yml":
        old_str = """        if: |-
          ${{ steps.find_issues.outputs.issues_to_triage != '[]' }}
        uses: 'google-github-actions/run-gemini-cli@v0' # ratchet:exclude"""
        new_str = """        if: |-
          ${{ steps.find_issues.outputs.issues_to_triage != '[]' && (secrets.GEMINI_API_KEY != '' || secrets.GOOGLE_API_KEY != '' || vars.GCP_WIF_PROVIDER != '') }}
        uses: 'google-github-actions/run-gemini-cli@v0' # ratchet:exclude"""
        content = content.replace(old_str, new_str)

    if filename == "gemini-triage.yml":
        old_str = """        if: |-
          ${{ steps.get_labels.outputs.available_labels != '' }}
        uses: 'google-github-actions/run-gemini-cli@v0' # ratchet:exclude"""
        new_str = """        if: |-
          ${{ steps.get_labels.outputs.available_labels != '' && (secrets.GEMINI_API_KEY != '' || secrets.GOOGLE_API_KEY != '' || vars.GCP_WIF_PROVIDER != '') }}
        uses: 'google-github-actions/run-gemini-cli@v0' # ratchet:exclude"""
        content = content.replace(old_str, new_str)

    with open(filepath, 'w') as f:
        f.write(content)

fix_file("gemini-scheduled-triage.yml")
fix_file("gemini-triage.yml")
