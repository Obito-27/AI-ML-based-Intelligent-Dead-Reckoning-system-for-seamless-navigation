# Font Assets — Dracarys IDR

The design system requires two typefaces bundled as TTF files:

## Required files

Place these in `app/app/src/main/res/font/`:

| Filename | Family | Weight | Download |
|---|---|---|---|
| `space_grotesk_regular.ttf` | Space Grotesk | Regular (400) | [Google Fonts](https://fonts.google.com/specimen/Space+Grotesk) |
| `space_grotesk_bold.ttf` | Space Grotesk | Bold (700) | Same link, select Bold |
| `inter_regular.ttf` | Inter | Regular (400) | [Google Fonts](https://fonts.google.com/specimen/Inter) |

## License

Both families are released under the **SIL Open Font License 1.1** — bundling in the APK is permitted.

## Quick download (PowerShell)

```powershell
# From repo root
$fontDir = "app\app\src\main\res\font"
New-Item -ItemType Directory -Force -Path $fontDir

# Space Grotesk
Invoke-WebRequest -Uri "https://fonts.gstatic.com/s/spacegrotesk/v16/V8mDoQDjQSkFtoMM3T6r8E7mF71Q-gMznZQhID58YQ.ttf" `
    -OutFile "$fontDir\space_grotesk_regular.ttf"
Invoke-WebRequest -Uri "https://fonts.gstatic.com/s/spacegrotesk/v16/V8mDoQDjQSkFtoMM3T6r8E7mF71Q-gMznZQhRD18YQ.ttf" `
    -OutFile "$fontDir\space_grotesk_bold.ttf"

# Inter
Invoke-WebRequest -Uri "https://fonts.gstatic.com/s/inter/v13/UcCO3FwrK3iLTeHuS_fvQtMwCp50KnMw2boKoduKmMEVuLyfAZ9hiJ-Ek-_EeA.ttf" `
    -OutFile "$fontDir\inter_regular.ttf"
```

> **Note**: Google Fonts CDN URLs change with new font revisions. If the above URLs return 404, visit
> the Google Fonts links in the table, click "Download family", and extract the correct weight.
