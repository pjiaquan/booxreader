# Handoff: Boox Reader — Modernist UI Redesign

## Overview
Full redesign of the Boox Reader Android app (private EPUB reader with cloud sync and AI notes) across 18 screens. The design applies the **Modernist design system**: flat, architectural, Archivo typeface, red accent (#ec3013), zero corner radius, strong 2px horizontal rules, flush-left text, and high contrast for e-ink device optimisation.

## About the Design Files
The file `Boox Reader Redesign.dc.html` in this bundle is a **high-fidelity HTML design reference** — a prototype showing the intended look, layout, and component hierarchy. It is **not production code**. The task is to **recreate these designs in the existing Android codebase** using the app's established patterns: XML layouts, Material Components, and Kotlin/Java Activities.

Open the HTML file in any browser. Pan and zoom freely. Screens are grouped into 3 turns:
- **Turn 3** (top): Reader view, Text selection, Bookmarks, AI Profiles, Magic Tags, Settings sheet
- **Turn 2**: Login, Register, Login dark
- **Turn 1**: Onboarding, Login, Home (light + dark), AI Notes, AI Note Detail, Settings, Profile, Sync

## Fidelity
**High-fidelity.** Recreate pixel-precisely: exact hex colors, Archivo font weights, 2px/1px rule weights, 52dp row heights, icon sizes. The design system is strict — no rounded corners anywhere on UI elements (the phone bezel in the mockup is device chrome, not UI).

---

## Design Tokens

### Colors — Light Mode
| Token | Hex | Usage |
|---|---|---|
| bg | `#f3f2f2` | Screen background |
| surface | `#ffffff` | Cards, elevated surfaces |
| text | `#201e1d` | Primary text |
| text-2 | `#6b6865` | Secondary text, subtitles |
| text-3 | `#9a9490` | Labels, placeholders, captions |
| text-4 | `#b8b4b0` | Timestamps, very subtle |
| accent | `#ec3013` | Primary action, progress, active states |
| divider-major | `#d4d0cd` | 2px section rules |
| divider-minor | `#d4d0cd` | 1px list item separators |
| subtle | `#ece9e6` | Tinted fills, info boxes |
| reader-bg | `#f8f6f3` | EPUB reading surface |

### Colors — Dark Mode
| Token | Hex | Usage |
|---|---|---|
| bg | `#1a1817` | Screen background |
| surface | `#252220` | Cards |
| nav-header | `#201e1d` | Dark action bars |
| text | `#f0edea` | Primary text |
| text-2 | `#908c89` | Secondary text |
| text-3 | `#5a5550` | Labels, placeholders |
| text-4 | `#3a3533` | Very subtle, URL previews |
| accent | `#ec3013` | Same as light |
| divider-major | `#38332f` | 2px rules |
| divider-minor | `#2e2b29` | 1px list separators |

### Typography
All UI text uses **Archivo** (Google Fonts). Reader body text uses **Georgia** (serif).

| Role | Size | Weight | Letter-spacing | Notes |
|---|---|---|---|---|
| Hero heading | 36–38sp | 700 | — | Onboarding, login |
| Screen title | 24–28sp | 700 | — | Greeting, section titles |
| Nav header | 17–18sp | 700 | — | Action bar title |
| Card title / row label | 15sp | 600 | — | Book titles, settings rows |
| Body | 14–15sp | 400 | — | Descriptions, form labels |
| Section kicker | 10sp | 700 | +0.12–0.18em | ALL CAPS, uppercase labels |
| Caption | 11–13sp | 400 | — | Timestamps, meta |
| Reader text | 16sp | 400 | — | Georgia serif, line-height 1.85 |
| Reader heading | 18sp | 400 | — | Georgia serif, chapter title |

### Spacing & Sizing
| Element | Value |
|---|---|
| Screen horizontal padding | 24dp |
| Nav header height | 56dp |
| Standard list row height | 52dp |
| Tall list row (book items) | ~84dp (14dp top+bottom padding, 56dp cover) |
| Primary button height | 52dp |
| Bottom toolbar height | 64dp (reader) |
| FAB size | 52×52dp |
| Book cover placeholder | 40×56dp |
| Avatar (profile) | 72×72dp |

### Borders & Rules
- **Major section divider**: 2dp solid `#d4d0cd` (light) / `#38332f` (dark)
- **List item separator**: 1dp solid `#d4d0cd` (light) / `#2e2b29` (dark)
- **Corner radius: 0dp on all UI elements** — buttons, inputs, toggles, cards, tags
- **Nav header bottom border**: 2dp solid (major divider color)

### Toggles
Replace the existing iOS-style SwitchCompat with a square slider:
- **Track ON**: 44×24dp, fill `#ec3013`, thumb 16×16dp white, aligned right with 4dp padding
- **Track OFF**: 44×24dp, border 1.5dp `#c0bcb8`, thumb 16×16dp `#c0bcb8`, aligned left

### Input Fields
Underline-only style (no outlined box):
- Bottom border only: 1.5dp solid
  - Focused / filled: `#201e1d` (light) / `#908c89` (dark)
  - Empty / placeholder: `#c0bcb8`
- Label above field: 9sp, 700 weight, ALL CAPS, letter-spacing +0.12em, `#9a9490`
- Field text: 16sp, 400 weight

### Icons
Use **Lucide** icons throughout (same icon set referenced in Modernist DS). Stroke width: 1.75dp. Size: 18–22dp depending on context.

---

## Screens

### 1a — Onboarding / Welcome
**File**: `activity_welcome.xml`  
**Purpose**: First-run permission + brand intro

**Layout** (vertical, centered column, 32dp padding):
- Illustration area: full-width gray rect (`#ece9e6`), 228dp tall, book SVG icon centered (72dp, stroke `#b8b4b0`)
- Brand kicker: "BOOX READER" — 10sp, 700, ALL CAPS, `#9a9490`, margin-bottom 10dp
- Headline: "Read More. Distract Less." — 38sp, 700, `#201e1d`, line-height 1.05
- Body: 14sp, 400, `#6b6865`, line-height 1.7, flex-fill
- CTA button: "Get Started →" — 52dp, full-width, `#ec3013` fill, white text, 700, 15sp, **left-aligned text** (20dp left padding)
- Secondary link: "Sign in to existing account" — center, 14sp, 500, `#6b6865`

---

### 1b / 2a — Login
**File**: `activity_login.xml`  
**Purpose**: Email + password sign-in

**Layout**:
- Dark brand header (`#201e1d`, no fixed height — wraps content, 28dp padding): app icon (52×52dp `#ec3013` fill with book SVG), app name 18sp/700 white, tagline 13sp/400 `#5a5550`
- Form area (28dp horizontal padding):
  - "Welcome back." — 24sp, 700, `#201e1d`
  - Subtitle — 14sp, 400, `#9a9490`
  - Email field (underline style, mail icon 15dp `#b8b4b0`)
  - Password field (underline style, lock icon, eye toggle icon)
  - Remember me row: 17×17dp square checkbox (ON: `#ec3013` fill + white checkmark), "Forgot email?" right-aligned 13sp/600 `#ec3013`
  - "Sign In →" — 52dp, full-width, red fill, left-aligned
  - OR divider (1dp lines + "or" 12sp `#9a9490`)
  - "Continue as Guest" — 52dp, 2dp outlined black, 600 weight
  - Register link: center, "No account? **Register here →**" (bold red)

**2c** is the dark variant: same structure, `#1a1817` bg, `#ec3013` brand header instead of dark.

---

### 2b — Register / Create Account
**File**: `activity_register.xml`  
**Purpose**: New account creation

**Layout**:
- Dark nav header (`#201e1d`): back arrow + "Create Account"
- Form area (28dp padding):
  - "Start reading." — 32sp, 700
  - Subtitle — 14sp, 400, `#6b6865`
  - 4 underline fields: Display Name, Email, Password (eye toggle), Confirm Password
  - Info box: `#ece9e6` fill, info icon + verification notice text
  - "Create Account →" — 52dp, red fill, left-aligned
  - "Already registered? Sign in →" link

---

### 1c / 1d — Home / Library
**File**: `activity_main.xml`  
**Purpose**: Reading dashboard with recent books

**Layout** (no padding on scroll container — content goes edge to edge):
- **Header** (24dp padding): brand kicker + "Good morning." (28sp/700) + 40×40dp outlined profile button (2dp `#d4d0cd` border)
- **2dp divider**
- **Stats row** (3 equal cols, 1dp dividers between): value (30sp/700) + label (9sp/600 ALL CAPS `#9a9490`). Books value = `#ec3013`, others = `#201e1d`
- **2dp divider**
- **"Recent" label row** (14dp top, 24dp sides): "RECENT" left + "See all" right (`#ec3013`, 12sp/600)
- **Book rows** (border-top 1dp each): 14dp vertical padding, 24dp horizontal
  - 40×56dp cover rect (dark colors: `#2e2b29`, `#3d322a`, `#262422`)
  - Title 15sp/600, author 12sp/400 `#9a9490`
  - Progress bar: 2dp track `#ece9e6`, fill `#ec3013`, percentage label 11sp/700 `#ec3013`
  - Completed state: fill `#201e1d`, "✓ Done" label
  - Timestamp right-aligned 11sp/400 `#b8b4b0`
- **Sync strip** (border-top/bottom 2dp): cloud icon + "Synced · 2:34 PM" + "SYNC NOW" (`#ec3013`, 10sp/700)
- **FAB**: 52×52dp `#ec3013`, bottom-right 24/34dp insets, + icon 22dp white

**1d** is dark variant — same structure, all dark tokens.

---

### 1e — AI Notes List
**File**: `activity_ai_note_list.xml`  
**Purpose**: Browse all AI notes across books

**Layout** (all dark — `#1a1817` bg):
- Dark status bar
- **Dark nav header** (`#201e1d`, 56dp): kicker "Library" + title "AI Notes" (16sp/700 `#f0edea`) + search icon + more icon
- **Search bar** (14dp padding, `#201e1d` bg, border-bottom 1dp): search icon + placeholder text, underline-only
- **Note list** (border-bottom 1dp `#2e2b29` each item, 16dp vertical padding, 20dp horizontal):
  - Book tag: 9sp/700 ALL CAPS `#ec3013` + timestamp right `#5a5550`
  - Original excerpt: 13sp/400 `#908c89`, 2-line clamp, Georgia italic
  - AI response preview: 13sp/400 `#c8c4c0`, 1-line clamp

---

### 1f — AI Note Detail
**File**: `activity_ai_note_detail.xml`  
**Purpose**: Read full AI note + follow up

**Layout**:
- Dark status bar + dark nav header: back arrow + book kicker + "AI Note" + edit icon
- **Content** (20dp padding, light `#f3f2f2` bg):
  - "ORIGINAL TEXT" — 9sp/700 ALL CAPS `#ec3013`
  - Quote block: 3dp left border `#ec3013`, 14dp left padding, Georgia italic 14sp/400
  - 2dp divider
  - "AI RESPONSE" — 9sp/700 ALL CAPS `#9a9490`
  - Response paragraphs: 14sp/400, line-height 1.7
  - 2dp divider
  - "RELATED NOTES" — label + note chips (border 1dp `#d4d0cd`, white bg, book tag + excerpt)
- **Bottom input bar** (border-top 2dp): underline input + 40×40dp `#ec3013` send button

---

### 1g — Settings
**File**: `dialog_reader_settings.xml` + `activity_reader_settings.xml`  
**Purpose**: All app settings

**Layout** (light, nav header with close):
- Section headers: 10sp/700 ALL CAPS `#9a9490`, border-bottom 1dp, 16dp top padding
- **Rows** (52dp height, 0 24dp padding, border-bottom 1dp):
  - Label: 15sp/400 `#201e1d`, flex-fill
  - Value text: 14sp/400 `#9a9490`, margin-right 8dp
  - Chevron icon (18dp, `#c0bcb8`) for navigable rows
  - Square toggle for boolean rows (see Toggle spec above)
- Major section breaks: 2dp divider
- Sections: READING & DISPLAY, AI & CLOUD SYNC, ACCOUNT

---

### 1h — User Profile
**File**: `activity_user_profile.xml`  
**Purpose**: Edit name, email, change password

**Layout** (dark bg `#1a1817`):
- Dark status bar + dark nav header: close + "Profile" + "Save" (`#ec3013`, 13sp/700)
- **Avatar area** (`#201e1d` bg, 28dp padding): 72×72dp square with 2dp `#5a5550` border + initials (24sp/700 `#f0edea`) + name (20sp/700) + email (14sp/400 `#5a5550`) + "Change Avatar" link (`#ec3013`)
- **Form section** (20dp padding, border-bottom 2dp): underline fields (dark style)
- **Change Password section**: 3 underline fields + "Update Password" outlined button (44dp, `#f0edea` text, `#5a5550` border)
- **Logout row**: 56dp, logout icon + "Logout" 15sp/600 `#ec3013`

---

### 1i — Sync Status
**File**: `activity_main.xml` (cardSyncStatus)  
**Purpose**: Sync results and manual trigger

**Layout** (light):
- Light nav header: back + "Sync Status" + cloud icon
- **Info block** (white bg, 20dp padding): label "LAST SYNCED" + "Today, 2:34 PM" (20sp/700) + subtitle
- **Sync detail list** (56dp rows, border-bottom 1dp each): check icon (`#ec3013`) + label (14sp/600) + description (12sp/400 `#9a9490`) + timestamp right
- "Sync Now" — 52dp outlined button (2dp `#201e1d` border, full width)
- Storage self-test row: 8dp green square + "Storage Self-Test" + "PASSED"

---

### 3a — Reader View
**File**: `activity_reader.xml` + `fragment_native_reader.xml`  
**Purpose**: EPUB reading experience

**Layout**:
- Status bar (light, minimal)
- **Top strip** (border-bottom 1dp `#e0dcd8`): book title left (9sp/600 ALL CAPS `#9a9490`) + "Ch. X · 78%" right
- **Reading area** (28dp horizontal, 28dp top): Georgia serif, 16sp, line-height 1.85, `#2c2a28`; chapter label 13sp/700 ALL CAPS `#9a9490`
- **Progress bar**: 2dp strip full-width, track `#e0dcd8`, fill `#ec3013` (width = % read)
- **Bottom toolbar** (64dp, border-top 2dp `#d4d0cd`): 5 equal columns with 1dp dividers between
  - Each button: icon (20dp) centered + label (9sp/600 ALL CAPS `#9a9490`) below, 4dp gap
  - "Ask AI" button uses `#ec3013` icon + label (accent to mark primary action)
  - Icons: AI Note (edit), Bookmark, Ask AI (sparkle/sun), Contents (list), Settings (radio)
- **Home indicator**: 30dp strip, 120×4dp rounded pill `#d4d0cd`

---

### 3b — Text Selection + Ask AI Popup
**File**: Custom selection action mode / floating toolbar  
**Purpose**: In-reader text selection with AI action

**Selection highlight**: Background `rgba(236,48,19,0.15)`, bottom border 2dp `#ec3013`  
**Selection cursors**: 2dp wide `#ec3013` bars at selection start/end

**Popup** (appears above selection):
- `#201e1d` background, no border radius, box-shadow
- 3 actions separated by 1dp `#38332f` dividers: **"Ask AI"** (icon + label, `#ec3013`) | "Copy" (`#f0edea`) | "Bookmark" (`#f0edea`)
- Each action: 40dp height, 14–16dp horizontal padding, 12sp text
- Triangle pointer (8dp) below popup, pointing to selection

---

### 3c — Bookmark List
**File**: `activity_bookmark_list.xml`  
**Purpose**: All saved bookmarks

**Layout** (light, nav header with back):
- Header: "Bookmarks" + "X saved" right (10sp/700 ALL CAPS `#9a9490`)
- **Bookmark rows** (16dp padding, border-bottom 1dp):
  - Book + chapter tag: 10sp/700 ALL CAPS `#ec3013` + timestamp right `#b8b4b0`
  - Excerpt: 14sp/400 Georgia serif `#201e1d`, 2-line clamp, italic
  - Page reference: 12sp/400 `#9a9490`

---

### 3d — AI Profile List
**File**: `activity_ai_profile_list.xml`  
**Purpose**: Manage AI model configurations

**Layout** (dark `#1a1817`):
- Dark status + dark nav header: back + "AI Profiles" + plus icon (`#ec3013`)
- **Selection bar** (`#252220`, 44dp, border-bottom 1dp): checkbox + "Select all" + "Delete selected" right
- **Profile rows** (16dp padding, border-bottom 1dp `#38332f`): checkbox + name (15sp/700 `#f0edea`) + active dot (`#ec3013` 6dp square) + "ACTIVE" tag + model (12sp/400 `#5a5550`) + base URL (11sp/400 `#3a3533`) + more icon right
- **Bottom bar** (`#201e1d`, 60dp, border-top 2dp): 3 equal cols with 1dp dividers — Sync | Import | New (New uses `#ec3013` icon + label)

---

### 3e — AI Profile Edit
**File**: `activity_ai_profile_edit.xml`  
**Purpose**: Create or edit an AI model profile

**Layout** (dark `#1a1817`):
- Dark nav header: back + "Edit Profile" / "New Profile" + "Save" right (`#ec3013`)
- **Sections** (section header 10sp/700 ALL CAPS `#5a5550`, border-bottom 1dp `#38332f`):
  - **IDENTITY**: Profile Name, Model Name (+ "Fetch Models" outlined button, 36dp height, 12sp)
  - **AUTHENTICATION**: API Key (with eye toggle), Base URL
  - **PARAMETERS**: Temperature + Max Tokens in a 2-col grid (border-right 1dp between)
  - **OPTIONS**: "Use Streaming" toggle (ON) + "Enable Google Search" toggle (OFF)
- "Save Profile →" — 52dp red fill button, left-aligned text

---

### 3f — Magic Tags
**File**: `activity_magic_tag_list.xml`  
**Purpose**: Manage AI note auto-classification tags

**Layout** (light):
- Light nav header: back + "Magic Tags"
- **Explainer box** (`#ece9e6` bg, border-bottom 2dp): "WHAT ARE MAGIC TAGS?" label + description
- **Tag rows** (16dp padding, border-bottom 1dp):
  - Tag chip: filled square (no radius) — filled `#201e1d` for default, `#ec3013` for active, outlined for inactive
  - Tag label: 11sp/700, color per state
  - Edit + delete icons right (16dp each, `#9a9490`)
  - Description: 13sp/400 `#6b6865`
- **FAB**: 52×52dp `#ec3013`, fixed bottom-right 50dp from bottom, 24dp right

---

### 3g — Reader Settings Sheet
**File**: `dialog_reader_settings.xml` (shown as bottom sheet)  
**Purpose**: Quick reader settings while reading

**Layout**:
- Reader visible behind, dimmed with `rgba(26,24,23,0.5)` overlay
- Sheet rises from bottom, covers ~65% of screen height
- **Drag handle**: 40×4dp `#d4d0cd`, 12dp top padding
- **Header** (border-bottom 2dp): "Reader Settings" (16sp/700) + close icon (dark)
- **Theme section**: "READING THEME" label + 2×2 grid of 40dp theme buttons (Normal active with `#ec3013` border, others `#d4d0cd` border)
- **Font size row** (border-top 2dp): label + custom slider (2dp track, 14×14dp square thumb `#201e1d`) + value
- **Toggle rows** (48dp each, border-top 2dp on group, 1dp between): Tap to Turn (ON), Page Animation (OFF), Show Page Indicator (ON)
- **Quick links row** (border-top 2dp): 3 equal cols with 1dp dividers — Bookmarks | AI Profiles | Profile (icon + 12sp/600 label)
- Home indicator at bottom

---

## Interactions & Behavior

| Screen | Interaction | Target |
|---|---|---|
| Onboarding | "Get Started" → | Login screen |
| Onboarding | "Sign in" → | Login screen |
| Login | "Sign In" | Authenticate, go to Home |
| Login | "Continue as Guest" | Home (no sync) |
| Login | "Register here" → | Register screen |
| Home | Pull to refresh | Trigger sync |
| Home | Tap book row | Open reader |
| Home | FAB | File picker for EPUB |
| Home | Profile button | User Profile screen |
| Reader | Tap left/right half | Previous/next page (if tap-to-turn ON) |
| Reader | Long-press text | Show selection + popup |
| Reader | "Ask AI" popup | Create AI Note, open detail |
| Reader | Settings toolbar | Open Settings Sheet (bottom sheet) |
| AI Notes | Tap note | Open AI Note Detail |
| AI Note Detail | Send button | Submit follow-up to AI |
| Settings | AI Profiles row | AI Profile List |
| Settings | User Profile row | User Profile screen |
| AI Profile List | + button | AI Profile Edit (new) |
| AI Profile List | Row tap | AI Profile Edit (existing) |
| Magic Tags | FAB | New tag input row |

---

## Design System Notes for Android

1. **Remove all `cardCornerRadius`** from MaterialCardView — set to `0dp`
2. **Replace `SwitchCompat`** (iOS-style) with a custom square toggle drawable
3. **Buttons**: Set `cornerRadius="0dp"`, `textAlignment="textStart"`, `paddingStart="20dp"` on all primary buttons
4. **TextInputLayout**: Use a custom underline-only style, remove the outlined box
5. **Section headers**: Use a custom `TextAppearance` with `textAllCaps`, `letterSpacing`, small size
6. **Progress bars**: Use `android:progressTint="#ec3013"`, `android:progressBackgroundTint` to `#ece9e6` (light) or `#2e2b29` (dark)
7. **Font**: Add Archivo via `res/font/` or via `downloadable fonts`. Apply globally via `android:fontFamily` in the base theme
8. **Reader text**: Use `WebView` or `TextView` with `typeface = Typeface.create("serif", Typeface.NORMAL)` for reading content

---

## Files in this Bundle
- `Boox Reader Redesign.dc.html` — Full interactive HTML mockup (open in any browser, pan/zoom)
- `README.md` — This document

## Source Repo
Android source: `pjiaquan/booxreader` (branch: `main`)  
Key layout files to update: `activity_main.xml`, `activity_login.xml`, `activity_register.xml`, `activity_reader.xml`, `activity_ai_note_list.xml`, `activity_ai_note_detail.xml`, `dialog_reader_settings.xml`, `activity_user_profile.xml`, `activity_ai_profile_list.xml`, `activity_ai_profile_edit.xml`, `activity_magic_tag_list.xml`, `activity_bookmark_list.xml`, `activity_welcome.xml`
