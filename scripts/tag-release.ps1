<#
.SYNOPSIS
    Cut the GitHub release/tag for a Play release, safely.

.DESCRIPTION
    Every public Play release gets a matching GitHub release (docs/play-store/release-signing-and-aab.md
    #5) — the tag is what lets a future session check out the exact code that shipped a given
    versionName. The one way this goes wrong is tagging a moving branch (`main`) instead of the
    merge-commit sha that was actually built: main can move between the bump PR merging and the
    release going out, and a tag cut against the branch silently points at whatever landed after.

    This script refuses to do that: -Sha is required (never defaults to a branch), and it verifies
    that sha is on main, matches the versionName being tagged, and that the AAB built from it exists
    locally before creating the release.

.PARAMETER Version    Tag name / versionName, e.g. "1.0.49" (no "v" prefix — matches 1.0.49, the
                       first tag cut for this repo).
.PARAMETER Sha         The chore/release-<version> bump's merge commit on main. Get it right after
                       merging: git fetch origin && git rev-parse origin/main
.PARAMETER Title       Release title. Defaults to the version.
.PARAMETER NotesFile   Path to hand-written release notes. Omit to use `gh --generate-notes`
                       (works once a previous tag exists to diff against — true from 1.0.49 on).

.EXAMPLE
    .\scripts\tag-release.ps1 -Version 1.0.50 -Sha abc1234... -Title "1.0.50 — Whatever shipped"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$Sha,
    [string]$Title = $Version,
    [string]$NotesFile
)

$ErrorActionPreference = "Stop"
$root = (git rev-parse --show-toplevel 2>$null)
if (-not $root) { Write-Error "Not inside a git checkout."; exit 2 }
Set-Location $root

git fetch origin --tags -q

# 1. The sha must actually exist and be on main — never tag a commit main hasn't absorbed.
$null = git cat-file -e "$Sha^{commit}" 2>$null
if ($LASTEXITCODE -ne 0) { Write-Error "‘$Sha’ is not a commit in this repo. Fetch first?"; exit 1 }
git merge-base --is-ancestor $Sha origin/main
if ($LASTEXITCODE -ne 0) { Write-Error "$Sha is not an ancestor of origin/main — refusing to tag it."; exit 1 }

# 2. The tag must not already exist — no silent re-tagging.
$existing = git tag -l $Version
if ($existing) { Write-Error "Tag $Version already exists (points at $(git rev-parse $Version)). Bump the version or delete the stale tag first."; exit 1 }

# 3. versionName at that sha must match the tag being cut — catches a wrong/stale sha.
# git show returns a string[] (one element per line) in PowerShell; -match on an array filters
# elements instead of testing the whole blob, so join to a single string first.
$buildFile = (git show "${Sha}:app/build.gradle.kts") -join "`n"
if ($buildFile -notmatch 'versionName\s*=\s*"([^"]+)"') { Write-Error "Could not read versionName from app/build.gradle.kts at $Sha."; exit 1 }
$builtVersion = $Matches[1]
if ($builtVersion -ne $Version) { Write-Error "versionName at ${Sha} is '$builtVersion', not '$Version' — wrong sha?"; exit 1 }
if ($buildFile -notmatch 'versionCode\s*=\s*(\d+)') { Write-Error "Could not read versionCode from app/build.gradle.kts at $Sha."; exit 1 }
$versionCode = $Matches[1]

# 4. The AAB actually built from this sha should exist locally — don't tag before it's built.
$aab = "releases/openloop-$Version-$versionCode.aab"
if (-not (Test-Path $aab)) {
    Write-Warning "$aab not found locally — this tag should be cut AFTER building and uploading that bundle (release-signing-and-aab.md #3-5). Continuing anyway; verify you meant to."
}

Write-Host "Tagging $Version at $Sha (versionCode $versionCode)..."
$ghArgs = @("release", "create", $Version, "--target", $Sha, "--title", $Title)
if ($NotesFile) { $ghArgs += @("-F", $NotesFile) } else { $ghArgs += "--generate-notes" }
gh @ghArgs
if ($LASTEXITCODE -ne 0) { Write-Error "gh release create failed."; exit 1 }
Write-Host "Done: https://github.com/$(gh repo view --json nameWithOwner -q .nameWithOwner)/releases/tag/$Version"
